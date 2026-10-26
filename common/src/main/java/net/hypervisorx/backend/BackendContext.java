package net.hypervisorx.backend;

import java.nio.file.Path;
import java.util.Map;

/**
 * Host stuff every backend gets handed.
 *
 * @param dataRoot         {@code <game dir>/hypervisorx}
 * @param executableOverrides backend id to the exe path the user picked (empty string = auto-detect)
 * @param virtualBoxWebPort port for the {@code vboxwebsrv} we start and manage
 * @param allowVboxAuthChange if we're allowed to run {@code VBoxManage setproperty websrvauthlibrary null}
 * @param qemuAccelerator  "auto", "kvm", "whpx", "hvf" or "tcg"
 * @param allowHostCapture if the host window capture backend can be used at all
 */
public record BackendContext(
        Path dataRoot,
        Map<String, String> executableOverrides,
        int virtualBoxWebPort,
        boolean allowVboxAuthChange,
        String qemuAccelerator,
        boolean allowHostCapture) {

    public Path vmsRoot() {
        return dataRoot.resolve("vms");
    }

    public Path imagesRoot() {
        return dataRoot.resolve("images");
    }

    public Path drivesRoot() {
        return dataRoot.resolve("drives");
    }

    public Path firmwareRoot() {
        return dataRoot.resolve("firmware");
    }

    public String override(String backendId) {
        String s = executableOverrides.get(backendId);
        return s == null ? "" : s.trim();
    }
}
