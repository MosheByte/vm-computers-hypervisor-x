package net.hypervisorx.hardware;

/**
 * Fixed spec of one hardware item.
 *
 * @param type     slot kind
 * @param cores    CPU cores (CPU, BLADE)
 * @param ramMb    memory in MiB (RAM, BLADE)
 * @param width    max horizontal resolution (GPU)
 * @param height   max vertical resolution (GPU)
 * @param displays max monitors at once (GPU)
 * @param diskGb   capacity (STORAGE, USB)
 * @param nvme     NVMe bus (STORAGE)
 * @param feature  expansion card feature id ("gpio", "wifi")
 * @param tier     1..4 visual tier (tooltip colour, case renderer)
 */
public record HardwareSpec(ComponentType type, int cores, int ramMb, int width, int height, int displays, int diskGb,
                           boolean nvme, String feature, int tier) {
    public static HardwareSpec cpu(int cores, int tier) {
        return new HardwareSpec(ComponentType.CPU, cores, 0, 0, 0, 0, 0, false, "", tier);
    }

    public static HardwareSpec ram(int gb, int tier) {
        return new HardwareSpec(ComponentType.RAM, 0, gb * 1024, 0, 0, 0, 0, false, "", tier);
    }

    public static HardwareSpec gpu(int w, int h, int displays, int tier) {
        return new HardwareSpec(ComponentType.GPU, 0, 0, w, h, displays, 0, false, "", tier);
    }

    public static HardwareSpec storage(int gb, boolean nvme, int tier) {
        return new HardwareSpec(ComponentType.STORAGE, 0, 0, 0, 0, 0, gb, nvme, "", tier);
    }

    public static HardwareSpec usb(int gb) {
        return new HardwareSpec(ComponentType.USB, 0, 0, 0, 0, 0, gb, false, "", 1);
    }

    public static HardwareSpec expansion(String feature, int tier) {
        return new HardwareSpec(ComponentType.EXPANSION, 0, 0, 0, 0, 0, 0, false, feature, tier);
    }

    public static HardwareSpec blade(int cores, int ramGb, int tier) {
        return new HardwareSpec(ComponentType.BLADE, cores, ramGb * 1024, 0, 0, 0, 0, false, "", tier);
    }
}
