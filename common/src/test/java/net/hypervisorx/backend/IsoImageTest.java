package net.hypervisorx.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsoImageTest {
    private static final int SECTOR = 2048;

    @Test
    void filesAreFoundThroughTheJolietDirectory(@TempDir Path dir) throws Exception {
        byte[] small = "<unattend/>".getBytes(StandardCharsets.UTF_8);
        byte[] big = new byte[5000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31);
        Files.write(dir.resolve("a.xml"), small);
        Files.write(dir.resolve("b.bin"), big);
        Map<String, Path> files = new LinkedHashMap<>();
        files.put("Autounattend.xml", dir.resolve("a.xml"));
        files.put("virtio-win-guest-tools.exe", dir.resolve("b.bin"));
        Path iso = dir.resolve("out.iso");
        IsoImage.write(iso, "UNATTEND", files);

        byte[] img = Files.readAllBytes(iso);
        assertEquals(0, img.length % SECTOR);
        assertEquals("CD001", new String(img, 16 * SECTOR + 1, 5, StandardCharsets.US_ASCII));
        assertEquals(1, img[16 * SECTOR]);
        assertEquals(2, img[17 * SECTOR], "Joliet supplementary descriptor");
        assertEquals((byte) 255, img[18 * SECTOR]);
        assertEquals(img.length / SECTOR, le32(img, 16 * SECTOR + 80), "volume size");

        // Joliet root dir record sits in the supplementary descriptor at offset 156
        int root = le32(img, 17 * SECTOR + 156 + 2);
        assertArrayEquals(small, read(img, root, "Autounattend.xml"));
        assertArrayEquals(big, read(img, root, "virtio-win-guest-tools.exe"));
    }

    private static byte[] read(byte[] img, int rootSector, String name) {
        byte[] want = (name + ";1").getBytes(StandardCharsets.UTF_16BE);
        int p = rootSector * SECTOR;
        int end = p + SECTOR;
        while (p < end && img[p] != 0) {
            int len = img[p] & 255;
            int nameLen = img[p + 32] & 255;
            byte[] n = Arrays.copyOfRange(img, p + 33, p + 33 + nameLen);
            if (Arrays.equals(n, want)) {
                int extent = le32(img, p + 2);
                int size = le32(img, p + 10);
                return Arrays.copyOfRange(img, extent * SECTOR, extent * SECTOR + size);
            }
            p += len;
        }
        assertTrue(false, name + " not in the Joliet directory");
        return null;
    }

    private static int le32(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
