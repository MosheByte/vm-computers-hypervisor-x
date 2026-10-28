package net.hypervisorx.hardware;

import net.minecraft.ChatFormatting;

/** Kinds of hardware that fit a slot in a case, rack unit or laptop. */
public enum ComponentType {
    CPU("cpu", ChatFormatting.AQUA, 0xFF3BD1FF),
    RAM("ram", ChatFormatting.GREEN, 0xFF3DDC84),
    GPU("gpu", ChatFormatting.LIGHT_PURPLE, 0xFF7C5CFF),
    STORAGE("storage", ChatFormatting.GOLD, 0xFFFFB020),
    EXPANSION("expansion", ChatFormatting.RED, 0xFFFF5470),
    USB("usb", ChatFormatting.YELLOW, 0xFFC9D1DC),
    BLADE("blade", ChatFormatting.BLUE, 0xFF5B8CFF);

    private final String key;
    private final ChatFormatting color;
    private final int uiColor;

    ComponentType(String key, ChatFormatting color, int uiColor) {
        this.key = key;
        this.color = color;
        this.uiColor = uiColor;
    }

    /** Slot accent color in the UI's violet/cyan palette (tooltips still use the chat colors). */
    public int uiColor() {
        return uiColor;
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
