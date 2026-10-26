package net.hypervisorx.registry;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

import java.util.function.Supplier;

/** Sound events (see assets/hypervisorx/sounds.json). */
public final class HvxSounds {
    public static final Supplier<SoundEvent> KEYBOARD = register("keyboard_click");
    public static final Supplier<SoundEvent> POWER_ON = register("pc_power_on");
    public static final Supplier<SoundEvent> POWER_OFF = register("pc_power_off");
    public static final Supplier<SoundEvent> POD_INCOMING = register("pod_incoming");
    public static final Supplier<SoundEvent> POD_LAND = register("pod_land");
    public static final Supplier<SoundEvent> TABLET_BEEP = register("tablet_beep");
    public static final Supplier<SoundEvent> ORDER_CONFIRMED = register("order_confirmed");

    private HvxSounds() {
    }

    private static Supplier<SoundEvent> register(String name) {
        return Services.PLATFORM.register(BuiltInRegistries.SOUND_EVENT, name, () -> SoundEvent.createVariableRangeEvent(HyperVisorX.id(name)));
    }

    public static void init() {
    }
}
