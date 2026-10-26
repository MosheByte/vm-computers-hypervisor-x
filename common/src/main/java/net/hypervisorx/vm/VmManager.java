package net.hypervisorx.vm;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.backend.BackendContext;
import net.hypervisorx.backend.BackendException;
import net.hypervisorx.backend.BackendRegistry;
import net.hypervisorx.backend.BackendStatus;
import net.hypervisorx.backend.HypervisorBackend;
import net.hypervisorx.backend.VmInstance;
import net.hypervisorx.backend.VmLaunchSpec;
import net.hypervisorx.backend.embedded.EmbeddedBackend;
import net.hypervisorx.backend.virtualbox.VirtualBoxShutdown;
import net.hypervisorx.config.HvxConfig;
import net.hypervisorx.hardware.BuiltSpec;
import net.hypervisorx.hardware.HostLimits;
import net.hypervisorx.images.DriveStore;
import net.hypervisorx.images.ImageLibrary;
import net.hypervisorx.network.C2S;
import net.hypervisorx.network.S2C;
import net.hypervisorx.platform.Services;
import net.hypervisorx.vm.display.EncodedTile;
import net.hypervisorx.vm.display.FrameStream;
import net.hypervisorx.vm.display.Framebuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server side owner of every running VM. World access only happens on the server thread in
 * {@link #tick()}. Launching, encoding and streaming get their own background threads so the
 * game loop never sits waiting on a hypervisor.
 */
public final class VmManager {
    public enum StopMode { ACPI, FORCE, RESET }

    private static final int MAX_PACKET_BYTES = 900_000;
    private static volatile VmManager instance;

    private final MinecraftServer server;
    private final Map<UUID, VmSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService launcher;
    private final ScheduledExecutorService pump;
    private int ticks;

    private VmManager(MinecraftServer server) {
        this.server = server;
        AtomicInteger n = new AtomicInteger();
        this.launcher = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "HyperVisorX-Launcher-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.pump = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HyperVisorX-StreamPump");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        });
        long periodUs = 1_000_000L / HvxConfig.get().streamFps;
        pump.scheduleAtFixedRate(this::pumpCycle, periodUs, periodUs, TimeUnit.MICROSECONDS);
    }

    public static VmManager get() {
        return instance;
    }

    public static void onServerStarted(MinecraftServer server) {
        instance = new VmManager(server);
        BackendRegistry.rescan(HvxConfig.get().backendContext());
        HyperVisorX.LOG.info("{} ready (host: {} CPUs, {} MiB RAM)", HyperVisorX.MOD_NAME, HostLimits.cpus(), HostLimits.totalRamMb());
    }

    public static void onServerStopping(MinecraftServer server) {
        VmManager m = instance;
        instance = null;
        if (m != null) m.shutdownAll();
    }

    public MinecraftServer server() {
        return server;
    }

    public VmSavedData data() {
        return VmSavedData.get(server);
    }

    public BackendContext backendContext() {
        return HvxConfig.get().backendContext();
    }

    public VmRecord record(UUID id) {
        return data().get(id);
    }

    public VmSession session(UUID id) {
        return id == null ? null : sessions.get(id);
    }

    public Collection<VmSession> sessions() {
        return sessions.values();
    }

    public VmRecord createRecord(VmRecord.HostKind kind, String name, ServerPlayer owner) {
        VmRecord r = new VmRecord(UUID.randomUUID(), kind, name, owner == null ? null : owner.getUUID());
        if (owner != null) r.ownerName = owner.getGameProfile().getName();
        data().put(r);
        return r;
    }

    // ------------------------------------------------------------------ permissions

    public boolean isAdmin(ServerPlayer p) {
        return p.hasPermissions(2) || server.isSingleplayerOwner(p.getGameProfile());
    }

    public boolean canView(ServerPlayer p, VmRecord r) {
        if (r == null) return false;
        return isAdmin(p) || p.getUUID().equals(r.owner) || r.trusted.contains(p.getUUID()) || r.access != VmRecord.Access.PRIVATE;
    }

    public boolean canControl(ServerPlayer p, VmRecord r) {
        if (r == null) return false;
        return isAdmin(p) || p.getUUID().equals(r.owner) || r.trusted.contains(p.getUUID()) || r.access == VmRecord.Access.PUBLIC;
    }

    public boolean canConfigure(ServerPlayer p, VmRecord r) {
        return r != null && (isAdmin(p) || p.getUUID().equals(r.owner) || r.owner == null);
    }

    // ------------------------------------------------------------------ lifecycle

    public String resolveBackend(VmRecord r) {
        if (!r.backend.equals("auto")) return r.backend;
        if (!r.lastBackend.isEmpty() && BackendRegistry.status(r.lastBackend).available()) return r.lastBackend;
        return BackendRegistry.resolveAuto(HvxConfig.get().backendPreference);
    }

    /**
     * Boots a VM. Returns {@code null} if the launch started, otherwise why it didn't.
     *
     * @param host the host block, or null for a laptop held in hand
     */
    public Component start(VmRecord r, BuiltSpec hw, VmHost host) {
        HvxConfig cfg = HvxConfig.get();
        VmSession existing = sessions.get(r.id);
        if (existing != null && existing.isActive()) return Component.translatable("hypervisorx.msg.already_running");
        long running = sessions.values().stream().filter(VmSession::isActive).count();
        if (running >= cfg.maxRunningVms) return Component.translatable("hypervisorx.msg.too_many_vms", cfg.maxRunningVms);

        String backendId = resolveBackend(r);
        HypervisorBackend backend = BackendRegistry.get(backendId).orElse(null);
        if (backend == null) return Component.translatable("hypervisorx.msg.unknown_backend", backendId);
        BackendStatus status = BackendRegistry.status(backendId);
        if (!status.available()) return Component.translatable("hypervisorx.msg.backend_unavailable", backend.displayName(), status.message());
        List<Component> problems = hw.problemsFor(backendId);
        if (!problems.isEmpty()) return problems.get(0);

        boolean rack = r.kind == VmRecord.HostKind.RACK;
        int cores = HostLimits.clampCores(rack ? r.allocCores : hw.cores(), cfg.maxCoresPerVm);
        long used = sessions.values().stream().filter(VmSession::isActive).mapToLong(s -> s.ramCommittedMb).sum();
        int ram;
        if (backendId.equals(EmbeddedBackend.ID)) {
            ram = cfg.embeddedRamMb;
        } else {
            ram = HostLimits.clampRam(rack ? r.allocRamMb : hw.ramMb(), cfg.maxRamMbPerVm, cfg.maxHostRamPercent, used);
            if (ram < 256) return Component.translatable("hypervisorx.msg.host_ram", HostLimits.totalRamMb());
        }
        int span = Math.max(1, Math.min(r.spanMonitors, Math.max(1, hw.displays())));
        int width = Math.min(Framebuffer.MAX_DIMENSION, hw.width() * span);
        int height = Math.min(Framebuffer.MAX_DIMENSION, hw.height());
        int disk = rack ? Math.min(r.allocDiskGb, hw.diskGb()) : hw.diskGb();

        Path image = ImageLibrary.resolve(r.image);
        Path iso = image != null && !ImageLibrary.isDiskImage(image.getFileName().toString()) ? image : null;
        Path existingDisk = image != null && ImageLibrary.isDiskImage(image.getFileName().toString()) ? image : null;
        if (!r.image.isEmpty() && image == null && !backendId.equals(EmbeddedBackend.ID)) {
            return Component.translatable("hypervisorx.msg.image_missing", r.image);
        }
        List<Path> usb = new ArrayList<>();
        for (UUID d : hw.usbDrives()) {
            try {
                usb.add(DriveStore.ensure(d, 32));
            } catch (IOException e) {
                return Component.translatable("hypervisorx.msg.usb_failed", e.getMessage());
            }
        }
        boolean efi = r.efi || r.guestOs.needsEfiAndTpm();
        BackendContext ctx = backendContext();
        Path vmDir = ctx.vmsRoot().resolve(r.id.toString()).resolve(backendId);
        VmLaunchSpec spec = new VmLaunchSpec(r.id, r.name, r.guestOs, cores, ram, width, height, disk, hw.nvme(), efi,
                r.guestOs.needsEfiAndTpm(), vmDir, iso, existingDisk, usb, hw.gpio(), true, r.target, null);

        VmSession s = new VmSession(r, backendId, spec);
        s.ramCommittedMb = ram;
        s.handheld = host == null;
        sessions.put(r.id, s);
        r.lastBackend = backendId;
        r.lastBoot = System.currentTimeMillis();
        data().changed();
        HyperVisorX.LOG.info("Starting VM {} '{}' on {} ({} cores, {} MiB, {}x{})", r.id, r.name, backendId, cores, ram, width, height);

        launcher.execute(() -> {
            try {
                VmInstance inst = backend.launch(spec, ctx);
                inst.setListener(new VmInstance.Listener() {
                    @Override
                    public void stateChanged(VmInstance.State state, String message) {
                        s.setState(map(state), message);
                    }

                    @Override
                    public void audio(short[] samples, int count) {
                        fanOutAudio(s, samples, count);
                    }
                });
                s.attach(inst);
                s.gpio.attach(inst.gpioLink());
                s.setState(map(inst.state()), inst.statusMessage());
            } catch (BackendException e) {
                HyperVisorX.LOG.warn("VM {} failed to start: {}", r.id, e.getMessage());
                s.setState(VmSession.State.ERROR, e.getMessage());
            } catch (Throwable t) {
                HyperVisorX.LOG.error("VM {} crashed while starting", r.id, t);
                s.setState(VmSession.State.ERROR, t.toString());
            }
        });
        return null;
    }

    private static VmSession.State map(VmInstance.State s) {
        return switch (s) {
            case STARTING -> VmSession.State.STARTING;
            case RUNNING -> VmSession.State.RUNNING;
            case STOPPING -> VmSession.State.STOPPING;
            case STOPPED -> VmSession.State.STOPPED;
            case ERROR -> VmSession.State.ERROR;
        };
    }

    public void stop(UUID id, StopMode mode) {
        VmSession s = sessions.get(id);
        if (s == null) return;
        VmInstance inst = s.instance();
        if (inst == null) {
            if (mode == StopMode.FORCE) s.setState(VmSession.State.STOPPED, "Cancelled.");
            return;
        }
        switch (mode) {
            case ACPI -> {
                inst.powerButton();
                s.setState(VmSession.State.STOPPING, "Shutting down...");
            }
            case FORCE -> launcher.execute(() -> {
                try {
                    inst.forceOff();
                } catch (Throwable t) {
                    HyperVisorX.LOG.warn("Force off failed", t);
                }
                s.setState(VmSession.State.STOPPED, "Powered off.");
            });
            case RESET -> inst.reset();
        }
    }

    public void snapshot(UUID id, ServerPlayer requester) {
        VmSession s = sessions.get(id);
        if (s == null || s.instance() == null) return;
        String name = "hvx-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        launcher.execute(() -> {
            Component msg;
            try {
                s.instance().snapshot(name);
                msg = Component.translatable("hypervisorx.msg.snapshot_ok", name);
            } catch (BackendException e) {
                msg = Component.translatable("hypervisorx.msg.snapshot_failed", e.getMessage());
            }
            Component m = msg;
            server.execute(() -> requester.sendSystemMessage(m));
        });
    }

    public void changeMedia(UUID id, Path iso, ServerPlayer requester) {
        VmSession s = sessions.get(id);
        if (s == null || s.instance() == null) return;
        launcher.execute(() -> {
            try {
                s.instance().changeMedia(iso);
            } catch (BackendException e) {
                server.execute(() -> requester.sendSystemMessage(Component.literal(e.getMessage())));
            }
        });
    }

    /** Removes the record, stops the VM and optionally deletes backend files (rack slot removal). */
    public void delete(UUID id, boolean deleteDisks) {
        VmSession s = sessions.remove(id);
        VmRecord r = data().get(id);
        launcher.execute(() -> {
            if (s != null && s.instance() != null) s.instance().forceOff();
            if (r != null && deleteDisks && s != null) {
                BackendRegistry.get(s.backendId).ifPresent(b -> {
                    try {
                        b.destroy(s.spec, backendContext(), true);
                    } catch (BackendException e) {
                        HyperVisorX.LOG.warn("Could not delete VM files: {}", e.getMessage());
                    }
                });
            }
        });
        data().remove(id);
    }

    // ------------------------------------------------------------------ input & viewers

    public void input(ServerPlayer player, C2S.Input in) {
        VmSession s = sessions.get(in.vm());
        if (s == null || !canControl(player, s.record)) return;
        VmInstance inst = s.instance();
        if (inst == null) return;
        Framebuffer fb = inst.framebuffer();
        switch (in.kind()) {
            case C2S.Input.KEY -> inst.key(in.a(), in.b() != 0);
            case C2S.Input.TEXT -> {
                if (Character.isValidCodePoint(in.a())) inst.text(in.a());
            }
            case C2S.Input.POINTER -> inst.pointer(clamp(in.a(), fb.width() - 1), clamp(in.b(), fb.height() - 1), in.c() & 7);
            case C2S.Input.WHEEL -> inst.wheel(clamp(in.a(), fb.width() - 1), clamp(in.b(), fb.height() - 1), in.c() & 7, Math.max(-10, Math.min(10, in.d())));
            case C2S.Input.CTRL_ALT_DEL -> inst.ctrlAltDelete();
            default -> {
            }
        }
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    public void watch(ServerPlayer player, List<UUID> add, List<UUID> remove) {
        HvxConfig cfg = HvxConfig.get();
        boolean local = server.isSingleplayerOwner(player.getGameProfile());
        for (UUID id : remove) {
            VmSession s = sessions.get(id);
            if (s != null) s.viewers.remove(player.getUUID());
        }
        for (UUID id : add) {
            VmSession s = sessions.get(id);
            if (s == null || !canView(player, s.record)) continue;
            VmSession.Viewer v = s.addViewer(player, local, cfg.streamColorDepth == 16, cfg.maxBandwidthPerViewerKiB * 1024.0);
            v.lastSeen = System.currentTimeMillis();
            Services.PLATFORM.sendToPlayer(player, status(s, player));
        }
    }

    public S2C.VmStatus status(VmSession s, ServerPlayer p) {
        VmInstance inst = s.instance();
        int w = inst == null ? s.spec.width() : inst.framebuffer().width();
        int h = inst == null ? s.spec.height() : inst.framebuffer().height();
        int battery = s.record.kind == VmRecord.HostKind.LAPTOP && s.record.battery >= 0
                ? (int) Math.round(100.0 * s.record.battery / batteryMax(s.record)) : -1;
        return new S2C.VmStatus(s.record.id, s.record.name, s.state().name(), s.message() == null ? "" : s.message(), s.backendId,
                w, h, s.fps(), battery, canControl(p, s.record));
    }

    /** status for a VM that's not running (screens showing a powered off machine). */
    public S2C.VmStatus offlineStatus(VmRecord r, ServerPlayer p) {
        int battery = r.kind == VmRecord.HostKind.LAPTOP && r.battery >= 0
                ? (int) Math.round(100.0 * r.battery / batteryMax(r)) : -1;
        return new S2C.VmStatus(r.id, r.name, VmSession.State.STOPPED.name(), "", r.lastBackend, 0, 0, 0f, battery, canControl(p, r));
    }

    // ------------------------------------------------------------------ streaming (pump thread)

    private void pumpCycle() {
        try {
            for (VmSession s : sessions.values()) {
                if (s.viewers.isEmpty()) continue;
                FrameStream stream = s.stream();
                stream.commit();
                for (VmSession.Viewer v : s.viewers.values()) {
                    if (v.player.hasDisconnected()) {
                        s.viewers.remove(v.player.getUUID());
                        continue;
                    }
                    FrameStream.Batch batch = stream.collect(v.stream, MAX_PACKET_BYTES);
                    if (batch.reset()) {
                        Services.PLATFORM.sendToPlayer(v.player, new S2C.ScreenReset(s.record.id, batch.epoch(), batch.width(), batch.height()));
                    }
                    for (List<EncodedTile> packet : batch.packets()) {
                        Services.PLATFORM.sendToPlayer(v.player, new S2C.ScreenTiles(s.record.id, batch.epoch(), packet));
                    }
                }
            }
        } catch (Throwable t) {
            HyperVisorX.LOG.error("Stream pump error", t);
        }
    }

    private void fanOutAudio(VmSession s, short[] samples, int count) {
        if (count <= 0) return;
        List<ServerPlayer> targets = s.audioTargets;
        if (targets.isEmpty()) return;
        byte[] pcm = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            pcm[i * 2] = (byte) samples[i];
            pcm[i * 2 + 1] = (byte) (samples[i] >> 8);
        }
        S2C.Audio payload = new S2C.Audio(s.record.id, s.audioOrigin, pcm);
        for (ServerPlayer p : targets) {
            if (!p.hasDisconnected()) Services.PLATFORM.sendToPlayer(p, payload);
        }
    }

    // ------------------------------------------------------------------ server thread

    public VmHost host(VmRecord r) {
        if (r == null || r.dimension == null || r.hostPos == null) return null;
        ServerLevel level = server.getLevel(r.dimension);
        if (level == null || !level.isLoaded(r.hostPos)) return null;
        BlockEntity be = level.getBlockEntity(r.hostPos);
        return be instanceof VmHost h && h.slotOf(r.id) >= 0 ? h : null;
    }

    public void tick() {
        ticks++;
        HvxConfig cfg = HvxConfig.get();
        for (VmSession s : sessions.values()) {
            VmHost host = host(s.record);
            if (s.stateDirty) {
                s.stateDirty = false;
                if (host != null) host.onVmStateChanged(host.slotOf(s.record.id), s.state());
                broadcastStatus(s);
            } else if (ticks % 20 == 0 && !s.viewers.isEmpty()) {
                broadcastStatus(s);
            }
            if (s.state() == VmSession.State.RUNNING) s.gpio.tick(host);
            if (s.record.kind == VmRecord.HostKind.LAPTOP && s.isActive()) tickBattery(s, host != null, cfg);
            if (ticks % 20 == 0) refreshAudio(s, host, cfg);
            if (s.state() == VmSession.State.STOPPED || s.state() == VmSession.State.ERROR) {
                sessions.remove(s.record.id, s);
                if (host != null) host.onVmStateChanged(host.slotOf(s.record.id), s.state());
                broadcastStatus(s);
                if (s.state() == VmSession.State.ERROR) {
                    notifyOwner(s, Component.translatable("hypervisorx.msg.vm_error", s.record.name, s.message()));
                }
                VmInstance inst = s.instance();
                if (inst != null) launcher.execute(inst::close);
            }
        }
    }

    /** laptop VM battery capacity in ticks (rugged models last longer). */
    public static int batteryMax(VmRecord r) {
        return (int) (HvxConfig.get().laptopBatteryTicks * net.hypervisorx.hardware.LaptopModel.byId(r.laptopModel).batteryFactor());
    }

    private void tickBattery(VmSession s, boolean docked, HvxConfig cfg) {
        VmRecord r = s.record;
        int max = batteryMax(r);
        if (r.battery < 0) r.battery = max;
        if (docked) {
            r.battery = Math.min(max, r.battery + 4);
        } else if (--r.battery <= 0) {
            r.battery = 0;
            notifyOwner(s, Component.translatable("hypervisorx.msg.battery_empty", r.name));
            stop(r.id, StopMode.FORCE);
        }
        if (ticks % 200 == 0) data().changed();
    }

    private void refreshAudio(VmSession s, VmHost host, HvxConfig cfg) {
        List<ServerPlayer> targets = new ArrayList<>();
        if (s.handheld || host == null) {
            ServerPlayer owner = s.record.owner == null ? null : server.getPlayerList().getPlayer(s.record.owner);
            if (owner != null) targets.add(owner);
            s.audioOrigin = null;
        } else {
            BlockPos origin = host.hostPos();
            int range = cfg.audioRange;
            BlockPos speaker = net.hypervisorx.block.SpeakerBlock.findNear(host.hostLevel(), origin, 6);
            if (speaker != null) {
                origin = speaker;
                range *= 2;
            }
            s.audioOrigin = origin;
            double r2 = (double) range * range;
            for (ServerPlayer p : ((ServerLevel) host.hostLevel()).players()) {
                if (p.blockPosition().distSqr(origin) <= r2 && canView(p, s.record)) targets.add(p);
            }
        }
        s.audioTargets = List.copyOf(targets);
        long now = System.currentTimeMillis();
        // clients resend their watch list every few secs, so a quiet viewer has stopped rendering the screen
        s.viewers.values().removeIf(v -> v.player.hasDisconnected() || now - v.lastSeen > 15_000);
    }

    private void broadcastStatus(VmSession s) {
        for (VmSession.Viewer v : s.viewers.values()) {
            if (!v.player.hasDisconnected()) Services.PLATFORM.sendToPlayer(v.player, status(s, v.player));
        }
        if (s.record.owner != null) {
            ServerPlayer owner = server.getPlayerList().getPlayer(s.record.owner);
            if (owner != null && !s.viewers.containsKey(owner.getUUID())) Services.PLATFORM.sendToPlayer(owner, status(s, owner));
        }
    }

    private void notifyOwner(VmSession s, Component msg) {
        if (s.record.owner == null) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(s.record.owner);
        if (owner != null) owner.sendSystemMessage(msg);
    }

    /** keeps a viewer alive as long as the client is still rendering the screen. */
    public void touchViewer(ServerPlayer player, UUID vm) {
        VmSession s = sessions.get(vm);
        if (s != null) {
            VmSession.Viewer v = s.viewers.get(player.getUUID());
            if (v != null) v.lastSeen = System.currentTimeMillis();
        }
    }

    public void onPlayerLeft(ServerPlayer player) {
        for (VmSession s : sessions.values()) s.viewers.remove(player.getUUID());
    }

    // ------------------------------------------------------------------ shutdown

    private void shutdownAll() {
        pump.shutdownNow();
        String mode = HvxConfig.get().onServerStop;
        List<Thread> threads = new ArrayList<>();
        for (VmSession s : sessions.values()) {
            VmInstance inst = s.instance();
            if (inst == null) continue;
            Thread t = new Thread(() -> {
                try {
                    boolean done = false;
                    if (mode.equals("suspend")) done = inst.suspend();
                    if (!done && !mode.equals("kill")) {
                        inst.powerButton();
                        long end = System.currentTimeMillis() + 15_000;
                        while (System.currentTimeMillis() < end && inst.state() != VmInstance.State.STOPPED) Thread.sleep(250);
                        done = inst.state() == VmInstance.State.STOPPED;
                    }
                    if (!done) inst.forceOff();
                    inst.close();
                } catch (Throwable t2) {
                    HyperVisorX.LOG.warn("Error while stopping VM {}: {}", s.record.id, t2.toString());
                }
            }, "HyperVisorX-Shutdown");
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        long deadline = System.currentTimeMillis() + 30_000;
        for (Thread t : threads) {
            try {
                t.join(Math.max(1, deadline - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        sessions.clear();
        VirtualBoxShutdown.stopWebService();
        launcher.shutdownNow();
        HyperVisorX.LOG.info("All virtual machines stopped ({})", mode);
    }
}
