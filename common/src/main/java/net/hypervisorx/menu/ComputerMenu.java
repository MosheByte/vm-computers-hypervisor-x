package net.hypervisorx.menu;

import net.hypervisorx.block.entity.ComputerBlockEntity;
import net.hypervisorx.block.entity.PartsContainer;
import net.hypervisorx.block.entity.ServerRackBlockEntity;
import net.hypervisorx.hardware.ComponentType;
import net.hypervisorx.hardware.FormFactor;
import net.hypervisorx.registry.HvxMenus;
import net.hypervisorx.server.HostService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** PC assembly: typed part slots + player inventory. Also keeps sending host info to the screen. */
public class ComputerMenu extends AbstractContainerMenu {
    public static final int PART_X = 12;
    public static final int PART_Y = 36;
    public static final int PART_COLS = 4;
    public static final int PART_SPACING_X = 18;
    public static final int PART_SPACING_Y = 18;
    public static final int INV_X = 12;
    public static final int INV_Y = 144;

    private final BlockPos pos;
    private final FormFactor form;
    private final Container parts;
    private final Player player;
    private int infoTimer;
    /** Client only: motherboard page is open, so the slot grid and inventory are hidden. */
    public boolean boardView;

    /** Client-side constructor, slots mirror the server. */
    public static ComputerMenu client(int id, Inventory inv, BlockPos pos) {
        BlockEntity be = inv.player.level().getBlockEntity(pos);
        FormFactor form = be instanceof ComputerBlockEntity c ? c.form() : be instanceof ServerRackBlockEntity ? FormFactor.RACK : FormFactor.RETRO;
        return new ComputerMenu(id, inv, pos, form, new PartsContainer(form, () -> { }, p -> true));
    }

    public ComputerMenu(int id, Inventory inv, BlockPos pos, FormFactor form, Container parts) {
        super(HvxMenus.COMPUTER.get(), id);
        this.pos = pos;
        this.form = form;
        this.parts = parts;
        this.player = inv.player;
        for (int i = 0; i < form.slotCount(); i++) {
            int col = i % PART_COLS;
            int row = i / PART_COLS;
            addSlot(new PartSlot(parts, i, PART_X + col * PART_SPACING_X, PART_Y + row * PART_SPACING_Y, form.slots().get(i), () -> !boardView));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new GridSlot(inv, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) addSlot(new GridSlot(inv, col, INV_X + col * 18, INV_Y + 58));
    }

    public BlockPos pos() {
        return pos;
    }

    public FormFactor form() {
        return form;
    }

    /** Inventory slot that hides while the motherboard page is up. */
    private final class GridSlot extends Slot {
        GridSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean isActive() {
            return !boardView;
        }
    }

    public static final class PartSlot extends Slot {
        private final ComponentType type;
        private final java.util.function.BooleanSupplier active;

        PartSlot(Container container, int index, int x, int y, ComponentType type, java.util.function.BooleanSupplier active) {
            super(container, index, x, y);
            this.type = type;
            this.active = active;
        }

        @Override
        public boolean isActive() {
            return active.getAsBoolean();
        }

        public ComponentType type() {
            return type;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return PartsContainer.accepts(type, stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        int partEnd = form.slotCount();
        if (index < partEnd) {
            if (!moveItemStackTo(stack, partEnd, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            boolean moved = false;
            for (int i = 0; i < partEnd && !stack.isEmpty(); i++) {
                Slot target = slots.get(i);
                if (!target.hasItem() && target.mayPlace(stack)) {
                    target.setByPlayer(stack.split(1));
                    moved = true;
                }
            }
            if (!moved) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return parts.stillValid(player);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (player instanceof ServerPlayer sp && --infoTimer <= 0) {
            infoTimer = 10;
            HostService.sendHostInfo(sp, pos);
        }
    }

    /** Fallback container for when the block entity vanished between packets. */
    public static Container empty(FormFactor form) {
        return new SimpleContainer(form.slotCount());
    }
}
