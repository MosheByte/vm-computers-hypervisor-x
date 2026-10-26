package net.hypervisorx.registry;

import net.hypervisorx.hardware.HardwareSpec;
import net.hypervisorx.hardware.LaptopModel;
import net.hypervisorx.item.HardwareItem;
import net.hypervisorx.item.HdmiCableItem;
import net.hypervisorx.item.LaptopItem;
import net.hypervisorx.item.StoreTabletItem;
import net.hypervisorx.item.UsbDriveItem;
import net.hypervisorx.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class HvxItems {
    /** Everything, in creative tab order. */
    public static final List<Supplier<? extends Item>> ALL = new ArrayList<>();

    public static final Supplier<StoreTabletItem> STORE_TABLET = register("orbittech_tablet", () -> new StoreTabletItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
    public static final Supplier<HdmiCableItem> HDMI_CABLE = register("hdmi_cable", () -> new HdmiCableItem(new Item.Properties().stacksTo(16)));

    // chassis and displays
    public static final Supplier<BlockItem> RETRO_PC = block("retro_pc", HvxBlocks.RETRO_PC, Rarity.COMMON);
    public static final Supplier<BlockItem> GAMING_TOWER = block("gaming_tower", HvxBlocks.GAMING_TOWER, Rarity.RARE);
    public static final Supplier<BlockItem> SERVER_RACK = block("server_rack", HvxBlocks.SERVER_RACK, Rarity.RARE);
    public static final Supplier<BlockItem> MONITOR = block("monitor", HvxBlocks.MONITOR, Rarity.COMMON);
    public static final Supplier<BlockItem> CRT_MONITOR = block("crt_monitor", HvxBlocks.CRT_MONITOR, Rarity.COMMON);
    public static final Supplier<BlockItem> CURVED_MONITOR = block("curved_monitor", HvxBlocks.CURVED_MONITOR, Rarity.RARE);
    public static final Supplier<BlockItem> DUAL_MONITOR_ARM = block("dual_monitor_arm", HvxBlocks.DUAL_MONITOR_ARM, Rarity.UNCOMMON);
    public static final Supplier<BlockItem> TRIPLE_MONITOR_ARM = block("triple_monitor_arm", HvxBlocks.TRIPLE_MONITOR_ARM, Rarity.RARE);
    public static final Supplier<BlockItem> VIDEO_WALL = block("video_wall_panel", HvxBlocks.VIDEO_WALL, Rarity.UNCOMMON);
    public static final Supplier<LaptopItem> PRO_LAPTOP = register("pro_laptop",
            () -> new LaptopItem(HvxBlocks.PRO_LAPTOP.get(), new Item.Properties().stacksTo(1).rarity(Rarity.EPIC), LaptopModel.PRO));
    public static final Supplier<LaptopItem> RUGGED_LAPTOP = register("rugged_laptop",
            () -> new LaptopItem(HvxBlocks.RUGGED_LAPTOP.get(), new Item.Properties().stacksTo(1).rarity(Rarity.RARE), LaptopModel.RUGGED));
    public static final Supplier<BlockItem> KEYBOARD = block("mechanical_keyboard", HvxBlocks.KEYBOARD, Rarity.COMMON);
    public static final Supplier<BlockItem> MOUSE = block("gaming_mouse", HvxBlocks.MOUSE, Rarity.COMMON);
    public static final Supplier<BlockItem> SPEAKERS = block("desktop_speakers", HvxBlocks.SPEAKERS, Rarity.COMMON);
    public static final Supplier<BlockItem> DELIVERY_CRATE = block("delivery_crate", HvxBlocks.DELIVERY_CRATE, Rarity.COMMON);

    // CPUs
    public static final Supplier<HardwareItem> CPU_D2 = hw("cpu_orbitcore_d2", HardwareSpec.cpu(2, 1), Rarity.COMMON);
    public static final Supplier<HardwareItem> CPU_Q4 = hw("cpu_orbitcore_q4", HardwareSpec.cpu(4, 2), Rarity.COMMON);
    public static final Supplier<HardwareItem> CPU_X8 = hw("cpu_orbitcore_x8", HardwareSpec.cpu(8, 3), Rarity.UNCOMMON);
    public static final Supplier<HardwareItem> CPU_R16 = hw("cpu_orbitripper_16", HardwareSpec.cpu(16, 4), Rarity.EPIC);
    // RAM
    public static final Supplier<HardwareItem> RAM_1 = hw("ram_1gb", HardwareSpec.ram(1, 1), Rarity.COMMON);
    public static final Supplier<HardwareItem> RAM_2 = hw("ram_2gb", HardwareSpec.ram(2, 1), Rarity.COMMON);
    public static final Supplier<HardwareItem> RAM_4 = hw("ram_4gb", HardwareSpec.ram(4, 2), Rarity.COMMON);
    public static final Supplier<HardwareItem> RAM_8 = hw("ram_8gb", HardwareSpec.ram(8, 3), Rarity.UNCOMMON);
    public static final Supplier<HardwareItem> RAM_16 = hw("ram_16gb", HardwareSpec.ram(16, 3), Rarity.RARE);
    public static final Supplier<HardwareItem> RAM_32 = hw("ram_32gb", HardwareSpec.ram(32, 4), Rarity.EPIC);
    // GPUs
    public static final Supplier<HardwareItem> GPU_IGPU = hw("gpu_integrated", HardwareSpec.gpu(1280, 720, 1, 1), Rarity.COMMON);
    public static final Supplier<HardwareItem> GPU_GT = hw("gpu_orbitforce_gt", HardwareSpec.gpu(1920, 1080, 2, 2), Rarity.UNCOMMON);
    public static final Supplier<HardwareItem> GPU_GX = hw("gpu_orbitforce_gx", HardwareSpec.gpu(2560, 1440, 3, 3), Rarity.RARE);
    public static final Supplier<HardwareItem> GPU_TITAN = hw("gpu_orbitforce_titan", HardwareSpec.gpu(3840, 2160, 3, 4), Rarity.EPIC);
    // storage
    public static final Supplier<HardwareItem> HDD = hw("hdd_500gb", HardwareSpec.storage(500, false, 1), Rarity.COMMON);
    public static final Supplier<HardwareItem> SSD = hw("ssd_1tb", HardwareSpec.storage(1000, false, 2), Rarity.UNCOMMON);
    public static final Supplier<HardwareItem> NVME = hw("nvme_2tb", HardwareSpec.storage(2000, true, 3), Rarity.RARE);
    public static final Supplier<HardwareItem> ENTERPRISE_HDD = hw("enterprise_hdd_8tb", HardwareSpec.storage(8000, false, 3), Rarity.RARE);
    public static final Supplier<UsbDriveItem> USB_DRIVE = register("usb_flash_drive", () -> new UsbDriveItem(new Item.Properties().stacksTo(1), 32));
    // expansion cards + blades
    public static final Supplier<HardwareItem> GPIO_CARD = hw("redstone_gpio_card", HardwareSpec.expansion("gpio", 3), Rarity.RARE);
    public static final Supplier<HardwareItem> WIFI_CARD = hw("wifi_card", HardwareSpec.expansion("wifi", 2), Rarity.UNCOMMON);
    public static final Supplier<HardwareItem> BLADE_X8 = hw("server_blade_x8", HardwareSpec.blade(8, 16, 3), Rarity.RARE);
    public static final Supplier<HardwareItem> BLADE_X16 = hw("server_blade_x16", HardwareSpec.blade(16, 32, 4), Rarity.EPIC);

    private HvxItems() {
    }

    private static <T extends Item> Supplier<T> register(String name, Supplier<T> factory) {
        Supplier<T> s = Services.PLATFORM.register(BuiltInRegistries.ITEM, name, factory);
        ALL.add(s);
        return s;
    }

    private static Supplier<BlockItem> block(String name, Supplier<? extends Block> block, Rarity rarity) {
        return register(name, () -> new BlockItem(block.get(), new Item.Properties().rarity(rarity)));
    }

    private static Supplier<HardwareItem> hw(String name, HardwareSpec spec, Rarity rarity) {
        return register(name, () -> new HardwareItem(new Item.Properties().stacksTo(16).rarity(rarity), spec));
    }

    public static void init() {
    }
}
