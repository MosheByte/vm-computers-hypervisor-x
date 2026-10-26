package net.hypervisorx.block;

import com.mojang.serialization.MapCodec;
import net.hypervisorx.block.entity.HostBlockEntity;
import net.hypervisorx.block.entity.LaptopBlockEntity;
import net.hypervisorx.hardware.LaptopModel;
import net.hypervisorx.item.HdmiCableItem;
import net.hypervisorx.registry.HvxBlockEntities;
import net.hypervisorx.server.HostService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Laptop sitting open on a surface. Sneak + use with an empty hand picks it back up. */
public class LaptopBlock extends HorizontalEntityBlock {
    private final LaptopModel model;

    public LaptopBlock(Properties properties, LaptopModel model) {
        super(properties, Shapes.or(Shapes.box(2, 0, 3, 14, 1, 13), Shapes.box(2, 1, 11, 14, 10, 14)));
        this.model = model;
    }

    public LaptopModel model() {
        return model;
    }

    @Override
    protected MapCodec<? extends LaptopBlock> codec() {
        return simpleCodec(p -> new LaptopBlock(p, model));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LaptopBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, HvxBlockEntities.LAPTOP.get(), LaptopBlockEntity::serverTick);
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp) || !(level.getBlockEntity(pos) instanceof LaptopBlockEntity be)) return InteractionResult.PASS;
        if (player.isSecondaryUseActive()) {
            if (!HostService.mayPickUp(sp, be)) return InteractionResult.FAIL;
            ItemStack drop = new ItemStack(this);
            drop.applyComponents(be.collectComponents());
            level.removeBlock(pos, false);
            level.playSound(null, pos, SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.BLOCKS, 0.8f, 1.2f);
            if (!player.getInventory().add(drop)) player.drop(drop, false);
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

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        // VM keeps running on battery when you pick the laptop up, so nothing to stop here
        super.onRemove(state, level, pos, newState, moved);
    }

    public static Block.Properties defaultProperties() {
        return Block.Properties.of().strength(0.8f).noOcclusion();
    }
}
