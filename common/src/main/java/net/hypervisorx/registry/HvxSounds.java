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
    /** Motherboard assembly sounds (made with tools/gen_sounds.py). */
    public static final Supplier<SoundEvent> ASSEMBLY_CPU_DROP = register("assembly_cpu_drop");
    public static final Supplier<SoundEvent> ASSEMBLY_CPU_LEVER = register("assembly_cpu_lever");
    public static final Supplier<SoundEvent> ASSEMBLY_RAM_CLICK = register("assembly_ram_click");
    public static final Supplier<SoundEvent> ASSEMBLY_GPU_SEAT = register("assembly_gpu_seat");
    public static final Supplier<SoundEvent> ASSEMBLY_SCREW = register("assembly_screw");
    public static final Supplier<SoundEvent> ASSEMBLY_CARD_SEAT = register("assembly_card_seat");
    public static final Supplier<SoundEvent> ASSEMBLY_USB_PLUG = register("assembly_usb_plug");
    public static final Supplier<SoundEvent> ASSEMBLY_PART_REMOVE = register("assembly_part_remove");
    public static final Supplier<SoundEvent> ASSEMBLY_POST_BEEP = register("assembly_post_beep");
    public static final Supplier<SoundEvent> ASSEMBLY_PART_PICK = register("assembly_part_pick");

    private HvxSounds() {
    }

    private static Supplier<SoundEvent> register(String name) {
        return Services.PLATFORM.register(BuiltInRegistries.SOUND_EVENT, name, () -> SoundEvent.createVariableRangeEvent(HyperVisorX.id(name)));
    }

    public static void init() {
    }
}
