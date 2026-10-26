package net.hypervisorx.images;

import net.hypervisorx.config.HvxConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Where USB sticks actually keep their data: a VMDK "monolithicFlat" descriptor plus a sparse raw
 * extent. QEMU, VirtualBox and VMware can all attach that, so a stick works across PCs running
 * different hypervisors.
 */
public final class DriveStore {
    private DriveStore() {
    }

    public static Path dir() {
        return HvxConfig.dataRoot().resolve("drives");
    }

    public static Path descriptor(UUID id) {
        return dir().resolve(id + ".vmdk");
    }

    /** Creates the drive image the first time it's used, returns the descriptor path. */
    public static synchronized Path ensure(UUID id, int capacityGb) throws IOException {
        Path desc = descriptor(id);
        if (Files.exists(desc)) return desc;
        Files.createDirectories(dir());
        String extentName = id + "-flat.vmdk";
        Path extent = dir().resolve(extentName);
        long bytes = (long) capacityGb * 1024L * 1024L * 1024L;
        try (FileChannel ch = FileChannel.open(extent, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            ch.position(bytes - 1);
            ch.write(ByteBuffer.wrap(new byte[]{0}));
        }
        long sectors = bytes / 512;
        long cylinders = Math.min(16383, sectors / (16 * 63));
        String text = "# Disk DescriptorFile\n"
                + "version=1\n"
                + "CID=" + String.format("%08x", (int) (id.getMostSignificantBits() & 0x7fffffff)) + "\n"
                + "parentCID=ffffffff\n"
                + "createType=\"monolithicFlat\"\n\n"
                + "# Extent description\n"
                + "RW " + sectors + " FLAT \"" + extentName + "\" 0\n\n"
                + "# The Disk Data Base\n"
                + "#DDB\n\n"
                + "ddb.virtualHWVersion = \"4\"\n"
                + "ddb.geometry.cylinders = \"" + cylinders + "\"\n"
                + "ddb.geometry.heads = \"16\"\n"
                + "ddb.geometry.sectors = \"63\"\n"
                + "ddb.adapterType = \"ide\"\n"
                + "ddb.uuid.image = \"" + id + "\"\n";
        Files.writeString(desc, text, StandardCharsets.US_ASCII);
        return desc;
    }
}
