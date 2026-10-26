package net.hypervisorx.backend.serial;

import net.hypervisorx.backend.SerialLink;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Hooks up to a hypervisor serial port that's exposed as a TCP server (QEMU {@code -serial tcp:...,server},
 * VirtualBox {@code --uart-mode1 tcpserver}). Reconnects on its own while the VM is running.
 */
public final class TcpSerialLink implements SerialLink, AutoCloseable {
    private final int port;
    private volatile Consumer<byte[]> receiver = b -> { };
    private volatile OutputStream out;
    private volatile Socket socket;
    private volatile boolean open = true;
    private final Thread thread;

    public TcpSerialLink(int port) {
        this.port = port;
        this.thread = new Thread(this::loop, "HyperVisorX-Serial-" + port);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void loop() {
        byte[] buf = new byte[4096];
        while (open) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
                s.setTcpNoDelay(true);
                socket = s;
                out = s.getOutputStream();
                InputStream in = s.getInputStream();
                int n;
                while (open && (n = in.read(buf)) > 0) {
                    receiver.accept(Arrays.copyOf(buf, n));
                }
            } catch (IOException ignored) {
                // VM isn't listening yet or got restarted, just retry
            } finally {
                out = null;
                socket = null;
            }
            if (open) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    @Override
    public void send(byte[] data) {
        OutputStream o = out;
        if (o == null) return;
        try {
            o.write(data);
            o.flush();
        } catch (IOException ignored) {
            // the reader loop reconnects
        }
    }

    @Override
    public void setReceiver(Consumer<byte[]> receiver) {
        this.receiver = receiver;
    }

    @Override
    public boolean isConnected() {
        return out != null;
    }

    @Override
    public void close() {
        open = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        thread.interrupt();
    }
}
