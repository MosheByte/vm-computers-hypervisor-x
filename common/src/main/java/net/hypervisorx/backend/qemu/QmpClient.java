package net.hypervisorx.backend.qemu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * QMP (QEMU Machine Protocol) client, events come in async. Talks TCP to a private loopback address
 * or a unix socket (macOS), never to an address the guest can reach.
 */
final class QmpClient implements AutoCloseable {
    private final Closeable socket;
    private final BufferedReader reader;
    private final OutputStream out;
    private final BlockingQueue<JsonObject> responses = new LinkedBlockingQueue<>();
    private final Thread readerThread;
    private volatile Consumer<JsonObject> eventListener = e -> { };
    private volatile boolean open = true;

    private QmpClient(Closeable socket, InputStream in, OutputStream out) throws IOException {
        this.socket = socket;
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
        String greeting = reader.readLine();
        if (greeting == null || !greeting.contains("\"QMP\"")) throw new IOException("Unexpected QMP greeting: " + greeting);
        this.readerThread = new Thread(this::readLoop, "HyperVisorX-QMP");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
        JsonObject r = execute("qmp_capabilities", null, 5000);
        if (r.has("error")) throw new IOException("QMP capabilities negotiation failed: " + r);
    }

    static QmpClient connect(String host, int port, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), 1500);
                return new QmpClient(s, s.getInputStream(), s.getOutputStream());
            } catch (IOException e) {
                last = e;
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
            }
        }
        throw last != null ? last : new IOException("QMP connection timed out");
    }

    /** Connects to the unix socket made by {@code -qmp unix:<path>,server=on,wait=off}. */
    static QmpClient connectUnix(Path path, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                ch.connect(UnixDomainSocketAddress.of(path));
                // hand-rolled wrappers on purpose: Channels.newInputStream/newOutputStream share one lock and deadlock
                InputStream in = new InputStream() {
                    @Override
                    public int read() throws IOException {
                        byte[] b = new byte[1];
                        return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        return ch.read(ByteBuffer.wrap(b, off, len));
                    }
                };
                OutputStream out = new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        write(new byte[]{(byte) b}, 0, 1);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        ByteBuffer buf = ByteBuffer.wrap(b, off, len);
                        while (buf.hasRemaining()) ch.write(buf);
                    }
                };
                return new QmpClient(ch, in, out);
            } catch (IOException e) {
                last = e;
                ch.close();
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
            }
        }
        throw last != null ? last : new IOException("QMP connection timed out");
    }

    void onEvent(Consumer<JsonObject> listener) {
        this.eventListener = listener;
    }

    private void readLoop() {
        try {
            String line;
            while (open && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonElement el;
                try {
                    el = JsonParser.parseString(line);
                } catch (RuntimeException e) {
                    continue;
                }
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("event")) {
                    eventListener.accept(obj);
                } else {
                    responses.offer(obj);
                }
            }
        } catch (IOException ignored) {
            // socket's closed, we're done
        }
        open = false;
    }

    /** Runs a command and hands back the whole response ({@code return} or {@code error}). */
    synchronized JsonObject execute(String command, JsonObject arguments, long timeoutMs) throws IOException {
        if (!open) throw new IOException("QMP connection closed");
        responses.clear();
        JsonObject req = new JsonObject();
        req.addProperty("execute", command);
        if (arguments != null) req.add("arguments", arguments);
        out.write((req + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        try {
            JsonObject r = responses.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (r == null) throw new IOException("QMP command '" + command + "' timed out");
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** Runs a human monitor (HMP) command, returns its text output. */
    String hmp(String commandLine, long timeoutMs) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("command-line", commandLine);
        JsonObject r = execute("human-monitor-command", args, timeoutMs);
        if (r.has("error")) throw new IOException(r.getAsJsonObject("error").get("desc").getAsString());
        return r.has("return") ? r.get("return").getAsString() : "";
    }

    boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
