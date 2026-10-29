package net.hypervisorx.backend;

import java.util.Set;

/**
 * One virtualization engine (VMware, VirtualBox, QEMU, the embedded MicroVM or host window
 * capture). Implementations hold no state, they're just factories. {@link #launch} hands back a
 * {@link VmInstance} that owns every process, socket and thread of that one running machine.
 */
public interface HypervisorBackend {
    /** Id that gets saved in VM records, so never change it: {@code vmware}, {@code virtualbox}, {@code qemu}, {@code embedded}, {@code hostcapture}. */
    String id();

    String displayName();

    /** What this engine can do (when it's available). */
    Set<BackendFeature> features();

    /** Looks for this engine on the host. Can block for a few seconds, never call it on the game thread. */
    BackendStatus detect(BackendContext context);

    /**
     * Creates the machine from {@code spec} if needed and boots it. Blocks until the process is up,
     * but the display might still be connecting when this returns.
     */
    VmInstance launch(VmLaunchSpec spec, BackendContext context) throws BackendException;

    /**
     * This engine's guest tools disc for {@code spec}'s guest (VMware Tools, VirtualBox Guest Additions,
     * virtio-win), or null if it has none for that guest. Might download, so keep it off the server thread.
     */
    default GuestTools.Disc guestTools(VmLaunchSpec spec, BackendContext context) {
        return null;
    }

    /** Removes a VM's backend-specific files and registrations. Disk images stay unless {@code deleteDisks}. */
    default void destroy(VmLaunchSpec spec, BackendContext context, boolean deleteDisks) throws BackendException {
    }
}
