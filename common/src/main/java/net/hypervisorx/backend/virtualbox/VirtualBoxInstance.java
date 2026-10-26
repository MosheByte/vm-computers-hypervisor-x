package net.hypervisorx.backend.virtualbox;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.backend.BackendException;
import net.hypervisorx.backend.SerialLink;
import net.hypervisorx.backend.VmInstance;
import net.hypervisorx.backend.VmLaunchSpec;
import net.hypervisorx.backend.serial.TcpSerialLink;
import net.hypervisorx.util.Processes;
import net.hypervisorx.vm.display.Framebuffer;
import net.hypervisorx.vm.input.KeyMaps;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A running VirtualBox VM. The original mod locked and released the session every frame in a busy
 * loop on the client. This one keeps a single shared session, grabs frames at the target fps on a
 * background thread and only marks tiles that actually changed.
 */
final class VirtualBoxInstance implements VmInstance {
    private static final int TARGET_FPS = 30;

    private final VmLaunchSpec spec;
    private final Path manage;
    private final int webPort;
    private final int gpioPort;
    private final Framebuffer framebuffer;
    private final ExecutorService input = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HyperVisorX-VBoxInput");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong pendingPointer = new AtomicLong(Long.MIN_VALUE);
    private volatile boolean pointerQueued;
    private volatile VboxSoap soap;
    private String vbox;
    private String session;
    private volatile String display;
    private volatile String keyboard;
    private volatile String mouse;
    private TcpSerialLink serial;
    private volatile State state = State.STARTING;
    private volatile String message = "Starting VirtualBox...";
    private volatile Listener listener;
    private volatile boolean running = true;
    private volatile boolean stopping;
    private Thread capture;
    private Thread monitor;
    private int[] pixels = new int[0];

    VirtualBoxInstance(VmLaunchSpec spec, Path manage, int webPort, int gpioPort) {
        this.spec = spec;
        this.manage = manage;
        this.webPort = webPort;
        this.gpioPort = gpioPort;
        this.framebuffer = new Framebuffer(spec.width(), spec.height());
    }

    void connect() throws IOException {
        openSession();
        capture = new Thread(this::captureLoop, "HyperVisorX-VBoxCapture");
        capture.setDaemon(true);
        capture.start();
        monitor = new Thread(this::monitorLoop, "HyperVisorX-VBoxMonitor");
        monitor.setDaemon(true);
        monitor.start();
        if (gpioPort > 0) serial = new TcpSerialLink(gpioPort);
        setState(State.RUNNING, "Running" + (spec.audio() ? " - guest audio plays on the host speakers" : ""));
    }

    private synchronized void openSession() throws IOException {
        closeSession();
        soap = new VboxSoap(webPort);
        vbox = soap.call("IWebsessionManager_logon", "username", "", "password", "").ret();
        String machine = soap.call("IVirtualBox_findMachine", "_this", vbox, "nameOrId", spec.machineName()).ret();
        session = soap.call("IWebsessionManager_getSessionObject", "refIVirtualBox", vbox).ret();
        IOException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                soap.call("IMachine_lockMachine", "_this", machine, "session", session, "lockType", "Shared");
                last = null;
                break;
            } catch (IOException e) {
                // VM process might still be spawning, and a stale lock from a crashed client clears after logoff. retry
                last = e;
                LockSupport.parkNanos(500_000_000L);
            }
        }
        if (last != null) throw last;
        String console = soap.call("ISession_getConsole", "_this", session).ret();
        display = soap.call("IConsole_getDisplay", "_this", console).ret();
        keyboard = soap.call("IConsole_getKeyboard", "_this", console).ret();
        mouse = soap.call("IConsole_getMouse", "_this", console).ret();
    }

    private synchronized void closeSession() {
        if (soap == null) return;
        try {
            if (session != null) soap.call("ISession_unlockMachine", "_this", session);
        } catch (IOException ignored) {
        }
        try {
            if (vbox != null) soap.call("IWebsessionManager_logoff", "refIVirtualBox", vbox);
        } catch (IOException ignored) {
        }
        session = null;
        vbox = null;
    }

    private void captureLoop() {
        long frameNanos = 1_000_000_000L / TARGET_FPS;
        int failures = 0;
        long lastResCheck = 0;
        int w = spec.width();
        int h = spec.height();
        while (running) {
            long t0 = System.nanoTime();
            try {
                if (t0 - lastResCheck > 1_000_000_000L) {
                    VboxSoap.Response res = soap.call("IDisplay_getScreenResolution", "_this", display, "screenId", 0);
                    int rw = parseInt(res.get("width"), w);
                    int rh = parseInt(res.get("height"), h);
                    if (rw > 0 && rh > 0) {
                        w = rw;
                        h = rh;
                        framebuffer.resize(w, h);
                    }
                    lastResCheck = t0;
                }
                String b64 = soap.call("IDisplay_takeScreenShotToArray", "_this", display, "screenId", 0,
                        "width", w, "height", h, "bitmapFormat", "PNG").ret();
                if (b64 != null && !b64.isEmpty()) {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getMimeDecoder().decode(b64)));
                    if (img != null) {
                        int iw = img.getWidth();
                        int ih = img.getHeight();
                        if (iw != framebuffer.width() || ih != framebuffer.height()) framebuffer.resize(iw, ih);
                        if (pixels.length < iw * ih) pixels = new int[iw * ih];
                        img.getRGB(0, 0, iw, ih, pixels, 0, iw);
                        for (int i = 0; i < iw * ih; i++) pixels[i] &= 0xFFFFFF;
                        framebuffer.writeRgbCompare(0, 0, iw, ih, pixels, 0, iw);
                        framebuffer.frameCompleted();
                    }
                }
                failures = 0;
            } catch (IOException e) {
                if (!running) break;
                failures++;
                if (failures == 5 || failures % 20 == 0) {
                    try {
                        openSession();
                    } catch (IOException reopen) {
                        HyperVisorX.LOG.debug("VirtualBox session reopen failed: {}", reopen.getMessage());
                    }
                }
                LockSupport.parkNanos(200_000_000L);
                continue;
            }
            long wait = frameNanos - (System.nanoTime() - t0);
            if (wait > 0) LockSupport.parkNanos(wait);
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void monitorLoop() {
        while (running) {
            LockSupport.parkNanos(3_000_000_000L);
            if (!running) break;
            try {
                Map<String, String> info = VirtualBoxBackend.vmInfo(manage, spec.machineName());
                String st = info.getOrDefault("VMState", "");
                if (st.equals("poweroff") || st.equals("aborted") || st.equals("saved")) {
                    shutdownLocal(st.equals("aborted") ? State.ERROR : State.STOPPED,
                            st.equals("aborted") ? "The VirtualBox VM process aborted." : st.equals("saved") ? "Suspended." : "Powered off.");
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void shutdownLocal(State s, String msg) {
        running = false;
        closeSession();
        if (serial != null) serial.close();
        if (gpioPort > 0) Processes.releasePort(gpioPort);
        input.shutdown();
        setState(s, msg);
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

    private void soapAsync(String method, Object... params) {
        if (!running) return;
        input.execute(() -> {
            try {
                soap.call(method, params);
            } catch (IOException e) {
                HyperVisorX.LOG.debug("VirtualBox {} failed: {}", method, e.getMessage());
            }
        });
    }

    @Override
    public void key(int glfwKey, boolean down) {
        int[] codes = KeyMaps.scancodeBytes(glfwKey, down);
        if (codes.length == 0) return;
        List<Integer> list = new ArrayList<>(codes.length);
        for (int c : codes) list.add(c);
        soapAsync("IKeyboard_putScancodes", "_this", keyboard, "scancodes", list);
    }

    @Override
    public void pointer(int x, int y, int buttons) {
        // coalesce mouse moves, only the latest position gets sent
        pendingPointer.set(((long) (buttons & 0xFF) << 40) | ((long) (x & 0xFFFFF) << 20) | (y & 0xFFFFF));
        if (pointerQueued || !running) return;
        pointerQueued = true;
        input.execute(() -> {
            pointerQueued = false;
            long v = pendingPointer.get();
            int px = (int) ((v >>> 20) & 0xFFFFF);
            int py = (int) (v & 0xFFFFF);
            int rfb = (int) ((v >>> 40) & 0xFF);
            try {
                soap.call("IMouse_putMouseEventAbsolute", "_this", mouse, "x", px + 1, "y", py + 1, "dz", 0, "dw", 0, "buttonState", vboxButtons(rfb));
            } catch (IOException ignored) {
            }
        });
    }

    private static int vboxButtons(int rfbMask) {
        int b = 0;
        if ((rfbMask & 1) != 0) b |= 1; // left
        if ((rfbMask & 4) != 0) b |= 2; // right
        if ((rfbMask & 2) != 0) b |= 4; // middle
        return b;
    }

    @Override
    public void wheel(int x, int y, int buttons, int clicks) {
        soapAsync("IMouse_putMouseEventAbsolute", "_this", mouse, "x", x + 1, "y", y + 1, "dz", -clicks, "dw", 0, "buttonState", vboxButtons(buttons));
    }

    @Override
    public void ctrlAltDelete() {
        soapAsync("IKeyboard_putCAD", "_this", keyboard);
    }

    private void manageAsync(String... args) {
        Thread t = new Thread(() -> {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(manage.toString());
                cmd.addAll(List.of(args));
                Processes.run(cmd, 120);
            } catch (IOException ignored) {
            }
        }, "HyperVisorX-VBoxManage");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void powerButton() {
        manageAsync("controlvm", spec.machineName(), "acpipowerbutton");
    }

    @Override
    public void reset() {
        manageAsync("controlvm", spec.machineName(), "reset");
    }

    @Override
    public void forceOff() {
        stopping = true;
        try {
            Processes.run(120, manage.toString(), "controlvm", spec.machineName(), "poweroff");
        } catch (IOException ignored) {
        }
        shutdownLocal(State.STOPPED, "Powered off.");
    }

    @Override
    public boolean suspend() {
        stopping = true;
        try {
            Processes.Result r = Processes.run(300, manage.toString(), "controlvm", spec.machineName(), "savestate");
            shutdownLocal(State.STOPPED, "Suspended.");
            return r.ok();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void snapshot(String name) throws BackendException {
        try {
            Processes.Result r = Processes.run(600, manage.toString(), "snapshot", spec.machineName(), "take", name, "--live");
            if (!r.ok()) throw new BackendException(r.output());
        } catch (IOException e) {
            throw new BackendException("Snapshot failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void changeMedia(Path iso) throws BackendException {
        try {
            Processes.Result r = Processes.run(60, manage.toString(), "storageattach", spec.machineName(), "--storagectl", "SATA",
                    "--port", "1", "--device", "0", "--type", "dvddrive", "--medium", iso == null ? "emptydrive" : iso.toAbsolutePath().toString(),
                    "--forceunmount");
            if (!r.ok()) throw new BackendException(r.output());
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
        if (running) forceOff();
    }
}
