package net.hypervisorx.block;

import com.mojang.serialization.MapCodec;
import net.hypervisorx.block.entity.ComputerBlockEntity;
import net.hypervisorx.block.entity.HostBlockEntity;
import net.hypervisorx.block.entity.PartsContainer;
import net.hypervisorx.hardware.FormFactor;
import net.hypervisorx.item.HdmiCableItem;
import net.hypervisorx.item.HardwareItem;
import net.hypervisorx.server.HostService;
import net.hypervisorx.vm.VmManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Retro PC and the RGB tempered-glass gaming tower. Sends/reads redstone through the GPIO card. */
public class ComputerCaseBlock extends HorizontalEntityBlock {
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    private static final VoxelShape RETRO = Shapes.box(1, 0, 2, 15, 11, 15);
    private static final VoxelShape TOWER = Shapes.box(4, 0, 1, 12, 16, 15);
    private final FormFactor form;

    public ComputerCaseBlock(Properties properties, FormFactor form) {
        super(properties, form == FormFactor.TOWER ? TOWER : RETRO);
        this.form = form;
        registerDefaultState(defaultBlockState().setValue(POWERED, false));
    }

    public FormFactor form() {
        return form;
    }

    @Override
    protected MapCodec<? extends ComputerCaseBlock> codec() {
        return simpleCodec(p -> new ComputerCaseBlock(p, form));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(POWERED);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ComputerBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) HostService.openHostMenu(sp, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof HdmiCableItem) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if (stack.getItem() instanceof HardwareItem && !player.isSecondaryUseActive()
                && level.getBlockEntity(pos) instanceof ComputerBlockEntity be) {
            if (level.isClientSide) return ItemInteractionResult.SUCCESS;
            if (player instanceof ServerPlayer sp && !HostService.mayAssemble(sp, be)) return ItemInteractionResult.FAIL;
            PartsContainer parts = be.parts();
            for (int i = 0; i < parts.getContainerSize(); i++) {
                if (parts.getItem(i).isEmpty() && parts.canPlaceItem(i, stack)) {
                    parts.setItem(i, stack.split(1));
                    level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.6f, 1.6f);
                    player.displayClientMessage(Component.translatable("hypervisorx.msg.installed",
                            parts.getItem(i).getHoverName()), true);
                    return ItemInteractionResult.CONSUME;
                }
            }
            player.displayClientMessage(Component.translatable("hypervisorx.msg.no_free_slot"), true);
            return ItemInteractionResult.FAIL;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof HostBlockEntity be) {
            if (be.ownerId() == null && placer instanceof Player p) be.setOwner(p.getUUID(), p.getGameProfile().getName());
            be.bindRecords();
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof HostBlockEntity be) {
            VmManager m = VmManager.get();
            if (m != null) {
                for (int i = 0; i < be.vmSlots(); i++) {
                    if (be.vmId(i) != null) m.stop(be.vmId(i), VmManager.StopMode.FORCE);
                }
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof HostBlockEntity be ? be.gpioOutput(direction.getOpposite()) : 0;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean moving) {
        super.neighborChanged(state, level, pos, block, fromPos, moving);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof HostBlockEntity be) be.refreshInputs();
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moving) {
        super.onPlace(state, level, pos, oldState, moving);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof HostBlockEntity be) be.refreshInputs();
    }
}
