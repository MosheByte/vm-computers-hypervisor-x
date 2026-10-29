package net.hypervisorx.vm.display;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Thread safe RGB framebuffer. The hypervisor backend writes it, the stream pump reads it. Pixels are
 * {@code 0x00RRGGBB}. Every write marks the {@link #TILE}x{@link #TILE} tiles it touched as dirty, so
 * only changed areas get re-encoded, sent and uploaded to the GPU (dirty-rect streaming).
 */
public final class Framebuffer {
    public static final int TILE = 64;
    public static final int MAX_DIMENSION = 7680;

    private final Object lock = new Object();
    private boolean updating;
    private long updateStart;
    private int width;
    private int height;
    private int tilesX;
    private int tilesY;
    private int[] pixels;
    private final BitSet dirty = new BitSet();
    private int epoch;
    private long lastWriteNanos;
    private int framesThisSecond;
    private long fpsWindowStart = System.nanoTime();
    private volatile float sourceFps;

    public Framebuffer(int width, int height) {
        resize(width, height);
    }

    public Object lock() {
        return lock;
    }

    public void resize(int w, int h) {
        w = Math.max(1, Math.min(MAX_DIMENSION, w));
        h = Math.max(1, Math.min(MAX_DIMENSION, h));
        synchronized (lock) {
            if (pixels != null && w == width && h == height) {
                return;
            }
            width = w;
            height = h;
            tilesX = (w + TILE - 1) / TILE;
            tilesY = (h + TILE - 1) / TILE;
            pixels = new int[w * h];
            dirty.clear();
            dirty.set(0, tilesX * tilesY);
            epoch++;
        }
    }

    public int width() {
        synchronized (lock) {
            return width;
        }
    }

    public int height() {
        synchronized (lock) {
            return height;
        }
    }

    public int epoch() {
        synchronized (lock) {
            return epoch;
        }
    }

    public int tilesX() {
        synchronized (lock) {
            return tilesX;
        }
    }

    public int tilesY() {
        synchronized (lock) {
            return tilesY;
        }
    }

    public float sourceFps() {
        return sourceFps;
    }

    /**
     * A source frame is starting to come in, in pieces (VNC rects). The stream waits for the end so viewers
     * never see half of one frame next to half of the last one (tearing).
     */
    public void beginUpdate() {
        synchronized (lock) {
            updating = true;
            updateStart = System.nanoTime();
        }
    }

    /** true while a frame is half written. capped at 40 ms so a stalled source can't freeze the picture. */
    public boolean midFrame() {
        synchronized (lock) {
            return updating && System.nanoTime() - updateStart < 40_000_000L;
        }
    }

    /** backends have to call this once per finished source frame (feeds the FPS readout). */
    public void frameCompleted() {
        long now = System.nanoTime();
        synchronized (lock) {
            updating = false;
            lastWriteNanos = now;
            framesThisSecond++;
            if (now - fpsWindowStart >= 1_000_000_000L) {
                sourceFps = framesThisSecond * 1e9f / (now - fpsWindowStart);
                framesThisSecond = 0;
                fpsWindowStart = now;
            }
        }
    }

    public long lastWriteNanos() {
        synchronized (lock) {
            return lastWriteNanos;
        }
    }

    /** copies a rect of 0x00RRGGBB pixels in. anything out of bounds gets clipped. */
    public void writeRgb(int x, int y, int w, int h, int[] src, int srcOffset, int srcStride) {
        synchronized (lock) {
            int x0 = Math.max(0, x);
            int y0 = Math.max(0, y);
            int x1 = Math.min(width, x + w);
            int y1 = Math.min(height, y + h);
            if (x1 <= x0 || y1 <= y0) return;
            int cw = x1 - x0;
            for (int row = y0; row < y1; row++) {
                int s = srcOffset + (row - y) * srcStride + (x0 - x);
                System.arraycopy(src, s, pixels, row * width + x0, cw);
            }
            markDirtyLocked(x0, y0, cw, y1 - y0);
        }
    }

    /** writes a rect but only marks tiles dirty if their pixels actually changed. */
    public void writeRgbCompare(int x, int y, int w, int h, int[] src, int srcOffset, int srcStride) {
        synchronized (lock) {
            int x0 = Math.max(0, x);
            int y0 = Math.max(0, y);
            int x1 = Math.min(width, x + w);
            int y1 = Math.min(height, y + h);
            if (x1 <= x0 || y1 <= y0) return;
            for (int ty = y0 / TILE; ty <= (y1 - 1) / TILE; ty++) {
                for (int tx = x0 / TILE; tx <= (x1 - 1) / TILE; tx++) {
                    int rx0 = Math.max(x0, tx * TILE);
                    int ry0 = Math.max(y0, ty * TILE);
                    int rx1 = Math.min(x1, tx * TILE + TILE);
                    int ry1 = Math.min(y1, ty * TILE + TILE);
                    boolean changed = false;
                    int cw = rx1 - rx0;
                    for (int row = ry0; row < ry1; row++) {
                        int s = srcOffset + (row - y) * srcStride + (rx0 - x);
                        int d = row * width + rx0;
                        if (!changed && !Arrays.equals(src, s, s + cw, pixels, d, d + cw)) {
                            changed = true;
                        }
                        if (changed) {
                            System.arraycopy(src, s, pixels, d, cw);
                        }
                    }
                    if (changed) {
                        dirty.set(ty * tilesX + tx);
                    }
                }
            }
        }
    }

    public void fill(int x, int y, int w, int h, int rgb) {
        synchronized (lock) {
            int x0 = Math.max(0, x);
            int y0 = Math.max(0, y);
            int x1 = Math.min(width, x + w);
            int y1 = Math.min(height, y + h);
            if (x1 <= x0 || y1 <= y0) return;
            for (int row = y0; row < y1; row++) {
                Arrays.fill(pixels, row * width + x0, row * width + x1, rgb);
            }
            markDirtyLocked(x0, y0, x1 - x0, y1 - y0);
        }
    }

    /** RFB CopyRect: copies one area of the framebuffer onto another spot in it, overlap is fine. */
    public void copyRect(int srcX, int srcY, int dstX, int dstY, int w, int h) {
        synchronized (lock) {
            if (w <= 0 || h <= 0) return;
            if (srcX < 0 || srcY < 0 || dstX < 0 || dstY < 0 || srcX + w > width || dstX + w > width
                    || srcY + h > height || dstY + h > height) {
                return;
            }
            if (dstY > srcY) {
                for (int row = h - 1; row >= 0; row--) {
                    System.arraycopy(pixels, (srcY + row) * width + srcX, pixels, (dstY + row) * width + dstX, w);
                }
            } else {
                for (int row = 0; row < h; row++) {
                    System.arraycopy(pixels, (srcY + row) * width + srcX, pixels, (dstY + row) * width + dstX, w);
                }
            }
            markDirtyLocked(dstX, dstY, w, h);
        }
    }

    public void markDirty(int x, int y, int w, int h) {
        synchronized (lock) {
            markDirtyLocked(x, y, w, h);
        }
    }

    public void markAllDirty() {
        synchronized (lock) {
            dirty.set(0, tilesX * tilesY);
        }
    }

    private void markDirtyLocked(int x, int y, int w, int h) {
        int x0 = Math.max(0, x) / TILE;
        int y0 = Math.max(0, y) / TILE;
        int x1 = Math.min(width - 1, x + w - 1) / TILE;
        int y1 = Math.min(height - 1, y + h - 1) / TILE;
        for (int ty = y0; ty <= y1; ty++) {
            dirty.set(ty * tilesX + x0, ty * tilesX + x1 + 1);
        }
    }

    /**
     * Moves the dirty tile set into {@code out} (clears it first) and returns the epoch it's for.
     * Call {@link #copyTile} after this while still holding {@link #lock()}, or the snapshot won't be consistent.
     */
    public int drainDirty(BitSet out) {
        synchronized (lock) {
            out.clear();
            out.or(dirty);
            dirty.clear();
            return epoch;
        }
    }

    /** copies one tile into {@code dst} (row-major, stride = tile width). returns the pixel count. */
    public int copyTile(int tileIndex, int[] dst) {
        synchronized (lock) {
            int tx = tileIndex % tilesX;
            int ty = tileIndex / tilesX;
            int x0 = tx * TILE;
            int y0 = ty * TILE;
            int tw = Math.min(TILE, width - x0);
            int th = Math.min(TILE, height - y0);
            for (int row = 0; row < th; row++) {
                System.arraycopy(pixels, (y0 + row) * width + x0, dst, row * tw, tw);
            }
            return tw * th;
        }
    }

    /** reads one pixel. used by tests and the host capture preview. */
    public int pixel(int x, int y) {
        synchronized (lock) {
            if (x < 0 || y < 0 || x >= width || y >= height) return 0;
            return pixels[y * width + x];
        }
    }

    /** raw access for writers already holding {@link #lock()} (like the terminal rasterizer). */
    public int[] rawPixelsLocked() {
        return pixels;
    }

    public void markDirtyLockedExternal(int x, int y, int w, int h) {
        markDirtyLocked(x, y, w, h);
    }
}
