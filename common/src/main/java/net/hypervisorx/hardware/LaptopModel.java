package net.hypervisorx.hardware;

import java.util.List;

/** The fixed laptop configs. */
public enum LaptopModel {
    PRO("pro_laptop", 8, 16 * 1024, 1920, 1080, 1000, true, 1.0f),
    RUGGED("rugged_laptop", 4, 8 * 1024, 1600, 900, 512, false, 2.0f);

    private final String id;
    private final int cores;
    private final int ramMb;
    private final int width;
    private final int height;
    private final int diskGb;
    private final boolean nvme;
    private final float batteryFactor;

    LaptopModel(String id, int cores, int ramMb, int width, int height, int diskGb, boolean nvme, float batteryFactor) {
        this.id = id;
        this.cores = cores;
        this.ramMb = ramMb;
        this.width = width;
        this.height = height;
        this.diskGb = diskGb;
        this.nvme = nvme;
        this.batteryFactor = batteryFactor;
    }

    public String id() {
        return id;
    }

    /** Rugged Toughbooks last twice as long on a charge. */
    public float batteryFactor() {
        return batteryFactor;
    }

    public BuiltSpec spec() {
        return new BuiltSpec(cores, ramMb, width, height, 1, diskGb, nvme, false, true, List.of(), 3, List.of());
    }

    public static LaptopModel byId(String id) {
        for (LaptopModel m : values()) if (m.id.equals(id)) return m;
        return PRO;
    }
}
