package net.hypervisorx.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.hypervisorx.config.HvxClientConfig;
import net.hypervisorx.vm.display.EncodedTile;
import net.hypervisorx.vm.display.Framebuffer;
import net.hypervisorx.vm.display.TileCodec;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.BitSet;

/**
 * GPU texture for one VM display.
 * <p>
 * Decoder threads write tiles into an off-heap RGBA staging image and flag 64x64 dirty tiles.
 * Once per frame the render thread merges dirty tiles into horizontal runs, streams them through
 * an orphaned pixel-buffer object (asynchronous DMA, no pipeline stall) with
 * {@code glTexSubImage2D}, and regenerates mipmaps at most 30 times a second. Unchanged pixels are
 * never touched, so a static desktop costs nothing per frame.
 */
public final class ScreenTexture extends AbstractTexture {
    /** Reused across uploads, one int triple per dirty run. */
    private int[] runBuffer = new int[0];
    private static final int TILE = Framebuffer.TILE;

    private final ResourceLocation location;
    private final Object lock = new Object();
    private int width;
    private int height;
    private int epoch = Integer.MIN_VALUE;
    private ByteBuffer staging;
    private IntBuffer stagingInts;
    private int tilesX;
    private int tilesY;
    private final BitSet dirty = new BitSet();
    private boolean needsAllocation;
    private int allocatedW;
    private int allocatedH;
    private boolean mipmapped;
    private int pbo;
    private long lastMipmap;
    private boolean mipmapPending;
    private volatile long lastRendered = System.currentTimeMillis();
    private volatile boolean hasContent;
    private final TileCodec.PixelSink sink = new TileCodec.PixelSink() {
        @Override
        public int get(int index) {
            return stagingInts.get(index);
        }

        @Override
        public void set(int index, int abgr) {
            stagingInts.put(index, abgr);
        }
    };

    public ScreenTexture(ResourceLocation location) {
        this.location = location;
    }

    public ResourceLocation location() {
        return location;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int epoch() {
        return epoch;
    }

    public boolean hasContent() {
        return hasContent;
    }

    public void markRendered() {
        lastRendered = System.currentTimeMillis();
    }

    public long lastRendered() {
        return lastRendered;
    }

    // ------------------------------------------------------------- decoder side

    public void reset(int newEpoch, int w, int h) {
        w = Math.max(1, Math.min(Framebuffer.MAX_DIMENSION, w));
        h = Math.max(1, Math.min(Framebuffer.MAX_DIMENSION, h));
        synchronized (lock) {
            if (staging == null || w != width || h != height) {
                if (staging != null) MemoryUtil.memFree(staging);
                staging = MemoryUtil.memCalloc(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
                stagingInts = staging.asIntBuffer();
                width = w;
                height = h;
                tilesX = (w + TILE - 1) / TILE;
                tilesY = (h + TILE - 1) / TILE;
                needsAllocation = true;
            }
            for (int i = 0; i < w * h; i++) stagingInts.put(i, 0xFF000000);
            epoch = newEpoch;
            dirty.set(0, tilesX * tilesY);
            hasContent = false;
        }
    }

    /** Applies one decoded tile. Skipped if it's from an older resolution epoch. */
    public void apply(int tileEpoch, EncodedTile tile, byte[] unpacked) {
        synchronized (lock) {
            if (tileEpoch != epoch || staging == null) return;
            if (tile.x() + tile.w() > width || tile.y() + tile.h() > height) return;
            TileCodec.apply(tile, unpacked, sink, width);
            int tx = tile.x() / TILE;
            int ty = tile.y() / TILE;
            dirty.set(ty * tilesX + tx);
            hasContent = true;
        }
    }

    // ------------------------------------------------------------- render thread

    @Override
    public void load(ResourceManager manager) {
        // it's all streamed, nothing to load from resources
    }

    @Override
    public void setFilter(boolean blur, boolean mipmap) {
        // keep our own trilinear/anisotropic filtering, even if a render type asks for nearest
    }

    /**
     * Uploads the dirty regions and returns how many bytes went up.
     *
     * @param budget max bytes to upload this frame
     */
    public long upload(long budget) {
        RenderSystem.assertOnRenderThread();
        long uploaded = 0;
        synchronized (lock) {
            if (staging == null) return 0;
            int id = getId();
            GlStateManager._bindTexture(id);
            HvxClientConfig cfg = HvxClientConfig.get();
            if (needsAllocation || allocatedW != width || allocatedH != height || mipmapped != cfg.mipmaps) {
                allocate(cfg);
            }
            if (dirty.isEmpty()) {
                finishMipmaps(false);
                return 0;
            }
            // collect horizontal runs of dirty tiles row by row until the budget runs out
            int need = tilesX * tilesY * 3;
            if (runBuffer.length < need) runBuffer = new int[need];
            int[] runs = runBuffer;
            int runCount = 0;
            long total = 0;
            outer:
            for (int ty = 0; ty < tilesY; ty++) {
                int tx = 0;
                while (tx < tilesX) {
                    int idx = ty * tilesX + tx;
                    if (!dirty.get(idx)) {
                        tx++;
                        continue;
                    }
                    int start = tx;
                    while (tx < tilesX && dirty.get(ty * tilesX + tx)) tx++;
                    int x0 = start * TILE;
                    int x1 = Math.min(width, tx * TILE);
                    int y0 = ty * TILE;
                    int y1 = Math.min(height, y0 + TILE);
                    long bytes = (long) (x1 - x0) * (y1 - y0) * 4;
                    if (total > 0 && total + bytes > budget) break outer;
                    runs[runCount * 3] = ty * tilesX + start;
                    runs[runCount * 3 + 1] = tx - start;
                    runs[runCount * 3 + 2] = 0;
                    runCount++;
                    total += bytes;
                }
            }
            if (runCount == 0) return 0;

            if (pbo == 0) pbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, total, GL15.GL_STREAM_DRAW);
            ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_UNPACK_BUFFER, 0, total,
                    GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_BUFFER_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT);
            if (mapped == null) {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                return uploadDirect(runs, runCount);
            }
            long dst = MemoryUtil.memAddress(mapped);
            long src = MemoryUtil.memAddress(staging);
            long offset = 0;
            for (int r = 0; r < runCount; r++) {
                int first = runs[r * 3];
                int count = runs[r * 3 + 1];
                int x0 = (first % tilesX) * TILE;
                int y0 = (first / tilesX) * TILE;
                int x1 = Math.min(width, x0 + count * TILE);
                int y1 = Math.min(height, y0 + TILE);
                int rowBytes = (x1 - x0) * 4;
                runs[r * 3 + 2] = (int) offset;
                for (int y = y0; y < y1; y++) {
                    MemoryUtil.memCopy(src + ((long) y * width + x0) * 4, dst + offset, rowBytes);
                    offset += rowBytes;
                }
            }
            GL15.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
            for (int r = 0; r < runCount; r++) {
                int first = runs[r * 3];
                int count = runs[r * 3 + 1];
                int x0 = (first % tilesX) * TILE;
                int y0 = (first / tilesX) * TILE;
                int x1 = Math.min(width, x0 + count * TILE);
                int y1 = Math.min(height, y0 + TILE);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x0, y0, x1 - x0, y1 - y0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, runs[r * 3 + 2]);
                dirty.clear(first, first + count);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            uploaded = total;
        }
        finishMipmaps(true);
        return uploaded;
    }

    /** Fallback for when buffer mapping isn't available: upload straight from the staging image. */
    private long uploadDirect(int[] runs, int runCount) {
        long total = 0;
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, width);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        for (int r = 0; r < runCount; r++) {
            int first = runs[r * 3];
            int count = runs[r * 3 + 1];
            int x0 = (first % tilesX) * TILE;
            int y0 = (first / tilesX) * TILE;
            int x1 = Math.min(width, x0 + count * TILE);
            int y1 = Math.min(height, y0 + TILE);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, x0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, y0);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x0, y0, x1 - x0, y1 - y0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(staging));
            dirty.clear(first, first + count);
            total += (long) (x1 - x0) * (y1 - y0) * 4;
        }
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        mipmapPending = true;
        return total;
    }

    private void finishMipmaps(boolean changed) {
        if (!mipmapped) return;
        if (changed) mipmapPending = true;
        long now = System.nanoTime();
        if (mipmapPending && now - lastMipmap > 33_000_000L) {
            GlStateManager._bindTexture(getId());
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            lastMipmap = now;
            mipmapPending = false;
        }
    }

    private void allocate(HvxClientConfig cfg) {
        mipmapped = cfg.mipmaps;
        int levels = 1;
        if (mipmapped) {
            int max = Math.max(width, height);
            while (levels < 6 && (max >> levels) >= 16) levels++;
        }
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        for (int l = 0; l < levels; l++) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, l, GL11.GL_RGBA8, Math.max(1, width >> l), Math.max(1, height >> l), 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, mipmapped ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
            float max = GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT,
                    Math.min(max, Math.max(1, cfg.anisotropy)));
        }
        allocatedW = width;
        allocatedH = height;
        needsAllocation = false;
        dirty.set(0, tilesX * tilesY);
    }

    private boolean closed;

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (staging != null) {
                MemoryUtil.memFree(staging);
                staging = null;
                stagingInts = null;
            }
        }
        int buffer = pbo;
        pbo = 0;
        if (buffer != 0) RenderSystem.recordRenderCall(() -> GL15.glDeleteBuffers(buffer));
        releaseId();
    }
}
