package net.hypervisorx.registry;

import net.hypervisorx.menu.ComputerMenu;
import net.hypervisorx.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;

import java.util.function.Supplier;

public final class HvxMenus {
    public static final Supplier<MenuType<ComputerMenu>> COMPUTER = Services.PLATFORM.register(BuiltInRegistries.MENU, "computer",
            () -> Services.PLATFORM.menuType(ComputerMenu::client));

    private HvxMenus() {
    }

    public static void init() {
    }
}
