package net.hypervisorx.backend.embedded;

import net.hypervisorx.backend.SerialLink;
import net.hypervisorx.backend.VmInstance;
import net.hypervisorx.backend.VmLaunchSpec;
import net.hypervisorx.vm.display.Framebuffer;
import net.hypervisorx.vm.input.KeyMaps;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One running embedded MicroVM: the RISC-V machine plus a VT terminal drawn into the shared framebuffer. */
final class EmbeddedInstance implements VmInstance {
    static final int COLS = 120;
    static final int ROWS = 34;

    private static final ScheduledExecutorService RENDERER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "HyperVisorX-TerminalRenderer");
        t.setDaemon(true);
        return t;
    });

    private final VmLaunchSpec spec;
    private final Framebuffer framebuffer;
    private final GlyphFont font = GlyphFont.defaultFont();
    private final VtTerminal terminal;
    private final Rv32Machine machine;
    private final OscGpioLink gpio = new OscGpioLink();
    private final boolean doom;
    private volatile State state = State.STARTING;
    private volatile String message = "Booting RISC-V Linux...";
    private volatile Listener listener;
    private ScheduledFuture<?> renderTask;
    private boolean ctrl;
    private boolean alt;
    private final StringBuilder loginWatch = new StringBuilder();
    private boolean autoLoggedIn;
    private boolean doomLaunched;

    EmbeddedInstance(VmLaunchSpec spec, byte[] kernel, byte[] dtb, int ramBytes, boolean doom) {
        this.spec = spec;
        this.doom = doom;
        this.framebuffer = new Framebuffer(COLS * 8, ROWS * 16);
        this.terminal = new VtTerminal(COLS, ROWS, new VtTerminal.Host() {
            @Override
            public void reply(byte[] data) {
                machine().sendInput(data);
            }

            @Override
            public void osc(String payload) {
                if (payload.startsWith("1337;GPIO;")) {
                    gpio.fromGuest(payload.substring("1337;GPIO;".length()));
                }
            }

            @Override
            public void bell() {
            }
        });
        this.machine = new Rv32Machine(ramBytes, kernel, dtb, this::onUart, new Rv32Machine.Listener() {
            @Override
            public void onPowerOff() {
                setState(State.STOPPED, "The guest powered off.");
            }

            @Override
            public void onFault(String msg) {
                setState(State.ERROR, "CPU fault: " + msg);
            }
        });
        terminal.writeString("\033[1;36mHyperVisor X\033[0m Instant-Boot MicroVM  \033[90m(RV32IMA, " + (ramBytes >> 20) + " MiB RAM)\033[0m\r\n\r\n");
    }

    private Rv32Machine machine() {
        return machine;
    }

    void start() {
        machine.start();
        renderTask = RENDERER.scheduleAtFixedRate(this::render, 0, 16, TimeUnit.MILLISECONDS);
        setState(State.RUNNING, "Running");
    }

    private void render() {
        try {
            terminal.render(framebuffer, font, 0, 0);
        } catch (Throwable ignored) {
            // a render hiccup can't be allowed to kill the scheduler thread, every embedded VM shares it
        }
    }

    private void onUart(int b) {
        terminal.write(b);
        if (!autoLoggedIn) {
            loginWatch.append((char) b);
            if (loginWatch.length() > 64) loginWatch.delete(0, loginWatch.length() - 64);
            if (loginWatch.indexOf("login: ") >= 0) {
                autoLoggedIn = true;
                machine.sendInput("root\r".getBytes(StandardCharsets.US_ASCII));
            }
        } else if (doom && !doomLaunched) {
            loginWatch.append((char) b);
            if (loginWatch.length() > 64) loginWatch.delete(0, loginWatch.length() - 64);
            if (loginWatch.indexOf("# ") >= 0) {
                doomLaunched = true;
                machine.sendInput("cd /root && ./emdoom\r".getBytes(StandardCharsets.US_ASCII));
            }
        }
    }

    private void setState(State s, String msg) {
        state = s;
        message = msg;
        Listener l = listener;
        if (l != null) l.stateChanged(s, msg);
    }

    @Override
    public Framebuffer framebuffer() {
        return framebuffer;
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public String statusMessage() {
        return message;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public synchronized void key(int glfwKey, boolean down) {
        if (KeyMaps.isCtrl(glfwKey)) {
            ctrl = down;
            return;
        }
        if (KeyMaps.isAlt(glfwKey)) {
            alt = down;
            return;
        }
        if (!down) return;
        byte[] seq = KeyMaps.terminalSequence(glfwKey, ctrl, alt, terminal.applicationCursorKeys());
        if (seq != null) machine.sendInput(seq);
    }

    @Override
    public synchronized void text(int codepoint) {
        if (ctrl) return; // ctrl combos already went out as key sequences
        byte[] utf8 = new String(Character.toChars(codepoint)).getBytes(StandardCharsets.UTF_8);
        if (alt) {
            byte[] withEsc = new byte[utf8.length + 1];
            withEsc[0] = 0x1B;
            System.arraycopy(utf8, 0, withEsc, 1, utf8.length);
            utf8 = withEsc;
        }
        machine.sendInput(utf8);
    }

    @Override
    public void pointer(int x, int y, int buttons) {
        // text console, no mouse
    }

    @Override
    public void wheel(int x, int y, int buttons, int clicks) {
        // BusyBox console has no Shift+PageUp style scrollback, so just ignore it
    }

    @Override
    public void ctrlAltDelete() {
        machine.sendInput(new byte[]{0x03});
    }

    @Override
    public void powerButton() {
        // BusyBox poweroff writes 0x5555 to the syscon, which lands in onPowerOff()
        machine.sendInput("\003\rpoweroff\r".getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public void reset() {
        autoLoggedIn = false;
        doomLaunched = false;
        terminal.reset();
        machine.requestReset();
    }

    @Override
    public void forceOff() {
        machine.stop();
        setState(State.STOPPED, "Powered off.");
    }

    @Override
    public SerialLink gpioLink() {
        return spec.gpio() ? gpio : null;
    }

    @Override
    public void close() {
        if (renderTask != null) renderTask.cancel(false);
        machine.stop();
        if (state != State.ERROR) state = State.STOPPED;
    }

    /**
     * GPIO over terminal escape codes. The guest prints {@code ESC ] 1337 ; GPIO ; <command> BEL}
     * (fields split by ';' or spaces) and replies come back the same way on its stdin.
     */
    private final class OscGpioLink implements SerialLink {
        private volatile Consumer<byte[]> receiver;

        void fromGuest(String command) {
            Consumer<byte[]> r = receiver;
            if (r != null) r.accept((command.replace(';', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void send(byte[] data) {
            String text = new String(data, StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) return;
            for (String line : text.split("\n")) {
                machine.sendInput(("\033]1337;GPIO;" + line.strip() + "\007").getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override
        public void setReceiver(Consumer<byte[]> receiver) {
            this.receiver = receiver;
        }

        @Override
        public boolean isConnected() {
            return machine.isRunning();
        }
    }
}
