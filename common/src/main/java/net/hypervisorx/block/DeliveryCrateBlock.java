package net.hypervisorx.block;

import com.mojang.serialization.MapCodec;
import net.hypervisorx.block.entity.CrateBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;

/** Storage crate the OrbitTech satellite drops off. */
public class DeliveryCrateBlock extends BaseEntityBlock {
    private static final VoxelShape SHAPE = Shapes.box(1, 0, 1, 15, 14, 15);

    public DeliveryCrateBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends DeliveryCrateBlock> codec() {
        return simpleCodec(DeliveryCrateBlock::new);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CrateBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof net.hypervisorx.block.entity.CrateBlockEntity crate && !crate.mayAccess(player)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("hypervisorx.msg.crate_private")
                    .withStyle(net.minecraft.ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }
        if (level.getBlockEntity(pos) instanceof MenuProvider provider) player.openMenu(provider);
        return InteractionResult.CONSUME;
    }

    /** And no mining someone else's fresh delivery open either. */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof net.hypervisorx.block.entity.CrateBlockEntity crate && !crate.mayAccess(player)) return 0f;
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof CrateBlockEntity crate) {
            Containers.dropContents(level, pos, crate);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
