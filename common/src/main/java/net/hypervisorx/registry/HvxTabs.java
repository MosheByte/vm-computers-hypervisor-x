package net.hypervisorx.registry;

import net.hypervisorx.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

public final class HvxTabs {
    public static final Supplier<CreativeModeTab> MAIN = Services.PLATFORM.register(BuiltInRegistries.CREATIVE_MODE_TAB, "main",
            () -> Services.PLATFORM.creativeTabBuilder()
                    .title(Component.translatable("itemGroup.hypervisorx"))
                    .icon(() -> new ItemStack(HvxItems.GAMING_TOWER.get()))
                    .displayItems((params, output) -> HvxItems.ALL.forEach(s -> output.accept(s.get())))
                    .build());

    private HvxTabs() {
    }

    public static void init() {
    }
}
