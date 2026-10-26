package net.hypervisorx.backend.serial;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import net.hypervisorx.backend.SerialLink;
import net.hypervisorx.util.Processes;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Client end of a VMware "pipe" serial port (VMware is the server). On Windows it's a named pipe, and
 * we only read after {@code PeekNamedPipe} says there's data, so a pending read never blocks writes on
 * the same synchronous handle. On Linux/macOS VMware uses a unix socket instead.
 */
public final class PipeSerialLink implements SerialLink, AutoCloseable {
    private final String pipeName;
    private volatile Consumer<byte[]> receiver = b -> { };
    private volatile boolean open = true;
    private volatile Object handle;
    private final Thread thread;

    public PipeSerialLink(String pipeName) {
        this.pipeName = pipeName;
        this.thread = new Thread(Processes.WINDOWS ? this::windowsLoop : this::unixLoop, "HyperVisorX-Pipe");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void windowsLoop() {
        Kernel32 k = Kernel32.INSTANCE;
        byte[] buf = new byte[4096];
        IntByReference avail = new IntByReference();
        IntByReference read = new IntByReference();
        while (open) {
            WinNT.HANDLE h = k.CreateFile(pipeName, WinNT.GENERIC_READ | WinNT.GENERIC_WRITE, 0, null, WinNT.OPEN_EXISTING, 0, null);
            if (h == null || WinBase.INVALID_HANDLE_VALUE.equals(h)) {
                sleep(1000);
                continue;
            }
            handle = h;
            try {
                while (open) {
                    if (!k.PeekNamedPipe(h, null, 0, null, avail, null)) break;
                    if (avail.getValue() <= 0) {
                        sleep(10);
                        continue;
                    }
                    int want = Math.min(buf.length, avail.getValue());
                    if (!k.ReadFile(h, buf, want, read, null) || read.getValue() <= 0) break;
                    receiver.accept(Arrays.copyOf(buf, read.getValue()));
                }
            } finally {
                handle = null;
                k.CloseHandle(h);
            }
            sleep(1000);
        }
    }

    private void unixLoop() {
        ByteBuffer buf = ByteBuffer.allocate(4096);
        while (open) {
            try (SocketChannel ch = SocketChannel.open(UnixDomainSocketAddress.of(Path.of(pipeName)))) {
                handle = ch;
                while (open) {
                    buf.clear();
                    int n = ch.read(buf);
                    if (n < 0) break;
                    if (n > 0) receiver.accept(Arrays.copyOf(buf.array(), n));
                }
            } catch (IOException | UnsupportedOperationException ignored) {
                // VM's not up yet
            } finally {
                handle = null;
            }
            sleep(1000);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void send(byte[] data) {
        Object h = handle;
        if (h == null) return;
        if (h instanceof WinNT.HANDLE wh) {
            Kernel32.INSTANCE.WriteFile(wh, data, data.length, new IntByReference(), null);
        } else if (h instanceof SocketChannel ch) {
            try {
                ByteBuffer b = ByteBuffer.wrap(data);
                while (b.hasRemaining()) ch.write(b);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void setReceiver(Consumer<byte[]> receiver) {
        this.receiver = receiver;
    }

    @Override
    public boolean isConnected() {
        return handle != null;
    }

    @Override
    public void close() {
        open = false;
        thread.interrupt();
    }
}
