package net.hypervisorx.backend.embedded;

import net.hypervisorx.backend.BackendContext;
import net.hypervisorx.backend.BackendException;
import net.hypervisorx.backend.BackendFeature;
import net.hypervisorx.backend.BackendStatus;
import net.hypervisorx.backend.HypervisorBackend;
import net.hypervisorx.backend.VmInstance;
import net.hypervisorx.backend.VmLaunchSpec;
import net.hypervisorx.util.Downloads;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The zero-install backend. A pure-Java RV32IMA machine that boots a tiny Linux (BusyBox) kernel.
 * Runs on any OS, CPU or server, no hypervisor needed.
 */
public final class EmbeddedBackend implements HypervisorBackend {
    public static final String ID = "embedded";

    /** Kernel images from the mini-rv32ima project (Linux 6.1, BusyBox userland, GPL). */
    public enum KernelImage {
        LINUX("linux-6.1.14-rv32nommu-cnl-1", "https://github.com/cnlohr/mini-rv32ima-images/raw/master/images/linux-6.1.14-rv32nommu-cnl-1.zip"),
        DOOM("emdoom-max-order-14", "https://github.com/cnlohr/mini-rv32ima-images/raw/master/images/Image-emdoom-MAX_ORDER_14.zip");

        private final String folder;
        private final String url;

        KernelImage(String folder, String url) {
            this.folder = folder;
            this.url = url;
        }

        public String folder() {
            return folder;
        }

        public String url() {
            return url;
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Embedded MicroVM (RISC-V Linux, zero install)";
    }

    @Override
    public Set<BackendFeature> features() {
        return EnumSet.of(BackendFeature.ZERO_INSTALL, BackendFeature.INPUT, BackendFeature.GPIO);
    }

    @Override
    public BackendStatus detect(BackendContext context) {
        Path kernel = kernelPath(context, KernelImage.LINUX);
        String msg = Files.isRegularFile(kernel)
                ? "Ready. Pure-Java RV32IMA core, boots Linux 6.1 in about a second."
                : "Ready. The 1.5 MB Linux kernel is downloaded automatically on first power-on.";
        return BackendStatus.ok("1.0", "built-in", msg, false);
    }

    public static Path kernelPath(BackendContext context, KernelImage image) {
        return context.firmwareRoot().resolve("riscv").resolve(image.folder()).resolve("Image");
    }

    /** Downloads and unpacks a kernel image if we don't have it yet. */
    public static Path ensureKernel(BackendContext context, KernelImage image, Downloads.Progress progress) throws BackendException {
        Path target = kernelPath(context, image);
        if (Files.isRegularFile(target)) return target;
        try {
            Files.createDirectories(target.getParent());
            Path zip = target.resolveSibling("download.zip");
            Downloads.download(image.url(), zip, progress);
            try (InputStream in = Files.newInputStream(zip); ZipInputStream zin = new ZipInputStream(in)) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    String name = e.getName();
                    if (!e.isDirectory() && (name.equals("Image") || name.endsWith("/Image"))) {
                        Path tmp = target.resolveSibling("Image.part");
                        Files.copy(zin, tmp, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                        break;
                    }
                }
            }
            Files.deleteIfExists(zip);
            if (!Files.isRegularFile(target)) throw new BackendException("Kernel archive did not contain an 'Image' file");
            return target;
        } catch (IOException ex) {
            throw new BackendException("Could not download the embedded Linux kernel: " + ex.getMessage(), ex);
        }
    }

    @Override
    public VmInstance launch(VmLaunchSpec spec, BackendContext context) throws BackendException {
        KernelImage image = "doom".equalsIgnoreCase(spec.target()) ? KernelImage.DOOM : KernelImage.LINUX;
        Path kernel = spec.embeddedKernel() != null && Files.isRegularFile(spec.embeddedKernel())
                ? spec.embeddedKernel()
                : ensureKernel(context, image, null);
        byte[] kernelBytes;
        byte[] dtb;
        try {
            kernelBytes = Files.readAllBytes(kernel);
            dtb = Rv32Machine.loadDefaultDtb();
        } catch (IOException e) {
            throw new BackendException("Unable to read kernel image " + kernel + ": " + e.getMessage(), e);
        }
        int ramMb = Math.max(16, Math.min(256, spec.ramMb()));
        EmbeddedInstance instance = new EmbeddedInstance(spec, kernelBytes, dtb, ramMb * 1024 * 1024, image == KernelImage.DOOM);
        instance.start();
        return instance;
    }
}
