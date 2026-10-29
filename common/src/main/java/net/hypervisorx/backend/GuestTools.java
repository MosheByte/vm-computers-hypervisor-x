package net.hypervisorx.backend;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.util.Downloads;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Each hypervisor's own guest drivers (VMware Tools, VirtualBox Guest Additions, virtio-win guest tools
 * for QEMU). This is the disc each engine attaches next to the OS disc, plus the silent install command.
 * The Windows answer file runs it on new installs, the "install guest tools" action on systems that are
 * already installed. With the tools in, the guest gets the hypervisor's display driver (any resolution,
 * smooth redraws), an exact mouse, time sync and clean shutdowns.
 */
public final class GuestTools {
    /** virtio-win guest tools, pinned release + digest. Not the "latest" link, that one redirects to plain http. */
    private static final String VIRTIO_URL =
            "https://fedorapeople.org/groups/virt/virtio-win/direct-downloads/archive-virtio/virtio-win-0.1.302-1/virtio-win-guest-tools.exe";
    private static final String VIRTIO_SHA256 = "d8ae9ea1e943384ac195e012cd36f82c3161620e5fc9529be4e42e6f420e222d";
    private static final String VIRTIO_EXE = "virtio-win-guest-tools.exe";

    /**
     * @param disc    the CD image with the installer
     * @param marker  a file in the disc's root, how we find its drive letter inside Windows
     * @param install the silent install command, {@code %d} is the drive letter
     */
    public record Disc(Path disc, String marker, String install) {
        /** One cmd line: look for the disc on drives D to K and install from it. */
        public String windowsCommand() {
            return "cmd /c for %d in (D E F G H I J K) do if exist %d:\\" + marker + " " + install;
        }
    }

    private GuestTools() {
    }

    /** VMware Tools for Windows. Workstation/Player ships it as windows.iso next to vmrun. Null if it's not there. */
    public static Disc vmware(Path vmrunDir, GuestOs os) {
        if (os != GuestOs.WINDOWS_11 && os != GuestOs.WINDOWS_10 && os != GuestOs.WINDOWS_LEGACY) return null;
        Path iso = vmrunDir.resolve("windows.iso");
        if (!Files.isRegularFile(iso)) return null;
        return new Disc(iso, "setup64.exe", "start \"\" /wait %d:\\setup64.exe /S /v\"/qn REBOOT=R ADDLOCAL=ALL\"");
    }

    /** VirtualBox Guest Additions (Windows, Linux and macOS installers all on one disc). Null if it's not there. */
    public static Disc virtualBox(Path manageDir) {
        Path iso = manageDir.resolve("VBoxGuestAdditions.iso");
        if (!Files.isRegularFile(iso)) {
            // Linux packages keep it elsewhere
            for (String p : new String[]{"/usr/share/virtualbox/VBoxGuestAdditions.iso", "/usr/lib/virtualbox/additions/VBoxGuestAdditions.iso",
                    "/Applications/VirtualBox.app/Contents/MacOS/VBoxGuestAdditions.iso"}) {
                if (Files.isRegularFile(Path.of(p))) {
                    iso = Path.of(p);
                    break;
                }
            }
        }
        if (!Files.isRegularFile(iso)) return null;
        // add Oracle's driver certs first, that way Windows installs the drivers without asking
        return new Disc(iso, "VBoxWindowsAdditions.exe", "(%d:\\cert\\VBoxCertUtil.exe add-trusted-publisher %d:\\cert\\vbox*.cer"
                + " --root %d:\\cert\\vbox*.cer & start \"\" /wait %d:\\VBoxWindowsAdditions.exe /S)");
    }

    /**
     * virtio-win guest tools (QEMU guest agent, balloon, virtio drivers) on a small disc we build in the
     * download cache. Downloaded once (32 MB), checked against the pinned digest. Null if we can't get it.
     */
    public static Disc qemu(Path dataRoot, GuestOs os) {
        if (os != GuestOs.WINDOWS_11 && os != GuestOs.WINDOWS_10) return null;
        Path dir = dataRoot.resolve("cache").resolve("guest-tools");
        Path exe = dir.resolve(VIRTIO_EXE);
        Path iso = dir.resolve("virtio-win-guest-tools.iso");
        try {
            if (!Files.isRegularFile(iso)) {
                if (!Files.isRegularFile(exe)) {
                    Path tmp = dir.resolve(VIRTIO_EXE + ".download");
                    String sha = Downloads.download(VIRTIO_URL, tmp, null);
                    if (!sha.equalsIgnoreCase(VIRTIO_SHA256)) {
                        Files.deleteIfExists(tmp);
                        HyperVisorX.LOG.warn("virtio-win guest tools download did not match its digest; skipping guest tools");
                        return null;
                    }
                    Files.move(tmp, exe, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                IsoImage.write(iso, "VIRTIO_TOOLS", Map.of(VIRTIO_EXE, exe));
            }
        } catch (IOException e) {
            HyperVisorX.LOG.warn("Could not prepare the QEMU guest tools: {}", e.getMessage());
            return null;
        }
        return new Disc(iso, VIRTIO_EXE, "start \"\" /wait %d:\\" + VIRTIO_EXE + " /install /quiet /norestart");
    }
}
