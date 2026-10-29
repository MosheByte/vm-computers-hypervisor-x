package net.hypervisorx.backend;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * All a backend needs to create and boot one machine. Hardware values are already clamped
 * to what the host can safely give.
 *
 * @param id           VM id (same across restarts, used for file and registration names)
 * @param name         display name
 * @param guestOs      guest family, drives firmware/disk/NIC defaults
 * @param cores        virtual CPU cores
 * @param ramMb        guest RAM in MiB
 * @param width        preferred display width (EDID / max resolution)
 * @param height       preferred display height
 * @param diskGb       primary disk size (sparse), 0 = no disk
 * @param nvme         put the primary disk on NVMe instead of SATA/AHCI
 * @param efi          boot with UEFI firmware
 * @param tpm          add a virtual TPM 2.0 if the backend can
 * @param vmDir        per-VM working folder, the backend owns it
 * @param isoImage     disc to put in the optical drive, or {@code null}
 * @param existingDisk imported disk image (.vmdk/.vdi/.qcow2/.vhd) to boot instead of a fresh blank disk, or {@code null}
 * @param usbDrives    USB flash drive images (VMDK) to attach
 * @param gpio         expose the Redstone GPIO serial port
 * @param audio        turn on the virtual sound card / audio streaming
 * @param target       backend-specific: window title filter for host capture, or kernel variant ("linux"/"doom") for embedded
 * @param embeddedKernel kernel image for the embedded MicroVM
 * @param guestTools   attach the hypervisor's guest tools disc and install it during a fresh Windows setup
 */
public record VmLaunchSpec(
        UUID id,
        String name,
        GuestOs guestOs,
        int cores,
        int ramMb,
        int width,
        int height,
        int diskGb,
        boolean nvme,
        boolean efi,
        boolean tpm,
        Path vmDir,
        Path isoImage,
        Path existingDisk,
        List<Path> usbDrives,
        boolean gpio,
        boolean audio,
        String target,
        Path embeddedKernel,
        boolean guestTools) {

    /** Bare-bones spec that just points at an existing VM's files, for unregistering/deleting them. */
    public static VmLaunchSpec forFiles(UUID id, String name, Path vmDir) {
        return new VmLaunchSpec(id, name, GuestOs.LINUX, 1, 256, 640, 480, 0, false, false, false, vmDir, null, null, List.of(),
                false, false, "", null, false);
    }

    /** QEMU/VirtualBox format name for the imported disk, going by its extension. */
    public String existingDiskFormat() {
        if (existingDisk == null) return "";
        String n = existingDisk.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".qcow2")) return "qcow2";
        if (n.endsWith(".vmdk")) return "vmdk";
        if (n.endsWith(".vdi")) return "vdi";
        if (n.endsWith(".vhd")) return "vpc";
        if (n.endsWith(".vhdx")) return "vhdx";
        return "raw";
    }

    /** Short, safe name for VirtualBox/VMware registrations and process titles. */
    public String machineName() {
        return "hvx-" + id.toString().substring(0, 8);
    }
}
