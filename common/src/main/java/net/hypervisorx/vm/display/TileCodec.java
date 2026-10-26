package net.hypervisorx.vm.display;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Pixel packing and (de)compression for streamed tiles. A delta tile is the new tile XOR the old one in
 * packed form, so anything that didn't change turns into runs of zeros that deflate to almost nothing
 * (typing, cursor blinks, scrolling text).
 */
public final class TileCodec {
    private static final ThreadLocal<Deflater> DEFLATERS = ThreadLocal.withInitial(() -> new Deflater(1));
    private static final ThreadLocal<Inflater> INFLATERS = ThreadLocal.withInitial(Inflater::new);
    private static final ThreadLocal<byte[]> SCRATCH = ThreadLocal.withInitial(() -> new byte[Framebuffer.TILE * Framebuffer.TILE * 3 + 1024]);

    private TileCodec() {
    }

    public static int bytesPerPixel(int mode) {
        return (mode & EncodedTile.RGB565) != 0 ? 2 : 3;
    }

    /** packs {@code count} 0x00RRGGBB pixels into bytes (XOR'd with {@code previous} if it's not null). */
    public static byte[] pack(int[] pixels, int[] previous, int count, boolean rgb565) {
        byte[] out = new byte[count * (rgb565 ? 2 : 3)];
        int o = 0;
        for (int i = 0; i < count; i++) {
            int p = pixels[i];
            if (rgb565) {
                int v = ((p >> 8) & 0xF800) | ((p >> 5) & 0x07E0) | ((p >> 3) & 0x001F);
                if (previous != null) {
                    int q = previous[i];
                    v ^= ((q >> 8) & 0xF800) | ((q >> 5) & 0x07E0) | ((q >> 3) & 0x001F);
                }
                out[o++] = (byte) v;
                out[o++] = (byte) (v >> 8);
            } else {
                if (previous != null) p ^= previous[i];
                out[o++] = (byte) (p >> 16);
                out[o++] = (byte) (p >> 8);
                out[o++] = (byte) p;
            }
        }
        return out;
    }

    public static byte[] deflate(byte[] raw) {
        Deflater d = DEFLATERS.get();
        d.reset();
        d.setInput(raw);
        d.finish();
        byte[] buf = SCRATCH.get();
        ByteArrayOutputStream bos = null;
        int total = 0;
        while (!d.finished()) {
            int n = d.deflate(buf, total, buf.length - total);
            total += n;
            if (total == buf.length) {
                if (bos == null) bos = new ByteArrayOutputStream(buf.length * 2);
                bos.write(buf, 0, total);
                total = 0;
            }
        }
        if (bos == null) {
            byte[] out = new byte[total];
            System.arraycopy(buf, 0, out, 0, total);
            return out;
        }
        bos.write(buf, 0, total);
        return bos.toByteArray();
    }

    public static byte[] inflate(byte[] data, int expected) throws DataFormatException {
        Inflater inf = INFLATERS.get();
        inf.reset();
        inf.setInput(data);
        byte[] out = new byte[expected];
        int n = 0;
        while (n < expected && !inf.finished()) {
            int r = inf.inflate(out, n, expected - n);
            if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
            n += r;
        }
        if (n != expected) throw new DataFormatException("tile size mismatch " + n + " != " + expected);
        return out;
    }

    /**
     * Writes a decoded tile into the client's RGBA staging image (R,G,B,A bytes per pixel).
     *
     * @param dst     staging buffer accessor
     * @param stride  staging width in pixels
     */
    public static void apply(EncodedTile tile, byte[] unpacked, PixelSink dst, int stride) {
        boolean rgb565 = (tile.mode() & EncodedTile.RGB565) != 0;
        boolean delta = (tile.mode() & EncodedTile.DELTA) != 0;
        int i = 0;
        for (int row = 0; row < tile.h(); row++) {
            int base = (tile.y() + row) * stride + tile.x();
            for (int col = 0; col < tile.w(); col++) {
                int rgb;
                if (rgb565) {
                    int v = (unpacked[i] & 0xFF) | (unpacked[i + 1] & 0xFF) << 8;
                    i += 2;
                    if (delta) {
                        int old = dst.get(base + col);
                        int oldR = old & 0xFF, oldG = (old >> 8) & 0xFF, oldB = (old >> 16) & 0xFF;
                        int oldV = ((oldR >> 3) << 11) | ((oldG >> 2) << 5) | (oldB >> 3);
                        v ^= oldV;
                    }
                    int r5 = (v >> 11) & 0x1F, g6 = (v >> 5) & 0x3F, b5 = v & 0x1F;
                    rgb = ((r5 << 3) | (r5 >> 2)) << 16 | ((g6 << 2) | (g6 >> 4)) << 8 | ((b5 << 3) | (b5 >> 2));
                } else {
                    rgb = (unpacked[i] & 0xFF) << 16 | (unpacked[i + 1] & 0xFF) << 8 | (unpacked[i + 2] & 0xFF);
                    i += 3;
                    if (delta) {
                        int old = dst.get(base + col);
                        rgb ^= ((old & 0xFF) << 16) | (old & 0xFF00) | ((old >> 16) & 0xFF);
                    }
                }
                // staging layout as little-endian int: 0xAABBGGRR -> bytes R,G,B,A
                dst.set(base + col, 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16) & 0xFF);
            }
        }
    }

    /** wraps the client staging buffer (an off-heap int view) so this class stays testable. */
    public interface PixelSink {
        int get(int index);

        void set(int index, int abgr);
    }
}
