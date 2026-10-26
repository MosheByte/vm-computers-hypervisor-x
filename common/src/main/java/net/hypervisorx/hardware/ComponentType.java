package net.hypervisorx.hardware;

import net.minecraft.ChatFormatting;

/** Kinds of hardware that fit a slot in a case, rack unit or laptop. */
public enum ComponentType {
    CPU("cpu", ChatFormatting.AQUA),
    RAM("ram", ChatFormatting.GREEN),
    GPU("gpu", ChatFormatting.LIGHT_PURPLE),
    STORAGE("storage", ChatFormatting.GOLD),
    EXPANSION("expansion", ChatFormatting.RED),
    USB("usb", ChatFormatting.YELLOW),
    BLADE("blade", ChatFormatting.BLUE);

    private final String key;
    private final ChatFormatting color;

    ComponentType(String key, ChatFormatting color) {
        this.key = key;
        this.color = color;
    }

    public String key() {
        return key;
    }

    public ChatFormatting color() {
        return color;
    }

    public String translationKey() {
        return "hypervisorx.component." + key;
    }
}
