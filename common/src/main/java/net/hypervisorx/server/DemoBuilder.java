package net.hypervisorx.server;

import net.hypervisorx.backend.GuestOs;
import net.hypervisorx.block.HorizontalEntityBlock;
import net.hypervisorx.block.VideoWallBlock;
import net.hypervisorx.block.entity.ComputerBlockEntity;
import net.hypervisorx.block.entity.DisplayBlockEntity;
import net.hypervisorx.block.entity.ServerRackBlockEntity;
import net.hypervisorx.registry.HvxBlocks;
import net.hypervisorx.registry.HvxItems;
import net.hypervisorx.vm.VmManager;
import net.hypervisorx.vm.VmRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code /hvx demo}: drops a ready-to-film showroom in front of you (desk PC + monitor, RGB tower
 * with a curved ultrawide, 4x3 video wall, a laptop and a server rack), links all the displays and
 * boots the embedded Linux MicroVM so it works with no hypervisor installed.
 */
public final class DemoBuilder {
    /** Where the last demo PC went (the dev autotest reads it). */
    public static volatile BlockPos lastPc;

    private DemoBuilder() {
    }

    public static Component build(ServerPlayer player) {
        VmManager m = VmManager.get();
        if (m == null) return Component.literal("Server not ready");
        ServerLevel level = player.serverLevel();
        Direction look = player.getDirection();
        Direction facing = look.getOpposite();          // displays face the player
        Direction right = look.getClockWise();          // player's right
        BlockPos base = player.blockPosition().relative(look, 4).relative(right.getOpposite(), 5);

        // floor, and clear out the space above it
        for (int a = -1; a <= 11; a++) {
            for (int d = -1; d <= 6; d++) {
                BlockPos p = base.relative(right, a).relative(look, d);
                level.setBlockAndUpdate(p.below(), Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                for (int y = 0; y < 6; y++) level.setBlockAndUpdate(p.above(y), Blocks.AIR.defaultBlockState());
            }
        }

        // desk PC + monitor + keyboard/mouse
        BlockPos pc = base;
        lastPc = pc;
        place(level, pc, HvxBlocks.RETRO_PC.get(), facing);
        ComputerBlockEntity retro = (ComputerBlockEntity) level.getBlockEntity(pc);
        retro.setOwner(player.getUUID(), player.getGameProfile().getName());
        install(retro, HvxItems.CPU_Q4.get().getDefaultInstance(), HvxItems.RAM_4.get().getDefaultInstance(),
                HvxItems.RAM_4.get().getDefaultInstance(), HvxItems.GPU_IGPU.get().getDefaultInstance(),
                HvxItems.GPIO_CARD.get().getDefaultInstance());
        VmRecord r = HostService.ensureRecord(player, retro, 0);
        r.backend = "embedded";
        r.guestOs = GuestOs.LINUX;
        r.name = "Demo Linux";
        r.access = VmRecord.Access.PUBLIC;
        BlockPos monitor = pc.above();
        place(level, monitor, HvxBlocks.MONITOR.get(), facing);
        link(level, monitor, pc);
        place(level, pc.relative(look.getOpposite()), HvxBlocks.KEYBOARD.get(), facing);
        place(level, pc.relative(look.getOpposite()).relative(right), HvxBlocks.MOUSE.get(), facing);
        place(level, pc.relative(right.getOpposite()), HvxBlocks.SPEAKERS.get(), facing);

        // RGB tower + curved ultrawide showing the same desktop
        BlockPos tower = base.relative(right, 3);
        place(level, tower, HvxBlocks.GAMING_TOWER.get(), facing);
        ComputerBlockEntity gaming = (ComputerBlockEntity) level.getBlockEntity(tower);
        gaming.setOwner(player.getUUID(), player.getGameProfile().getName());
        install(gaming, HvxItems.CPU_X8.get().getDefaultInstance(), HvxItems.RAM_16.get().getDefaultInstance(),
                HvxItems.RAM_16.get().getDefaultInstance(), HvxItems.GPU_GX.get().getDefaultInstance(),
                HvxItems.NVME.get().getDefaultInstance());
        BlockPos curved = base.relative(right, 5);
        place(level, curved, HvxBlocks.CURVED_MONITOR.get(), facing);
        link(level, curved, pc);

        // 4x3 video wall behind everything
        BlockPos wallOrigin = base.relative(look, 5).relative(right, 1);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 3; y++) {
                place(level, wallOrigin.relative(right, x).above(y + 1), HvxBlocks.VIDEO_WALL.get(), facing);
            }
        }
        VideoWallBlock.reform(level, wallOrigin.above(), facing);
        DisplayBlockEntity master = VideoWallBlock.master(level, wallOrigin.above());
        if (master != null) master.link(pc, 0);

        // triple monitor arm and a laptop on the desk
        BlockPos arm = base.relative(right, 8);
        place(level, arm, HvxBlocks.TRIPLE_MONITOR_ARM.get(), facing);
        link(level, arm, pc);
        place(level, base.relative(right, 10), HvxBlocks.PRO_LAPTOP.get(), facing);

        // two-unit server rack
        BlockPos rack = base.relative(right, 11).relative(look, 3);
        place(level, rack, HvxBlocks.SERVER_RACK.get(), facing);
        place(level, rack.above(), HvxBlocks.SERVER_RACK.get(), facing);
        if (level.getBlockEntity(rack) instanceof ServerRackBlockEntity unit) {
            unit.setOwner(player.getUUID(), player.getGameProfile().getName());
            unit.parts().setItem(0, HvxItems.BLADE_X16.get().getDefaultInstance());
            unit.parts().setItem(2, HvxItems.ENTERPRISE_HDD.get().getDefaultInstance());
        }

        m.data().changed();
        Component err = m.start(r, retro.builtSpec(), retro);
        return err != null ? err : Component.literal("HyperVisor X showroom built - booting the embedded Linux MicroVM...");
    }

    private static void place(ServerLevel level, BlockPos pos, Block block, Direction facing) {
        BlockState s = block.defaultBlockState();
        if (s.hasProperty(HorizontalEntityBlock.FACING)) s = s.setValue(HorizontalEntityBlock.FACING, facing);
        level.setBlockAndUpdate(pos, s);
    }

    private static void install(ComputerBlockEntity be, ItemStack... parts) {
        for (ItemStack part : parts) {
            for (int i = 0; i < be.parts().getContainerSize(); i++) {
                if (be.parts().getItem(i).isEmpty() && be.parts().canPlaceItem(i, part)) {
                    be.parts().setItem(i, part);
                    break;
                }
            }
        }
    }

    private static void link(ServerLevel level, BlockPos display, BlockPos host) {
        if (level.getBlockEntity(display) instanceof DisplayBlockEntity d) d.link(host, 0);
    }
}
