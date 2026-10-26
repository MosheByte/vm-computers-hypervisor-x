package net.hypervisorx.backend.embedded;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

/**
 * The embedded "Instant-Boot MicroVM". RAM, an 8250-style UART, a CLINT timer and a syscon
 * reboot/poweroff device hooked up to {@link Rv32Cpu}. Memory map is exactly mini-rv32ima's, so the
 * stock no-MMU Linux images boot as-is. Runs on its own thread and never touches the game thread.
 */
public final class Rv32Machine implements Rv32Cpu.Bus, Runnable {
    /** sizeof(struct MiniRV32IMAState). The reference layout keeps this much free below the DTB. */
    private static final int CORE_STATE_BYTES = 48 * 4;
    private static final int UART_DATA = 0x10000000;
    private static final int UART_LCR = 0x10000003;
    private static final int UART_LSR = 0x10000005;
    private static final int CLINT_MTIMECMP_LO = 0x11004000;
    private static final int CLINT_MTIMECMP_HI = 0x11004004;
    private static final int CLINT_MTIME_LO = 0x1100bff8;
    private static final int CLINT_MTIME_HI = 0x1100bffc;
    private static final int SYSCON = 0x11100000;

    public interface Listener {
        void onPowerOff();

        void onFault(String message);
    }

    private final byte[] ram;
    private final Rv32Cpu cpu;
    private final byte[] kernel;
    private final byte[] dtbTemplate;
    private final IntConsumer uartOut;
    private final Listener listener;
    private final ConcurrentLinkedQueue<Integer> uartIn = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private volatile boolean resetRequested;
    private volatile Thread thread;
    private volatile long instructionsRetired;
    /** Line control register. Bit 7 (DLAB) points offset 0 at the baud-rate divisor instead. */
    private int lcr;

    public Rv32Machine(int ramBytes, byte[] kernel, byte[] dtbTemplate, IntConsumer uartOut, Listener listener) {
        if (kernel.length > ramBytes - dtbTemplate.length - CORE_STATE_BYTES) {
            throw new IllegalArgumentException("Kernel image (" + kernel.length + " bytes) does not fit into " + ramBytes + " bytes of RAM");
        }
        this.ram = new byte[ramBytes];
        this.cpu = new Rv32Cpu(ram, this);
        this.kernel = kernel;
        this.dtbTemplate = dtbTemplate;
        this.uartOut = uartOut;
        this.listener = listener;
    }

    public static byte[] loadDefaultDtb() throws IOException {
        try (InputStream in = Rv32Machine.class.getResourceAsStream("/hypervisorx/embedded/minirv32-64mb.dtb")) {
            if (in == null) throw new IOException("missing embedded DTB resource");
            return in.readAllBytes();
        }
    }

    private void loadImage() {
        java.util.Arrays.fill(ram, (byte) 0);
        System.arraycopy(kernel, 0, ram, 0, kernel.length);
        int dtbPtr = ram.length - dtbTemplate.length - CORE_STATE_BYTES;
        System.arraycopy(dtbTemplate, 0, ram, dtbPtr, dtbTemplate.length);
        // patch the /memory reg size (big-endian u32 at 0x13c in the reference DTB) to the usable RAM
        int memSizeOfs = dtbPtr + 0x13c;
        if ((ram[memSizeOfs] & 0xff) == 0x03 && (ram[memSizeOfs + 1] & 0xff) == 0xff && (ram[memSizeOfs + 2] & 0xff) == 0xc0) {
            ram[memSizeOfs] = (byte) (dtbPtr >>> 24);
            ram[memSizeOfs + 1] = (byte) (dtbPtr >>> 16);
            ram[memSizeOfs + 2] = (byte) (dtbPtr >>> 8);
            ram[memSizeOfs + 3] = (byte) dtbPtr;
        }
        cpu.reset(dtbPtr + Rv32Cpu.RAM_BASE);
    }

    public synchronized void start() {
        if (running) return;
        loadImage();
        running = true;
        Thread t = new Thread(this, "HyperVisorX-MicroVM");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        thread = t;
        t.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    public void requestReset() {
        resetRequested = true;
        Thread t = thread;
        if (t != null) LockSupport.unpark(t);
    }

    public boolean isRunning() {
        return running;
    }

    public long instructionsRetired() {
        return instructionsRetired;
    }

    /** Queues bytes for the guest's serial console (i.e. keyboard input). */
    public void sendInput(byte[] bytes) {
        for (byte b : bytes) uartIn.add(b & 0xFF);
        Thread t = thread;
        if (t != null) LockSupport.unpark(t);
    }

    @Override
    public void run() {
        long lastUs = System.nanoTime() / 1000L;
        final int batch = 4096;
        long retired = 0;
        try {
            while (running) {
                if (resetRequested) {
                    resetRequested = false;
                    uartIn.clear();
                    loadImage();
                }
                long nowUs = System.nanoTime() / 1000L;
                int elapsed = (int) Math.min(Integer.MAX_VALUE, nowUs - lastUs);
                lastUs += elapsed;
                int ret = cpu.step(elapsed, batch);
                switch (ret) {
                    case Rv32Cpu.STEP_OK -> retired += batch;
                    case Rv32Cpu.STEP_WFI -> {
                        // idle, sleep until the next timer tick or a key comes in
                        if (uartIn.isEmpty()) LockSupport.parkNanos(500_000L);
                        else cpu.extraflags &= ~4;
                    }
                    case Rv32Cpu.STEP_RESTART -> loadImage();
                    case Rv32Cpu.STEP_POWEROFF -> {
                        running = false;
                        listener.onPowerOff();
                    }
                    default -> { }
                }
                if ((retired & 0xFFFFF) == 0) instructionsRetired = retired;
            }
        } catch (Throwable t) {
            running = false;
            listener.onFault(t.toString());
        }
        instructionsRetired = retired;
    }

    @Override
    public int store(int address, int value) {
        switch (address) {
            case UART_DATA -> {
                if ((lcr & 0x80) == 0) uartOut.accept(value & 0xFF);
            }
            case UART_LCR -> lcr = value & 0xFF;
            case CLINT_MTIMECMP_HI -> cpu.timermatchh = value;
            case CLINT_MTIMECMP_LO -> cpu.timermatchl = value;
            case SYSCON -> {
                return value == 0 ? 0 : value;
            }
            default -> { }
        }
        return 0;
    }

    @Override
    public int load(int address) {
        return switch (address) {
            case UART_LSR -> 0x60 | (uartIn.isEmpty() ? 0 : 1);
            case UART_DATA -> {
                if ((lcr & 0x80) != 0) yield 0;
                Integer b = uartIn.poll();
                yield b == null ? 0 : b;
            }
            case UART_LCR -> lcr;
            case CLINT_MTIME_HI -> cpu.timerh;
            case CLINT_MTIME_LO -> cpu.timerl;
            default -> 0;
        };
    }
}
