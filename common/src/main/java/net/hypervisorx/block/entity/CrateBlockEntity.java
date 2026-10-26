package net.hypervisorx.block.entity;

import net.hypervisorx.registry.HvxBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** OrbitTech delivery crate, what a supply pod leaves behind after it lands. */
public class CrateBlockEntity extends BaseContainerBlockEntity {
    private NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);

    public CrateBlockEntity(BlockPos pos, BlockState state) {
        super(HvxBlockEntities.CRATE.get(), pos, state);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.hypervisorx.delivery_crate");
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return ChestMenu.threeRows(id, inventory, this);
    }

    @Override
    public int getContainerSize() {
        return 27;
    }

    /** Adds items, returns whatever didn't fit. */
    public NonNullList<ItemStack> fill(java.util.List<ItemStack> stacks) {
        NonNullList<ItemStack> leftover = NonNullList.create();
        for (ItemStack s : stacks) {
            ItemStack rest = s.copy();
            for (int i = 0; i < items.size() && !rest.isEmpty(); i++) {
                ItemStack cur = items.get(i);
                if (cur.isEmpty()) {
                    items.set(i, rest);
                    rest = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameComponents(cur, rest) && cur.getCount() < cur.getMaxStackSize()) {
                    int n = Math.min(rest.getCount(), cur.getMaxStackSize() - cur.getCount());
                    cur.grow(n);
                    rest.shrink(n);
                }
            }
            if (!rest.isEmpty()) leftover.add(rest);
        }
        setChanged();
        return leftover;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
    }
}
