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
    public static volatile BlockPos lastTower;
    public static volatile BlockPos lastCurved;
    /** Empty spot next to the crate, the autotest parks a supply pod there for a still. */
    public static volatile BlockPos lastCorner;

    private DemoBuilder() {
    }

    public static Component build(ServerPlayer player) {
        return build(player, true);
    }

    /** @param towerVm boot a MicroVM on the gaming tower too (its RGB and fans only light up while it's running) */
    public static Component build(ServerPlayer player, boolean towerVm) {
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
        // speakers are one per block, so put one on each side of the PC + monitor
        place(level, pc.relative(right.getOpposite()), HvxBlocks.SPEAKERS.get(), facing);
        place(level, pc.relative(right), HvxBlocks.SPEAKERS.get(), facing);

        // RGB tower + curved ultrawide showing the same desktop
        BlockPos tower = base.relative(right, 3);
        lastTower = tower;
        place(level, tower, HvxBlocks.GAMING_TOWER.get(), facing);
        ComputerBlockEntity gaming = (ComputerBlockEntity) level.getBlockEntity(tower);
        gaming.setOwner(player.getUUID(), player.getGameProfile().getName());
        install(gaming, HvxItems.CPU_X8.get().getDefaultInstance(), HvxItems.RAM_16.get().getDefaultInstance(),
                HvxItems.RAM_16.get().getDefaultInstance(), HvxItems.GPU_GX.get().getDefaultInstance(),
                HvxItems.NVME.get().getDefaultInstance());
        BlockPos curved = base.relative(right, 5);
        lastCurved = curved;
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

        // retro corner behind the arms: CRT on the same desktop, rugged laptop, delivery crate
        BlockPos crt = base.relative(right, 6).relative(look, 3);
        place(level, crt, HvxBlocks.CRT_MONITOR.get(), facing);
        link(level, crt, pc);
        place(level, base.relative(right, 7).relative(look, 3), HvxBlocks.RUGGED_LAPTOP.get(), facing);
        level.setBlockAndUpdate(base.relative(right, 8).relative(look, 3), HvxBlocks.DELIVERY_CRATE.get().defaultBlockState());
        lastCorner = base.relative(right, 9).relative(look, 3);

        // two-unit server rack
        BlockPos rack = base.relative(right, 11).relative(look, 3);
        place(level, rack, HvxBlocks.SERVER_RACK.get(), facing);
        place(level, rack.above(), HvxBlocks.SERVER_RACK.get(), facing);
        if (level.getBlockEntity(rack) instanceof ServerRackBlockEntity unit) {
            unit.setOwner(player.getUUID(), player.getGameProfile().getName());
            unit.parts().setItem(0, HvxItems.BLADE_X16.get().getDefaultInstance());
            unit.parts().setItem(2, HvxItems.ENTERPRISE_HDD.get().getDefaultInstance());
        }

        VmRecord towerRecord = null;
        if (towerVm) {
            towerRecord = HostService.ensureRecord(player, gaming, 0);
            towerRecord.backend = "embedded";
            towerRecord.guestOs = GuestOs.LINUX;
            towerRecord.name = "Demo Linux (tower)";
            towerRecord.access = VmRecord.Access.PUBLIC;
            link(level, curved, tower);
        }

        m.data().changed();
        if (towerRecord != null) m.startUnchecked(towerRecord, gaming.builtSpec(), gaming);
        Component err = m.startUnchecked(r, retro.builtSpec(), retro); // unchecked is fine, /hvx demo is op-only
        return err != null ? err : Component.literal("HyperVisor X showroom built - booting the embedded Linux MicroVM...");
    }

    /**
     * Swaps the showroom for a small room around the gaming tower (tower keeps running): wooden desk with
     * a 24" monitor, keyboard, mouse, speakers and a plant, plus a chair, floor lamp, bookshelves and a
     * window wall. {@code facing} is where the displays point (at the camera). Returns the monitor pos.
     */
    public static BlockPos buildDesk(ServerLevel level, BlockPos tower, Direction facing) {
        Direction right = facing.getCounterClockWise();
        java.util.function.BiFunction<int[], Integer, BlockPos> at = (ad, y) -> tower.relative(right, ad[0]).relative(facing, ad[1]).above(y);
        for (int a = -4; a <= 8; a++) {
            for (int d = -7; d <= 3; d++) {
                for (int y = 0; y <= 5; y++) {
                    BlockPos p = at.apply(new int[]{a, d}, y);
                    if (!p.equals(tower)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        for (int a = -3; a <= 6; a++) {
            for (int d = -1; d <= 6; d++) level.setBlockAndUpdate(at.apply(new int[]{a, d}, -1), Blocks.DARK_OAK_PLANKS.defaultBlockState());
        }
        // back wall with a window band, log pillars at the ends
        for (int a = -3; a <= 6; a++) {
            for (int y = 0; y <= 4; y++) {
                boolean pillar = a == -3 || a == 6;
                boolean window = !pillar && a >= -1 && a <= 5 && (y == 2 || y == 3);
                level.setBlockAndUpdate(at.apply(new int[]{a, -1}, y), pillar ? Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState()
                        : window ? Blocks.GLASS_PANE.defaultBlockState() : Blocks.DARK_OAK_PLANKS.defaultBlockState());
            }
        }
        // side walls: bookshelves on the left, a window on the right
        for (int d = 0; d <= 3; d++) {
            for (int y = 0; y <= 3; y++) {
                level.setBlockAndUpdate(at.apply(new int[]{-3, d}, y), y < 3 ? Blocks.BOOKSHELF.defaultBlockState() : Blocks.DARK_OAK_PLANKS.defaultBlockState());
                boolean window = (d == 1 || d == 2) && (y == 1 || y == 2);
                level.setBlockAndUpdate(at.apply(new int[]{6, d}, y), window ? Blocks.GLASS_PANE.defaultBlockState() : Blocks.DARK_OAK_PLANKS.defaultBlockState());
            }
        }
        // desk: slab top, panel on the free end (the tower props up the other end)
        for (int a = 1; a <= 3; a++) {
            for (int d = 0; d <= 1; d++) {
                level.setBlockAndUpdate(at.apply(new int[]{a, d}, 0), Blocks.DARK_OAK_SLAB.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP));
            }
        }
        for (int d = 0; d <= 1; d++) {
            level.setBlockAndUpdate(at.apply(new int[]{4, d}, 0), Blocks.DARK_OAK_TRAPDOOR.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN, true)
                    .setValue(net.minecraft.world.level.block.TrapDoorBlock.FACING, right));
        }
        BlockPos monitor = at.apply(new int[]{2, 0}, 1);
        place(level, monitor, HvxBlocks.MONITOR.get(), facing);
        link(level, monitor, tower);
        place(level, at.apply(new int[]{1, 0}, 1), HvxBlocks.SPEAKERS.get(), facing);
        place(level, at.apply(new int[]{3, 0}, 1), HvxBlocks.SPEAKERS.get(), facing);
        place(level, at.apply(new int[]{2, 1}, 1), HvxBlocks.KEYBOARD.get(), facing);
        place(level, at.apply(new int[]{3, 1}, 1), HvxBlocks.MOUSE.get(), facing);
        level.setBlockAndUpdate(at.apply(new int[]{5, 0}, 0), Blocks.POTTED_AZALEA.defaultBlockState());
        // chair and rug
        for (int a = 1; a <= 3; a++) {
            for (int d = 2; d <= 4; d++) level.setBlockAndUpdate(at.apply(new int[]{a, d}, 0), Blocks.GRAY_CARPET.defaultBlockState());
        }
        level.setBlockAndUpdate(at.apply(new int[]{2, 3}, 0), Blocks.DARK_OAK_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.StairBlock.FACING, facing));
        // floor lamp beside the tower
        level.setBlockAndUpdate(at.apply(new int[]{-2, 0}, 0), Blocks.DARK_OAK_FENCE.defaultBlockState());
        level.setBlockAndUpdate(at.apply(new int[]{-2, 0}, 1), Blocks.DARK_OAK_FENCE.defaultBlockState());
        level.setBlockAndUpdate(at.apply(new int[]{-2, 0}, 2), Blocks.LANTERN.defaultBlockState());
        return monitor;
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
