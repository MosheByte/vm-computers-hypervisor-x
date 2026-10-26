package net.hypervisorx.item;

import net.hypervisorx.hardware.HardwareSpec;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** A PC part: CPU, RAM stick, GPU, drive, expansion card or a server blade. */
public class HardwareItem extends Item {
    private final HardwareSpec spec;

    public HardwareItem(Properties properties, HardwareSpec spec) {
        super(properties);
        this.spec = spec;
    }

    public HardwareSpec spec() {
        return spec;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(spec.type().translationKey()).withStyle(spec.type().color()));
        switch (spec.type()) {
            case CPU -> tooltip.add(Component.translatable("hypervisorx.spec.cores", spec.cores()).withStyle(ChatFormatting.GRAY));
            case RAM -> tooltip.add(Component.translatable("hypervisorx.spec.ram", spec.ramMb() / 1024).withStyle(ChatFormatting.GRAY));
            case GPU -> {
                tooltip.add(Component.translatable("hypervisorx.spec.resolution", spec.width(), spec.height()).withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.translatable("hypervisorx.spec.displays", spec.displays()).withStyle(ChatFormatting.GRAY));
            }
            case STORAGE -> tooltip.add(Component.translatable(spec.nvme() ? "hypervisorx.spec.disk_nvme" : "hypervisorx.spec.disk_sata",
                    formatCapacity(spec.diskGb())).withStyle(ChatFormatting.GRAY));
            case EXPANSION -> tooltip.add(Component.translatable("hypervisorx.spec.feature." + spec.feature()).withStyle(ChatFormatting.GRAY));
            case BLADE -> {
                tooltip.add(Component.translatable("hypervisorx.spec.cores", spec.cores()).withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.translatable("hypervisorx.spec.ram", spec.ramMb() / 1024).withStyle(ChatFormatting.GRAY));
            }
            default -> {
            }
        }
    }

    public static String formatCapacity(int gb) {
        return gb >= 1000 ? (gb % 1000 == 0 ? gb / 1000 + " TB" : String.format("%.1f TB", gb / 1000.0)) : gb + " GB";
    }
}
