package net.hypervisorx.store;

import net.hypervisorx.registry.HvxItems;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Everything the OrbitTech Store 2.0 sells. Prices are in credits, paid out of the player's
 * inventory: iron 1, gold 3, emerald 6, diamond 16, netherite 64.
 */
public final class StoreCatalog {
    public enum Category { BUNDLES, CPU, MEMORY, GRAPHICS, STORAGE, EXPANSION, CASES, DISPLAYS, LAPTOPS, PERIPHERALS }

    public record Line(Supplier<? extends Item> item, int count) {
    }

    public record Product(String id, Category category, int price, List<Line> contents, String descriptionKey) {
        public ItemStack icon() {
            return new ItemStack(contents.get(0).item().get());
        }

        public boolean isBundle() {
            return category == Category.BUNDLES;
        }
    }

    /** currency items and what they're worth, cheapest first (we pay with the small coins first). */
    public static final List<Map.Entry<Item, Integer>> CURRENCY = List.of(
            Map.entry(Items.IRON_INGOT, 1), Map.entry(Items.GOLD_INGOT, 3), Map.entry(Items.EMERALD, 4), Map.entry(Items.DIAMOND, 9));

    private static final Map<String, Product> PRODUCTS = new LinkedHashMap<>();

    static {
        bundle("starter_pc", 80, List.of(l(HvxItems.RETRO_PC), l(HvxItems.CPU_Q4), l(HvxItems.RAM_4, 2), l(HvxItems.GPU_IGPU),
                l(HvxItems.HDD), l(HvxItems.MONITOR), l(HvxItems.KEYBOARD), l(HvxItems.MOUSE), l(HvxItems.HDMI_CABLE)));
        bundle("streamer_setup", 290, List.of(l(HvxItems.GAMING_TOWER), l(HvxItems.CPU_X8), l(HvxItems.RAM_16, 2), l(HvxItems.GPU_GX),
                l(HvxItems.NVME), l(HvxItems.CURVED_MONITOR), l(HvxItems.KEYBOARD), l(HvxItems.MOUSE), l(HvxItems.SPEAKERS),
                l(HvxItems.HDMI_CABLE, 2)));
        bundle("server_rack_kit", 620, List.of(l(HvxItems.SERVER_RACK, 3), l(HvxItems.BLADE_X16, 2), l(HvxItems.ENTERPRISE_HDD, 2),
                l(HvxItems.NVME), l(HvxItems.WIFI_CARD), l(HvxItems.GPIO_CARD), l(HvxItems.VIDEO_WALL, 16), l(HvxItems.HDMI_CABLE, 4)));
        bundle("mobile_pro", 150, List.of(l(HvxItems.PRO_LAPTOP), l(HvxItems.USB_DRIVE), l(HvxItems.WIFI_CARD)));

        single("cpu_orbitcore_d2", Category.CPU, 8, HvxItems.CPU_D2);
        single("cpu_orbitcore_q4", Category.CPU, 16, HvxItems.CPU_Q4);
        single("cpu_orbitcore_x8", Category.CPU, 40, HvxItems.CPU_X8);
        single("cpu_orbitripper_16", Category.CPU, 96, HvxItems.CPU_R16);
        single("server_blade_x8", Category.CPU, 80, HvxItems.BLADE_X8);
        single("server_blade_x16", Category.CPU, 160, HvxItems.BLADE_X16);
        single("ram_1gb", Category.MEMORY, 4, HvxItems.RAM_1);
        single("ram_2gb", Category.MEMORY, 6, HvxItems.RAM_2);
        single("ram_4gb", Category.MEMORY, 10, HvxItems.RAM_4);
        single("ram_8gb", Category.MEMORY, 18, HvxItems.RAM_8);
        single("ram_16gb", Category.MEMORY, 32, HvxItems.RAM_16);
        single("ram_32gb", Category.MEMORY, 60, HvxItems.RAM_32);
        single("gpu_integrated", Category.GRAPHICS, 6, HvxItems.GPU_IGPU);
        single("gpu_orbitforce_gt", Category.GRAPHICS, 20, HvxItems.GPU_GT);
        single("gpu_orbitforce_gx", Category.GRAPHICS, 45, HvxItems.GPU_GX);
        single("gpu_orbitforce_titan", Category.GRAPHICS, 110, HvxItems.GPU_TITAN);
        single("hdd_500gb", Category.STORAGE, 8, HvxItems.HDD);
        single("ssd_1tb", Category.STORAGE, 16, HvxItems.SSD);
        single("nvme_2tb", Category.STORAGE, 32, HvxItems.NVME);
        single("enterprise_hdd_8tb", Category.STORAGE, 40, HvxItems.ENTERPRISE_HDD);
        single("usb_flash_drive", Category.STORAGE, 4, HvxItems.USB_DRIVE);
        single("redstone_gpio_card", Category.EXPANSION, 24, HvxItems.GPIO_CARD);
        single("wifi_card", Category.EXPANSION, 12, HvxItems.WIFI_CARD);
        single("retro_pc", Category.CASES, 10, HvxItems.RETRO_PC);
        single("gaming_tower", Category.CASES, 30, HvxItems.GAMING_TOWER);
        single("server_rack", Category.CASES, 40, HvxItems.SERVER_RACK);
        single("monitor", Category.DISPLAYS, 12, HvxItems.MONITOR);
        single("crt_monitor", Category.DISPLAYS, 8, HvxItems.CRT_MONITOR);
        single("curved_monitor", Category.DISPLAYS, 40, HvxItems.CURVED_MONITOR);
        single("dual_monitor_arm", Category.DISPLAYS, 30, HvxItems.DUAL_MONITOR_ARM);
        single("triple_monitor_arm", Category.DISPLAYS, 45, HvxItems.TRIPLE_MONITOR_ARM);
        single("video_wall_panel", Category.DISPLAYS, 10, HvxItems.VIDEO_WALL);
        single("pro_laptop", Category.LAPTOPS, 140, HvxItems.PRO_LAPTOP);
        single("rugged_laptop", Category.LAPTOPS, 100, HvxItems.RUGGED_LAPTOP);
        single("mechanical_keyboard", Category.PERIPHERALS, 3, HvxItems.KEYBOARD);
        single("gaming_mouse", Category.PERIPHERALS, 2, HvxItems.MOUSE);
        single("desktop_speakers", Category.PERIPHERALS, 4, HvxItems.SPEAKERS);
        single("hdmi_cable", Category.PERIPHERALS, 2, HvxItems.HDMI_CABLE);
    }

    private StoreCatalog() {
    }

    private static Line l(Supplier<? extends Item> item) {
        return new Line(item, 1);
    }

    private static Line l(Supplier<? extends Item> item, int count) {
        return new Line(item, count);
    }

    private static void bundle(String id, int price, List<Line> lines) {
        PRODUCTS.put(id, new Product(id, Category.BUNDLES, price, lines, "store.hypervisorx.bundle." + id));
    }

    private static void single(String id, Category c, int price, Supplier<? extends Item> item) {
        PRODUCTS.put(id, new Product(id, c, price, List.of(new Line(item, 1)), ""));
    }

    public static List<Product> all() {
        return new ArrayList<>(PRODUCTS.values());
    }

    public static Product get(String id) {
        return PRODUCTS.get(id);
    }
}
