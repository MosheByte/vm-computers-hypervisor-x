package net.hypervisorx.backend.hostcapture;

import net.hypervisorx.backend.BackendContext;
import net.hypervisorx.backend.BackendException;
import net.hypervisorx.backend.BackendFeature;
import net.hypervisorx.backend.BackendStatus;
import net.hypervisorx.backend.HypervisorBackend;
import net.hypervisorx.backend.VmInstance;
import net.hypervisorx.backend.VmLaunchSpec;
import net.hypervisorx.util.Processes;
import net.hypervisorx.vm.display.Framebuffer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

/**
 * Mirrors a window from the PC running the world (Discord, VS Code, a browser, OBS...) onto an
 * in-game screen. View only, Windows only. Has to be turned on in the server config since it shows
 * the host's desktop to other players.
 */
public final class HostCaptureBackend implements HypervisorBackend {
    public static final String ID = "hostcapture";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Host Window Capture (view only)";
    }

    @Override
    public Set<BackendFeature> features() {
        return EnumSet.of(BackendFeature.HOST_WINDOWS, BackendFeature.ZERO_INSTALL);
    }

    @Override
    public BackendStatus detect(BackendContext context) {
        if (!context.allowHostCapture()) {
            return BackendStatus.unavailable("Disabled. An operator can enable 'allowHostWindowCapture' (it shows the host's windows to players).");
        }
        if (!Processes.WINDOWS) return BackendStatus.unavailable("Host window capture is currently implemented for Windows hosts only.");
        try {
            int n = Win32WindowCapture.listWindows().size();
            return BackendStatus.ok("1.0", "user32/gdi32", "Ready, " + n + " capturable windows.", false);
        } catch (Throwable t) {
            return BackendStatus.unavailable("JNA is unavailable: " + t);
        }
    }

    /** Titles of the host windows we can capture, for the window picker. */
    public static List<String> windowTitles() {
        List<String> titles = new ArrayList<>();
        if (!Processes.WINDOWS) return titles;
        try {
            for (Win32WindowCapture.WindowInfo w : Win32WindowCapture.listWindows()) titles.add(w.title());
        } catch (Throwable ignored) {
        }
        return titles;
    }

    @Override
    public VmInstance launch(VmLaunchSpec spec, BackendContext context) throws BackendException {
        if (!context.allowHostCapture()) throw new BackendException("Host window capture is disabled in the server configuration");
        if (!Processes.WINDOWS) throw new BackendException("Host window capture needs a Windows host");
        Win32WindowCapture.WindowInfo win = Win32WindowCapture.find(spec.target());
        if (win == null) throw new BackendException("No visible window matches '" + spec.target() + "'");
        return new CaptureInstance(win);
    }

    private static final class CaptureInstance implements VmInstance {
        private final Win32WindowCapture capture;
        private final Framebuffer framebuffer = new Framebuffer(1280, 720);
        private volatile boolean running = true;
        private volatile State state = State.RUNNING;
        private volatile String message;
        private volatile Listener listener;

        CaptureInstance(Win32WindowCapture.WindowInfo window) {
            this.capture = new Win32WindowCapture(window.hwnd());
            this.message = "Mirroring '" + window.title() + "'";
            Thread t = new Thread(this::loop, "HyperVisorX-HostCapture");
            t.setDaemon(true);
            t.start();
        }

        private void loop() {
            int[][] holder = new int[1][];
            long frame = 1_000_000_000L / 60;
            while (running) {
                long t0 = System.nanoTime();
                if (!capture.isValid()) {
                    running = false;
                    set(State.STOPPED, "The captured window was closed.");
                    break;
                }
                int[] size = capture.capture(holder);
                if (size != null) {
                    framebuffer.resize(size[0], size[1]);
                    framebuffer.writeRgbCompare(0, 0, size[0], size[1], holder[0], 0, size[0]);
                    framebuffer.frameCompleted();
                }
                long wait = frame - (System.nanoTime() - t0);
                LockSupport.parkNanos(Math.max(wait, 2_000_000L));
            }
            capture.close();
        }

        private void set(State s, String m) {
            state = s;
            message = m;
            Listener l = listener;
            if (l != null) l.stateChanged(s, m);
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
            // view only, we never send input to host apps
        }

        @Override
        public void pointer(int x, int y, int buttons) {
        }

        @Override
        public void wheel(int x, int y, int buttons, int clicks) {
        }

        @Override
        public void ctrlAltDelete() {
        }

        @Override
        public void powerButton() {
            forceOff();
        }

        @Override
        public void reset() {
        }

        @Override
        public void forceOff() {
            running = false;
            set(State.STOPPED, "Capture stopped.");
        }

        @Override
        public void close() {
            running = false;
        }
    }
}
