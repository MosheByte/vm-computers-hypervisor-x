package net.hypervisorx.backend;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.backend.embedded.EmbeddedBackend;
import net.hypervisorx.backend.hostcapture.HostCaptureBackend;
import net.hypervisorx.backend.qemu.QemuBackend;
import net.hypervisorx.backend.virtualbox.VirtualBoxBackend;
import net.hypervisorx.backend.vmware.VmwareBackend;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Every hypervisor backend, plus a cached detect result for each one that gets refreshed in the background. */
public final class BackendRegistry {
    private static final Map<String, HypervisorBackend> BACKENDS = new LinkedHashMap<>();
    private static final Map<String, BackendStatus> STATUS = new ConcurrentHashMap<>();
    private static volatile CompletableFuture<Void> scan = CompletableFuture.completedFuture(null);
    private static volatile long lastScan;

    static {
        register(new VmwareBackend());
        register(new VirtualBoxBackend());
        register(new QemuBackend());
        register(new EmbeddedBackend());
        register(new HostCaptureBackend());
    }

    private BackendRegistry() {
    }

    private static void register(HypervisorBackend b) {
        BACKENDS.put(b.id(), b);
        STATUS.put(b.id(), BackendStatus.unavailable("Not scanned yet"));
    }

    public static List<HypervisorBackend> all() {
        return List.copyOf(BACKENDS.values());
    }

    public static Optional<HypervisorBackend> get(String id) {
        return Optional.ofNullable(BACKENDS.get(id));
    }

    public static BackendStatus status(String id) {
        return STATUS.getOrDefault(id, BackendStatus.unavailable("Unknown backend " + id));
    }

    public static Map<String, BackendStatus> statuses() {
        Map<String, BackendStatus> m = new LinkedHashMap<>();
        for (String id : BACKENDS.keySet()) m.put(id, status(id));
        return m;
    }

    public static long lastScanMillis() {
        return lastScan;
    }

    /** Re-probes every backend off-thread. The probes run in parallel. */
    public static synchronized CompletableFuture<Void> rescan(BackendContext ctx) {
        if (!scan.isDone()) return scan;
        CompletableFuture<?>[] all = BACKENDS.values().stream().map(b -> CompletableFuture.runAsync(() -> {
            BackendStatus s;
            try {
                s = b.detect(ctx);
            } catch (Throwable t) {
                s = BackendStatus.unavailable("Detection failed: " + t);
            }
            STATUS.put(b.id(), s);
        })).toArray(CompletableFuture[]::new);
        scan = CompletableFuture.allOf(all).whenComplete((v, t) -> {
            lastScan = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder("Hypervisor scan:");
            STATUS.forEach((id, st) -> sb.append(' ').append(id).append('=').append(st.available() ? "OK(" + st.version() + ")" : "no"));
            HyperVisorX.LOG.info(sb.toString());
        });
        return scan;
    }

    /**
     * Backend for a VM set to "auto": first available one in {@code preference} order,
     * otherwise the embedded MicroVM (that one's always there).
     */
    public static String resolveAuto(List<String> preference) {
        for (String id : preference) {
            if (!id.equals(HostCaptureBackend.ID) && status(id).available()) return id;
        }
        return EmbeddedBackend.ID;
    }
}
