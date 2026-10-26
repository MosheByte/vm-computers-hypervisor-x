package net.hypervisorx.vm;

import net.hypervisorx.hardware.BuiltSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** a block that owns one or more VMs: computer case, server rack controller or a placed laptop. */
public interface VmHost {
    VmRecord.HostKind hostKind();

    int vmSlots();

    UUID vmId(int slot);

    void setVmId(int slot, UUID id);

    /** what's installed right now (racks: the pooled cluster resources). */
    BuiltSpec builtSpec();

    Direction facing();

    Level hostLevel();

    BlockPos hostPos();

    UUID ownerId();

    String ownerName();

    void setOwner(UUID owner, String name);

    /** called on the server thread whenever one of this host's VMs changes state. */
    void onVmStateChanged(int slot, VmSession.State state);

    /** redstone level (0-15) the GPIO card puts out on {@code side}. */
    int gpioOutput(Direction side);

    void setGpioOutput(Direction side, int level);

    /** redstone level coming in on {@code side}. */
    int gpioInput(Direction side);

    /** slot for a VM id, or -1. */
    default int slotOf(UUID vm) {
        for (int i = 0; i < vmSlots(); i++) {
            if (vm != null && vm.equals(vmId(i))) return i;
        }
        return -1;
    }
}
