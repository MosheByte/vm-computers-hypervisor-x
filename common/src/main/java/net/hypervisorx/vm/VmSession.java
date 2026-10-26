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

    void attach(VmInstance instance) {
        this.instance = instance;
        this.stream = new FrameStream(instance.framebuffer());
        stateDirty = true;
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
        return viewers.computeIfAbsent(player.getUUID(), k -> new Viewer(player, new FrameStream.Viewer(local, rgb565, bytesPerSecond)));
    }
}
