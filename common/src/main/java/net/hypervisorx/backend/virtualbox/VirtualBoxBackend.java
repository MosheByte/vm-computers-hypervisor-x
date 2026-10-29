package net.hypervisorx.backend.virtualbox;

import net.hypervisorx.backend.BackendContext;
import net.hypervisorx.backend.BackendException;
import net.hypervisorx.backend.BackendFeature;
import net.hypervisorx.backend.BackendStatus;
import net.hypervisorx.backend.GuestOs;
import net.hypervisorx.backend.GuestTools;
import net.hypervisorx.backend.HypervisorBackend;
import net.hypervisorx.backend.VmInstance;
import net.hypervisorx.backend.VmLaunchSpec;
import net.hypervisorx.backend.WindowsUnattend;
import net.hypervisorx.util.Processes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * VirtualBox 6.1/7.x backend. VBoxManage creates and sets up the machines, and our own
 * {@code vboxwebsrv} handles screenshots and input over one long-lived shared session.
 */
public final class VirtualBoxBackend implements HypervisorBackend {
    public static final String ID = "virtualbox";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Oracle VirtualBox";
    }

    @Override
    public Set<BackendFeature> features() {
        return EnumSet.of(BackendFeature.DISKS, BackendFeature.SNAPSHOTS, BackendFeature.SUSPEND, BackendFeature.GPIO,
                BackendFeature.USB_DRIVES, BackendFeature.INPUT);
    }

    static List<String> knownLocations() {
        List<String> l = new ArrayList<>();
        String env = System.getenv("VBOX_MSI_INSTALL_PATH");
        if (env != null) l.add(env);
        env = System.getenv("VBOX_INSTALL_PATH");
        if (env != null) l.add(env);
        if (Processes.WINDOWS) {
            l.add("C:\\Program Files\\Oracle\\VirtualBox");
        } else if (Processes.MAC) {
            l.add("/Applications/VirtualBox.app/Contents/MacOS");
            l.add("/usr/local/bin");
        } else {
            l.add("/usr/bin");
            l.add("/usr/lib/virtualbox");
            l.add("/opt/VirtualBox");
        }
        return l;
    }

    static Optional<Path> findManage(BackendContext ctx) {
        Optional<Path> p = Processes.find(ctx.override(ID), "VBoxManage", knownLocations());
        return p.isPresent() ? p : Processes.find(ctx.override(ID), "vboxmanage", knownLocations());
    }

    static Optional<Path> findWebSrv(Path manage) {
        Path p = manage.resolveSibling(Processes.exe("vboxwebsrv"));
        if (Files.isRegularFile(p)) return Optional.of(p);
        return Processes.find("", "vboxwebsrv", knownLocations());
    }

    @Override
    public BackendStatus detect(BackendContext ctx) {
        Optional<Path> manage = findManage(ctx);
        if (manage.isEmpty()) {
            return BackendStatus.unavailable("VBoxManage not found. Install VirtualBox 7 (virtualbox.org) or set its folder in settings.");
        }
        try {
            Processes.Result r = Processes.run(20, manage.get().toString(), "--version");
            if (!r.ok()) return BackendStatus.unavailable("VBoxManage failed: " + r.output());
            String version = r.output().lines().reduce((a, b) -> b).orElse("?").trim();
            if (findWebSrv(manage.get()).isEmpty()) {
                return BackendStatus.unavailable("VirtualBox " + version + " found, but vboxwebsrv is missing (install the full package).");
            }
            String note = version.startsWith("6.") || version.startsWith("7.") ? "" : " (untested version)";
            return BackendStatus.ok(version, manage.get().toString(), "Ready. The web service is started and managed automatically" + note + ".", true);
        } catch (IOException e) {
            return BackendStatus.unavailable("VBoxManage could not be run: " + e.getMessage());
        }
    }

    /** Parses {@code showvminfo --machinereadable} into a map. */
    static Map<String, String> vmInfo(Path manage, String name) throws IOException {
        Processes.Result r = Processes.run(30, manage.toString(), "showvminfo", name, "--machinereadable");
        if (!r.ok()) return Map.of();
        Map<String, String> m = new HashMap<>();
        for (String line : r.output().split("\\R")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).replace("\"", "");
            String v = line.substring(eq + 1).replace("\"", "");
            m.put(k, v);
        }
        return m;
    }

    private static void manage(Path manage, String... args) throws BackendException {
        List<String> cmd = new ArrayList<>();
        cmd.add(manage.toString());
        cmd.addAll(List.of(args));
        try {
            Processes.Result r = Processes.run(cmd, 180);
            if (!r.ok()) throw new BackendException("VBoxManage " + args[0] + " failed: " + r.output());
        } catch (IOException e) {
            throw new BackendException("VBoxManage " + args[0] + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * "terminated unexpectedly" tells you nothing, VirtualBox's own logs usually do. Most common one right
     * after installing VirtualBox: driver hardening blocks the VM process until Windows restarts.
     */
    static String explainStartFailure(VmLaunchSpec spec, String name, String raw) {
        Path logs = spec.vmDir().resolve(name).resolve("Logs");
        try {
            Path hardening = logs.resolve("VBoxHardening.log");
            if (Files.isRegularFile(hardening)) {
                String h = Files.readString(hardening, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (h.contains("SystemExtendedHandleInformation failed: 0xc0000004") || h.contains("querying handles")) {
                    // the driver check lists every open handle on the system, so one app leaking millions of them breaks it
                    String culprit = topHandleProcess();
                    return "VirtualBox could not start: Windows has too many open handles for its driver check"
                            + (culprit != null ? " (" + culprit + " holds the most; close it)" : "")
                            + ". Closing the program that leaks handles, or restarting Windows, fixes it. Details: " + hardening;
                }
                if (h.contains("VBoxDrvStub error") || h.contains("supR3HardenedWinReSpawn")) {
                    return "VirtualBox could not start its VM process (driver hardening check failed). Restart Windows once after"
                            + " installing or updating VirtualBox; if it persists, reinstall VirtualBox. Details: " + hardening;
                }
            }
            Path vbox = logs.resolve("VBox.log");
            if (Files.isRegularFile(vbox)) {
                for (String l : Files.readAllLines(vbox, java.nio.charset.StandardCharsets.ISO_8859_1).reversed()) {
                    if (l.contains("VERR_") || l.contains("ERROR")) return "VirtualBox failed to start the VM: " + l.strip();
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return raw;
    }

    /** "name (handles)" of whatever process holds the most handles, or null. Windows only. */
    private static String topHandleProcess() {
        if (!Processes.WINDOWS) return null;
        try {
            Processes.Result r = Processes.run(20, "powershell", "-NoProfile", "-Command",
                    "Get-Process | Sort-Object Handles -Descending | Select-Object -First 1 | ForEach-Object { $_.ProcessName + '.exe, PID ' + $_.Id + ', ' + $_.Handles + ' handles' }");
            String out = r.output().strip();
            return r.ok() && !out.isEmpty() ? out : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean manageQuiet(Path manage, String... args) {
        try {
            manage(manage, args);
            return true;
        } catch (BackendException e) {
            return false;
        }
    }

    @Override
    public VmInstance launch(VmLaunchSpec spec, BackendContext ctx) throws BackendException {
        Path manage = findManage(ctx).orElseThrow(() -> new BackendException("VirtualBox is not installed"));
        Path webSrv = findWebSrv(manage).orElseThrow(() -> new BackendException("vboxwebsrv not found"));
        String name = spec.machineName();
        int gpioPort = -1;
        try {
            Files.createDirectories(spec.vmDir());
            Map<String, String> info = vmInfo(manage, name);
            if (info.isEmpty()) {
                manage(manage, "createvm", "--name", name, "--ostype", spec.guestOs().virtualBoxType(), "--register",
                        "--basefolder", spec.vmDir().toString());
                info = vmInfo(manage, name);
            }
            String state = info.getOrDefault("VMState", "poweroff");
            if (state.equals("running") || state.equals("paused") || state.equals("stuck")) {
                manageQuiet(manage, "controlvm", name, "poweroff");
            }

            // a saved (suspended) machine won't take any changes, so just resume it as is
            boolean saved = state.equals("saved");
            // brand-new Windows VM installs itself, answer disc on first boot only
            boolean freshWindows = !saved && spec.existingDisk() == null && spec.diskGb() > 0 && !Files.exists(spec.vmDir().resolve("disk.vdi"))
                    && spec.isoImage() != null && WindowsUnattend.applies(spec.guestOs());
            if (!saved) {
                boolean legacy = spec.guestOs() == GuestOs.DOS;
                List<String> modify = Processes.list("modifyvm", name,
                        "--ostype", spec.guestOs().virtualBoxType(),
                        "--memory", Integer.toString(Math.max(64, spec.ramMb())),
                        "--cpus", Integer.toString(Math.max(1, spec.cores())),
                        "--vram", Integer.toString(Math.min(256, Math.max(16, (int) ((long) spec.width() * spec.height() * 4 * 2 >> 20) + 8))),
                        "--graphicscontroller", spec.guestOs().isWindowsFamily() ? "vboxsvga" : "vmsvga",
                        "--firmware", spec.efi() && !legacy ? "efi" : "bios",
                        "--nic1", "nat",
                        "--nictype1", legacy ? "Am79C973" : "82540EM",
                        "--mouse", legacy ? "ps2" : "usbtablet",
                        "--keyboard", "ps2",
                        "--rtcuseutc", spec.guestOs().windowsClock() ? "off" : "on",
                        "--vrde", "off");
                List<String> extended = new ArrayList<>(modify);
                extended.addAll(List.of("--usb-xhci", legacy ? "off" : "on", "--audio-enabled", spec.audio() ? "on" : "off", "--audio-out", "on"));
                if (spec.tpm() && !legacy) extended.addAll(List.of("--tpm-type", "2.0"));
                if (!manageQuiet(manage, extended.toArray(String[]::new))) {
                    manage(manage, modify.toArray(String[]::new)); // 6.1 doesn't know the new option names
                }
                manageQuiet(manage, "setextradata", name, "CustomVideoMode1", spec.width() + "x" + spec.height() + "x32");
                manageQuiet(manage, "setextradata", name, "GUI/MaxGuestResolution", "any");

                info = vmInfo(manage, name);
                if (!info.containsValue("SATA")) {
                    manage(manage, "storagectl", name, "--name", "SATA", "--add", "sata", "--controller", "IntelAhci", "--portcount", "6", "--bootable", "on");
                }
                boolean nvme = spec.nvme() && !legacy;
                if (nvme && !info.containsValue("NVMe")) {
                    manage(manage, "storagectl", name, "--name", "NVMe", "--add", "pcie", "--controller", "NVMe", "--bootable", "on");
                }
                if (spec.existingDisk() != null || spec.diskGb() > 0) {
                    Path disk = spec.existingDisk() != null ? spec.existingDisk() : spec.vmDir().resolve("disk.vdi");
                    String fmt = spec.existingDiskFormat();
                    if (fmt.equals("qcow2") || fmt.equals("raw")) {
                        throw new BackendException("VirtualBox cannot boot ." + fmt + " disks; convert it to VDI/VMDK or use the QEMU backend.");
                    }
                    if (spec.existingDisk() == null && !Files.exists(disk)) {
                        manage(manage, "createmedium", "disk", "--filename", disk.toString(), "--size", Long.toString(spec.diskGb() * 1024L), "--format", "VDI");
                    }
                    manageQuiet(manage, "storageattach", name, "--storagectl", nvme ? "SATA" : "NVMe", "--port", "0", "--device", "0", "--medium", "none");
                    manage(manage, "storageattach", name, "--storagectl", nvme ? "NVMe" : "SATA", "--port", "0", "--device", "0",
                            "--type", "hdd", "--medium", disk.toString());
                }
                String iso = spec.isoImage() != null && Files.isRegularFile(spec.isoImage()) ? spec.isoImage().toAbsolutePath().toString() : "emptydrive";
                manage(manage, "storageattach", name, "--storagectl", "SATA", "--port", "1", "--device", "0", "--type", "dvddrive", "--medium", iso);
                for (int port = 2; port < 6; port++) {
                    manageQuiet(manage, "storageattach", name, "--storagectl", "SATA", "--port", Integer.toString(port), "--device", "0", "--medium", "none");
                }
                int port = 2;
                for (Path usb : spec.usbDrives()) {
                    if (!Files.isRegularFile(usb) || port >= 6) continue;
                    manageQuiet(manage, "storageattach", name, "--storagectl", "SATA", "--port", Integer.toString(port++), "--device", "0",
                            "--type", "hdd", "--hotpluggable", "on", "--medium", usb.toAbsolutePath().toString());
                }
                // Guest Additions disc on every boot (display driver, mouse integration, time sync). a fresh Windows installs it by itself
                GuestTools.Disc tools = spec.guestTools() ? GuestTools.virtualBox(manage.getParent()) : null;
                Path answers = freshWindows ? WindowsUnattend.buildIso(spec, tools) : null;
                for (Path cd : new Path[]{answers, tools != null ? tools.disc() : null}) {
                    if (cd == null || port >= 6) continue;
                    manageQuiet(manage, "storageattach", name, "--storagectl", "SATA", "--port", Integer.toString(port++), "--device", "0",
                            "--type", "dvddrive", "--medium", cd.toAbsolutePath().toString());
                }
                if (answers != null) net.hypervisorx.HyperVisorX.LOG.info("Windows VM {}: unattended setup disc attached", spec.id());
            }
            if (saved) {
                String uart = info.getOrDefault("uartmode1", "");
                if (spec.gpio() && uart.startsWith("tcpserver,")) {
                    gpioPort = Integer.parseInt(uart.substring("tcpserver,".length()).trim());
                    Processes.reservePort(gpioPort);
                }
            } else if (spec.gpio()) {
                gpioPort = Processes.allocatePort(48100, 48999);
                if (!manageQuiet(manage, "modifyvm", name, "--uart1", "0x3F8", "4", "--uart-mode1", "tcpserver", Integer.toString(gpioPort))) {
                    manage(manage, "modifyvm", name, "--uart1", "0x3F8", "4", "--uartmode1", "tcpserver", Integer.toString(gpioPort));
                }
            } else {
                manageQuiet(manage, "modifyvm", name, "--uart1", "off");
            }

            // guests shouldn't reach stuff on the host's loopback (default in VirtualBox 7, we force it on older ones)
            if (!saved && !manageQuiet(manage, "modifyvm", name, "--nat-localhostreachable1", "off")) {
                manageQuiet(manage, "modifyvm", name, "--natlocalhostreachable1", "off");
            }
            try {
                manage(manage, "startvm", name, "--type", "headless");
            } catch (BackendException e) {
                throw new BackendException(explainStartFailure(spec, name, e.getMessage()), e);
            }
            if (freshWindows) pressKeyToBootDvd(manage, name);
            VboxWebService.ensure(webSrv, manage, ctx.virtualBoxWebPort(), ctx.allowVboxAuthChange(), ctx.dataRoot().resolve("logs"));
            VirtualBoxInstance instance = new VirtualBoxInstance(spec, manage, ctx.virtualBoxWebPort(), gpioPort);
            instance.connect();
            return instance;
        } catch (IOException e) {
            if (gpioPort > 0) Processes.releasePort(gpioPort);
            manageQuiet(manage, "controlvm", name, "poweroff");
            throw new BackendException("VirtualBox failed to start: " + e.getMessage(), e);
        } catch (BackendException e) {
            if (gpioPort > 0) Processes.releasePort(gpioPort);
            throw e;
        }
    }

    /**
     * Windows install media waits ~5 seconds on "Press any key to boot from CD or DVD" (always under UEFI).
     * Nobody's at the keyboard during an unattended install, so we tap Space for the first 45 seconds.
     */
    private static void pressKeyToBootDvd(Path manage, String name) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 45; i++) {
                if (!manageQuiet(manage, "controlvm", name, "keyboardputscancode", "39", "b9") && i > 5) return;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "hvx-vbox-bootkey");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public GuestTools.Disc guestTools(VmLaunchSpec spec, BackendContext context) {
        return findManage(context).map(m -> GuestTools.virtualBox(m.getParent())).orElse(null);
    }

    @Override
    public void destroy(VmLaunchSpec spec, BackendContext context, boolean deleteDisks) throws BackendException {
        Optional<Path> manage = findManage(context);
        if (manage.isEmpty()) return;
        if (deleteDisks) {
            manageQuiet(manage.get(), "unregistervm", spec.machineName(), "--delete");
        }
    }
}
