package net.hypervisorx.block;

import com.mojang.serialization.MapCodec;
import net.hypervisorx.block.entity.DisplayBlockEntity;
import net.hypervisorx.registry.HvxSounds;
import net.hypervisorx.server.HostService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/**
 * Desk peripherals. Mechanical keyboard and mouse focus the nearest linked screen. Speakers make
 * nearby computers louder and move where their sound comes from onto the speakers.
 */
public class PeripheralBlock extends HorizontalDirectionalBlock {
    public enum Kind { KEYBOARD, MOUSE, SPEAKERS }

    private final Kind kind;
    private final Map<Direction, VoxelShape> shapes;

    public PeripheralBlock(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
        VoxelShape north = switch (kind) {
            case KEYBOARD -> Shapes.box(1, 0, 4, 15, 1.5, 10);
            case MOUSE -> Shapes.box(6, 0, 5, 10, 1.5, 11);
            case SPEAKERS -> Shapes.or(Shapes.box(0, 0, 6, 4, 8, 10), Shapes.box(12, 0, 6, 16, 8, 10));
        };
        this.shapes = Shapes.allFacings(north);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    public Kind kind() {
        return kind;
    }

    @Override
    protected MapCodec<? extends PeripheralBlock> codec() {
        return simpleCodec(p -> new PeripheralBlock(p, kind));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapes.get(state.getValue(FACING));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (kind == Kind.SPEAKERS) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        level.playSound(null, pos, HvxSounds.KEYBOARD.get(), SoundSource.BLOCKS, 0.7f, 0.9f + level.random.nextFloat() * 0.2f);
        BlockPos display = nearestDisplay(level, pos, 4);
        if (display != null && player instanceof ServerPlayer sp) HostService.requestFocus(sp, display);
        return InteractionResult.CONSUME;
    }

    /** Closest monitor / wall showing a VM within {@code radius} blocks. */
    public static BlockPos nearestDisplay(Level level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -2, -radius), center.offset(radius, 3, radius))) {
            if (level.getBlockEntity(p) instanceof DisplayBlockEntity d && d.displayedVm() != null) {
                double dist = p.distSqr(center);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = p.immutable();
                }
            }
        }
        return best;
    }
}
