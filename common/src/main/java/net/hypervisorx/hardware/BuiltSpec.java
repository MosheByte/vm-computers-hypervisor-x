package net.hypervisorx.hardware;

import net.hypervisorx.item.HardwareItem;
import net.hypervisorx.item.UsbDriveItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The machine you get from the parts in a chassis.
 *
 * @param problems what's still missing before it can boot (empty = ready)
 */
public record BuiltSpec(int cores, int ramMb, int width, int height, int displays, int diskGb, boolean nvme,
                        boolean gpio, boolean wifi, List<UUID> usbDrives, int gpuTier, List<Component> problems) {

    public boolean bootable() {
        return problems.isEmpty();
    }

    public static BuiltSpec fromItems(FormFactor form, List<ItemStack> items) {
        int cores = 0;
        int ram = 0;
        int w = 0;
        int h = 0;
        int displays = 0;
        int disk = 0;
        boolean nvme = false;
        boolean gpio = false;
        boolean wifi = false;
        int gpuTier = 0;
        List<UUID> usb = new ArrayList<>();
        for (ItemStack stack : items) {
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof UsbDriveItem) {
                UUID id = UsbDriveItem.driveId(stack);
                if (id != null) usb.add(id);
                continue;
            }
            if (!(stack.getItem() instanceof HardwareItem hw)) continue;
            HardwareSpec s = hw.spec();
            switch (s.type()) {
                case CPU -> cores = Math.max(cores, s.cores());
                case BLADE -> {
                    cores += s.cores();
                    ram += s.ramMb();
                }
                case RAM -> ram += s.ramMb();
                case GPU -> {
                    if (s.width() * s.height() > w * h) {
                        w = s.width();
                        h = s.height();
                        displays = s.displays();
                        gpuTier = s.tier();
                    }
                }
                case STORAGE -> {
                    // two drives in a tower (or a rack) get striped (RAID 0) into one bigger volume
                    disk += s.diskGb();
                    nvme |= s.nvme();
                }
                case EXPANSION -> {
                    gpio |= "gpio".equals(s.feature());
                    wifi |= "wifi".equals(s.feature());
                }
                default -> {
                }
            }
        }
        List<Component> problems = new ArrayList<>();
        if (form == FormFactor.RACK) {
            if (cores == 0) problems.add(Component.translatable("hypervisorx.problem.no_blade"));
            w = 1920;
            h = 1080;
            displays = 3;
            gpuTier = 3;
        } else {
            if (cores == 0) problems.add(Component.translatable("hypervisorx.problem.no_cpu"));
            if (ram == 0) problems.add(Component.translatable("hypervisorx.problem.no_ram"));
            if (w == 0) problems.add(Component.translatable("hypervisorx.problem.no_gpu"));
        }
        return new BuiltSpec(cores, ram, w, h, displays, disk, nvme, gpio, wifi, usb, gpuTier, problems);
    }

    /** Real hypervisors need a disk. The embedded MicroVM and window capture don't. */
    public List<Component> problemsFor(String backend) {
        List<Component> p = new ArrayList<>(problems);
        boolean diskless = "embedded".equals(backend) || "hostcapture".equals(backend);
        if (!diskless && diskGb == 0) p.add(Component.translatable("hypervisorx.problem.no_storage"));
        return p;
    }
}
