package net.hypervisorx.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Our keybinds. They show up under "HyperVisor X" in Controls. */
public final class HvxKeys {
    public static final String CATEGORY = "key.categories.hypervisorx";
    /** Gets you out of the focused VM. Escape goes to the guest, not us. */
    public static final KeyMapping RELEASE = new KeyMapping("key.hypervisorx.release", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_CONTROL, CATEGORY);
    /** Focus the screen you're looking at, or open the laptop in your hand. */
    public static final KeyMapping FOCUS = new KeyMapping("key.hypervisorx.focus", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);
    public static final KeyMapping LAPTOP_POWER = new KeyMapping("key.hypervisorx.laptop_power", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping TOGGLE_PIP = new KeyMapping("key.hypervisorx.toggle_pip", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping SETTINGS = new KeyMapping("key.hypervisorx.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    public static final List<KeyMapping> ALL = List.of(RELEASE, FOCUS, LAPTOP_POWER, TOGGLE_PIP, SETTINGS);

    private HvxKeys() {
    }

    /** true if {@code key}/{@code scancode} is whatever RELEASE is bound to */
    public static boolean isRelease(int key, int scancode) {
        return RELEASE.matches(key, scancode);
    }
}
