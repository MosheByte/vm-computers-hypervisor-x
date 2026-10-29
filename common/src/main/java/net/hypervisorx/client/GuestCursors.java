package net.hypervisorx.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.network.S2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guest mouse cursors, as the hypervisor reports them (with VMware Tools or on QEMU the guest stops
 * painting the cursor into the picture). The focus screen draws them on top of the guest picture.
 */
public final class GuestCursors {
    private record Shape(ResourceLocation texture, DynamicTexture owner, int width, int height, int hotX, int hotY, boolean visible) {
    }

    private static final Map<UUID, Shape> SHAPES = new ConcurrentHashMap<>();
    /** Plain arrow for guests whose cursor shape we don't know (yet). */
    private static final String[] ARROW = {
            "X...........",
            "XX..........",
            "XWX.........",
            "XWWX........",
            "XWWWX.......",
            "XWWWWX......",
            "XWWWWWX.....",
            "XWWWWWWX....",
            "XWWWWWWWX...",
            "XWWWWWWWWX..",
            "XWWWWWWWWWX.",
            "XWWWWWWXXXXX",
            "XWWWXWWX....",
            "XWWXXWWX....",
            "XWX..XWWX...",
            "XX...XWWX...",
            "X.....XWWX..",
            "......XWWX..",
            ".......XX...",
    };
    private static Shape arrow;

    private GuestCursors() {
    }

    /** Main thread: swaps in the new cursor texture for {@code p.vm()}. */
    public static void onShape(S2C.CursorShape p) {
        Minecraft mc = Minecraft.getInstance();
        if (p.width() <= 0 || p.height() <= 0 || p.argb().length < p.width() * p.height()) return;
        NativeImage img = new NativeImage(p.width(), p.height(), true);
        for (int y = 0; y < p.height(); y++) {
            for (int x = 0; x < p.width(); x++) img.setPixelRGBA(x, y, abgr(p.argb()[y * p.width() + x]));
        }
        Shape old = SHAPES.get(p.vm());
        DynamicTexture tex = new DynamicTexture(img);
        ResourceLocation loc = HyperVisorX.id("guest_cursor/" + p.vm() + "/" + System.nanoTime());
        mc.getTextureManager().register(loc, tex);
        SHAPES.put(p.vm(), new Shape(loc, tex, p.width(), p.height(), p.hotX(), p.hotY(), p.visible()));
        if (old != null) mc.getTextureManager().release(old.texture());
    }

    /** True once the guest reported a cursor, so the viewer has to draw it (it's not in the picture). */
    public static boolean known(UUID vm) {
        return SHAPES.containsKey(vm);
    }

    /** False while the guest hides its cursor (e.g. a game grabbed the mouse). */
    public static boolean visible(UUID vm) {
        Shape s = SHAPES.get(vm);
        return s == null || s.visible();
    }

    /**
     * Draws the cursor of {@code vm} with its hot spot at GUI position (x, y), one guest pixel = {@code scale}
     * GUI units. No shape reported yet? A plain arrow fills in.
     */
    public static void draw(GuiGraphics g, UUID vm, float x, float y, float scale) {
        Shape s = SHAPES.get(vm);
        if (s == null) s = arrow();
        if (!s.visible()) return;
        // never too small to read, never huge
        float k = Math.max(scale, 0.25f);
        float x0 = x - s.hotX() * k;
        float y0 = y - s.hotY() * k;
        g.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, s.texture());
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        Matrix4f m = g.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        float x1 = x0 + s.width() * k;
        float y1 = y0 + s.height() * k;
        bb.addVertex(m, x0, y0, 0).setUv(0, 0);
        bb.addVertex(m, x0, y1, 0).setUv(0, 1);
        bb.addVertex(m, x1, y1, 0).setUv(1, 1);
        bb.addVertex(m, x1, y0, 0).setUv(1, 0);
        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.disableBlend();
    }

    private static Shape arrow() {
        if (arrow == null) {
            NativeImage img = new NativeImage(12, ARROW.length, true);
            for (int y = 0; y < ARROW.length; y++) {
                for (int x = 0; x < 12; x++) {
                    char c = ARROW[y].charAt(x);
                    img.setPixelRGBA(x, y, c == 'X' ? 0xFF000000 : c == 'W' ? 0xFFFFFFFF : 0);
                }
            }
            DynamicTexture tex = new DynamicTexture(img);
            ResourceLocation loc = HyperVisorX.id("guest_cursor/arrow");
            Minecraft.getInstance().getTextureManager().register(loc, tex);
            arrow = new Shape(loc, tex, 12, ARROW.length, 0, 0, true);
        }
        return arrow;
    }

    /** NativeImage stores ABGR. */
    private static int abgr(int argb) {
        return argb & 0xFF00FF00 | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF;
    }

    public static void clear() {
        for (Shape s : SHAPES.values()) Minecraft.getInstance().getTextureManager().release(s.texture());
        SHAPES.clear();
    }
}
