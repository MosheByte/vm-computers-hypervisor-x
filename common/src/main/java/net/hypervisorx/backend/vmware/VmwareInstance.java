package net.hypervisorx.backend.vmware;

import net.hypervisorx.backend.BackendException;
import net.hypervisorx.backend.SerialLink;
import net.hypervisorx.backend.VmInstance;
import net.hypervisorx.backend.VmLaunchSpec;
import net.hypervisorx.backend.rfb.RfbClient;
import net.hypervisorx.backend.serial.PipeSerialLink;
import net.hypervisorx.util.Processes;
import net.hypervisorx.vm.display.Framebuffer;
import net.hypervisorx.vm.input.KeyMaps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class VmwareInstance implements VmInstance {
    private static final ScheduledExecutorService MONITOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "HyperVisorX-VMwareMonitor");
        t.setDaemon(true);
        return t;
    });

    private final VmLaunchSpec spec;
    private final VmwareBackend.Install install;
    private final Path vmx;
    private final int vncPort;
    private final String password;
    private final String pipe;
    private final Framebuffer framebuffer;
    private RfbClient rfb;
    private PipeSerialLink serial;
    private volatile State state = State.STARTING;
    private volatile String message = "Starting VMware...";
    private volatile Listener listener;
    private volatile boolean stopping;
    private java.util.concurrent.ScheduledFuture<?> monitorTask;

    VmwareInstance(VmLaunchSpec spec, VmwareBackend.Install install, Path vmx, int vncPort, String password, String pipe) {
        this.spec = spec;
        this.install = install;
        this.vmx = vmx;
        this.vncPort = vncPort;
        this.password = password;
        this.pipe = pipe;
        this.framebuffer = new Framebuffer(spec.width(), spec.height());
    }

    private volatile int bootKeyPresses;
    private volatile java.util.concurrent.ScheduledFuture<?> bootKeyTask;

    /** First boot of a fresh Windows install, so answer the DVD boot prompt. */
    void pressKeyToBootDvd() {
        bootKeyPresses = 45;
    }

    void connect() throws IOException {
        rfb = new RfbClient("127.0.0.1", vncPort, password, framebuffer, 60, new RfbClient.Listener() {
            @Override
            public void connected(String desktopName, int width, int height) {
                setState(State.RUNNING, spec.audio() ? "Guest audio plays on the host speakers" : "");
            }

            @Override
            public void disconnected(String reason) {
                if (!stopping) checkRunning();
            }
        });
        rfb.connect(45_000);
        if (bootKeyPresses > 0) {
            // Windows install media waits ~5 s on "Press any key to boot from CD or DVD" and nobody's at the keyboard
            // during an unattended install, so tap Space for the first 45 seconds (does nothing once setup loads)
            bootKeyTask = MONITOR.scheduleAtFixedRate(() -> {
                if (--bootKeyPresses < 0 || rfb == null) {
                    if (bootKeyTask != null) bootKeyTask.cancel(false);
                    return;
                }
                // VMware ignores a press if the release comes in the same instant, so hold it ~80 ms
                rfb.sendKeysym(0x20, true);
                MONITOR.schedule(() -> {
                    if (rfb != null) rfb.sendKeysym(0x20, false);
                }, 80, TimeUnit.MILLISECONDS);
            }, 500, 1000, TimeUnit.MILLISECONDS);
        }
        if (pipe != null) serial = new PipeSerialLink(pipe);
        monitorTask = MONITOR.scheduleWithFixedDelay(this::checkRunning, 5, 5, TimeUnit.SECONDS);
    }

    private static final Object LIST_LOCK = new Object();
    private static String listOutput = "";
    private static long listTime;

    /** One shared {@code vmrun list} every few seconds for all VMware VMs, not one process per VM. */
    private boolean isListed() {
        String out;
        synchronized (LIST_LOCK) {
            if (System.currentTimeMillis() - listTime > 4000) {
                try {
                    listOutput = Processes.run(30, install.vmrun().toString(), "-T", install.hostType(), "list").output().toLowerCase();
                    listTime = System.currentTimeMillis();
                } catch (IOException e) {
                    return true; // vmrun hiccuped, just assume it's still running
                }
            }
            out = listOutput;
        }
        String norm = vmx.toAbsolutePath().normalize().toString().toLowerCase();
        return out.lines().anyMatch(l -> l.trim().equals(norm));
    }

    private void checkRunning() {
        if (state == State.STOPPED || state == State.ERROR) return;
        if (!isListed()) {
            // double check with a fresh list before calling the VM gone (the shared one can be a few seconds old)
            synchronized (LIST_LOCK) {
                listTime = 0;
            }
            if (isListed()) return;
            cleanup();
            setState(State.STOPPED, stopping ? "Powered off." : "The virtual machine stopped.");
        } else if (rfb != null && !rfb.isConnected() && !stopping) {
            try {
                rfb.connect(10_000);
            } catch (IOException e) {
                setState(State.ERROR, "VNC display unavailable: " + e.getMessage());
            }
        }
    }

    private void cleanup() {
        if (monitorTask != null) monitorTask.cancel(false);
        if (rfb != null) rfb.close();
        if (serial != null) serial.close();
        Processes.releasePort(vncPort);
    }

    private void setState(State s, String msg) {
        state = s;
        message = msg;
        Listener l = listener;
        if (l != null) l.stateChanged(s, msg);
    }

    private Processes.Result vmrun(long timeout, String... args) throws IOException {
        String[] cmd = new String[args.length + 3];
        cmd[0] = install.vmrun().toString();
        cmd[1] = "-T";
        cmd[2] = install.hostType();
        System.arraycopy(args, 0, cmd, 3, args.length);
        return Processes.run(timeout, cmd);
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
        if (r != null) r.sendKeysym(KeyMaps.keysym(glfwKey), down);
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
        RfbClient r = rfb;
        if (r == null) return;
        r.sendKeysym(0xffe3, true);
        r.sendKeysym(0xffe9, true);
        r.sendKeysym(0xffff, true);
        r.sendKeysym(0xffff, false);
        r.sendKeysym(0xffe9, false);
        r.sendKeysym(0xffe3, false);
    }

    @Override
    public void powerButton() {
        Thread t = new Thread(() -> {
            try {
                Processes.Result r = vmrun(120, "stop", vmx.toString(), "soft");
                if (!r.ok()) {
                    // soft stop goes through VMware Tools, without them there's no way to ask the guest to shut down
                    setState(State.STOPPING, "No VMware Tools in the guest - powering off in 10 s");
                    Thread.sleep(10_000);
                    stopping = true;
                    vmrun(120, "stop", vmx.toString(), "hard");
                    synchronized (LIST_LOCK) {
                        listTime = 0;
                    }
                    checkRunning();
                }
            } catch (IOException ignored) {
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "HyperVisorX-VMwareStop");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void reset() {
        try {
            vmrun(60, "reset", vmx.toString(), "hard");
        } catch (IOException ignored) {
        }
    }

    @Override
    public void forceOff() {
        stopping = true;
        try {
            vmrun(120, "stop", vmx.toString(), "hard");
        } catch (IOException ignored) {
        }
        cleanup();
        setState(State.STOPPED, "Powered off.");
    }

    @Override
    public boolean suspend() {
        stopping = true;
        try {
            Processes.Result r = vmrun(300, "suspend", vmx.toString(), "hard");
            cleanup();
            setState(State.STOPPED, "Suspended.");
            return r.ok();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void snapshot(String name) throws BackendException {
        try {
            Processes.Result r = vmrun(600, "snapshot", vmx.toString(), name);
            if (!r.ok()) throw new BackendException(r.output());
        } catch (IOException e) {
            throw new BackendException("Snapshot failed: " + e.getMessage(), e);
        }
    }

    @Override
    public net.hypervisorx.backend.GuestCursor cursor() {
        RfbClient r = rfb;
        return r == null ? null : r.cursor();
    }

    @Override
    public SerialLink gpioLink() {
        return serial;
    }

    @Override
    public void close() {
        if (state != State.STOPPED) forceOff();
    }
}
