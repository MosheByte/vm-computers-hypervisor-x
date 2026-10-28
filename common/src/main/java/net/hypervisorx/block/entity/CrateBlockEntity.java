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
    /** How long a fresh delivery is buyer-only (after that anyone can open it, like a chest). */
    public static final long PRIVATE_MS = 10 * 60 * 1000L;
    private NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
    private java.util.UUID owner;
    private long deliveredAt;

    public void setOwner(java.util.UUID owner) {
        this.owner = owner;
        this.deliveredAt = System.currentTimeMillis();
        setChanged();
    }

    /** Buyer and ops always, everyone else once the private window is over. */
    public boolean mayAccess(net.minecraft.world.entity.player.Player p) {
        return owner == null || owner.equals(p.getUUID()) || p.hasPermissions(2) || System.currentTimeMillis() - deliveredAt > PRIVATE_MS;
    }

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
        if (owner != null) {
            tag.putUUID("Owner", owner);
            tag.putLong("DeliveredAt", deliveredAt);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        deliveredAt = tag.getLong("DeliveredAt");
    }
}
