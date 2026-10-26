package net.hypervisorx.hardware;

import java.lang.management.ManagementFactory;

/** How much the real host can safely hand out to VMs. */
public final class HostLimits {
    private static final long TOTAL_RAM_MB = detectTotalRamMb();
    private static final int CPUS = Runtime.getRuntime().availableProcessors();

    private HostLimits() {
    }

    private static long detectTotalRamMb() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize() / (1024 * 1024);
            }
        } catch (Throwable ignored) {
            // not a HotSpot JVM, just assume 8 GB
        }
        return 8192;
    }

    public static long totalRamMb() {
        return TOTAL_RAM_MB;
    }

    public static int cpus() {
        return CPUS;
    }

    /** Always leaves at least one host core for Minecraft itself. */
    public static int clampCores(int requested, int configMax) {
        int hostMax = Math.max(1, CPUS - 1);
        return Math.max(1, Math.min(requested, Math.min(configMax, hostMax)));
    }

    /**
     * @param alreadyUsedMb RAM the other running VMs already have
     */
    public static int clampRam(int requestedMb, int configMaxMb, int hostPercent, long alreadyUsedMb) {
        long budget = TOTAL_RAM_MB * hostPercent / 100 - alreadyUsedMb;
        long v = Math.min(requestedMb, Math.min(configMaxMb, budget));
        return (int) Math.max(0, v);
    }
}
