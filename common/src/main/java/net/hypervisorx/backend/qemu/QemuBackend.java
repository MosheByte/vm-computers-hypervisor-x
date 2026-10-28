package net.hypervisorx.backend.qemu;

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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QEMU backend. Accelerates with KVM (Linux), WHPX (Windows, runs fine next to Hyper-V/WSL2) or HVF
 * (macOS), falls back to TCG. Display and audio come over a localhost VNC socket, control over QMP,
 * GPIO over a TCP serial port.
 */
public final class QemuBackend implements HypervisorBackend {
    public static final String ID = "qemu";
    private static final Pattern VERSION = Pattern.compile("version\\s+([0-9][0-9.]*)");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "QEMU / KVM / WHPX / HVF";
    }

    @Override
    public Set<BackendFeature> features() {
        return EnumSet.of(BackendFeature.DISKS, BackendFeature.AUDIO, BackendFeature.SNAPSHOTS, BackendFeature.GPIO,
                BackendFeature.USB_DRIVES, BackendFeature.INPUT);
    }

    static List<String> knownLocations() {
        List<String> l = new ArrayList<>();
        if (Processes.WINDOWS) {
            l.add("C:\\Program Files\\qemu");
            l.add("C:\\Program Files\\QEMU");
            l.add("C:\\msys64\\ucrt64\\bin");
            l.add("C:\\msys64\\mingw64\\bin");
        } else if (Processes.MAC) {
            l.add("/opt/homebrew/bin");
            l.add("/usr/local/bin");
            l.add("/opt/local/bin");
        } else {
            l.add("/usr/bin");
            l.add("/usr/local/bin");
            l.add("/usr/libexec");
        }
        return l;
    }

    static Optional<Path> findQemu(BackendContext ctx) {
        return Processes.find(ctx.override(ID), "qemu-system-x86_64", knownLocations());
    }

    static Optional<Path> findQemuImg(Path qemu) {
        Path img = qemu.resolveSibling(Processes.exe("qemu-img"));
        if (Files.isRegularFile(img)) return Optional.of(img);
        return Processes.find("", "qemu-img", knownLocations());
    }

    /** Accelerators QEMU tries, in order. */
    static List<String> accelerators(BackendContext ctx, String helpOutput) {
        String pref = ctx.qemuAccelerator() == null ? "auto" : ctx.qemuAccelerator().toLowerCase(Locale.ROOT);
        List<String> chain = new ArrayList<>();
        if (!pref.equals("auto")) {
            chain.add(pref.equals("whpx") ? "whpx,kernel-irqchip=off" : pref);
        } else if (Processes.WINDOWS && helpOutput.contains("whpx")) {
            chain.add("whpx,kernel-irqchip=off");
        } else if (Processes.LINUX && Files.isReadable(Path.of("/dev/kvm")) && Files.isWritable(Path.of("/dev/kvm"))) {
            chain.add("kvm");
        } else if (Processes.MAC && helpOutput.contains("hvf")) {
            chain.add("hvf");
        }
        if (!chain.contains("tcg")) chain.add("tcg,thread=multi");
        return chain;
    }

    @Override
    public BackendStatus detect(BackendContext ctx) {
        Optional<Path> qemu = findQemu(ctx);
        if (qemu.isEmpty()) {
            return BackendStatus.unavailable("qemu-system-x86_64 not found. Install QEMU (qemu.org) or set its path in HyperVisor X settings.");
        }
        try {
            Processes.Result ver = Processes.run(15, qemu.get().toString(), "--version");
            Matcher m = VERSION.matcher(ver.output());
            String version = m.find() ? m.group(1) : ver.output().lines().findFirst().orElse("?");
            Processes.Result accel = Processes.run(15, qemu.get().toString(), "-accel", "help");
            List<String> chain = accelerators(ctx, accel.output().toLowerCase(Locale.ROOT));
            boolean hw = !chain.get(0).startsWith("tcg");
            String accelName = chain.get(0).split(",")[0].toUpperCase(Locale.ROOT);
            String msg = hw
                    ? "Ready with " + accelName + " hardware acceleration."
                    : "Ready, software emulation only (TCG). " + (Processes.WINDOWS
                    ? "Enable 'Windows Hypervisor Platform' in Windows Features for WHPX speed."
                    : Processes.LINUX ? "Add your user to the 'kvm' group for KVM speed." : "");
            if (findQemuImg(qemu.get()).isEmpty()) {
                return BackendStatus.unavailable("qemu-img was not found next to " + qemu.get());
            }
            return BackendStatus.ok(version, qemu.get().toString(), msg, hw);
        } catch (IOException e) {
            return BackendStatus.unavailable("QEMU found at " + qemu.get() + " but failed to run: " + e.getMessage());
        }
    }

    static Optional<Path[]> findOvmf(Path qemu) {
        List<Path[]> candidates = new ArrayList<>();
        Path share = qemu.getParent() == null ? Path.of(".") : qemu.getParent().resolve("share");
        candidates.add(new Path[]{share.resolve("edk2-x86_64-code.fd"), share.resolve("edk2-i386-vars.fd")});
        if (qemu.getParent() != null && qemu.getParent().getParent() != null) {
            Path prefixShare = qemu.getParent().getParent().resolve("share").resolve("qemu");
            candidates.add(new Path[]{prefixShare.resolve("edk2-x86_64-code.fd"), prefixShare.resolve("edk2-i386-vars.fd")});
        }
        candidates.add(new Path[]{Path.of("/usr/share/OVMF/OVMF_CODE_4M.fd"), Path.of("/usr/share/OVMF/OVMF_VARS_4M.fd")});
        candidates.add(new Path[]{Path.of("/usr/share/OVMF/OVMF_CODE.fd"), Path.of("/usr/share/OVMF/OVMF_VARS.fd")});
        candidates.add(new Path[]{Path.of("/usr/share/edk2/x64/OVMF_CODE.4m.fd"), Path.of("/usr/share/edk2/x64/OVMF_VARS.4m.fd")});
        candidates.add(new Path[]{Path.of("/usr/share/edk2/ovmf/OVMF_CODE.fd"), Path.of("/usr/share/edk2/ovmf/OVMF_VARS.fd")});
        candidates.add(new Path[]{Path.of("/usr/share/qemu/edk2-x86_64-code.fd"), Path.of("/usr/share/qemu/edk2-i386-vars.fd")});
        for (Path[] c : candidates) {
            if (Files.isRegularFile(c[0]) && Files.isRegularFile(c[1])) return Optional.of(c);
        }
        return Optional.empty();
    }

    @Override
    public VmInstance launch(VmLaunchSpec spec, BackendContext ctx) throws BackendException {
        Path qemu = findQemu(ctx).orElseThrow(() -> new BackendException("QEMU is not installed"));
        Path qemuImg = findQemuImg(qemu).orElseThrow(() -> new BackendException("qemu-img not found"));
        List<String> notes = new ArrayList<>();
        int vncPort = -1;
        int qmpPort = -1;
        int gpioPort = -1;
        String bindHost = "127.0.0.1";
        Path qmpSocket = null;
        String vncPassword = "";
        try {
            Files.createDirectories(spec.vmDir());
            Path disk = spec.vmDir().resolve("disk.qcow2");
            if (spec.existingDisk() == null && spec.diskGb() > 0 && !Files.exists(disk)) {
                Processes.Result r = Processes.run(120, qemuImg.toString(), "create", "-f", "qcow2", disk.toString(), spec.diskGb() + "G");
                if (!r.ok()) throw new BackendException("qemu-img create failed: " + r.output());
            }
            // VM got suspended when the world closed? resume it from its internal qcow2 snapshot
            boolean resume = spec.existingDisk() == null && Files.exists(disk)
                    && Processes.run(30, qemuImg.toString(), "snapshot", "-l", disk.toString()).output().contains(QemuInstance.SUSPEND_TAG);
            String accelHelp = Processes.run(15, qemu.toString(), "-accel", "help").output().toLowerCase(Locale.ROOT);
            List<String> accel = accelerators(ctx, accelHelp);

            // gotcha: QEMU's user-mode network lets guests reach the host's 127.0.0.1 via 10.0.2.2. So every
            // control/display/serial socket listens on a private loopback address (or a unix socket on macOS,
            // which only has 127.0.0.1) and the display also needs a random password on top.
            bindHost = privateLoopback();
            vncPort = Processes.allocatePort(5950, 6099);
            if (Processes.MAC) {
                qmpSocket = Path.of(System.getProperty("java.io.tmpdir"), "hvx-qmp-" + spec.id().toString().substring(0, 8) + ".sock");
                Files.deleteIfExists(qmpSocket);
            } else {
                qmpPort = Processes.allocatePort(47100, 47999);
            }
            if (spec.gpio()) gpioPort = Processes.allocatePort(48100, 48999);
            vncPassword = randomPassword();

            boolean legacy = spec.guestOs() == GuestOs.DOS;
            List<String> cmd = new ArrayList<>();
            cmd.add(qemu.toString());
            cmd.addAll(List.of("-name", spec.machineName() + ",process=" + spec.machineName()));
            cmd.add("-nodefaults");
            cmd.addAll(List.of("-machine", legacy ? "pc" : "q35"));
            for (String a : accel) cmd.addAll(List.of("-accel", a));
            String first = accel.get(0);
            if (first.startsWith("kvm") || first.startsWith("hvf")) {
                String cpu = "host";
                if (spec.guestOs().isWindowsFamily() && first.startsWith("kvm")) cpu += ",hv_relaxed,hv_vapic,hv_spinlocks=0x1fff,hv_time";
                cmd.addAll(List.of("-cpu", cpu));
            } else if (first.startsWith("tcg")) {
                cmd.addAll(List.of("-cpu", "max"));
            }
            int cores = Math.max(1, spec.cores());
            cmd.addAll(List.of("-smp", cores + ",sockets=1,cores=" + cores + ",threads=1"));
            cmd.addAll(List.of("-m", Integer.toString(Math.max(64, spec.ramMb()))));
            cmd.addAll(List.of("-vga", "none", "-device", "VGA,vgamem_mb=" + vgaMemMb(spec.width(), spec.height())
                    + ",edid=on,xres=" + spec.width() + ",yres=" + spec.height()));
            cmd.addAll(List.of("-display", "none"));
            String vnc = bindHost + ":" + (vncPort - 5900) + ",password=on";
            if (spec.audio()) {
                cmd.addAll(List.of("-audiodev", "none,id=snd0"));
                if (legacy) {
                    cmd.addAll(List.of("-device", "sb16,audiodev=snd0"));
                } else {
                    cmd.addAll(List.of("-device", "intel-hda", "-device", "hda-duplex,audiodev=snd0"));
                }
                vnc += ",audiodev=snd0";
            }
            cmd.addAll(List.of("-vnc", vnc));
            cmd.addAll(List.of("-qmp", qmpSocket != null ? "unix:" + opt(qmpSocket) + ",server=on,wait=off"
                    : "tcp:" + bindHost + ":" + qmpPort + ",server=on,wait=off"));
            cmd.addAll(List.of("-monitor", "none"));
            cmd.addAll(List.of("-pidfile", spec.vmDir().resolve("qemu.pid").toString()));
            if (spec.guestOs().windowsClock()) cmd.addAll(List.of("-rtc", "base=localtime"));

            if (spec.efi() && !legacy) {
                Optional<Path[]> ovmf = findOvmf(qemu);
                if (ovmf.isPresent()) {
                    Path vars = spec.vmDir().resolve("efivars.fd");
                    if (!Files.exists(vars)) Files.copy(ovmf.get()[1], vars, StandardCopyOption.REPLACE_EXISTING);
                    cmd.addAll(List.of("-drive", "if=pflash,format=raw,unit=0,readonly=on,file=" + opt(ovmf.get()[0])));
                    cmd.addAll(List.of("-drive", "if=pflash,format=raw,unit=1,file=" + opt(vars)));
                } else {
                    notes.add("UEFI firmware (OVMF/edk2) not found, booting with legacy BIOS.");
                }
            }
            if (spec.tpm() && !legacy) {
                Optional<String> tpm = startSwtpm(spec.vmDir(), cmd);
                tpm.ifPresent(notes::add);
            }

            boolean hasDisk = spec.existingDisk() != null || spec.diskGb() > 0;
            if (hasDisk) {
                Path diskFile = spec.existingDisk() != null ? spec.existingDisk() : disk;
                String fmt = spec.existingDisk() != null ? spec.existingDiskFormat() : "qcow2";
                cmd.addAll(List.of("-drive", "file=" + opt(diskFile) + ",if=none,id=disk0,format=" + fmt + ",discard=unmap,cache=writeback"));
                if (spec.nvme() && !legacy) {
                    cmd.addAll(List.of("-device", "nvme,drive=disk0,serial=HVX" + spec.id().toString().substring(0, 8) + ",bootindex=1"));
                } else {
                    cmd.addAll(List.of("-device", "ide-hd,drive=disk0,bus=ide.0,bootindex=1"));
                }
            }
            String cdFile = spec.isoImage() != null && Files.isRegularFile(spec.isoImage()) ? ",file=" + opt(spec.isoImage()) : "";
            cmd.addAll(List.of("-drive", "if=none,id=cd0,media=cdrom,readonly=on" + cdFile));
            cmd.addAll(List.of("-device", "ide-cd,drive=cd0,bus=ide." + (legacy ? "1" : "2") + ",id=cd0dev,bootindex=2"));

            String nic = switch (spec.guestOs()) {
                case DOS -> "ne2k_pci";
                case REACTOS, WINDOWS_LEGACY -> "e1000";
                default -> "e1000e";
            };
            cmd.addAll(List.of("-netdev", "user,id=net0", "-device", nic + ",netdev=net0"));
            if (!legacy) {
                cmd.addAll(List.of("-device", "qemu-xhci,id=xhci", "-device", "usb-tablet,bus=xhci.0"));
                int i = 0;
                for (Path usb : spec.usbDrives()) {
                    if (!Files.isRegularFile(usb)) continue;
                    cmd.addAll(List.of("-drive", "if=none,id=usb" + i + ",format=vmdk,file=" + opt(usb)));
                    cmd.addAll(List.of("-device", "usb-storage,bus=xhci.0,drive=usb" + i + ",removable=on"));
                    i++;
                }
            }
            if (gpioPort > 0) {
                cmd.addAll(List.of("-serial", "tcp:" + bindHost + ":" + gpioPort + ",server=on,wait=off"));
            } else {
                cmd.addAll(List.of("-serial", "none"));
            }
            cmd.addAll(List.of("-parallel", "none"));
            if (resume) cmd.addAll(List.of("-loadvm", QemuInstance.SUSPEND_TAG));

            Path log = spec.vmDir().resolve("qemu.log");
            Process process = Processes.spawn(cmd, spec.vmDir(), log);
            Files.writeString(spec.vmDir().resolve("last-command.txt"), String.join(" ", cmd));
            QemuInstance instance = new QemuInstance(spec, process, log, bindHost, vncPort, qmpPort, qmpSocket, vncPassword, gpioPort,
                    String.join(" ", notes));
            instance.connect();
            return instance;
        } catch (IOException e) {
            if (vncPort > 0) Processes.releasePort(vncPort);
            if (qmpPort > 0) Processes.releasePort(qmpPort);
            if (gpioPort > 0) Processes.releasePort(gpioPort);
            throw new BackendException("QEMU failed to start: " + e.getMessage(), e);
        }
    }

    /**
     * Loopback address slirp never forwards guest traffic to (it only maps 10.0.2.2 to 127.0.0.1).
     * Windows and Linux answer on all of 127/8, macOS only has 127.0.0.1.
     */
    static String privateLoopback() {
        if (Processes.MAC) return "127.0.0.1";
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        return "127.43." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
    }

    /** Random 8-char VNC password (RFB won't use more than 8 anyway). */
    static String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    /** Escapes a path for QEMU's comma-separated options (commas get doubled). */
    static String opt(Path p) {
        return p.toAbsolutePath().toString().replace(",", ",,");
    }

    static int vgaMemMb(int w, int h) {
        long bytes = (long) w * h * 4 * 2;
        int mb = 16;
        while (mb < 256 && (long) mb * 1024 * 1024 < bytes) mb *= 2;
        return mb;
    }

    /** Starts swtpm (Linux/macOS) and adds the TPM devices. Returns a note if there's no TPM. */
    private static Optional<String> startSwtpm(Path vmDir, List<String> cmd) {
        Optional<Path> swtpm = Processes.find("", "swtpm", knownLocations());
        if (swtpm.isEmpty() || Processes.WINDOWS) {
            return Optional.of("No software TPM available (swtpm); Windows 11 setup needs the TPM/Secure Boot bypass.");
        }
        try {
            Path state = vmDir.resolve("tpm");
            Files.createDirectories(state);
            Path sock = state.resolve("swtpm-sock");
            Files.deleteIfExists(sock);
            Processes.spawn(List.of(swtpm.get().toString(), "socket", "--tpmstate", "dir=" + state,
                    "--ctrl", "type=unixio,path=" + sock, "--tpm2", "--terminate"), vmDir, vmDir.resolve("swtpm.log"));
            for (int i = 0; i < 40 && !Files.exists(sock); i++) Thread.sleep(50);
            cmd.addAll(List.of("-chardev", "socket,id=chrtpm,path=" + opt(sock), "-tpmdev", "emulator,id=tpm0,chardev=chrtpm",
                    "-device", "tpm-tis,tpmdev=tpm0"));
            return Optional.empty();
        } catch (IOException | InterruptedException e) {
            return Optional.of("swtpm failed to start: " + e.getMessage());
        }
    }

    @Override
    public void destroy(VmLaunchSpec spec, BackendContext context, boolean deleteDisks) throws BackendException {
        if (!deleteDisks) return;
        try {
            Files.deleteIfExists(spec.vmDir().resolve("disk.qcow2"));
            Files.deleteIfExists(spec.vmDir().resolve("efivars.fd"));
        } catch (IOException e) {
            throw new BackendException("Could not delete QEMU disk: " + e.getMessage(), e);
        }
    }
}
