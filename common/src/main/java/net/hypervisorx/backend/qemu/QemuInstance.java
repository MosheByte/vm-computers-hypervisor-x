package net.hypervisorx.backend.qemu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.backend.BackendException;
import net.hypervisorx.backend.SerialLink;
import net.hypervisorx.backend.VmInstance;
import net.hypervisorx.backend.VmLaunchSpec;
import net.hypervisorx.backend.rfb.RfbClient;
import net.hypervisorx.backend.serial.TcpSerialLink;
import net.hypervisorx.util.Processes;
import net.hypervisorx.vm.audio.PcmConverter;
import net.hypervisorx.vm.display.Framebuffer;
import net.hypervisorx.vm.input.KeyMaps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class QemuInstance implements VmInstance {
    private final VmLaunchSpec spec;
    private final Process process;
    private final Path log;
    private final String bindHost;
    private final int vncPort;
    private final int qmpPort;
    private final java.nio.file.Path qmpSocket;
    private final String vncPassword;
    private final int gpioPort;
    private final String notes;
    private final Framebuffer framebuffer;
    private RfbClient rfb;
    private QmpClient qmp;
    private TcpSerialLink serial;
    private volatile State state = State.STARTING;
    private volatile String message = "Starting QEMU...";
    private volatile Listener listener;
    private volatile boolean stopping;
    private final PcmConverter audio = new PcmConverter(44100, 2, AUDIO_RATE);

    QemuInstance(VmLaunchSpec spec, Process process, Path log, String bindHost, int vncPort, int qmpPort, Path qmpSocket, String vncPassword,
                 int gpioPort, String notes) {
        this.spec = spec;
        this.process = process;
        this.log = log;
        this.bindHost = bindHost;
        this.vncPort = vncPort;
        this.qmpPort = qmpPort;
        this.qmpSocket = qmpSocket;
        this.vncPassword = vncPassword;
        this.gpioPort = gpioPort;
        this.notes = notes;
        this.framebuffer = new Framebuffer(spec.width(), spec.height());
        process.onExit().thenRun(this::onProcessExit);
    }

    void connect() throws IOException {
        try {
            qmp = qmpSocket != null ? QmpClient.connectUnix(qmpSocket, 20_000) : QmpClient.connect(bindHost, qmpPort, 20_000);
            // VNC was started with password=on, so set a random per-boot password before we connect
            com.google.gson.JsonObject pw = new com.google.gson.JsonObject();
            pw.addProperty("protocol", "vnc");
            pw.addProperty("password", vncPassword);
            com.google.gson.JsonObject res = qmp.execute("set_password", pw, 5000);
            if (res.has("error")) throw new IOException("could not set the VNC password: " + res.get("error"));
            qmp.onEvent(ev -> {
                String name = ev.get("event").getAsString();
                if (name.equals("SHUTDOWN") || name.equals("POWERDOWN")) {
                    setState(State.STOPPING, "Guest is shutting down...");
                }
            });
            rfb = new RfbClient(bindHost, vncPort, vncPassword, framebuffer, 60, new RfbClient.Listener() {
                @Override
                public void connected(String desktopName, int width, int height) {
                    String accelNote = accelerationNote();
                    setState(State.RUNNING, ("Running " + width + "x" + height + accelNote + (notes.isEmpty() ? "" : " - " + notes)).trim());
                }

                @Override
                public void disconnected(String reason) {
                    if (!stopping && process.isAlive()) setState(State.ERROR, "Display connection lost: " + reason);
                }

                @Override
                public void audio(byte[] pcm, int length) {
                    Listener l = listener;
                    if (l == null) return;
                    int n;
                    synchronized (audio) {
                        n = audio.convert(pcm, length);
                        l.audio(audio.buffer(), n);
                    }
                }
            });
            rfb.connect(20_000);
            if (spec.audio()) rfb.requestAudio(44100, 2);
            if (gpioPort > 0) serial = new TcpSerialLink(bindHost, gpioPort);
            Thread drop = new Thread(this::dropSuspendSnapshot, "HyperVisorX-QemuSnapshot");
            drop.setDaemon(true);
            drop.start();
        } catch (IOException e) {
            String tail = logTail();
            process.destroyForcibly();
            throw new IOException(e.getMessage() + (tail.isEmpty() ? "" : " | QEMU said: " + tail), e);
        }
    }

    static final String SUSPEND_TAG = "hvx-suspend";

    /**
     * Saves the whole machine state into the qcow2 disk (internal snapshot) and quits, next launch
     * resumes with -loadvm. Returns false (caller falls back to a shutdown) if the VM has a disk
     * that can't hold snapshots (imported non-qcow2 image, USB sticks).
     */
    @Override
    public boolean suspend() {
        QmpClient q = qmp;
        if (q == null || spec.existingDisk() != null || !spec.usbDrives().isEmpty()) return false;
        try {
            q.hmp("stop", 5000);
            String out = q.hmp("savevm " + SUSPEND_TAG, 180_000);
            if (out.toLowerCase(java.util.Locale.ROOT).contains("error")) {
                q.hmp("cont", 5000);
                return false;
            }
            stopping = true;
            q.execute("quit", null, 5000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Once we've resumed, the snapshot is just wasting disk space, so drop it. */
    private void dropSuspendSnapshot() {
        QmpClient q = qmp;
        if (q == null) return;
        try {
            q.hmp("delvm " + SUSPEND_TAG, 60_000);
        } catch (IOException ignored) {
        }
    }

    private String accelerationNote() {
        String t = logTail().toLowerCase();
        if (t.contains("no accelerator found") || t.contains("whpx: no") || t.contains("could not access kvm")) {
            return " (software emulation - hardware acceleration unavailable)";
        }
        return "";
    }

    private String logTail() {
        try {
            if (!Files.exists(log)) return "";
            List<String> lines = Files.readAllLines(log);
            return String.join(" ", lines.subList(Math.max(0, lines.size() - 4), lines.size())).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private void onProcessExit() {
        if (rfb != null) rfb.close();
        if (qmp != null) qmp.close();
        if (serial != null) serial.close();
        Processes.releasePort(vncPort);
        Processes.releasePort(qmpPort);
        if (gpioPort > 0) Processes.releasePort(gpioPort);
        if (qmpSocket != null) {
            try {
                java.nio.file.Files.deleteIfExists(qmpSocket);
            } catch (IOException ignored) {
            }
        }
        int code = process.exitValue();
        if (stopping || code == 0) {
            setState(State.STOPPED, "Powered off.");
        } else {
            setState(State.ERROR, "QEMU exited with code " + code + ": " + logTail());
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
    public void key(int glfwKey, boolean down) {
        RfbClient r = rfb;
        if (r == null) return;
        int keysym = KeyMaps.keysym(glfwKey);
        if (r.supportsScancodes()) {
            int code = KeyMaps.qemuKeycode(glfwKey);
            if (code != 0) {
                r.sendScancode(keysym, code, down);
                return;
            }
        }
        r.sendKeysym(keysym, down);
    }

    @Override
    public void pointer(int x, int y, int buttons) {
        RfbClient r = rfb;
        if (r != null) r.sendPointer(x, y, buttons);
    }

    @Override
    public void wheel(int x, int y, int buttons, int clicks) {
        RfbClient r = rfb;
        if (r == null) return;
        int bit = clicks > 0 ? 8 : 16;
        for (int i = 0; i < Math.min(10, Math.abs(clicks)); i++) {
            r.sendPointer(x, y, buttons | bit);
            r.sendPointer(x, y, buttons);
        }
    }

    @Override
    public void ctrlAltDelete() {
        JsonObject args = new JsonObject();
        JsonArray keys = new JsonArray();
        for (String k : new String[]{"ctrl", "alt", "delete"}) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "qcode");
            o.addProperty("data", k);
            keys.add(o);
        }
        args.add("keys", keys);
        qmpQuiet("send-key", args);
    }

    private void qmpQuiet(String command, JsonObject args) {
        QmpClient q = qmp;
        if (q == null) return;
        try {
            q.execute(command, args, 5000);
        } catch (IOException e) {
            HyperVisorX.LOG.debug("QMP {} failed: {}", command, e.getMessage());
        }
    }

    @Override
    public void powerButton() {
        qmpQuiet("system_powerdown", null);
    }

    @Override
    public void reset() {
        qmpQuiet("system_reset", null);
    }

    @Override
    public void forceOff() {
        stopping = true;
        qmpQuiet("quit", null);
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    @Override
    public void snapshot(String name) throws BackendException {
        QmpClient q = qmp;
        if (q == null) throw new BackendException("QEMU is not connected");
        try {
            String out = q.hmp("savevm " + name.replaceAll("[^A-Za-z0-9_-]", "_"), 300_000);
            if (out.toLowerCase().contains("error")) throw new BackendException(out.trim());
        } catch (IOException e) {
            throw new BackendException("Snapshot failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void changeMedia(Path iso) throws BackendException {
        QmpClient q = qmp;
        if (q == null) throw new BackendException("QEMU is not connected");
        try {
            JsonObject args = new JsonObject();
            args.addProperty("id", "cd0dev");
            JsonObject r;
            if (iso == null) {
                args.addProperty("force", true);
                r = q.execute("eject", args, 10_000);
            } else {
                args.addProperty("filename", iso.toAbsolutePath().toString());
                args.addProperty("format", "raw");
                r = q.execute("blockdev-change-medium", args, 10_000);
            }
            if (r.has("error")) throw new BackendException(r.getAsJsonObject("error").get("desc").getAsString());
        } catch (IOException e) {
            throw new BackendException("Media change failed: " + e.getMessage(), e);
        }
    }

    @Override
    public SerialLink gpioLink() {
        return serial;
    }

    @Override
    public void close() {
        if (process.isAlive()) forceOff();
    }
}
