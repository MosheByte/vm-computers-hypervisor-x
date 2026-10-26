package net.hypervisorx.item;

import net.hypervisorx.hardware.HardwareSpec;
import net.hypervisorx.registry.HvxComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.UUID;

/**
 * USB flash drive. The data lives in {@code hypervisorx/drives/<id>.vmdk} on the host, so you can
 * carry the same stick between computers to move files around.
 */
public class UsbDriveItem extends HardwareItem {
    public UsbDriveItem(Properties properties, int capacityGb) {
        super(properties, HardwareSpec.usb(capacityGb));
    }

    public static UUID driveId(ItemStack stack) {
        return stack.get(HvxComponents.DRIVE_ID.get());
    }

    /** Gives the stick a fresh drive id the first time it's plugged in. */
    public static UUID ensureDriveId(ItemStack stack) {
        UUID id = driveId(stack);
        if (id == null) {
            id = UUID.randomUUID();
            stack.set(HvxComponents.DRIVE_ID.get(), id);
        }
        return id;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("hypervisorx.component.usb").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("hypervisorx.spec.usb_capacity", formatCapacity(spec().diskGb())).withStyle(ChatFormatting.GRAY));
        UUID id = driveId(stack);
        tooltip.add(id == null
                ? Component.translatable("hypervisorx.tooltip.usb_blank").withStyle(ChatFormatting.DARK_GRAY)
                : Component.translatable("hypervisorx.tooltip.usb_id", id.toString().substring(0, 8)).withStyle(ChatFormatting.DARK_GRAY));
    }
}
