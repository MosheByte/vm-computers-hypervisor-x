package net.hypervisorx.server;

import com.mojang.authlib.GameProfile;
import net.hypervisorx.backend.BackendRegistry;
import net.hypervisorx.backend.GuestOs;
import net.hypervisorx.block.entity.ComputerBlockEntity;
import net.hypervisorx.block.entity.DisplayBlockEntity;
import net.hypervisorx.block.entity.HostBlockEntity;
import net.hypervisorx.block.entity.LaptopBlockEntity;
import net.hypervisorx.block.entity.ServerRackBlockEntity;
import net.hypervisorx.hardware.BuiltSpec;
import net.hypervisorx.hardware.FormFactor;
import net.hypervisorx.images.ImageLibrary;
import net.hypervisorx.menu.ComputerMenu;
import net.hypervisorx.network.C2S;
import net.hypervisorx.network.S2C;
import net.hypervisorx.platform.Services;
import net.hypervisorx.registry.HvxSounds;
import net.hypervisorx.vm.VmHost;
import net.hypervisorx.vm.VmManager;
import net.hypervisorx.vm.VmRecord;
import net.hypervisorx.vm.VmSession;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server side of the computer screen, monitors, keyboards and placed laptops. */
public final class HostService {
    private static final double MAX_USE_DISTANCE_SQ = 10 * 10;

    private HostService() {
    }

    private static VmManager mgr() {
        return VmManager.get();
    }

    // ---------------------------------------------------------------- access rules

    public static boolean mayAssemble(ServerPlayer p, HostBlockEntity host) {
        VmManager m = mgr();
        if (m == null) return false;
        if (host.ownerId() == null || host.ownerId().equals(p.getUUID()) || m.isAdmin(p)) return true;
        for (int i = 0; i < host.vmSlots(); i++) {
            VmRecord r = m.record(host.vmId(i));
            if (r != null && r.trusted.contains(p.getUUID())) return true;
        }
        p.displayClientMessage(Component.translatable("hypervisorx.msg.owned_by", host.ownerName()).withStyle(ChatFormatting.RED), true);
        return false;
    }

    public static boolean mayEditDisplay(ServerPlayer p, DisplayBlockEntity d) {
        if (d.linkHost() == null || d.getLevel() == null) return true;
        if (d.getLevel().getBlockEntity(d.linkHost()) instanceof HostBlockEntity host) return mayAssemble(p, host);
        return true;
    }

    public static boolean mayPickUp(ServerPlayer p, LaptopBlockEntity be) {
        return mayAssemble(p, be);
    }

    // ---------------------------------------------------------------- menu & info

    /** VM host behind a block pos (rack units resolve to their controller). */
    public static HostBlockEntity resolveHost(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ServerRackBlockEntity rack) return rack.controller();
        return be instanceof HostBlockEntity h ? h : null;
    }

    public static void openHostMenu(ServerPlayer p, BlockPos pos) {
        BlockEntity be = p.level().getBlockEntity(pos);
        Container parts;
        FormFactor form;
        HostBlockEntity owner;
        if (be instanceof ComputerBlockEntity c) {
            parts = c.parts();
            form = c.form();
            owner = c;
        } else if (be instanceof ServerRackBlockEntity r) {
            parts = r.parts();
            form = FormFactor.RACK;
            owner = r.controller();
        } else {
            return;
        }
        if (!mayAssemble(p, owner)) return;
        Component title = Component.translatable("block.hypervisorx." + form.id());
        Services.PLATFORM.openMenu(p, new SimpleMenuProvider((id, inv, pl) -> new ComputerMenu(id, inv, pos, form, parts), title), pos);
        sendHostInfo(p, pos);
    }

    public static void sendHostInfo(ServerPlayer p, BlockPos pos) {
        VmManager m = mgr();
        HostBlockEntity host = resolveHost(p.level(), pos);
        if (m == null || host == null) return;
        BuiltSpec spec = host.builtSpec();
        boolean rack = host instanceof ServerRackBlockEntity;
        List<S2C.VmSlotInfo> slots = new ArrayList<>();
        int usedCores = 0;
        int usedRam = 0;
        int usedDisk = 0;
        for (int i = 0; i < host.vmSlots(); i++) {
            UUID id = host.vmId(i);
            VmRecord r = m.record(id);
            if (rack && r == null) continue;
            if (r != null && rack) {
                usedCores += r.allocCores;
                usedRam += r.allocRamMb;
                usedDisk += r.allocDiskGb;
            }
            slots.add(slotInfo(p, i, r, m));
        }
        S2C.HostInfo info = new S2C.HostInfo(pos, rack, spec.cores(), spec.ramMb(), spec.width(), spec.height(), spec.displays(),
                spec.diskGb(), spec.nvme(), spec.gpio(), spec.wifi(), spec.problems(), slots,
                Math.max(0, spec.cores() - usedCores), Math.max(0, spec.ramMb() - usedRam), Math.max(0, spec.diskGb() - usedDisk));
        Services.PLATFORM.sendToPlayer(p, info);
    }

    private static S2C.VmSlotInfo slotInfo(ServerPlayer p, int slot, VmRecord r, VmManager m) {
        if (r == null) {
            return new S2C.VmSlotInfo(slot, null, "", "STOPPED", "", "auto", GuestOs.LINUX.name(), "", "", VmRecord.Access.PRIVATE.name(),
                    1, 0, 0, 0, false, true, true, true);
        }
        VmSession s = m.session(r.id);
        String backendLabel = r.backend.equals("auto") ? "auto" : r.backend;
        return new S2C.VmSlotInfo(slot, r.id, r.name, s == null ? "STOPPED" : s.state().name(), s == null ? "" : s.message(),
                backendLabel, r.guestOs.name(), r.image, r.target, r.access.name(), r.spanMonitors, r.allocCores, r.allocRamMb,
                r.allocDiskGb, r.efi, r.guestTools, m.canControl(p, r), m.canConfigure(p, r));
    }

    /** Makes the VM record for a host slot the first time it's used. */
    public static VmRecord ensureRecord(ServerPlayer p, VmHost host, int slot) {
        VmManager m = mgr();
        VmRecord r = m.record(host.vmId(slot));
        if (r != null) return r;
        String ownerName = host.ownerId() != null && host.ownerName() != null && !host.ownerName().isEmpty()
                ? host.ownerName() : p.getGameProfile().getName();
        String name = switch (host.hostKind()) {
            case RACK -> "Server VM " + (slot + 1);
            case LAPTOP -> ownerName + "'s Laptop";
            case CASE -> ownerName + "'s PC";
        };
        r = m.createRecord(host.hostKind(), name, host.ownerId() != null && !host.ownerId().equals(p.getUUID()) ? null : p);
        if (r.owner == null && host.ownerId() != null) {
            r.owner = host.ownerId();
            r.ownerName = host.ownerName();
        }
        if (host.hostKind() == VmRecord.HostKind.RACK) {
            BuiltSpec pool = host.builtSpec();
            r.allocCores = Math.max(1, Math.min(4, pool.cores()));
            r.allocRamMb = Math.max(1024, Math.min(8192, pool.ramMb()));
            r.allocDiskGb = Math.max(16, Math.min(128, pool.diskGb()));
        }
        if (host instanceof LaptopBlockEntity lb) r.laptopModel = lb.model().id();
        r.hostPos = host.hostPos().immutable();
        r.dimension = host.hostLevel().dimension();
        host.setVmId(slot, r.id);
        m.data().changed();
        return r;
    }

    private static boolean near(ServerPlayer p, BlockPos pos) {
        return p.distanceToSqr(pos.getCenter()) <= MAX_USE_DISTANCE_SQ;
    }

    // ---------------------------------------------------------------- actions from the screen

    private static final java.util.Map<java.util.UUID, net.hypervisorx.util.RateLimiter> ACTION_LIMITS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Screen buttons: max 8 actions a second per player (any of them can hit disks or spawn processes). */
    static boolean allowAction(ServerPlayer p) {
        if (ACTION_LIMITS.size() > 512) ACTION_LIMITS.clear();
        return ACTION_LIMITS.computeIfAbsent(p.getUUID(), k -> new net.hypervisorx.util.RateLimiter(8, 16)).tryAcquire();
    }

    public static String clean(String s, int max) {
        return net.hypervisorx.util.Names.clean(s, max);
    }

    public static void action(ServerPlayer p, C2S.HostAction a) {
        VmManager m = mgr();
        if (m == null || !near(p, a.pos()) || !allowAction(p)) return;
        HostBlockEntity host = resolveHost(p.level(), a.pos());
        if (host == null) return;
        int slot = Math.max(0, Math.min(host.vmSlots() - 1, a.slot()));
        if (a.action() == C2S.HostAction.REOPEN_MENU) {
            openHostMenu(p, a.pos());
            return;
        }
        if (a.action() == C2S.HostAction.ADD_VM) {
            if (!(host instanceof ServerRackBlockEntity) || !mayAssemble(p, host)) return;
            for (int i = 0; i < host.vmSlots(); i++) {
                if (m.record(host.vmId(i)) == null) {
                    ensureRecord(p, host, i);
                    break;
                }
            }
            sendHostInfo(p, a.pos());
            return;
        }
        VmRecord r = m.record(host.vmId(slot));
        if (r == null && a.action() == C2S.HostAction.POWER_ON) {
            // only the owner (or an op) gets to create a computer's first VM
            if (!mayAssemble(p, host)) {
                deny(p);
                return;
            }
            r = ensureRecord(p, host, slot);
        }
        if (r == null) return;
        switch (a.action()) {
            case C2S.HostAction.POWER_ON -> {
                if (!m.canControl(p, r)) {
                    deny(p);
                    return;
                }
                Component err = m.start(p, r, host.builtSpec(), host);
                if (err != null) {
                    p.sendSystemMessage(err.copy().withStyle(ChatFormatting.RED));
                } else {
                    p.level().playSound(null, host.hostPos(), HvxSounds.POWER_ON.get(), SoundSource.BLOCKS, 0.8f, 1f);
                }
            }
            case C2S.HostAction.SHUTDOWN, C2S.HostAction.FORCE_OFF, C2S.HostAction.RESET -> {
                if (!m.canControl(p, r)) {
                    deny(p);
                    return;
                }
                VmManager.StopMode mode = a.action() == C2S.HostAction.SHUTDOWN ? VmManager.StopMode.ACPI
                        : a.action() == C2S.HostAction.FORCE_OFF ? VmManager.StopMode.FORCE : VmManager.StopMode.RESET;
                m.stop(r.id, mode);
                if (mode != VmManager.StopMode.RESET) p.level().playSound(null, host.hostPos(), HvxSounds.POWER_OFF.get(), SoundSource.BLOCKS, 0.8f, 1f);
            }
            case C2S.HostAction.SNAPSHOT -> {
                if (m.canConfigure(p, r)) m.snapshot(r.id, p);
                else deny(p);
            }
            case C2S.HostAction.CTRL_ALT_DEL -> {
                if (m.canControl(p, r)) m.input(p, new C2S.Input(r.id, C2S.Input.CTRL_ALT_DEL, 0, 0, 0, 0));
            }
            case C2S.HostAction.DELETE_VM -> {
                if (!m.canConfigure(p, r)) {
                    deny(p);
                    return;
                }
                m.delete(r.id, true);
                host.setVmId(slot, null);
            }
            case C2S.HostAction.FOCUS -> openFocus(p, r, false);
            case C2S.HostAction.EJECT -> {
                if (m.canControl(p, r)) m.changeMedia(r.id, null, p);
            }
            case C2S.HostAction.INSERT_MEDIA -> {
                if (m.canControl(p, r)) m.changeMedia(r.id, ImageLibrary.resolve(r.image), p);
            }
            case C2S.HostAction.INSTALL_TOOLS -> {
                if (m.canControl(p, r)) m.installGuestTools(r.id, p);
                else deny(p);
            }
            default -> {
            }
        }
        sendHostInfo(p, a.pos());
    }

    /** Power actions by VM id, from the focus screen of any VM the player can control. */
    public static void vmAction(ServerPlayer p, C2S.VmAction a) {
        VmManager m = mgr();
        if (m == null || !allowAction(p)) return;
        VmRecord r = m.record(a.vm());
        if (r == null) return;
        if (!m.canControl(p, r)) {
            deny(p);
            return;
        }
        switch (a.action()) {
            case C2S.HostAction.POWER_ON -> {
                VmHost host = m.host(r);
                Component err;
                if (host != null) {
                    err = m.start(p, r, host.builtSpec(), host);
                } else if (r.kind == VmRecord.HostKind.LAPTOP) {
                    if (r.battery == 0) {
                        err = Component.translatable("hypervisorx.msg.battery_empty", r.name);
                    } else {
                        err = m.start(p, r, net.hypervisorx.hardware.LaptopModel.byId(r.laptopModel).spec(), null);
                    }
                } else {
                    err = Component.translatable("hypervisorx.msg.host_unloaded");
                }
                if (err != null) p.sendSystemMessage(err.copy().withStyle(ChatFormatting.RED));
            }
            case C2S.HostAction.SHUTDOWN -> m.stop(r.id, VmManager.StopMode.ACPI);
            case C2S.HostAction.FORCE_OFF -> m.stop(r.id, VmManager.StopMode.FORCE);
            case C2S.HostAction.RESET -> m.stop(r.id, VmManager.StopMode.RESET);
            case C2S.HostAction.SNAPSHOT -> {
                if (m.canConfigure(p, r)) m.snapshot(r.id, p);
            }
            case C2S.HostAction.CTRL_ALT_DEL -> m.input(p, new C2S.Input(r.id, C2S.Input.CTRL_ALT_DEL, 0, 0, 0, 0));
            case C2S.HostAction.INSTALL_TOOLS -> m.installGuestTools(r.id, p);
            default -> {
            }
        }
        VmSession s = m.session(r.id);
        Services.PLATFORM.sendToPlayer(p, s != null ? m.status(s, p) : m.offlineStatus(r, p));
    }

    public static void configure(ServerPlayer p, C2S.ConfigureVm c) {
        VmManager m = mgr();
        if (m == null || !near(p, c.pos()) || !allowAction(p)) return;
        HostBlockEntity host = resolveHost(p.level(), c.pos());
        if (host == null || !mayAssemble(p, host)) return;
        int slot = Math.max(0, Math.min(host.vmSlots() - 1, c.slot()));
        VmRecord r = ensureRecord(p, host, slot);
        if (!m.canConfigure(p, r)) {
            deny(p);
            return;
        }
        boolean admin = m.isAdmin(p);
        String name = clean(c.name(), 48);
        if (!name.isEmpty()) r.name = name;
        String backend = c.backend();
        if (backend.equals(net.hypervisorx.backend.hostcapture.HostCaptureBackend.ID) && !admin) {
            p.sendSystemMessage(Component.translatable("hypervisorx.msg.capture_admin_only").withStyle(ChatFormatting.RED));
            backend = r.backend;
        }
        r.backend = backend.equals("auto") || BackendRegistry.get(backend).isPresent() ? backend : "auto";
        r.guestOs = GuestOs.parse(c.guestOs());
        String image = clean(c.image(), 200);
        if (ImageLibrary.isLinked(image) && !admin) {
            p.sendSystemMessage(Component.translatable("hypervisorx.msg.linked_image_admin_only").withStyle(ChatFormatting.RED));
        } else if (image.isEmpty() || ImageLibrary.resolve(image) != null) {
            r.image = image;
        }
        r.target = clean(c.target(), 200);
        r.access = VmRecord.Access.parse(c.access());
        r.spanMonitors = Math.max(1, Math.min(3, c.span()));
        r.efi = c.efi();
        r.guestTools = c.guestTools();
        if (host instanceof ServerRackBlockEntity) {
            BuiltSpec pool = host.builtSpec();
            int otherCores = 0, otherRam = 0, otherDisk = 0;
            for (int i = 0; i < host.vmSlots(); i++) {
                VmRecord o = m.record(host.vmId(i));
                if (o == null || o == r) continue;
                otherCores += o.allocCores;
                otherRam += o.allocRamMb;
                otherDisk += o.allocDiskGb;
            }
            r.allocCores = Math.max(1, Math.min(c.cores(), pool.cores() - otherCores));
            r.allocRamMb = Math.max(256, Math.min(c.ramMb(), pool.ramMb() - otherRam));
            r.allocDiskGb = Math.max(4, Math.min(c.diskGb(), Math.max(4, pool.diskGb() - otherDisk)));
        }
        m.data().changed();
        p.displayClientMessage(Component.translatable("hypervisorx.msg.saved"), true);
        sendHostInfo(p, c.pos());
    }

    public static void trust(ServerPlayer p, C2S.Trust t) {
        VmManager m = mgr();
        if (m == null || !near(p, t.pos())) return;
        HostBlockEntity host = resolveHost(p.level(), t.pos());
        if (host == null) return;
        VmRecord r = m.record(host.vmId(Math.max(0, Math.min(host.vmSlots() - 1, t.slot()))));
        if (r == null || !m.canConfigure(p, r)) {
            deny(p);
            return;
        }
        Optional<GameProfile> profile = p.server.getProfileCache() == null ? Optional.empty() : p.server.getProfileCache().get(t.player());
        if (profile.isEmpty()) {
            p.sendSystemMessage(Component.translatable("hypervisorx.msg.unknown_player", t.player()).withStyle(ChatFormatting.RED));
            return;
        }
        if (t.add()) r.trusted.add(profile.get().getId());
        else r.trusted.remove(profile.get().getId());
        m.data().changed();
        p.sendSystemMessage(Component.translatable(t.add() ? "hypervisorx.msg.trusted" : "hypervisorx.msg.untrusted", profile.get().getName()));
        sendHostInfo(p, t.pos());
    }

    // ---------------------------------------------------------------- focus

    public static void requestFocus(ServerPlayer p, BlockPos pos) {
        VmManager m = mgr();
        if (m == null || !near(p, pos)) return;
        BlockEntity be = p.level().getBlockEntity(pos);
        if (be instanceof DisplayBlockEntity d) {
            UUID vm = d.displayedVm();
            if (vm == null) {
                p.displayClientMessage(Component.translatable("hypervisorx.msg.no_signal").withStyle(ChatFormatting.YELLOW), true);
                return;
            }
            openFocus(p, m.record(vm), false);
        } else if (be instanceof LaptopBlockEntity lb) {
            if (lb.remoteTarget() != null && m.record(lb.remoteTarget()) != null) {
                openFocus(p, m.record(lb.remoteTarget()), true);
            } else if (m.record(lb.vmId(0)) != null) {
                openFocus(p, m.record(lb.vmId(0)), true);
            } else if (mayAssemble(p, lb)) {
                openFocus(p, ensureRecord(p, lb, 0), true);
            } else {
                deny(p);
            }
        } else if (be instanceof HostBlockEntity h && h.vmId(0) != null) {
            openFocus(p, m.record(h.vmId(0)), false);
        }
    }

    public static void openFocus(ServerPlayer p, VmRecord r, boolean laptop) {
        VmManager m = mgr();
        if (m == null || r == null) return;
        if (!m.canView(p, r)) {
            deny(p);
            return;
        }
        Services.PLATFORM.sendToPlayer(p, new S2C.OpenFocus(r.id, laptop));
        VmSession s = m.session(r.id);
        Services.PLATFORM.sendToPlayer(p, s != null ? m.status(s, p) : m.offlineStatus(r, p));
    }

    static void deny(ServerPlayer p) {
        p.displayClientMessage(Component.translatable("hypervisorx.msg.denied").withStyle(ChatFormatting.RED), true);
    }
}
