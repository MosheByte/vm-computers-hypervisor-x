package net.hypervisorx.backend;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a tiny CD image (ISO 9660 + Joliet names) with a flat list of files in the root. We use it
 * for the Windows answer disc and the QEMU guest tools disc. Pure Java, so the host doesn't need
 * mkisofs or oscdimg.
 */
public final class IsoImage {
    private static final int SECTOR = 2048;
    /** Sectors 0-15 system area, 16 primary, 17 Joliet, 18 terminator, 19-22 path tables. */
    private static final int ROOT_PRIMARY = 23;

    private IsoImage() {
    }

    /**
     * Writes {@code files} (name on disc to source file) into {@code iso}, swapped in atomically.
     * Use plain file names. The primary directory gets upper-cased copies, Joliet keeps them as-is.
     */
    public static void write(Path iso, String volumeId, Map<String, Path> files) throws IOException {
        TreeMap<String, Path> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(files);
        List<String> names = new ArrayList<>(sorted.keySet());
        List<Long> sizes = new ArrayList<>();
        for (String n : names) sizes.add(Files.size(sorted.get(n)));

        // both roots list the same files, so they get the same sector count
        int primaryDirBytes = dirBytes(names, false);
        int jolietDirBytes = dirBytes(names, true);
        int dirSectors = Math.max(sectors(primaryDirBytes), sectors(jolietDirBytes));
        int rootJoliet = ROOT_PRIMARY + dirSectors;
        int next = rootJoliet + dirSectors;
        List<Integer> extents = new ArrayList<>();
        for (long size : sizes) {
            extents.add(next);
            next += Math.max(1, sectors(size));
        }
        int totalSectors = next;

        Path part = iso.resolveSibling(iso.getFileName() + ".part");
        Files.createDirectories(iso.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(part)) {
            out.write(new byte[16 * SECTOR]);
            int dirSize = dirSectors * SECTOR;
            out.write(volumeDescriptor(false, volumeId, totalSectors, ROOT_PRIMARY, dirSize));
            out.write(volumeDescriptor(true, volumeId, totalSectors, rootJoliet, dirSize));
            byte[] term = new byte[SECTOR];
            term[0] = (byte) 255;
            System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII), 0, term, 1, 5);
            term[6] = 1;
            out.write(term);
            out.write(pathTable(ROOT_PRIMARY, true));
            out.write(pathTable(ROOT_PRIMARY, false));
            out.write(pathTable(rootJoliet, true));
            out.write(pathTable(rootJoliet, false));
            out.write(directory(names, sizes, extents, ROOT_PRIMARY, dirSize, false, dirSectors));
            out.write(directory(names, sizes, extents, rootJoliet, dirSize, true, dirSectors));
            byte[] buf = new byte[1 << 16];
            for (int i = 0; i < names.size(); i++) {
                long written = 0;
                try (InputStream in = Files.newInputStream(sorted.get(names.get(i)))) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        written += n;
                    }
                }
                long padded = (long) Math.max(1, sectors(written)) * SECTOR;
                out.write(new byte[(int) (padded - written)]);
            }
        }
        Files.move(part, iso, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static int sectors(long bytes) {
        return (int) ((bytes + SECTOR - 1) / SECTOR);
    }

    private static byte[] primaryName(String name) {
        String n = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.]", "_");
        if (n.length() > 30) n = n.substring(0, 30);
        if (!n.contains(".")) n += ".";
        return (n + ";1").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] jolietName(String name) {
        String n = name.length() > 64 ? name.substring(0, 64) : name;
        return (n + ";1").getBytes(StandardCharsets.UTF_16BE);
    }

    private static int recordLength(int nameLength) {
        int len = 33 + nameLength;
        return len + (len & 1);
    }

    private static int dirBytes(List<String> names, boolean joliet) {
        // "." and ".." go first. records can't cross a sector boundary
        int used = 2 * 34;
        for (String n : names) {
            int len = recordLength((joliet ? jolietName(n) : primaryName(n)).length);
            if (used % SECTOR + len > SECTOR) used += SECTOR - used % SECTOR;
            used += len;
        }
        return used;
    }

    private static byte[] directory(List<String> names, List<Long> sizes, List<Integer> extents, int self, int dirSize,
                                    boolean joliet, int dirSectors) {
        ByteBuffer b = ByteBuffer.allocate(dirSectors * SECTOR);
        b.put(record(new byte[]{0}, self, dirSize, true));
        b.put(record(new byte[]{1}, self, dirSize, true));
        for (int i = 0; i < names.size(); i++) {
            byte[] r = record(joliet ? jolietName(names.get(i)) : primaryName(names.get(i)), extents.get(i), sizes.get(i), false);
            if (b.position() % SECTOR + r.length > SECTOR) b.position(b.position() + SECTOR - b.position() % SECTOR);
            b.put(r);
        }
        return b.array();
    }

    private static byte[] record(byte[] name, int extent, long size, boolean dir) {
        ByteBuffer b = ByteBuffer.allocate(recordLength(name.length));
        b.put((byte) b.capacity());
        b.put((byte) 0);
        both32(b, extent);
        both32(b, (int) size);
        b.put(recordingDate());
        b.put((byte) (dir ? 2 : 0));
        b.put((byte) 0);
        b.put((byte) 0);
        both16(b, 1);
        b.put((byte) name.length);
        b.put(name);
        return b.array();
    }

    private static byte[] pathTable(int rootExtent, boolean littleEndian) {
        ByteBuffer b = ByteBuffer.allocate(SECTOR).order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        b.put((byte) 1);
        b.put((byte) 0);
        b.putInt(rootExtent);
        b.putShort((short) 1);
        b.put((byte) 0);
        b.put((byte) 0);
        return b.array();
    }

    private static byte[] volumeDescriptor(boolean joliet, String volumeId, int totalSectors, int rootExtent, int rootSize) {
        ByteBuffer b = ByteBuffer.allocate(SECTOR);
        b.put((byte) (joliet ? 2 : 1));
        b.put("CD001".getBytes(StandardCharsets.US_ASCII));
        b.put((byte) 1);
        b.put((byte) 0);
        b.put(text("", 32, joliet));
        b.put(text(volumeId, 32, joliet));
        b.position(80);
        both32(b, totalSectors);
        if (joliet) b.put(new byte[]{0x25, 0x2F, 0x45}); // "%/E": UCS-2 level 3
        b.position(120);
        both16(b, 1);
        both16(b, 1);
        both16(b, SECTOR);
        both32(b, 10);
        int tables = joliet ? 21 : 19;
        b.order(ByteOrder.LITTLE_ENDIAN).putInt(tables).putInt(0);
        b.order(ByteOrder.BIG_ENDIAN).putInt(tables + 1).putInt(0);
        b.put(record(new byte[]{0}, rootExtent, rootSize, true));
        b.put(text("", 128, joliet));
        b.put(text("HYPERVISOR X", 128, joliet));
        b.put(text("HYPERVISOR X", 128, joliet));
        b.put(text("HYPERVISOR X", 128, joliet));
        b.put(text("", 37, joliet));
        b.put(text("", 37, joliet));
        b.put(text("", 37, joliet));
        byte[] now = longDate(ZonedDateTime.now(ZoneOffset.UTC));
        b.put(now);
        b.put(now);
        b.put(longDate(null));
        b.put(longDate(null));
        b.put((byte) 1);
        return b.array();
    }

    private static byte[] text(String s, int length, boolean joliet) {
        byte[] out = new byte[length];
        if (joliet) {
            for (int i = 0; i + 1 < length; i += 2) {
                out[i] = 0;
                out[i + 1] = ' ';
            }
            byte[] t = s.getBytes(StandardCharsets.UTF_16BE);
            System.arraycopy(t, 0, out, 0, Math.min(t.length, length - length % 2));
        } else {
            java.util.Arrays.fill(out, (byte) ' ');
            byte[] t = s.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(t, 0, out, 0, Math.min(t.length, length));
        }
        return out;
    }

    private static byte[] recordingDate() {
        ZonedDateTime t = ZonedDateTime.now(ZoneOffset.UTC);
        return new byte[]{(byte) (t.getYear() - 1900), (byte) t.getMonthValue(), (byte) t.getDayOfMonth(), (byte) t.getHour(),
                (byte) t.getMinute(), (byte) t.getSecond(), 0};
    }

    private static byte[] longDate(ZonedDateTime t) {
        byte[] out = new byte[17];
        String digits = t == null ? "0000000000000000"
                : String.format(Locale.ROOT, "%04d%02d%02d%02d%02d%02d00", t.getYear(), t.getMonthValue(), t.getDayOfMonth(),
                t.getHour(), t.getMinute(), t.getSecond());
        System.arraycopy(digits.getBytes(StandardCharsets.US_ASCII), 0, out, 0, 16);
        return out;
    }

    private static void both16(ByteBuffer b, int v) {
        b.order(ByteOrder.LITTLE_ENDIAN).putShort((short) v);
        b.order(ByteOrder.BIG_ENDIAN).putShort((short) v);
    }

    private static void both32(ByteBuffer b, int v) {
        b.order(ByteOrder.LITTLE_ENDIAN).putInt(v);
        b.order(ByteOrder.BIG_ENDIAN).putInt(v);
    }
}
