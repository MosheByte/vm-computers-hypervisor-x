package net.hypervisorx.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.backend.BackendContext;
import net.hypervisorx.platform.Services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side (world host) config, saved in {@code config/hypervisorx-server.json}.
 * Ops or the singleplayer host can edit it in-game from the settings screen.
 */
public final class HvxConfig {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile HvxConfig instance;
    private static final List<String> KNOWN_BACKENDS = List.of("vmware", "qemu", "virtualbox", "embedded", "hostcapture");

    /** Order "auto" tries the backends in. */
    public List<String> backendPreference = new ArrayList<>(List.of("vmware", "qemu", "virtualbox", "embedded"));
    /** Executable or install folder per backend id. Empty = auto-detect. */
    public Map<String, String> executablePaths = new LinkedHashMap<>(Map.of("vmware", "", "virtualbox", "", "qemu", ""));
    /** auto | kvm | whpx | hvf | tcg */
    public String qemuAccelerator = "auto";
    public int virtualBoxWebPort = 18083;
    public boolean allowVirtualBoxAuthChange = true;
    /** Shows host windows to players. Keep this off on public servers. */
    public boolean allowHostWindowCapture = false;
    /** How many frames per second the stream pump sends to players. */
    public int streamFps = 60;
    /** Bandwidth budget per watching player on remote (non-local) connections, in KiB/s. */
    public int maxBandwidthPerViewerKiB = 4096;
    /** 24 = lossless RGB888, 16 = RGB565 (half the bandwidth for remote players). */
    public int streamColorDepth = 24;
    public int maxCoresPerVm = 8;
    public int maxRamMbPerVm = 16384;
    /** Max percent of the host's physical RAM that all VMs together can take. */
    public int maxHostRamPercent = 60;
    public int maxRunningVms = 6;
    /** How many running VMs one player can own at once (ops are exempt). */
    public int maxRunningVmsPerPlayer = 2;
    /** Total RAM one player's running VMs can use together, in MiB (ops are exempt). */
    public int maxRamMbPerPlayer = 16384;
    /** Permission level needed to boot VMware/VirtualBox/QEMU machines (2 = ops, 0 = everyone). Embedded VMs are always allowed. */
    public int minPermissionForHostBackends = 2;
    /** Max distance in blocks between a player and a screen for the server to still send them its stream. */
    public int maxWatchDistance = 160;
    /** VMs whose computer never shows up again (burned item, deleted block) get cleaned up after this many days. 0 = never. */
    public int orphanVmDays = 30;
    /** suspend | shutdown | kill: what running VMs do when the world closes. */
    public String onServerStop = "suspend";
    public boolean storeEnabled = true;
    /** Store orders are free (creative players never pay anyway). */
    public boolean freeStore = false;
    /** Max laptop remote-desktop range in blocks (0 = anywhere in the same dimension). */
    public int remoteDesktopRange = 0;
    /** Laptop battery life in ticks of running time (20 ticks = 1 second). */
    public int laptopBatteryTicks = 20 * 60 * 45;
    /** RAM for embedded MicroVMs in MiB (16-256). */
    public int embeddedRamMb = 64;
    /** How far (in blocks) players can hear a VM's audio. */
    public int audioRange = 24;

    public static HvxConfig get() {
        HvxConfig c = instance;
        if (c == null) {
            synchronized (HvxConfig.class) {
                c = instance;
                if (c == null) {
                    c = load();
                    instance = c;
                }
            }
        }
        return c;
    }

    private static Path file() {
        return Services.PLATFORM.configDirectory().resolve("hypervisorx-server.json");
    }

    private static HvxConfig load() {
        Path f = file();
        HvxConfig c = null;
        if (Files.exists(f)) {
            try {
                c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), HvxConfig.class);
            } catch (IOException | JsonParseException e) {
                HyperVisorX.LOG.error("Invalid {}, using defaults: {}", f, e.getMessage());
            }
        }
        if (c == null) c = new HvxConfig();
        c.sanitize();
        c.save();
        return c;
    }

    /** Re-reads the file from disk ({@code /hvx reload}). */
    public static synchronized void reloadFromDisk() {
        instance = load();
    }

    public static synchronized void replace(HvxConfig updated) {
        updated.sanitize();
        instance = updated;
        updated.save();
    }

    /**
     * Applies settings edited in-game. Anything that makes the server launch programs or exposes the
     * host (executable paths, host window capture, VirtualBox web-service auth) can only be changed
     * by whoever's sitting at the host machine (singleplayer/LAN host), never by ops connecting over
     * the network. Dedicated servers change those in the config file.
     */
    public synchronized void applyFromPlayer(HvxConfig incoming, boolean atHostMachine) {
        replace(merged(incoming, atHostMachine));
    }

    /** What the settings end up as after a player's edit (host-only fields stay put unless they're at the host machine). */
    public HvxConfig merged(HvxConfig incoming, boolean atHostMachine) {
        HvxConfig next = incoming.copy();
        if (!atHostMachine) {
            next.executablePaths = new LinkedHashMap<>(executablePaths);
            next.allowHostWindowCapture = allowHostWindowCapture;
            next.allowVirtualBoxAuthChange = allowVirtualBoxAuthChange;
            next.virtualBoxWebPort = virtualBoxWebPort;
            next.minPermissionForHostBackends = minPermissionForHostBackends;
        }
        return next;
    }

    public HvxConfig copy() {
        HvxConfig c = GSON.fromJson(GSON.toJson(this), HvxConfig.class);
        c.sanitize();
        return c;
    }

    public void sanitize() {
        if (backendPreference == null || backendPreference.isEmpty()) {
            backendPreference = new ArrayList<>(List.of("vmware", "qemu", "virtualbox", "embedded"));
        }
        backendPreference.removeIf(id -> !KNOWN_BACKENDS.contains(id));
        if (backendPreference.isEmpty()) backendPreference = new ArrayList<>(List.of("embedded"));
        if (executablePaths == null) executablePaths = new LinkedHashMap<>();
        // never run programs off a network location
        executablePaths.replaceAll((k, v) -> v == null || net.hypervisorx.util.Processes.isNetworkPath(v) ? "" : v.strip());
        if (qemuAccelerator == null || !List.of("auto", "kvm", "whpx", "hvf", "tcg").contains(qemuAccelerator)) qemuAccelerator = "auto";
        if (onServerStop == null) onServerStop = "suspend";
        streamFps = clamp(streamFps, 5, 120);
        maxBandwidthPerViewerKiB = clamp(maxBandwidthPerViewerKiB, 64, 1 << 20);
        streamColorDepth = streamColorDepth == 16 ? 16 : 24;
        maxCoresPerVm = clamp(maxCoresPerVm, 1, 128);
        maxRamMbPerVm = clamp(maxRamMbPerVm, 256, 1 << 20);
        maxHostRamPercent = clamp(maxHostRamPercent, 10, 95);
        maxRunningVms = clamp(maxRunningVms, 1, 64);
        maxRunningVmsPerPlayer = clamp(maxRunningVmsPerPlayer, 1, 64);
        maxRamMbPerPlayer = clamp(maxRamMbPerPlayer, 256, 1 << 22);
        minPermissionForHostBackends = clamp(minPermissionForHostBackends, 0, 4);
        maxWatchDistance = clamp(maxWatchDistance, 16, 4096);
        orphanVmDays = clamp(orphanVmDays, 0, 3650);
        laptopBatteryTicks = clamp(laptopBatteryTicks, 20 * 30, 20 * 60 * 60 * 24);
        embeddedRamMb = clamp(embeddedRamMb, 16, 256);
        audioRange = clamp(audioRange, 4, 128);
        virtualBoxWebPort = clamp(virtualBoxWebPort, 1024, 65535);
        remoteDesktopRange = clamp(remoteDesktopRange, 0, 1 << 20);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            HyperVisorX.LOG.error("Could not save HyperVisor X server config", e);
        }
    }

    public BackendContext backendContext() {
        return new BackendContext(dataRoot(), Map.copyOf(executablePaths), virtualBoxWebPort, allowVirtualBoxAuthChange,
                qemuAccelerator, allowHostWindowCapture);
    }

    public static Path dataRoot() {
        return Services.PLATFORM.gameDirectory().resolve("hypervisorx");
    }
}
