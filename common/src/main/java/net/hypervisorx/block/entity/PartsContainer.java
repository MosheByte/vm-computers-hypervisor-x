package net.hypervisorx.block.entity;

import net.hypervisorx.hardware.ComponentType;
import net.hypervisorx.hardware.FormFactor;
import net.hypervisorx.item.HardwareItem;
import net.hypervisorx.item.UsbDriveItem;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Typed part slots for a chassis. Each slot only takes its own {@link ComponentType}. */
public class PartsContainer extends SimpleContainer {
    private final FormFactor form;
    private final Runnable onChange;
    private final java.util.function.Predicate<Player> validator;

    public PartsContainer(FormFactor form, Runnable onChange, java.util.function.Predicate<Player> validator) {
        super(form.slotCount());
        this.form = form;
        this.onChange = onChange;
        this.validator = validator;
    }

    public FormFactor form() {
        return form;
    }

    public ComponentType slotType(int slot) {
        return form.slots().get(slot);
    }

    public static boolean accepts(ComponentType type, ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (type == ComponentType.USB) return stack.getItem() instanceof UsbDriveItem;
        return stack.getItem() instanceof HardwareItem hw && !(stack.getItem() instanceof UsbDriveItem) && hw.spec().type() == type;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot >= 0 && slot < getContainerSize() && accepts(slotType(slot), stack);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean stillValid(Player player) {
        return validator.test(player);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        onChange.run();
    }
}
