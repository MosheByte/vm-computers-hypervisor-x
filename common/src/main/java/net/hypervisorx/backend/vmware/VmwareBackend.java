package net.hypervisorx.backend.vmware;

import net.hypervisorx.backend.BackendContext;
import net.hypervisorx.backend.BackendException;
import net.hypervisorx.backend.BackendFeature;
import net.hypervisorx.backend.BackendStatus;
import net.hypervisorx.backend.GuestOs;
import net.hypervisorx.backend.HypervisorBackend;
import net.hypervisorx.backend.VmInstance;
import net.hypervisorx.backend.VmLaunchSpec;
import net.hypervisorx.util.Processes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VMware Workstation Pro / Player / Fusion backend. Machines are just plain {@code .vmx} files run
 * through {@code vmrun}, and the display is VMware's built-in VNC server (password protected, localhost).
 */
public final class VmwareBackend implements HypervisorBackend {
    public static final String ID = "vmware";
    private static final Pattern VERSION = Pattern.compile("vmrun version ([0-9.]+)(?: build-(\\d+))?");
    private static final String PW_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "VMware Workstation / Player / Fusion";
    }

    @Override
    public Set<BackendFeature> features() {
        return EnumSet.of(BackendFeature.DISKS, BackendFeature.SNAPSHOTS, BackendFeature.SUSPEND, BackendFeature.GPIO,
                BackendFeature.USB_DRIVES, BackendFeature.INPUT);
    }

    static List<String> knownLocations() {
        List<String> l = new ArrayList<>();
        if (Processes.WINDOWS) {
            l.add("C:\\Program Files (x86)\\VMware\\VMware Workstation");
            l.add("C:\\Program Files\\VMware\\VMware Workstation");
            l.add("C:\\Program Files (x86)\\VMware\\VMware Player");
            l.add("C:\\Program Files\\VMware\\VMware Player");
            l.add("C:\\Program Files (x86)\\VMware\\VMware VIX");
        } else if (Processes.MAC) {
            l.add("/Applications/VMware Fusion.app/Contents/Library");
            l.add("/Applications/VMware Fusion.app/Contents/Public");
        } else {
            l.add("/usr/bin");
            l.add("/usr/local/bin");
        }
        return l;
    }

    record Install(Path vmrun, Path vdiskmanager, String hostType) {
    }

    static Optional<Install> find(BackendContext ctx) {
        Optional<Path> vmrun = Processes.find(ctx.override(ID), "vmrun", knownLocations());
        if (vmrun.isEmpty()) return Optional.empty();
        Path dir = vmrun.get().getParent();
        Path vdm = dir.resolve(Processes.exe("vmware-vdiskmanager"));
        if (!Files.isRegularFile(vdm)) {
            vdm = Processes.find("", "vmware-vdiskmanager", knownLocations()).orElse(vdm);
        }
        String type;
        if (Processes.MAC) {
            type = "fusion";
        } else if (Files.isRegularFile(dir.resolve(Processes.exe("vmware"))) || Files.isRegularFile(Path.of("/usr/bin/vmware"))) {
            type = "ws";
        } else {
            type = "player";
        }
        return Optional.of(new Install(vmrun.get(), vdm, type));
    }

    @Override
    public BackendStatus detect(BackendContext ctx) {
        Optional<Install> inst = find(ctx);
        if (inst.isEmpty()) {
            return BackendStatus.unavailable("vmrun not found. Install VMware Workstation Pro (free for personal use) or set its folder in settings.");
        }
        try {
            Processes.Result r = Processes.run(15, inst.get().vmrun().toString());
            Matcher m = VERSION.matcher(r.output());
            String version = m.find() ? m.group(1) + (m.group(2) != null ? " build " + m.group(2) : "") : "?";
            if (!Files.isRegularFile(inst.get().vdiskmanager())) {
                return BackendStatus.unavailable("vmware-vdiskmanager not found next to vmrun; reinstall VMware.");
            }
            String product = switch (inst.get().hostType()) {
                case "ws" -> "Workstation";
                case "fusion" -> "Fusion";
                default -> "Player";
            };
            String productVersion = productVersion();
            boolean hyperV = Processes.WINDOWS && windowsHypervisorPresent();
            String shown = productVersion.isEmpty() ? "vmrun " + version : productVersion;
            return BackendStatus.ok(shown, inst.get().vmrun().toString(), "Ready (VMware " + product
                    + (hyperV ? ", running on Hyper-V through the Windows Hypervisor Platform" : ", hardware virtualization (VT-x/AMD-V)") + ").", true);
        } catch (IOException e) {
            return BackendStatus.unavailable("vmrun failed to run: " + e.getMessage());
        }
    }

    @Override
    public VmInstance launch(VmLaunchSpec spec, BackendContext ctx) throws BackendException {
        Install inst = find(ctx).orElseThrow(() -> new BackendException("VMware is not installed"));
        int vncPort = -1;
        try {
            Files.createDirectories(spec.vmDir());
            Path vmxPath = spec.vmDir().resolve(spec.machineName() + ".vmx");
            Path disk = spec.vmDir().resolve("disk.vmdk");
            if (spec.existingDisk() != null) {
                if (!spec.existingDiskFormat().equals("vmdk")) {
                    throw new BackendException("VMware can only boot .vmdk disks; use QEMU for ." + spec.existingDiskFormat() + " images.");
                }
                disk = spec.existingDisk();
            } else if (spec.diskGb() > 0 && !Files.exists(disk)) {
                Processes.Result r = Processes.run(300, inst.vdiskmanager().toString(), "-c", "-s", spec.diskGb() + "GB", "-a", "lsilogic", "-t", "0", disk.toString());
                if (!r.ok()) throw new BackendException("vmware-vdiskmanager failed: " + r.output());
            }
            String pipe = Processes.WINDOWS ? "\\\\.\\pipe\\hvx-gpio-" + spec.id().toString().substring(0, 8)
                    : spec.vmDir().resolve("gpio.sock").toString();
            String password;
            if (isSuspended(spec.vmDir()) && Files.exists(vmxPath)) {
                // resuming a suspended VM: the .vmx can't change (memory size, devices), so reuse its VNC settings
                VmxFile old = VmxFile.load(vmxPath);
                vncPort = Integer.parseInt(old.get("RemoteDisplay.vnc.port"));
                password = old.get("RemoteDisplay.vnc.password");
                Processes.reservePort(vncPort);
            } else {
                vncPort = Processes.allocatePort(5970, 6099);
                password = randomPassword();
                writeVmx(spec, vmxPath, disk, vncPort, password, pipe);
            }

            Processes.Result start = Processes.run(240, inst.vmrun().toString(), "-T", inst.hostType(), "start", vmxPath.toString(), "nogui");
            if (!start.ok()) {
                throw new BackendException("vmrun start failed: " + start.output());
            }
            VmwareInstance instance = new VmwareInstance(spec, inst, vmxPath, vncPort, password, spec.gpio() ? pipe : null);
            instance.connect();
            return instance;
        } catch (IOException e) {
            if (vncPort > 0) Processes.releasePort(vncPort);
            throw new BackendException("VMware failed to start: " + e.getMessage(), e);
        } catch (BackendException e) {
            if (vncPort > 0) Processes.releasePort(vncPort);
            throw e;
        }
    }

    /** Installed Workstation/Player version ("26H1u1", "17.6.2"), or "" if we can't read it. */
    private static String productVersion() {
        try {
            if (Processes.WINDOWS) {
                for (String key : List.of("HKLM\\SOFTWARE\\WOW6432Node\\VMware, Inc.\\VMware Workstation",
                        "HKLM\\SOFTWARE\\VMware, Inc.\\VMware Workstation", "HKLM\\SOFTWARE\\WOW6432Node\\VMware, Inc.\\VMware Player")) {
                    Processes.Result r = Processes.run(10, "reg", "query", key, "/v", "ProductVersion");
                    Matcher m = Pattern.compile("ProductVersion\\s+REG_SZ\\s+(\\S+)").matcher(r.output());
                    if (m.find()) return m.group(1);
                }
            } else if (Processes.LINUX) {
                Processes.Result r = Processes.run(10, "vmware", "-v");
                Matcher m = Pattern.compile("VMware \\w+ (\\S+)").matcher(r.output());
                if (m.find()) return m.group(1);
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    private static boolean windowsHypervisorPresent() {
        try {
            Processes.Result r = Processes.run(15, "powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_ComputerSystem).HypervisorPresent");
            return r.output().trim().equalsIgnoreCase("true");
        } catch (IOException e) {
            return false;
        }
    }

    private static String randomPassword() {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(PW_CHARS.charAt(rnd.nextInt(PW_CHARS.length())));
        return sb.toString();
    }

    /** A suspended VMware VM keeps its memory image in a .vmss next to the .vmx. */
    static boolean isSuspended(Path vmDir) {
        try (var files = Files.list(vmDir)) {
            return files.anyMatch(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".vmss"));
        } catch (IOException e) {
            return false;
        }
    }

    static void writeVmx(VmLaunchSpec spec, Path vmxPath, Path disk, int vncPort, String password, String pipe) throws IOException {
        VmxFile vmx = VmxFile.load(vmxPath);
        boolean legacy = spec.guestOs() == GuestOs.DOS;
        vmx.set(".encoding", "UTF-8");
        vmx.set("config.version", "8");
        if (vmx.get("virtualHW.version") == null) vmx.set("virtualHW.version", legacy ? "14" : "19");
        vmx.set("displayName", spec.name() + " (HyperVisor X)");
        vmx.set("guestOS", spec.guestOs().vmwareType());
        vmx.set("memsize", Math.max(64, spec.ramMb()));
        vmx.set("numvcpus", Math.max(1, spec.cores()));
        vmx.set("cpuid.coresPerSocket", Math.max(1, spec.cores()));
        vmx.set("firmware", spec.efi() && !legacy ? "efi" : "bios");
        vmx.set("msg.autoAnswer", true);
        vmx.set("tools.syncTime", true);
        vmx.set("floppy0.present", false);
        vmx.set("pciBridge0.present", true);
        for (int i = 4; i <= 7; i++) {
            vmx.set("pciBridge" + i + ".present", true);
            vmx.set("pciBridge" + i + ".virtualDev", "pcieRootPort");
            vmx.set("pciBridge" + i + ".functions", "8");
        }
        vmx.removePrefix("nvme0");
        vmx.removePrefix("sata0:0.");
        if (spec.diskGb() > 0 || spec.existingDisk() != null) {
            String diskName = spec.existingDisk() != null ? disk.toAbsolutePath().toString() : disk.getFileName().toString();
            if (spec.nvme() && !legacy) {
                vmx.set("nvme0.present", true);
                vmx.set("nvme0:0.present", true);
                vmx.set("nvme0:0.fileName", diskName);
            } else {
                vmx.set("sata0:0.present", true);
                vmx.set("sata0:0.fileName", diskName);
            }
        }
        vmx.set("sata0.present", true);
        vmx.removePrefix("sata0:1.");
        if (spec.isoImage() != null && Files.isRegularFile(spec.isoImage())) {
            vmx.set("sata0:1.present", true);
            vmx.set("sata0:1.deviceType", "cdrom-image");
            vmx.set("sata0:1.fileName", spec.isoImage().toAbsolutePath().toString());
            vmx.set("sata0:1.startConnected", true);
        } else {
            vmx.set("sata0:1.present", true);
            vmx.set("sata0:1.deviceType", "cdrom-image");
            vmx.set("sata0:1.fileName", "");
            vmx.set("sata0:1.startConnected", false);
        }
        for (int i = 2; i < 6; i++) vmx.removePrefix("sata0:" + i + ".");
        int port = 2;
        for (Path usb : spec.usbDrives()) {
            if (!Files.isRegularFile(usb) || port >= 6) continue;
            vmx.set("sata0:" + port + ".present", true);
            vmx.set("sata0:" + port + ".fileName", usb.toAbsolutePath().toString());
            vmx.set("sata0:" + port + ".mode", "independent-persistent");
            port++;
        }
        vmx.set("ethernet0.present", true);
        vmx.set("ethernet0.connectionType", "nat");
        vmx.set("ethernet0.virtualDev", legacy ? "vlance" : spec.guestOs() == GuestOs.WINDOWS_LEGACY || spec.guestOs() == GuestOs.REACTOS ? "e1000" : "e1000e");
        if (vmx.get("ethernet0.addressType") == null) vmx.set("ethernet0.addressType", "generated");
        vmx.set("usb.present", true);
        vmx.set("ehci.present", true);
        vmx.set("usb_xhci.present", !legacy);
        vmx.set("sound.present", spec.audio());
        vmx.set("sound.virtualDev", legacy ? "sb16" : "hdaudio");
        vmx.set("sound.fileName", "-1");
        vmx.set("sound.autoDetect", true);
        long vram = Math.max(16L << 20, Math.min(256L << 20, (long) spec.width() * spec.height() * 4 * 2));
        vmx.set("svga.vramSize", vram);
        vmx.set("svga.maxWidth", spec.width());
        vmx.set("svga.maxHeight", spec.height());
        vmx.set("mks.enable3d", false);
        vmx.set("RemoteDisplay.vnc.enabled", true);
        vmx.set("RemoteDisplay.vnc.port", vncPort);
        vmx.set("RemoteDisplay.vnc.password", password);
        vmx.set("RemoteDisplay.vnc.ip", "127.0.0.1");
        vmx.removePrefix("serial0");
        if (spec.gpio()) {
            vmx.set("serial0.present", true);
            vmx.set("serial0.fileType", "pipe");
            vmx.set("serial0.fileName", pipe);
            vmx.set("serial0.pipe.endPoint", "server");
            vmx.set("serial0.tryNoRxLoss", true);
            vmx.set("serial0.yieldOnMsrRead", true);
        }
        vmx.save(vmxPath);
    }

    @Override
    public void destroy(VmLaunchSpec spec, BackendContext context, boolean deleteDisks) throws BackendException {
        Optional<Install> inst = find(context);
        Path vmx = spec.vmDir().resolve(spec.machineName() + ".vmx");
        if (deleteDisks && inst.isPresent() && Files.exists(vmx)) {
            try {
                Processes.run(120, inst.get().vmrun().toString(), "-T", inst.get().hostType(), "deleteVM", vmx.toString());
            } catch (IOException e) {
                throw new BackendException("vmrun deleteVM failed: " + e.getMessage(), e);
            }
        }
    }
}
