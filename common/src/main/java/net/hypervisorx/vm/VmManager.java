package net.hypervisorx.vm;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.backend.BackendContext;
import net.hypervisorx.backend.BackendException;
import net.hypervisorx.backend.BackendRegistry;
import net.hypervisorx.backend.BackendStatus;
import net.hypervisorx.backend.GuestTools;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
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
    private final Map<UUID, net.hypervisorx.util.RateLimiter> inputLimits = new ConcurrentHashMap<>();
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

    /** marks a VM as still belonging to a computer that exists (block loaded or laptop carried). */
    public static void touch(UUID id) {
        VmManager m = instance;
        if (m == null || id == null) return;
        VmRecord r = m.record(id);
        if (r != null) r.lastSeen = System.currentTimeMillis();
    }

    public static void onServerStarted(MinecraftServer server) {
        instance = new VmManager(server);
        instance.collectOrphanDirectories();
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

    /** true for backends that run programs on the host machine (so everything except the embedded MicroVM). */
    public static boolean isHostBackend(String backendId) {
        return !backendId.equals(EmbeddedBackend.ID);
    }

    /**
     * Boots a VM for a player. Checks backend perms, host window capture rights, linked images and
     * per-player quotas, then calls {@link #startUnchecked(VmRecord, BuiltSpec, VmHost)}. Anything a
     * player starts has to go through here.
     */
    public Component start(ServerPlayer requester, VmRecord r, BuiltSpec hw, VmHost host) {
        HvxConfig cfg = HvxConfig.get();
        boolean admin = isAdmin(requester);
        String backendId = resolveBackend(r);
        if (!admin) {
            if (backendId.equals(net.hypervisorx.backend.hostcapture.HostCaptureBackend.ID)) {
                return Component.translatable("hypervisorx.msg.capture_admin_only");
            }
            if (isHostBackend(backendId) && !requester.hasPermissions(cfg.minPermissionForHostBackends)) {
                return Component.translatable("hypervisorx.msg.backend_permission");
            }
            if (ImageLibrary.isLinked(r.image)) return Component.translatable("hypervisorx.msg.linked_image_admin_only");
            UUID owner = r.owner != null ? r.owner : requester.getUUID();
            List<VmSession> mine = sessions.values().stream().filter(VmSession::isActive)
                    .filter(s -> owner.equals(s.record.owner)).toList();
            if (mine.size() >= cfg.maxRunningVmsPerPlayer) {
                return Component.translatable("hypervisorx.msg.player_vm_quota", cfg.maxRunningVmsPerPlayer);
            }
            int mineRam = mine.stream().mapToInt(s -> s.ramCommittedMb).sum();
            int wanted = backendId.equals(EmbeddedBackend.ID) ? cfg.embeddedRamMb
                    : Math.min(cfg.maxRamMbPerVm, r.kind == VmRecord.HostKind.RACK ? r.allocRamMb : hw.ramMb());
            if (mineRam + wanted > cfg.maxRamMbPerPlayer) {
                return Component.translatable("hypervisorx.msg.player_ram_quota", cfg.maxRamMbPerPlayer / 1024);
            }
        }
        return startUnchecked(r, hw, host);
    }

    /** folder with a VM's backend files: inside the world save, or the old pre-1.1 global folder if it's already there. */
    public Path vmRoot(UUID id) {
        Path legacy = HvxConfig.dataRoot().resolve("vms").resolve(id.toString());
        if (Files.isDirectory(legacy)) return legacy;
        return worldVmsRoot().resolve(id.toString());
    }

    private Path worldVmsRoot() {
        // LevelResource.ROOT is ".", normalize it so hypervisors hand back the same path (vmrun list)
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize()
                .resolve("hypervisorx").resolve("vms");
    }

    /** deletes VM folders in this world that no record points to anymore (deleted racks, removed records). */
    private void collectOrphanDirectories() {
        Path root = worldVmsRoot();
        if (!Files.isDirectory(root)) return;
        Set<String> known = new HashSet<>();
        for (VmRecord r : data().all()) known.add(r.id.toString());
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : dirs.toList()) {
                String name = d.getFileName().toString();
                if (!Files.isDirectory(d) || known.contains(name)) continue;
                try {
                    UUID.fromString(name);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                launcher.execute(() -> destroyFiles(UUID.fromString(name), name, d));
            }
        } catch (IOException e) {
            HyperVisorX.LOG.warn("Could not scan {}: {}", root, e.getMessage());
        }
    }

    /**
     * VMs whose computer hasn't been loaded or carried for {@code days} days (burned items, destroyed
     * blocks). For {@code /hvx gc}, it doesn't delete anything on its own.
     */
    public List<VmRecord> staleRecords(int days) {
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        return data().all().stream().filter(r -> !sessions.containsKey(r.id) && r.lastSeen < cutoff).toList();
    }

    /** unregisters a VM from every backend that has files for it, then wipes its folder (launcher thread). */
    private void destroyFiles(UUID id, String name, Path root) {
        if (Files.isDirectory(root)) {
            try (Stream<Path> backends = Files.list(root)) {
                for (Path dir : backends.toList()) {
                    String backendId = dir.getFileName().toString();
                    BackendRegistry.get(backendId).ifPresent(b -> {
                        try {
                            b.destroy(VmLaunchSpec.forFiles(id, name, dir), backendContext(), true);
                        } catch (BackendException e) {
                            HyperVisorX.LOG.warn("Could not unregister VM {} from {}: {}", id, backendId, e.getMessage());
                        }
                    });
                }
            } catch (IOException e) {
                HyperVisorX.LOG.warn("Could not list {}: {}", root, e.getMessage());
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            } catch (IOException e) {
                HyperVisorX.LOG.warn("Could not delete {}: {}", root, e.getMessage());
            }
        }
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
    public Component startUnchecked(VmRecord r, BuiltSpec hw, VmHost host) {
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
            // leftover host budget can be any number but hypervisors want whole blocks (VMware: multiple of 4 MB)
            ram = ram / 64 * 64;
            if (ram < 256) {
                long allowed = HostLimits.totalRamMb() * cfg.maxHostRamPercent / 100;
                return Component.translatable("hypervisorx.msg.host_ram", used, allowed, cfg.maxHostRamPercent, HostLimits.totalRamMb());
            }
        }
        int span = Math.max(1, Math.min(r.spanMonitors, Math.max(1, hw.displays())));
        int width = Math.min(Framebuffer.MAX_DIMENSION, hw.width() * span);
        int height = Math.min(Framebuffer.MAX_DIMENSION, hw.height());
        int disk = rack ? Math.min(r.allocDiskGb, hw.diskGb()) : hw.diskGb();

        Path image = ImageLibrary.resolve(r.image);
        Path iso = image != null && !ImageLibrary.isDiskImage(image.getFileName().toString()) ? image : null;
        Path existingDisk = image != null && ImageLibrary.isDiskImage(image.getFileName().toString()) ? image : null;
        Path librarySource = existingDisk;
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
        Path vmDir = vmRoot(r.id).resolve(backendId);
        if (existingDisk != null) {
            // don't boot a library image in place: each VM gets its own copy (made on the launcher thread)
            existingDisk = vmDir.resolve("imported-" + existingDisk.getFileName());
        }
        VmLaunchSpec spec = new VmLaunchSpec(r.id, r.name, r.guestOs, cores, ram, width, height, disk, hw.nvme(), efi,
                r.guestOs.needsEfiAndTpm(), vmDir, iso, existingDisk, usb, hw.gpio(), true, r.target, null, r.guestTools);

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
                if (librarySource != null && !Files.exists(spec.existingDisk())) {
                    s.setState(VmSession.State.STARTING, "Copying the disk image...");
                    ImageLibrary.copyDiskImage(librarySource, spec.existingDisk());
                }
                if (s.cancelled()) return;
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
                if (!s.attachIfLive(inst)) {
                    HyperVisorX.LOG.info("VM {} was cancelled while booting; powering it off", r.id);
                    inst.forceOff();
                    inst.close();
                    return;
                }
                s.gpio.attach(inst.gpioLink());
                s.setState(map(inst.state()), inst.statusMessage());
            } catch (BackendException | IOException e) {
                HyperVisorX.LOG.warn("VM {} failed to start: {}", r.id, e.getMessage());
                if (!s.cancelled()) s.setState(VmSession.State.ERROR, e.getMessage());
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
            if (mode != StopMode.RESET) s.cancel("Cancelled.");
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

    /**
     * The computer got removed (broken, blown up). Ask the guest to shut down like a power button press
     * and only pull the plug if it's still running a minute later, so a creeper doesn't wreck the guest
     * file system mid-write.
     */
    public void stopGracefully(UUID id) {
        VmSession s = sessions.get(id);
        if (s == null) return;
        VmInstance inst = s.instance();
        if (inst == null) {
            s.cancel("The computer was removed.");
            return;
        }
        inst.powerButton();
        s.setState(VmSession.State.STOPPING, "The computer was removed - shutting down...");
        launcher.execute(() -> {
            long end = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < end && inst.state() != VmInstance.State.STOPPED && inst.state() != VmInstance.State.ERROR) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (inst.state() != VmInstance.State.STOPPED) {
                inst.forceOff();
                s.setState(VmSession.State.STOPPED, "Powered off.");
            }
        });
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

    /**
     * Installs the engine's guest tools on a running, already installed Windows. Puts the tools disc in the
     * drive if it wasn't attached at boot, then types the silent install into the Run box and runs it as admin.
     * (A fresh Windows install does this on its own through its answer file.)
     */
    public void installGuestTools(UUID id, ServerPlayer requester) {
        VmSession s = sessions.get(id);
        VmRecord r = data().get(id);
        if (s == null || r == null || s.instance() == null || s.state() != VmSession.State.RUNNING) {
            requester.sendSystemMessage(Component.translatable("hypervisorx.msg.tools_not_running"));
            return;
        }
        if (!r.guestOs.isWindowsFamily()) {
            requester.sendSystemMessage(Component.translatable("hypervisorx.msg.tools_not_windows"));
            return;
        }
        HypervisorBackend backend = BackendRegistry.get(s.backendId).orElse(null);
        if (backend == null) return;
        BackendContext ctx = backendContext();
        launcher.execute(() -> {
            GuestTools.Disc disc = backend.guestTools(s.spec, ctx);
            Component msg;
            if (disc == null) {
                msg = Component.translatable("hypervisorx.msg.tools_none", backend.displayName());
            } else {
                try {
                    if (!s.spec.guestTools()) s.instance().changeMedia(disc.disc());
                    GuestKeyboard.runElevated(s.instance(), disc.windowsCommand());
                    msg = Component.translatable("hypervisorx.msg.tools_installing");
                } catch (BackendException e) {
                    msg = Component.translatable("hypervisorx.msg.tools_restart");
                }
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

    /** removes the record, stops the VM and, if asked, deletes all its backend files (running or not). */
    public void delete(UUID id, boolean deleteDisks) {
        VmSession s = sessions.remove(id);
        VmRecord r = data().get(id);
        if (s != null && s.instance() == null) s.cancel("Deleted.");
        Path root = vmRoot(id);
        String name = r != null ? r.name : id.toString();
        launcher.execute(() -> {
            if (s != null && s.instance() != null) {
                s.instance().forceOff();
                s.instance().close();
            }
            if (deleteDisks) destroyFiles(id, name, root);
        });
        data().remove(id);
    }

    // ------------------------------------------------------------------ input & viewers

    /**
     * Keyboard/mouse input. On NeoForge this runs on the network thread, so it only reads thread safe
     * state: the per-session controller set that {@link #tick()} refreshes, and a per-player rate limit
     * (the client already merges pointer moves, and 400 events/s is way above real typing).
     */
    public void input(ServerPlayer player, C2S.Input in) {
        VmSession s = sessions.get(in.vm());
        if (s == null || !s.controllers.contains(player.getUUID())) return;
        if (!inputLimits.computeIfAbsent(player.getUUID(), k -> new net.hypervisorx.util.RateLimiter(400, 800)).tryAcquire()) return;
        VmInstance inst = s.instance();
        if (inst == null) return;
        Framebuffer fb = inst.framebuffer();
        switch (in.kind()) {
            case C2S.Input.KEY -> inst.key(in.a(), in.b() != 0);
            case C2S.Input.TEXT -> {
                if (Character.isValidCodePoint(in.a())) inst.text(in.a());
            }
            case C2S.Input.POINTER -> {
                int x = clamp(in.a(), fb.width() - 1);
                int y = clamp(in.b(), fb.height() - 1);
                s.pointerAt(x, y);
                inst.pointer(x, y, in.c() & 7);
            }
            case C2S.Input.WHEEL -> inst.wheel(clamp(in.a(), fb.width() - 1), clamp(in.b(), fb.height() - 1), in.c() & 7, Math.max(-10, Math.min(10, in.d())));
            case C2S.Input.CTRL_ALT_DEL -> inst.ctrlAltDelete();
            // captured mouse: motion in guest pixels, applied where the guest pointer actually is
            case C2S.Input.POINTER_REL -> s.pointerMotion(inst, Math.max(-2000, Math.min(2000, in.a())), Math.max(-2000, Math.min(2000, in.b())), in.c() & 7);
            case C2S.Input.WHEEL_REL -> {
                int[] p = s.wheelAt(inst);
                inst.wheel(p[0], p[1], in.c() & 7, Math.max(-10, Math.min(10, in.d())));
            }
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
        long watching = sessions.values().stream().filter(s -> s.viewers.containsKey(player.getUUID())).count();
        for (UUID id : add) {
            VmSession s = sessions.get(id);
            if (s == null || !canView(player, s.record) || !mayStreamTo(player, s, cfg)) continue;
            if (!s.viewers.containsKey(player.getUUID()) && watching >= MAX_STREAMS_PER_PLAYER) continue;
            if (!s.viewers.containsKey(player.getUUID())) watching++;
            VmSession.Viewer v = s.addViewer(player, local, cfg.streamColorDepth == 16, cfg.maxBandwidthPerViewerKiB * 1024.0);
            v.lastSeen = System.currentTimeMillis();
            Services.PLATFORM.sendToPlayer(player, status(s, player));
        }
    }

    private static final int MAX_STREAMS_PER_PLAYER = 6;

    /**
     * Server decides which screens a player gets. Owners, trusted players and ops: from anywhere (focus
     * mode, remote desktop). Everyone else: only near the computer.
     */
    private boolean mayStreamTo(ServerPlayer player, VmSession s, HvxConfig cfg) {
        if (isAdmin(player) || canControl(player, s.record) && s.record.access != VmRecord.Access.PUBLIC) return true;
        if (player.getUUID().equals(s.record.owner) || s.record.trusted.contains(player.getUUID())) return true;
        VmRecord r = s.record;
        if (r.hostPos == null || r.dimension == null || !player.level().dimension().equals(r.dimension)) return false;
        double max = cfg.maxWatchDistance;
        return player.distanceToSqr(r.hostPos.getCenter()) <= max * max;
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
                sendCursor(s);
                for (VmSession.Viewer v : s.viewers.values()) {
                    if (v.player.hasDisconnected()) {
                        s.viewers.remove(v.player.getUUID());
                        continue;
                    }
                    FrameStream.Batch batch = stream.collect(v.stream, MAX_PACKET_BYTES);
                    if (batch.reset()) {
                        Services.PLATFORM.sendToPlayer(v.player, new S2C.ScreenReset(s.record.id, batch.epoch(), batch.width(), batch.height()));
                    }
                    List<List<EncodedTile>> packets = batch.packets();
                    for (int i = 0; i < packets.size(); i++) {
                        Services.PLATFORM.sendToPlayer(v.player, new S2C.ScreenTiles(s.record.id, batch.epoch(), packets.get(i),
                                i == packets.size() - 1));
                    }
                }
            }
        } catch (Throwable t) {
            HyperVisorX.LOG.error("Stream pump error", t);
        }
    }

    /** shape changes go to every viewer. positions the guest forced only go to controllers (they're the ones moving the pointer). */
    private void sendCursor(VmSession s) {
        VmInstance inst = s.instance();
        net.hypervisorx.backend.GuestCursor c = inst == null ? null : inst.cursor();
        if (c == null) return;
        int shape = c.shapeVersion();
        if (shape != s.sentCursorShape && c.hasShape()) {
            s.sentCursorShape = shape;
            int[] snap = c.snapshot();
            S2C.CursorShape p = new S2C.CursorShape(s.record.id, snap[0], snap[1], snap[2], snap[3], snap[4] != 0,
                    java.util.Arrays.copyOfRange(snap, 5, snap.length));
            for (VmSession.Viewer v : s.viewers.values()) {
                if (!v.player.hasDisconnected()) Services.PLATFORM.sendToPlayer(v.player, p);
            }
        }
        int warp = c.warpVersion();
        if (warp != s.sentCursorWarp) {
            s.sentCursorWarp = warp;
            int[] pos = c.warpPosition();
            S2C.CursorWarp p = new S2C.CursorWarp(s.record.id, pos[0], pos[1]);
            for (VmSession.Viewer v : s.viewers.values()) {
                if (!v.player.hasDisconnected() && s.controllers.contains(v.player.getUUID())) Services.PLATFORM.sendToPlayer(v.player, p);
            }
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
        if (ticks % 20 == 0) {
            inputLimits.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        }
        for (VmSession s : sessions.values()) {
            VmHost host = host(s.record);
            if (ticks % 20 == 0) {
                s.record.lastSeen = System.currentTimeMillis();
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (canControl(p, s.record)) s.controllers.add(p.getUUID());
                    else s.controllers.remove(p.getUUID());
                }
                s.controllers.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            }
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
            if (inst == null) {
                s.cancel("World closed.");
                continue;
            }
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
