package net.hypervisorx.vm.display;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Server encoder into client tile decoder, end to end. */
class FrameStreamTest {
    /** Fake client staging image (0xAABBGGRR ints, same as the off-heap buffer). */
    private static final class Staging implements TileCodec.PixelSink {
        final int[] px;

        Staging(int n) {
            px = new int[n];
        }

        @Override
        public int get(int index) {
            return px[index];
        }

        @Override
        public void set(int index, int abgr) {
            px[index] = abgr;
        }

        int rgb(int i) {
            int v = px[i];
            return (v & 0xFF) << 16 | (v & 0xFF00) | (v >> 16) & 0xFF;
        }
    }

    private static int apply(FrameStream.Batch batch, Staging staging, int width) throws DataFormatException {
        int tiles = 0;
        for (List<EncodedTile> packet : batch.packets()) {
            for (EncodedTile t : packet) {
                int expected = t.w() * t.h() * TileCodec.bytesPerPixel(t.mode());
                byte[] raw = (t.mode() & EncodedTile.DEFLATE) != 0 ? TileCodec.inflate(t.data(), expected) : t.data();
                TileCodec.apply(t, raw, staging, width);
                tiles++;
            }
        }
        return tiles;
    }

    @Test
    void losslessRemoteStreamWithDeltas() throws Exception {
        int w = 200, h = 100;
        Framebuffer fb = new Framebuffer(w, h);
        Random r = new Random(42);
        int[] src = new int[w * h];
        for (int i = 0; i < src.length; i++) src[i] = r.nextInt(0x1000000);
        fb.writeRgb(0, 0, w, h, src, 0, w);
        FrameStream stream = new FrameStream(fb);
        FrameStream.Viewer viewer = new FrameStream.Viewer(false, false, 1e12);
        Staging staging = new Staging(w * h);

        assertTrue(stream.commit());
        FrameStream.Batch first = stream.collect(viewer, 900_000);
        assertTrue(first.reset());
        int tiles = apply(first, staging, w);
        assertEquals(((w + 63) / 64) * ((h + 63) / 64), tiles);
        for (int i = 0; i < src.length; i++) assertEquals(src[i], staging.rgb(i), "pixel " + i);

        // poke a small region: only that tile goes out again, as an XOR delta
        int[] patch = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF};
        fb.writeRgb(70, 10, 2, 2, patch, 0, 2);
        stream.commit();
        FrameStream.Batch second = stream.collect(viewer, 900_000);
        assertEquals(1, second.packets().get(0).size());
        EncodedTile t = second.packets().get(0).get(0);
        assertTrue((t.mode() & EncodedTile.DELTA) != 0, "steady-state updates are deltas");
        apply(second, staging, w);
        assertEquals(0xFF0000, staging.rgb(10 * w + 70));
        assertEquals(0xFFFFFF, staging.rgb(11 * w + 71));
        for (int i = 0; i < src.length; i++) {
            int x = i % w, y = i / w;
            if (x >= 70 && x < 72 && y >= 10 && y < 12) continue;
            assertEquals(src[i], staging.rgb(i));
        }

        // nothing changed -> nothing sent
        stream.commit();
        assertTrue(stream.collect(viewer, 900_000).packets().isEmpty());
    }

    @Test
    void rgb565ModeAndLateJoiner() throws Exception {
        int w = 130, h = 70;
        Framebuffer fb = new Framebuffer(w, h);
        int[] src = new int[w * h];
        for (int i = 0; i < src.length; i++) src[i] = (i * 2654435761L % 0xFFFFFF) > 0 ? (int) (i * 2654435761L % 0xFFFFFF) : 0;
        fb.writeRgb(0, 0, w, h, src, 0, w);
        FrameStream stream = new FrameStream(fb);
        stream.commit();
        FrameStream.Viewer early = new FrameStream.Viewer(false, true, 1e12);
        stream.collect(early, 900_000);
        fb.fill(0, 0, 10, 10, 0x123456);
        stream.commit();
        // late joiner gets the whole current frame
        FrameStream.Viewer late = new FrameStream.Viewer(false, true, 1e12);
        Staging staging = new Staging(w * h);
        apply(stream.collect(late, 900_000), staging, w);
        int got = staging.rgb(0);
        assertEquals(0x12 >> 3, (got >> 16 & 0xFF) >> 3);
        assertEquals(0x34 >> 2, (got >> 8 & 0xFF) >> 2);
        assertEquals(0x56 >> 3, (got & 0xFF) >> 3);
    }

    @Test
    void bandwidthBudgetDefersTiles() {
        int w = 640, h = 480;
        Framebuffer fb = new Framebuffer(w, h);
        int[] src = new int[w * h];
        Random r = new Random(1);
        for (int i = 0; i < src.length; i++) src[i] = r.nextInt(0x1000000);
        fb.writeRgb(0, 0, w, h, src, 0, w);
        FrameStream stream = new FrameStream(fb);
        stream.commit();
        FrameStream.Viewer slow = new FrameStream.Viewer(false, false, 50_000); // 50 KB/s
        int sent = stream.collect(slow, 900_000).packets().stream().mapToInt(List::size).sum();
        assertTrue(sent > 0 && sent < 80, "budget should limit the first burst, sent " + sent);
    }
}
