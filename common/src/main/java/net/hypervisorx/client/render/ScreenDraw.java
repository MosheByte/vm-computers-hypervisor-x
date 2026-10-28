package net.hypervisorx.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.client.ClientVmCache;
import net.hypervisorx.client.ScreenHits;
import net.hypervisorx.client.ScreenTracker;
import net.hypervisorx.network.S2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.UUID;

/** Drawing code every in-world screen shares. Coords are in blocks, model faces north. */
public final class ScreenDraw {
    public static final ResourceLocation NO_SIGNAL = HyperVisorX.id("textures/screen/no_signal.png");
    public static final ResourceLocation BOOT = HyperVisorX.id("textures/screen/boot.png");
    public static final ResourceLocation SHELL = HyperVisorX.id("textures/block/screen_shell.png");
    public static final ResourceLocation WHITE = HyperVisorX.id("textures/screen/white.png");
    private static final float Z_EPS = 0.0015f;

    private ScreenDraw() {
    }

    /** Rotates the pose from block space into our standard north-facing model space. */
    public static void faceNorth(PoseStack pose, Direction facing) {
        pose.translate(0.5, 0, 0.5);
        int modelY = ((int) facing.toYRot() + 180) % 360;
        pose.mulPose(Axis.YP.rotationDegrees(-modelY));
        pose.translate(-0.5, 0, -0.5);
    }

    /**
     * Draws a flat screen facing -Z at depth {@code z}. {@code xLeft > xRight} since the viewer's
     * left is +X in the north-facing model. {@code u0..u1} picks which horizontal slice of the desktop shows.
     */
    public static void screen(PoseStack pose, MultiBufferSource buffers, UUID vm, BlockPos pos,
                              float xLeft, float yBottom, float xRight, float yTop, float z, float u0, float u1) {
        screen(pose, buffers, vm, pos, xLeft, yBottom, xRight, yTop, z, u0, u1, true);
    }

    /** Texture of a running VM that's visible from here, or null. Also marks the VM as watched. */
    public static ScreenTexture liveTexture(UUID vm, BlockPos pos) {
        S2C.VmStatus status = ClientVmCache.get(vm);
        boolean running = status != null && (status.state().equals("RUNNING") || status.state().equals("STARTING") || status.state().equals("STOPPING"));
        ScreenTexture tex = running && ScreenTracker.withinRenderDistance(pos) ? texture(vm, pos) : null;
        return tex != null && tex.hasContent() ? tex : null;
    }

    /**
     * @param letterbox keep the guest's aspect ratio inside this quad. false stretches the
     *                  {@code u0..u1} slice over the whole quad (curved segments use that)
     */
    public static void screen(PoseStack pose, MultiBufferSource buffers, UUID vm, BlockPos pos,
                              float xLeft, float yBottom, float xRight, float yTop, float z, float u0, float u1, boolean letterbox) {
        Matrix4f m = pose.last().pose();
        float panelW = Math.abs(xLeft - xRight);
        float panelH = yTop - yBottom;
        S2C.VmStatus status = ClientVmCache.get(vm);
        boolean running = status != null && (status.state().equals("RUNNING") || status.state().equals("STARTING") || status.state().equals("STOPPING"));
        ScreenTexture tex = running && ScreenTracker.withinRenderDistance(pos) ? texture(vm, pos) : null;
        // the in-world view needs the panel's position even while it's black or booting
        if (tex == null || !tex.hasContent()) ScreenHits.add(vm, pos, m, xLeft, yBottom, xRight, yTop, z, u0, u1);

        if (tex != null && tex.hasContent() && !letterbox) {
            quad(buffers.getBuffer(HvxRenderTypes.screen(tex.location())), m, xLeft, yBottom, xRight, yTop, z, u0, 0f, u1, 1f, 0xFFFFFFFF);
            ScreenHits.add(vm, pos, m, xLeft, yBottom, xRight, yTop, z, u0, u1);
        } else if (tex != null && tex.hasContent()) {
            // letterbox so the guest keeps its aspect ratio
            float texAspect = (tex.width() * (u1 - u0)) / Math.max(1f, tex.height());
            float panelAspect = panelW / panelH;
            float w = panelW;
            float h = panelH;
            if (texAspect > panelAspect) h = panelW / texAspect;
            else w = panelH * texAspect;
            float cx = (xLeft + xRight) / 2f;
            float cy = (yBottom + yTop) / 2f;
            float dir = Math.signum(xLeft - xRight);
            if (w < panelW - 0.001f || h < panelH - 0.001f) {
                quad(buffers.getBuffer(HvxRenderTypes.screen(WHITE)), m, xLeft, yBottom, xRight, yTop, z + Z_EPS * 0.5f, 0f, 0f, 1f, 1f, 0xFF000000);
            }
            quad(buffers.getBuffer(HvxRenderTypes.screen(tex.location())), m,
                    cx + dir * w / 2f, cy - h / 2f, cx - dir * w / 2f, cy + h / 2f, z, u0, 0f, u1, 1f, 0xFFFFFFFF);
            ScreenHits.add(vm, pos, m, cx + dir * w / 2f, cy - h / 2f, cx - dir * w / 2f, cy + h / 2f, z, u0, u1);
        } else if (running && !ScreenTracker.withinRenderDistance(pos) && ScreenTextureManager.peek(vm) != null) {
            // past streaming distance: show the last picture dimmed instead of the boot splash
            ScreenTexture last = ScreenTextureManager.peek(vm);
            quad(buffers.getBuffer(HvxRenderTypes.screen(last.location())), m, xLeft, yBottom, xRight, yTop, z, u0, 0f, u1, 1f, 0xFF4D4D4D);
        } else if (running) {
            quad(buffers.getBuffer(HvxRenderTypes.screen(BOOT)), m, xLeft, yBottom, xRight, yTop, z, u0, 0f, u1, 1f, 0xFFFFFFFF);
            if (tex == null && status != null && letterbox) label(pose, buffers, (xLeft + xRight) / 2f, yBottom + panelH * 0.18f, z - Z_EPS,
                    panelW, Component.translatable("hypervisorx.screen.booting"));
        } else if (vm != null) {
            quad(buffers.getBuffer(HvxRenderTypes.screen(WHITE)), m, xLeft, yBottom, xRight, yTop, z, 0f, 0f, 1f, 1f, 0xFF050608);
        } else {
            quad(buffers.getBuffer(HvxRenderTypes.screen(NO_SIGNAL)), m, xLeft, yBottom, xRight, yTop, z, u0, 0f, u1, 1f, 0xFFFFFFFF);
            // the splash has no text baked in, the words get translated and drawn here
            if (letterbox) {
                label(pose, buffers, (xLeft + xRight) / 2f, yBottom + panelH * 0.52f, z - Z_EPS, panelW * 0.7f,
                        Component.translatable("hypervisorx.screen.no_signal"));
                label(pose, buffers, (xLeft + xRight) / 2f, yBottom + panelH * 0.32f, z - Z_EPS, panelW * 0.9f,
                        Component.translatable("hypervisorx.screen.no_signal_hint").withStyle(s -> s.withColor(0x8A94A6)));
            }
        }
    }

    private static ScreenTexture texture(UUID vm, BlockPos pos) {
        ScreenTracker.markVisible(vm, pos);
        return ScreenTextureManager.get(vm);
    }

    /** Full-bright textured quad facing -Z (POSITION_COLOR_TEX_LIGHTMAP). */
    public static void quad(VertexConsumer vc, Matrix4f m, float xLeft, float yBottom, float xRight, float yTop, float z,
                            float u0, float v0, float u1, float v1, int argb) {
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int light = LightTexture.FULL_BRIGHT;
        vc.addVertex(m, xLeft, yTop, z).setColor(r, g, b, a).setUv(u0, v0).setLight(light);
        vc.addVertex(m, xLeft, yBottom, z).setColor(r, g, b, a).setUv(u0, v1).setLight(light);
        vc.addVertex(m, xRight, yBottom, z).setColor(r, g, b, a).setUv(u1, v1).setLight(light);
        vc.addVertex(m, xRight, yTop, z).setColor(r, g, b, a).setUv(u1, v0).setLight(light);
    }

    /** Solid lit box in the current pose, for the monitor bodies we draw in code. */
    public static void box(PoseStack pose, MultiBufferSource buffers, float x0, float y0, float z0, float x1, float y1, float z1, int light, int argb) {
        // white texture so the vertex colour is the real colour (the old shell texture was ~10% grey and made everything black)
        VertexConsumer vc = buffers.getBuffer(RenderType.entitySolid(WHITE));
        PoseStack.Pose p = pose.last();
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        float[][] faces = {
                // x, y, z for 4 corners + normal
                {x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0, 0, -1},
                {x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, 0, 0, 1},
                {x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, -1, 0, 0},
                {x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1, 0, 0},
                {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0},
                {x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, 0, -1, 0}
        };
        for (float[] f : faces) {
            for (int i = 0; i < 4; i++) {
                vc.addVertex(p, f[i * 3], f[i * 3 + 1], f[i * 3 + 2]).setColor(r, g, b, a).setUv(i < 2 ? 0 : 1, i % 3 == 0 ? 1 : 0)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, f[12], f[13], f[14]);
            }
        }
    }

    /** Glowing box (additive, full-bright) for light bars and status LEDs. */
    public static void glow(PoseStack pose, MultiBufferSource buffers, float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
        VertexConsumer vc = buffers.getBuffer(RenderType.eyes(WHITE));
        PoseStack.Pose p = pose.last();
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        float[][] faces = {
                {x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0, 0, -1},
                {x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, 0, 0, 1},
                {x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, -1, 0, 0},
                {x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1, 0, 0},
                {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0},
                {x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, 0, -1, 0}
        };
        for (float[] f : faces) {
            for (int i = 0; i < 4; i++) {
                vc.addVertex(p, f[i * 3], f[i * 3 + 1], f[i * 3 + 2]).setColor(r, g, b, a).setUv(i < 2 ? 0 : 1, i % 3 == 0 ? 1 : 0)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(p, f[12], f[13], f[14]);
            }
        }
    }

    /**
     * Sagging cable (the HDMI link) from {@code a} to {@code b} in the current pose. It's a thin
     * square tube and never dips below the lower end.
     */
    public static void cable(PoseStack pose, MultiBufferSource buffers, org.joml.Vector3f a, org.joml.Vector3f b, int light, int argb) {
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        PoseStack.Pose p = pose.last();
        int ca = argb >>> 24, cr = (argb >> 16) & 0xFF, cg = (argb >> 8) & 0xFF, cb = argb & 0xFF;
        float len = a.distance(b);
        float sag = 0.1f + 0.05f * len;
        float h = 0.013f;
        float floor = Math.min(a.y, b.y) + h;
        int n = Math.max(6, Math.min(48, (int) (len * 6)));
        org.joml.Vector3f prev = new org.joml.Vector3f(a);
        for (int i = 1; i <= n; i++) {
            float t = i / (float) n;
            org.joml.Vector3f cur = new org.joml.Vector3f(a).lerp(b, t);
            cur.y = Math.max(floor, cur.y - sag * 4 * t * (1 - t));
            org.joml.Vector3f d = new org.joml.Vector3f(cur).sub(prev);
            if (d.lengthSquared() < 1e-8f) continue;
            d.normalize();
            org.joml.Vector3f up = Math.abs(d.y) > 0.9f ? new org.joml.Vector3f(1, 0, 0) : new org.joml.Vector3f(0, 1, 0);
            org.joml.Vector3f s1 = new org.joml.Vector3f(d).cross(up).normalize().mul(h);
            org.joml.Vector3f s2 = new org.joml.Vector3f(d).cross(s1).normalize().mul(h);
            org.joml.Vector3f[] o = {new org.joml.Vector3f(s1).add(s2), new org.joml.Vector3f(s1).sub(s2),
                    new org.joml.Vector3f(s1).negate().sub(s2), new org.joml.Vector3f(s2).sub(s1)};
            for (int k = 0; k < 4; k++) {
                org.joml.Vector3f o0 = o[k], o1 = o[(k + 1) % 4];
                org.joml.Vector3f nrm = new org.joml.Vector3f(o0).add(o1).normalize();
                float[][] v = {{prev.x + o0.x, prev.y + o0.y, prev.z + o0.z}, {cur.x + o0.x, cur.y + o0.y, cur.z + o0.z},
                        {cur.x + o1.x, cur.y + o1.y, cur.z + o1.z}, {prev.x + o1.x, prev.y + o1.y, prev.z + o1.z}};
                for (float[] c : v) {
                    vc.addVertex(p, c[0], c[1], c[2]).setColor(cr, cg, cb, ca).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                            .setNormal(p, nrm.x, nrm.y, nrm.z);
                }
            }
            prev = cur;
        }
    }

    /** Translucent textured quad facing -Z. Used for the CRT scanline/vignette mask and the glass. */
    public static void overlay(PoseStack pose, MultiBufferSource buffers, ResourceLocation texture, float xLeft, float yBottom, float xRight, float yTop,
                               float z, int argb, int light) {
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(texture));
        PoseStack.Pose p = pose.last();
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        float[][] v = {{xLeft, yTop, 0, 0}, {xLeft, yBottom, 0, 1}, {xRight, yBottom, 1, 1}, {xRight, yTop, 1, 0}};
        for (float[] c : v) {
            vc.addVertex(p, c[0], c[1], z).setColor(r, g, b, a).setUv(c[2], c[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                    .setNormal(p, 0, 0, -1);
        }
    }

    /** Small centered text on a screen surface. */
    public static void label(PoseStack pose, MultiBufferSource buffers, float cx, float y, float z, float panelWidth, Component text) {
        Font font = Minecraft.getInstance().font;
        float scale = Math.min(0.012f, panelWidth / Math.max(1, font.width(text)) * 0.8f);
        pose.pushPose();
        pose.translate(cx, y, z);
        pose.mulPose(Axis.YP.rotationDegrees(180));
        pose.scale(scale, -scale, scale);
        font.drawInBatch(text, -font.width(text) / 2f, 0, 0xFFE8F0FF, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
