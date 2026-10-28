package net.hypervisorx.block;

import com.mojang.serialization.MapCodec;
import net.hypervisorx.block.entity.DisplayBlockEntity;
import net.hypervisorx.block.entity.MonitorBlockEntity;
import net.hypervisorx.item.HdmiCableItem;
import net.hypervisorx.registry.HvxBlockEntities;
import net.hypervisorx.server.HostService;
import net.minecraft.core.BlockPos;
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

/** Flat, CRT, curved ultrawide and dual/triple arm monitors. */
public class MonitorBlock extends HorizontalEntityBlock {
    private final MonitorType type;

    /** On while the linked machine runs, so the panel gives off a bit of light like a real screen. */
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty LIT =
            net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT;
    private final java.util.Map<net.minecraft.core.Direction, net.minecraft.world.phys.shapes.VoxelShape> outlines;

    public MonitorBlock(Properties properties, MonitorType type) {
        super(properties, type.shape());
        this.type = type;
        this.outlines = Shapes.allFacings(type.outline());
        registerDefaultState(defaultBlockState().setValue(LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(LIT);
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                                               net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return outlines.get(state.getValue(FACING));
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                                                        net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return super.getShape(state, level, pos, ctx);
    }

    public MonitorType type() {
        return type;
    }

    @Override
    protected MapCodec<? extends MonitorBlock> codec() {
        return simpleCodec(p -> new MonitorBlock(p, type));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MonitorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, HvxBlockEntities.MONITOR.get(), DisplayBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp) || !(level.getBlockEntity(pos) instanceof DisplayBlockEntity be)) return InteractionResult.PASS;
        if (player.isSecondaryUseActive()) {
            if (!HostService.mayEditDisplay(sp, be)) return InteractionResult.FAIL;
            String part = be.cyclePart();
            sp.displayClientMessage(Component.translatable(part.equals("full") ? "hypervisorx.msg.part_full" : "hypervisorx.msg.part", part), true);
            return InteractionResult.CONSUME;
        }
        HostService.requestFocus(sp, pos);
        return InteractionResult.CONSUME;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof HdmiCableItem) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
