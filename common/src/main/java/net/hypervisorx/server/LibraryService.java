package net.hypervisorx.server;

import com.google.gson.JsonParseException;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.backend.BackendRegistry;
import net.hypervisorx.backend.BackendStatus;
import net.hypervisorx.backend.HypervisorBackend;
import net.hypervisorx.backend.hostcapture.HostCaptureBackend;
import net.hypervisorx.config.HvxConfig;
import net.hypervisorx.hardware.HostLimits;
import net.hypervisorx.images.ImageLibrary;
import net.hypervisorx.network.C2S;
import net.hypervisorx.network.S2C;
import net.hypervisorx.platform.Services;
import net.hypervisorx.vm.VmManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** OS library, downloads, backend detection and server settings behind the in-game screens. */
public final class LibraryService {
    private LibraryService() {
    }

    public static void send(ServerPlayer p) {
        VmManager m = VmManager.get();
        if (m == null) return;
        boolean admin = m.isAdmin(p);
        HvxConfig cfg = HvxConfig.get();
        List<S2C.BackendEntry> backends = new ArrayList<>();
        for (HypervisorBackend b : BackendRegistry.all()) {
            BackendStatus s = BackendRegistry.status(b.id());
            backends.add(new S2C.BackendEntry(b.id(), b.displayName(), s.available(), s.version(), s.message(), s.accelerated()));
        }
        List<String> windows = admin && cfg.allowHostWindowCapture ? HostCaptureBackend.windowTitles() : List.of();
        if (windows.size() > 64) windows = windows.subList(0, 64);
        boolean host = m.server().isSingleplayerOwner(p.getGameProfile());
        // files linked from the host's own PC only show up for ops
        List<S2C.ImageEntry> images = admin ? ImageLibrary.list() : ImageLibrary.list().stream().filter(e -> !e.linked()).toList();
        Services.PLATFORM.sendToPlayer(p, new S2C.Library(backends, images, ImageLibrary.jobs(), windows, admin, host,
                admin ? HvxConfig.GSON.toJson(cfg) : "", admin ? HostLimits.totalRamMb() : 0, admin ? HostLimits.cpus() : 0));
    }

    private static final java.util.Map<java.util.UUID, net.hypervisorx.util.RateLimiter> LIMITS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void action(ServerPlayer p, C2S.LibraryAction a) {
        VmManager m = VmManager.get();
        if (m == null) return;
        // library screen refreshes about once a second while downloads run, anything faster is spam
        if (LIMITS.size() > 512) LIMITS.clear();
        if (!LIMITS.computeIfAbsent(p.getUUID(), k -> new net.hypervisorx.util.RateLimiter(3, 6)).tryAcquire()) return;
        boolean admin = m.isAdmin(p);
        try {
            switch (a.action()) {
                case C2S.LibraryAction.REFRESH -> {
                }
                case C2S.LibraryAction.DOWNLOAD -> {
                    if (!admin) {
                        HostService.deny(p);
                        return;
                    }
                    ImageLibrary.download(a.arg(), m.backendContext());
                }
                case C2S.LibraryAction.CANCEL -> {
                    if (admin) ImageLibrary.cancel(a.arg());
                }
                case C2S.LibraryAction.IMPORT -> {
                    // only the singleplayer/LAN host gets to point at files on their own disk
                    if (!m.server().isSingleplayerOwner(p.getGameProfile())) {
                        HostService.deny(p);
                        return;
                    }
                    String name = ImageLibrary.importPath(a.arg());
                    p.sendSystemMessage(Component.translatable("hypervisorx.msg.imported", name).withStyle(ChatFormatting.GREEN));
                }
                case C2S.LibraryAction.DELETE -> {
                    if (!admin) {
                        HostService.deny(p);
                        return;
                    }
                    ImageLibrary.delete(a.arg());
                }
                case C2S.LibraryAction.RESCAN -> {
                    if (!admin) {
                        HostService.deny(p);
                        return;
                    }
                    BackendRegistry.rescan(m.backendContext()).thenRun(() -> m.server().execute(() -> send(p)));
                }
                default -> {
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            p.sendSystemMessage(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
        }
        send(p);
    }

    public static void applyConfig(ServerPlayer p, C2S.ServerConfig c) {
        VmManager m = VmManager.get();
        if (m == null || !m.isAdmin(p)) {
            if (m != null) HostService.deny(p);
            return;
        }
        try {
            HvxConfig updated = HvxConfig.GSON.fromJson(c.json(), HvxConfig.class);
            if (updated == null) throw new JsonParseException("empty");
            boolean atHost = !m.server().isDedicatedServer() && m.server().isSingleplayerOwner(p.getGameProfile());
            HvxConfig.get().applyFromPlayer(updated, atHost);
            HyperVisorX.LOG.info("{} updated the HyperVisor X server settings", p.getGameProfile().getName());
            p.sendSystemMessage(Component.translatable("hypervisorx.msg.settings_saved").withStyle(ChatFormatting.GREEN));
            BackendRegistry.rescan(HvxConfig.get().backendContext()).thenRun(() -> m.server().execute(() -> send(p)));
        } catch (JsonParseException e) {
            p.sendSystemMessage(Component.literal("Invalid settings: " + e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }
}
