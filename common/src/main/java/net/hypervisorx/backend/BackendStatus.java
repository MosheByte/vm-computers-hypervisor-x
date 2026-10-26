package net.hypervisorx.backend;

/**
 * What we found when probing the host for a hypervisor.
 *
 * @param available  can we launch VMs on this backend right now
 * @param version    detected version string (empty if we don't know)
 * @param executable the main exe or library we found (empty if none)
 * @param message    plain-English reason, shown in the settings screen
 * @param accelerated true if hardware virtualization (VT-x/AMD-V via KVM/WHPX/HVF/VMM) is in use
 */
public record BackendStatus(boolean available, String version, String executable, String message, boolean accelerated) {
    public static BackendStatus unavailable(String message) {
        return new BackendStatus(false, "", "", message, false);
    }

    public static BackendStatus ok(String version, String executable, String message, boolean accelerated) {
        return new BackendStatus(true, version == null ? "" : version, executable == null ? "" : executable, message, accelerated);
    }
}
