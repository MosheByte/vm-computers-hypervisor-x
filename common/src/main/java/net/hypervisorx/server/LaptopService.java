package net.hypervisorx.server;

import net.hypervisorx.config.HvxConfig;
import net.hypervisorx.block.entity.HostBlockEntity;
import net.hypervisorx.hardware.LaptopModel;
import net.hypervisorx.item.LaptopItem;
import net.hypervisorx.network.C2S;
import net.hypervisorx.registry.HvxComponents;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Laptops in hand: either their own VM on battery or a Wi-Fi remote desktop into a computer. */
public final class LaptopService {
    private LaptopService() {
    }

    public static VmRecord ensureRecord(ServerPlayer p, ItemStack stack, LaptopModel model) {
        VmManager m = VmManager.get();
        UUID id = stack.get(HvxComponents.VM_LINK.get());
        VmRecord r = m.record(id);
        if (r == null) {
            r = m.createRecord(VmRecord.HostKind.LAPTOP, p.getGameProfile().getName() + "'s " + (model == LaptopModel.PRO ? "Pro Laptop" : "Toughbook"), p);
            r.laptopModel = model.id();
            r.battery = VmManager.batteryMax(r);
            stack.set(HvxComponents.VM_LINK.get(), r.id);
            m.data().changed();
        }
        return r;
    }

    public static void handle(ServerPlayer p, C2S.Laptop a) {
        InteractionHand hand = a.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        switch (a.action()) {
            case C2S.Laptop.OPEN -> open(p, hand);
            case C2S.Laptop.POWER -> power(p, hand, false);
            case C2S.Laptop.FORCE_OFF -> power(p, hand, true);
            case C2S.Laptop.TOGGLE_REMOTE -> toggleRemote(p, hand);
            default -> {
            }
        }
    }

    public static void open(ServerPlayer p, InteractionHand hand) {
        VmManager m = VmManager.get();
        ItemStack stack = p.getItemInHand(hand);
        if (m == null || !(stack.getItem() instanceof LaptopItem laptop)) return;
        UUID remote = stack.get(HvxComponents.REMOTE_TARGET.get());
        if (remote != null) {
            VmRecord target = m.record(remote);
            Component problem = remoteProblem(p, target);
            if (problem == null) {
                HostService.openFocus(p, target, true);
                return;
            }
            p.displayClientMessage(problem.copy().withStyle(ChatFormatting.YELLOW), true);
        }
        HostService.openFocus(p, ensureRecord(p, stack, laptop.model()), true);
    }

    public static void power(ServerPlayer p, InteractionHand hand, boolean force) {
        VmManager m = VmManager.get();
        ItemStack stack = p.getItemInHand(hand);
        if (m == null || !(stack.getItem() instanceof LaptopItem laptop)) return;
        VmRecord r = ensureRecord(p, stack, laptop.model());
        if (!m.canControl(p, r)) {
            HostService.deny(p);
            return;
        }
        VmSession s = m.session(r.id);
        if (s != null && s.isActive()) {
            m.stop(r.id, force ? VmManager.StopMode.FORCE : VmManager.StopMode.ACPI);
            p.level().playSound(null, p.blockPosition(), HvxSounds.POWER_OFF.get(), SoundSource.PLAYERS, 0.6f, 1.2f);
            return;
        }
        if (r.battery == 0) {
            p.displayClientMessage(Component.translatable("hypervisorx.msg.battery_empty", r.name).withStyle(ChatFormatting.RED), true);
            return;
        }
        Component err = m.start(r, laptop.model().spec(), m.host(r));
        if (err != null) {
            p.sendSystemMessage(err.copy().withStyle(ChatFormatting.RED));
        } else {
            p.level().playSound(null, p.blockPosition(), HvxSounds.POWER_ON.get(), SoundSource.PLAYERS, 0.6f, 1.2f);
        }
    }

    private static void toggleRemote(ServerPlayer p, InteractionHand hand) {
        ItemStack stack = p.getItemInHand(hand);
        if (!(stack.getItem() instanceof LaptopItem)) return;
        if (stack.has(HvxComponents.REMOTE_TARGET.get())) {
            stack.remove(HvxComponents.REMOTE_TARGET.get());
            p.displayClientMessage(Component.translatable("hypervisorx.msg.remote_off"), true);
        } else {
            p.displayClientMessage(Component.translatable("hypervisorx.msg.remote_how"), true);
        }
    }

    /** Sneak-use on a computer pairs the laptop as a remote desktop client. */
    public static void pair(ServerPlayer p, InteractionHand hand, BlockPos pos) {
        VmManager m = VmManager.get();
        ItemStack stack = p.getItemInHand(hand);
        HostBlockEntity host = HostService.resolveHost(p.level(), pos);
        if (m == null || host == null || !(stack.getItem() instanceof LaptopItem)) return;
        if (!host.builtSpec().wifi()) {
            p.displayClientMessage(Component.translatable("hypervisorx.msg.needs_wifi").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        UUID vm = null;
        for (int i = 0; i < host.vmSlots() && vm == null; i++) vm = host.vmId(i);
        VmRecord r = m.record(vm);
        if (r == null) {
            p.displayClientMessage(Component.translatable("hypervisorx.msg.power_once").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        if (!m.canView(p, r)) {
            HostService.deny(p);
            return;
        }
        stack.set(HvxComponents.REMOTE_TARGET.get(), r.id);
        p.displayClientMessage(Component.translatable("hypervisorx.msg.paired", r.name).withStyle(ChatFormatting.AQUA), true);
    }

    private static Component remoteProblem(ServerPlayer p, VmRecord target) {
        VmManager m = VmManager.get();
        if (target == null) return Component.translatable("hypervisorx.msg.remote_gone");
        VmHost host = m.host(target);
        if (host == null) return Component.translatable("hypervisorx.msg.remote_unloaded");
        if (!host.builtSpec().wifi()) return Component.translatable("hypervisorx.msg.needs_wifi");
        if (host.hostLevel() != p.level()) return Component.translatable("hypervisorx.msg.remote_dimension");
        int range = HvxConfig.get().remoteDesktopRange;
        if (range > 0 && host.hostPos().distSqr(p.blockPosition()) > (double) range * range) {
            return Component.translatable("hypervisorx.msg.remote_range", range);
        }
        if (!m.canView(p, target)) return Component.translatable("hypervisorx.msg.denied");
        return null;
    }
}
