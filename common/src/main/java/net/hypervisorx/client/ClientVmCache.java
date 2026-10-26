package net.hypervisorx.client;

import net.hypervisorx.network.S2C;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Last status the server sent for each VM this client has looked at. */
public final class ClientVmCache {
    private static final Map<UUID, S2C.VmStatus> STATUS = new ConcurrentHashMap<>();
    private static volatile S2C.HostInfo hostInfo;
    private static volatile S2C.Library library;

    private ClientVmCache() {
    }

    public static void put(S2C.VmStatus s) {
        STATUS.put(s.vm(), s);
    }

    public static S2C.VmStatus get(UUID vm) {
        return vm == null ? null : STATUS.get(vm);
    }

    public static boolean isRunning(UUID vm) {
        S2C.VmStatus s = get(vm);
        return s != null && (s.state().equals("RUNNING") || s.state().equals("STARTING") || s.state().equals("STOPPING"));
    }

    public static int battery(UUID vm) {
        S2C.VmStatus s = get(vm);
        return s == null ? -1 : s.batteryPercent();
    }

    public static void hostInfo(S2C.HostInfo info) {
        hostInfo = info;
    }

    public static S2C.HostInfo hostInfo() {
        return hostInfo;
    }

    public static void library(S2C.Library lib) {
        library = lib;
    }

    public static S2C.Library library() {
        return library;
    }

    public static void clear() {
        STATUS.clear();
        hostInfo = null;
        library = null;
    }
}
