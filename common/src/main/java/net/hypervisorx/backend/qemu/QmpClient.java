package net.hypervisorx.backend.qemu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** QEMU Machine Protocol client (JSON over TCP) with asynchronous event delivery. */
final class QmpClient implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader reader;
    private final OutputStream out;
    private final BlockingQueue<JsonObject> responses = new LinkedBlockingQueue<>();
    private final Thread readerThread;
    private volatile Consumer<JsonObject> eventListener = e -> { };
    private volatile boolean open = true;

    private QmpClient(Socket socket) throws IOException {
        this.socket = socket;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = socket.getOutputStream();
        String greeting = reader.readLine();
        if (greeting == null || !greeting.contains("\"QMP\"")) throw new IOException("Unexpected QMP greeting: " + greeting);
        this.readerThread = new Thread(this::readLoop, "HyperVisorX-QMP");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
        JsonObject r = execute("qmp_capabilities", null, 5000);
        if (r.has("error")) throw new IOException("QMP capabilities negotiation failed: " + r);
    }

    static QmpClient connect(int port, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
                return new QmpClient(s);
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
