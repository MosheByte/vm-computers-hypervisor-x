package net.hypervisorx.client.render;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.config.HvxClientConfig;
import net.hypervisorx.network.S2C;
import net.hypervisorx.vm.display.EncodedTile;
import net.hypervisorx.vm.display.TileCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;

/**
 * Owns the GPU textures for every VM screen the client can see. Packets get decoded on one
 * background thread per VM (so order is kept), and uploads happen once a frame within a byte budget.
 */
public final class ScreenTextureManager {
    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();

    private static final class Entry {
        final ScreenTexture texture;
        final ExecutorService decoder;
        volatile boolean registered;

        Entry(UUID vm) {
            texture = new ScreenTexture(HyperVisorX.id("screen/" + vm));
            ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "HyperVisorX-Decoder-" + vm.toString().substring(0, 8));
                t.setDaemon(true);
                return t;
            });
            ex.allowCoreThreadTimeOut(true);
            decoder = ex;
        }
    }

    private ScreenTextureManager() {
    }

    private static Entry entry(UUID vm) {
        return ENTRIES.computeIfAbsent(vm, Entry::new);
    }

    public static void onReset(S2C.ScreenReset p) {
        Entry e = entry(p.vm());
        e.decoder.execute(() -> e.texture.reset(p.epoch(), p.width(), p.height()));
    }

    public static void onTiles(S2C.ScreenTiles p) {
        Entry e = entry(p.vm());
        e.decoder.execute(() -> {
            for (EncodedTile t : p.tiles()) {
                try {
                    int expected = t.w() * t.h() * TileCodec.bytesPerPixel(t.mode());
                    byte[] raw = (t.mode() & EncodedTile.DEFLATE) != 0 ? TileCodec.inflate(t.data(), expected) : t.data();
                    if (raw.length < expected) continue;
                    e.texture.apply(p.epoch(), t, raw);
                } catch (DataFormatException ex) {
                    HyperVisorX.LOG.debug("Dropped corrupt tile: {}", ex.getMessage());
                }
            }
        });
    }

    /** Texture of a VM once frames have arrived. Gets registered with the texture manager lazily. */
    public static ScreenTexture get(UUID vm) {
        if (vm == null) return null;
        Entry e = ENTRIES.get(vm);
        if (e == null) return null;
        if (!e.registered) {
            Minecraft.getInstance().getTextureManager().register(e.texture.location(), e.texture);
            e.registered = true;
        }
        e.texture.markRendered();
        return e.texture.width() > 0 ? e.texture : null;
    }

    public static ResourceLocation location(UUID vm) {
        ScreenTexture t = get(vm);
        return t == null ? null : t.location();
    }

    /** Every frame on the render thread, before the world renders. */
    public static void uploadAll() {
        long budget = HvxClientConfig.get().maxUploadMiBPerFrame * 1024L * 1024L;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Entry>> it = ENTRIES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Entry> me = it.next();
            Entry e = me.getValue();
            if (now - e.texture.lastRendered() > 30_000) {
                it.remove();
                release(e);
                continue;
            }
            if (!e.registered) continue;
            try {
                budget -= e.texture.upload(Math.max(1, budget));
            } catch (RuntimeException ex) {
                HyperVisorX.LOG.error("Screen texture upload failed", ex);
            }
        }
    }

    private static void release(Entry e) {
        e.decoder.shutdownNow();
        if (e.registered) {
            Minecraft.getInstance().getTextureManager().release(e.texture.location()); // closes the texture
        } else {
            e.texture.close();
        }
    }

    public static void clear() {
        for (Entry e : ENTRIES.values()) release(e);
        ENTRIES.clear();
    }
}
