package net.hypervisorx.vm.display;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Server side encoder for one VM screen, shared by all its viewers.
 * <p>
 * Every pump cycle {@link #commit()} copies the dirty tiles of the live framebuffer into a committed
 * copy with a version counter per tile. Each {@link Viewer} tracks which version of each tile it has,
 * so late joiners get a full frame, slow connections just skip the versions in between, and everyone
 * else gets tiny XOR deltas. Encoded tiles are cached per version, so N viewers still cost one encode.
 */
public final class FrameStream {
    private static final java.util.concurrent.atomic.AtomicInteger IDS = new java.util.concurrent.atomic.AtomicInteger();
    private final Framebuffer fb;
    private final int id = IDS.incrementAndGet();
    private int epoch = -1;
    private int width;
    private int height;
    private int tilesX;
    private int tileCount;
    private int[][] committed = new int[0][];
    private int[][] previous = new int[0][];
    private int[] version = new int[0];
    private int[] prevVersion = new int[0];
    /** encoded tile caches keyed by encoding flags (deflate RGB888, deflate RGB565). local/raw never gets cached. */
    private final java.util.Map<Integer, Cache> caches = new java.util.HashMap<>();
    private final BitSet dirty = new BitSet();
    private final int[] scratch = new int[Framebuffer.TILE * Framebuffer.TILE];

    public FrameStream(Framebuffer fb) {
        this.fb = fb;
    }

    public synchronized int epoch() {
        return epoch;
    }

    public synchronized int width() {
        return width;
    }

    public synchronized int height() {
        return height;
    }

    /** pulls changes from the framebuffer. true if the epoch (resolution) changed. */
    public synchronized boolean commit() {
        boolean reset = false;
        synchronized (fb.lock()) {
            int e = fb.drainDirty(dirty);
            if (e != epoch) {
                epoch = e;
                width = fb.width();
                height = fb.height();
                tilesX = fb.tilesX();
                tileCount = tilesX * fb.tilesY();
                committed = new int[tileCount][];
                previous = new int[tileCount][];
                version = new int[tileCount];
                prevVersion = new int[tileCount];
                caches.clear();
                dirty.set(0, tileCount);
                reset = true;
            }
            for (int t = dirty.nextSetBit(0); t >= 0 && t < tileCount; t = dirty.nextSetBit(t + 1)) {
                int n = fb.copyTile(t, scratch);
                int[] cur = committed[t];
                if (cur != null && cur.length == n && Arrays.equals(cur, 0, n, scratch, 0, n)) continue;
                int[] next = previous[t] != null && previous[t].length == n ? previous[t] : new int[n];
                System.arraycopy(scratch, 0, next, 0, n);
                previous[t] = cur;
                prevVersion[t] = version[t];
                committed[t] = next;
                version[t]++;
            }
        }
        return reset;
    }

    /** streaming state for one player. */
    public static final class Viewer {
        private int streamId;
        private int epoch = Integer.MIN_VALUE;
        private int[] sent = new int[0];
        private int cursor;
        private double tokens;
        private long lastRefill = System.nanoTime();
        private final boolean local;
        private final boolean rgb565;
        private final double bytesPerSecond;

        public Viewer(boolean local, boolean rgb565, double bytesPerSecond) {
            this.local = local;
            this.rgb565 = rgb565 && !local;
            this.bytesPerSecond = bytesPerSecond;
            this.tokens = bytesPerSecond * 0.5;
        }

        public boolean isLocal() {
            return local;
        }

        public int epoch() {
            return epoch;
        }
    }

    /** what {@link #collect} returns: the tiles to send, with reset set when the viewer has to (re)init first. */
    public record Batch(boolean reset, int epoch, int width, int height, List<List<EncodedTile>> packets) {
    }

    /**
     * Grabs the tiles a viewer is missing, capped by its bandwidth budget, split into packets of at
     * most {@code maxPacketBytes}.
     */
    public synchronized Batch collect(Viewer v, int maxPacketBytes) {
        boolean reset = false;
        if (v.epoch != epoch || v.streamId != id) {
            v.streamId = id;
            v.epoch = epoch;
            v.sent = new int[tileCount];
            v.cursor = 0;
            reset = true;
        }
        long now = System.nanoTime();
        if (!v.local) {
            v.tokens = Math.min(v.bytesPerSecond * 0.5, v.tokens + v.bytesPerSecond * (now - v.lastRefill) / 1e9);
        }
        v.lastRefill = now;
        int flags = v.local ? 0 : (EncodedTile.DEFLATE | (v.rgb565 ? EncodedTile.RGB565 : 0));
        List<List<EncodedTile>> packets = new ArrayList<>();
        List<EncodedTile> current = new ArrayList<>();
        int currentBytes = 0;
        int start = v.cursor;
        for (int k = 0; k < tileCount; k++) {
            int t = (start + k) % tileCount;
            if (committed[t] == null || v.sent[t] == version[t]) continue;
            if (!v.local && v.tokens <= 0) {
                v.cursor = t;
                break;
            }
            EncodedTile tile = encode(t, v.sent[t] != 0 && v.sent[t] == prevVersion[t] && previous[t] != null, flags);
            int size = tile.wireSize();
            if (currentBytes + size > maxPacketBytes && !current.isEmpty()) {
                packets.add(current);
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(tile);
            currentBytes += size;
            v.sent[t] = version[t];
            if (!v.local) v.tokens -= size;
        }
        if (!current.isEmpty()) packets.add(current);
        return new Batch(reset, wireEpoch(), width, height, packets);
    }

    private static final class Cache {
        final EncodedTile[] raw;
        final EncodedTile[] delta;
        final int[] rawVersion;
        final int[] deltaVersion;

        Cache(int n) {
            raw = new EncodedTile[n];
            delta = new EncodedTile[n];
            rawVersion = new int[n];
            deltaVersion = new int[n];
        }
    }

    /** epoch as clients see it: unique across streams of the same VM (backend restarts, resizes). */
    public synchronized int wireEpoch() {
        return id * 4096 + (epoch & 4095);
    }

    private EncodedTile encode(int t, boolean delta, int flags) {
        if ((flags & EncodedTile.DEFLATE) == 0) return encodeUncached(t, delta, flags);
        Cache c = caches.computeIfAbsent(flags, f -> new Cache(tileCount));
        EncodedTile[] cache = delta ? c.delta : c.raw;
        int[] cacheVersion = delta ? c.deltaVersion : c.rawVersion;
        if (cache[t] != null && cacheVersion[t] == version[t]) return cache[t];
        EncodedTile tile = encodeUncached(t, delta, flags);
        cache[t] = tile;
        cacheVersion[t] = version[t];
        return tile;
    }

    /** builds one tile. the uncompressed singleplayer path skips the cache (one viewer, nothing to reuse). */
    private EncodedTile encodeUncached(int t, boolean delta, int flags) {
        int tx = t % tilesX;
        int ty = t / tilesX;
        int x = tx * Framebuffer.TILE;
        int y = ty * Framebuffer.TILE;
        int w = Math.min(Framebuffer.TILE, width - x);
        int h = Math.min(Framebuffer.TILE, height - y);
        boolean rgb565 = (flags & EncodedTile.RGB565) != 0;
        byte[] packed = TileCodec.pack(committed[t], delta ? previous[t] : null, w * h, rgb565);
        int mode = (delta ? EncodedTile.DELTA : 0) | (rgb565 ? EncodedTile.RGB565 : 0);
        byte[] data = packed;
        if ((flags & EncodedTile.DEFLATE) != 0) {
            data = TileCodec.deflate(packed);
            mode |= EncodedTile.DEFLATE;
        }
        return new EncodedTile(x, y, w, h, mode, data);
    }
}
