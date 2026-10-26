package net.hypervisorx.block;

import com.mojang.serialization.MapCodec;
import net.hypervisorx.block.entity.DisplayBlockEntity;
import net.hypervisorx.block.entity.VideoWallBlockEntity;
import net.hypervisorx.item.HdmiCableItem;
import net.hypervisorx.registry.HvxBlockEntities;
import net.hypervisorx.server.HostService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bezel-less LED panel. Same-facing panels that make a full rectangle (up to
 * {@link #MAX_W}x{@link #MAX_H}) merge on their own into one big display.
 */
public class VideoWallBlock extends HorizontalEntityBlock {
    public static final int MAX_W = 32;
    public static final int MAX_H = 18;

    public VideoWallBlock(Properties properties) {
        super(properties, Shapes.box(0, 0, 13, 16, 16, 16));
    }

    @Override
    protected MapCodec<? extends VideoWallBlock> codec() {
        return simpleCodec(VideoWallBlock::new);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VideoWallBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, HvxBlockEntities.VIDEO_WALL.get(), DisplayBlockEntity::serverTick);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (!level.isClientSide && !old.is(this)) reform(level, pos, state.getValue(FACING));
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        boolean removed = !state.is(newState.getBlock());
        VideoWallBlockEntity old = removed && level.getBlockEntity(pos) instanceof VideoWallBlockEntity be ? be : null;
        super.onRemove(state, level, pos, newState, moving);
        if (removed && !level.isClientSide) {
            Direction facing = state.getValue(FACING);
            Direction right = facing.getCounterClockWise();
            for (Direction d : new Direction[]{Direction.UP, Direction.DOWN, right, right.getOpposite()}) {
                BlockPos n = pos.relative(d);
                BlockState ns = level.getBlockState(n);
                if (ns.getBlock() instanceof VideoWallBlock && ns.getValue(FACING) == facing) {
                    // master got removed? hand its link over to the rebuilt wall
                    if (old != null && old.isMaster() && old.linkHost() != null && level.getBlockEntity(n) instanceof VideoWallBlockEntity nb && nb.linkHost() == null) {
                        nb.link(old.linkHost(), old.linkSlot());
                    }
                    reform(level, n, facing);
                }
            }
        }
    }

    /** Flood-fills the panels around {@code origin} and hands out master + grid positions. */
    public static void reform(Level level, BlockPos origin, Direction facing) {
        Direction right = facing.getCounterClockWise();
        Set<BlockPos> seen = new HashSet<>();
        List<VideoWallBlockEntity> panels = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);
        int limit = MAX_W * MAX_H + 1;
        while (!queue.isEmpty() && panels.size() < limit) {
            BlockPos p = queue.poll();
            BlockState s = level.getBlockState(p);
            if (!(s.getBlock() instanceof VideoWallBlock) || s.getValue(FACING) != facing) continue;
            if (!(level.getBlockEntity(p) instanceof VideoWallBlockEntity be)) continue;
            panels.add(be);
            for (Direction d : new Direction[]{Direction.UP, Direction.DOWN, right, right.getOpposite()}) {
                BlockPos n = p.relative(d);
                if (seen.add(n)) queue.add(n);
            }
        }
        if (panels.isEmpty()) return;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (VideoWallBlockEntity be : panels) {
            int gx = coordX(be.getBlockPos(), origin, right);
            int gy = be.getBlockPos().getY() - origin.getY();
            minX = Math.min(minX, gx);
            maxX = Math.max(maxX, gx);
            minY = Math.min(minY, gy);
            maxY = Math.max(maxY, gy);
        }
        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        boolean rectangle = panels.size() == w * h && w <= MAX_W && h <= MAX_H;

        // keep whatever the wall was already showing
        BlockPos linkHost = null;
        int linkSlot = 0;
        for (VideoWallBlockEntity be : panels) {
            if (be.isMaster() && be.linkHost() != null) {
                linkHost = be.linkHost();
                linkSlot = be.linkSlot();
                break;
            }
        }
        if (!rectangle) {
            for (VideoWallBlockEntity be : panels) be.setGrid(be.getBlockPos(), 0, 0, 1, 1);
            return;
        }
        BlockPos master = null;
        for (VideoWallBlockEntity be : panels) {
            if (coordX(be.getBlockPos(), origin, right) == minX && be.getBlockPos().getY() - origin.getY() == minY) master = be.getBlockPos();
        }
        for (VideoWallBlockEntity be : panels) {
            int gx = coordX(be.getBlockPos(), origin, right) - minX;
            int gy = be.getBlockPos().getY() - origin.getY() - minY;
            be.setGrid(master, gx, gy, w, h);
            if (!be.getBlockPos().equals(master) && be.linkHost() != null) be.unlink();
        }
        if (linkHost != null && level.getBlockEntity(master) instanceof VideoWallBlockEntity m && m.linkHost() == null) {
            m.link(linkHost, linkSlot);
        }
    }

    private static int coordX(BlockPos p, BlockPos origin, Direction right) {
        return (p.getX() - origin.getX()) * right.getStepX() + (p.getZ() - origin.getZ()) * right.getStepZ();
    }

    /** Master panel of the wall that {@code pos} belongs to. */
    public static VideoWallBlockEntity master(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof VideoWallBlockEntity be)) return null;
        return level.getBlockEntity(be.master()) instanceof VideoWallBlockEntity m ? m : be;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) {
            VideoWallBlockEntity m = master(level, pos);
            if (m == null) return InteractionResult.PASS;
            if (player.isSecondaryUseActive()) {
                sp.displayClientMessage(Component.translatable("hypervisorx.msg.wall_size", m.gridW(), m.gridH()), true);
            } else {
                HostService.requestFocus(sp, m.getBlockPos());
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof HdmiCableItem) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
