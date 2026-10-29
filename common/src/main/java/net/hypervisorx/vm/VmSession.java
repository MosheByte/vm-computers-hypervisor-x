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
    /** The guest pointer a captured mouse moves (network thread). */
    private int pointerX = -1;
    private int pointerY = -1;
    private int pointerWarp;

    /** player pointed right at this spot (free mouse), captured motion picks up from here. */
    public synchronized void pointerAt(int x, int y) {
        pointerX = x;
        pointerY = y;
    }

    /**
     * Moves the guest pointer by a captured mouse's motion and returns where it is now. When the guest moved
     * its own cursor (a game re-centring it, reported by VMware), motion continues from there, so a turn in a
     * game is never applied twice nor jumps back.
     */
    public synchronized int[] movePointer(net.hypervisorx.backend.VmInstance inst, int dx, int dy) {
        net.hypervisorx.backend.GuestCursor c = inst.cursor();
        if (c != null && c.warpVersion() != pointerWarp) {
            pointerWarp = c.warpVersion();
            int[] p = c.warpPosition();
            pointerX = p[0];
            pointerY = p[1];
        }
        int w = inst.framebuffer().width();
        int h = inst.framebuffer().height();
        if (pointerX < 0 || pointerY < 0) {
            pointerX = w / 2;
            pointerY = h / 2;
        }
        pointerX = Math.max(0, Math.min(w - 1, pointerX + dx));
        pointerY = Math.max(0, Math.min(h - 1, pointerY + dy));
        return new int[]{pointerX, pointerY};
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
