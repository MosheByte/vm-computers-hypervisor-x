package net.hypervisorx.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.client.ClientVmCache;
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

        if (tex != null && tex.hasContent() && !letterbox) {
            quad(buffers.getBuffer(HvxRenderTypes.screen(tex.location())), m, xLeft, yBottom, xRight, yTop, z, u0, 0f, u1, 1f, 0xFFFFFFFF);
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
        } else if (running) {
            quad(buffers.getBuffer(HvxRenderTypes.screen(BOOT)), m, xLeft, yBottom, xRight, yTop, z, u0, 0f, u1, 1f, 0xFFFFFFFF);
            if (tex == null && status != null && letterbox) label(pose, buffers, (xLeft + xRight) / 2f, yBottom + panelH * 0.18f, z - Z_EPS,
                    panelW, Component.translatable("hypervisorx.screen.booting"));
        } else if (vm != null) {
            quad(buffers.getBuffer(HvxRenderTypes.screen(WHITE)), m, xLeft, yBottom, xRight, yTop, z, 0f, 0f, 1f, 1f, 0xFF050608);
        } else {
            quad(buffers.getBuffer(HvxRenderTypes.screen(NO_SIGNAL)), m, xLeft, yBottom, xRight, yTop, z, u0, 0f, u1, 1f, 0xFFFFFFFF);
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
        VertexConsumer vc = buffers.getBuffer(RenderType.entitySolid(SHELL));
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
