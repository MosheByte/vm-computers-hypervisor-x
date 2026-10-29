package net.hypervisorx.vm;

import net.hypervisorx.backend.VmInstance;
import net.hypervisorx.backend.VmLaunchSpec;
import net.hypervisorx.vm.display.Framebuffer;
import net.hypervisorx.vm.display.FrameStream;
import net.hypervisorx.vm.gpio.GpioBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** live state of one powered on VM: backend instance, encoder, viewers, GPIO, audio routing. */
public final class VmSession {
    public enum State { STARTING, RUNNING, STOPPING, STOPPED, ERROR }

    public final VmRecord record;
    public final String backendId;
    public final VmLaunchSpec spec;
    private volatile VmInstance instance;
    private volatile FrameStream stream;
    private final Framebuffer placeholder;
    private volatile State state = State.STARTING;
    private volatile String message = "Starting...";
    public final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    public final GpioBridge gpio;
    /** players who hear this VM. refreshed every second on the server thread. */
    public volatile List<ServerPlayer> audioTargets = List.of();
    public volatile BlockPos audioOrigin;
    public volatile boolean handheld;
    public final long startedAt = System.currentTimeMillis();
    public volatile boolean stateDirty = true;
    public volatile int ramCommittedMb;
    /** players allowed to send input. server thread refreshes it every second, network threads read it. */
    public final java.util.Set<UUID> controllers = ConcurrentHashMap.newKeySet();
    /** paces in-game mouse motion for all VMs (one small step every {@link #STEP_MS} ms). */
    private static final java.util.concurrent.ScheduledExecutorService PACER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "HyperVisorX-Pointer");
        t.setDaemon(true);
        return t;
    });
    /**
     * Gotcha: a game in the guest (VMware, cursor hidden) turns by the distance between pointer positions, but
     * only for small steps. ~35px or more at once counts as a jump and gets ignored, and steps sent faster than
     * the guest samples the mouse get merged into one. So we send steps of at most this many px, {@link #STEP_MS}
     * apart, and near an edge we jump back to the middle (big, so the game ignores it).
     */
    private static final int GAME_STEP = 24;
    private static final long STEP_MS = 10;
    private static final int EDGE = 300;

    /** guest pointer that a captured mouse drives (network thread and pacer both touch it). */
    private int pointerX = -1;
    private int pointerY = -1;
    private int pointerWarp;
    private double gameDx;
    private double gameDy;
    private int gameButtons;
    private int sentButtons;
    private int lastSentX = -1;
    private int lastSentY = -1;
    private java.util.concurrent.ScheduledFuture<?> pacer;
    private net.hypervisorx.backend.VmInstance pacedInstance;

    /** player pointed right at this spot (free mouse), captured motion picks up from here. */
    public synchronized void pointerAt(int x, int y) {
        pointerX = x;
        pointerY = y;
        lastSentX = x;
        lastSentY = y;
    }

    /**
     * Captured mouse motion, in guest pixels. On the desktop it moves the guest pointer (from wherever the guest
     * last put it). In a game (guest hides its cursor) it gets paced out in small steps so the game turns by
     * exactly that much.
     */
    public synchronized void pointerMotion(net.hypervisorx.backend.VmInstance inst, int dx, int dy, int buttons) {
        if (gameMode(inst)) {
            gameDx += dx;
            gameDy += dy;
            gameButtons = buttons;
            if (buttons != sentButtons) {
                int[] p = current(inst);
                send(inst, p[0], p[1], buttons);
            }
            pacedInstance = inst;
            if (pacer == null && (gameDx != 0 || gameDy != 0)) {
                pacer = PACER.scheduleAtFixedRate(this::paceStep, 0, STEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            return;
        }
        gameDx = 0;
        gameDy = 0;
        int[] p = position(inst);
        int w = inst.framebuffer().width();
        int h = inst.framebuffer().height();
        pointerX = Math.max(0, Math.min(w - 1, p[0] + dx));
        pointerY = Math.max(0, Math.min(h - 1, p[1] + dy));
        send(inst, pointerX, pointerY, buttons);
    }

    /** where a captured mouse's wheel notches land: at the pointer, without moving it. */
    public synchronized int[] wheelAt(net.hypervisorx.backend.VmInstance inst) {
        return gameMode(inst) ? current(inst) : position(inst);
    }

    private synchronized void paceStep() {
        net.hypervisorx.backend.VmInstance inst = pacedInstance;
        double most = Math.max(Math.abs(gameDx), Math.abs(gameDy));
        if (inst == null || most < 0.5 || !gameMode(inst)) {
            gameDx = 0;
            gameDy = 0;
            if (pacer != null) pacer.cancel(false);
            pacer = null;
            return;
        }
        double k = most > GAME_STEP ? GAME_STEP / most : 1;
        int sx = (int) Math.round(gameDx * k);
        int sy = (int) Math.round(gameDy * k);
        int w = inst.framebuffer().width();
        int h = inst.framebuffer().height();
        int[] p = current(inst);
        int x = p[0] + sx;
        int y = p[1] + sy;
        if (x < EDGE || x > w - EDGE || y < Math.min(EDGE, h / 4) || y > h - Math.min(EDGE, h / 4)) {
            // jump back to the middle (game ignores it), the real step goes out next tick
            send(inst, w / 2, h / 2, gameButtons);
            return;
        }
        send(inst, x, y, gameButtons);
        gameDx -= sx;
        gameDy -= sy;
    }

    private static boolean gameMode(net.hypervisorx.backend.VmInstance inst) {
        net.hypervisorx.backend.GuestCursor c = inst.cursor();
        return c != null && c.hasShape() && !c.visible() && c.absolute();
    }

    /** last position we sent (middle of the screen if nothing yet). */
    private int[] current(net.hypervisorx.backend.VmInstance inst) {
        if (lastSentX < 0 || lastSentY < 0) {
            lastSentX = inst.framebuffer().width() / 2;
            lastSentY = inst.framebuffer().height() / 2;
        }
        return new int[]{lastSentX, lastSentY};
    }

    private int[] position(net.hypervisorx.backend.VmInstance inst) {
        net.hypervisorx.backend.GuestCursor c = inst.cursor();
        if (c != null && c.warpVersion() != pointerWarp) {
            pointerWarp = c.warpVersion();
            int[] p = c.warpPosition();
            pointerX = p[0];
            pointerY = p[1];
        }
        if (pointerX < 0 || pointerY < 0) {
            pointerX = inst.framebuffer().width() / 2;
            pointerY = inst.framebuffer().height() / 2;
        }
        return new int[]{pointerX, pointerY};
    }

    private void send(net.hypervisorx.backend.VmInstance inst, int x, int y, int buttons) {
        inst.pointer(x, y, buttons);
        lastSentX = x;
        lastSentY = y;
        sentButtons = buttons;
    }

    /** last cursor shape / forced position sent to viewers (stream pump thread). -1 = resend the shape. */
    public volatile int sentCursorShape = -1;
    public volatile int sentCursorWarp;
    private boolean cancelled;

    public static final class Viewer {
        public final ServerPlayer player;
        public final FrameStream.Viewer stream;
        public volatile long lastSeen = System.currentTimeMillis();

        Viewer(ServerPlayer player, FrameStream.Viewer stream) {
            this.player = player;
            this.stream = stream;
        }
    }

    VmSession(VmRecord record, String backendId, VmLaunchSpec spec) {
        this.record = record;
        this.backendId = backendId;
        this.spec = spec;
        this.placeholder = new Framebuffer(Math.max(64, spec.width()), Math.max(64, spec.height()));
        this.stream = new FrameStream(placeholder);
        this.gpio = new GpioBridge(this);
    }

    /**
     * Hooks up the launched backend instance, unless the boot got cancelled in the meantime (force off,
     * delete or world shutdown while the backend was still starting). Returns false if the caller has
     * to power off the orphaned instance.
     */
    synchronized boolean attachIfLive(VmInstance instance) {
        if (cancelled) return false;
        this.instance = instance;
        this.stream = new FrameStream(instance.framebuffer());
        stateDirty = true;
        return true;
    }

    /** cancels a boot that has no instance yet. the launcher powers it off once it shows up. */
    synchronized void cancel(String msg) {
        cancelled = true;
        setState(State.STOPPED, msg);
    }

    synchronized boolean cancelled() {
        return cancelled;
    }

    public VmInstance instance() {
        return instance;
    }

    public FrameStream stream() {
        return stream;
    }

    public State state() {
        return state;
    }

    public String message() {
        return message;
    }

    public void setState(State s, String msg) {
        state = s;
        if (msg != null) message = msg;
        stateDirty = true;
    }

    public boolean isActive() {
        return state == State.STARTING || state == State.RUNNING || state == State.STOPPING;
    }

    public float fps() {
        VmInstance i = instance;
        return i == null ? 0f : i.framebuffer().sourceFps();
    }

    public Viewer addViewer(ServerPlayer player, boolean local, boolean rgb565, double bytesPerSecond) {
        // a new viewer needs the cursor shape too
        if (!viewers.containsKey(player.getUUID())) sentCursorShape = -1;
        return viewers.computeIfAbsent(player.getUUID(), k -> new Viewer(player, new FrameStream.Viewer(local, rgb565, bytesPerSecond)));
    }
}
