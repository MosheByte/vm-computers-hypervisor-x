package net.hypervisorx.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.hypervisorx.block.HorizontalEntityBlock;
import net.hypervisorx.block.MonitorType;
import net.hypervisorx.block.entity.MonitorBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Draws the live screen for every monitor type. Flat and CRT bodies come from block models. The
 * curved ultrawide and the dual/triple arm panels get drawn here instead, so screen and body use
 * the exact same geometry.
 */
public class MonitorRenderer implements BlockEntityRenderer<MonitorBlockEntity>, BoundedRenderer<MonitorBlockEntity> {
    private static final float PX = 1f / 16f;
    private static final int SHELL_COLOR = 0xFF2A2E35;
    private static final int BEZEL_COLOR = 0xFF0C0D10;
    private static final int ACCENT_COLOR = 0xFF38C6FF;
    private static final int FULL_BRIGHT = 0xF000F0;

    public MonitorRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(MonitorBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Direction facing = be.getBlockState().getValue(HorizontalEntityBlock.FACING);
        UUID vm = be.displayedVm();
        float part0 = be.partIndex() / (float) be.partCount();
        float partW = 1f / be.partCount();
        pose.pushPose();
        ScreenDraw.faceNorth(pose, facing);
        MonitorType type = be.type();
        switch (type) {
            case FLAT -> ScreenDraw.screen(pose, buffers, vm, be.getBlockPos(), 15.5f * PX, 3.5f * PX, 0.5f * PX, 12f * PX, 7f * PX - 0.002f,
                    part0, part0 + partW);
            case CRT -> ScreenDraw.screen(pose, buffers, vm, be.getBlockPos(), 13.5f * PX, 2.5f * PX, 2.5f * PX, 11f * PX, 2f * PX - 0.002f,
                    part0, part0 + partW);
            case ULTRAWIDE -> curved(be, pose, buffers, vm, light, part0, partW);
            case DUAL_ARM, TRIPLE_ARM -> arm(be, pose, buffers, vm, light, type.panels(), part0, partW);
        }
        pose.popPose();
    }

    /** 21:9 panel bent around a vertical axis ({@code radius} 2.2 blocks) so it wraps toward the viewer. */
    private void curved(MonitorBlockEntity be, PoseStack pose, MultiBufferSource buffers, UUID vm, int light, float part0, float partW) {
        final int segments = 16;
        final float width = 2.6f;
        final float height = 1.11f;
        final float radius = 3.0f;
        final float y0 = 4.5f * PX;
        final float zFront = 7f * PX;
        float arc = width / radius;
        // letterbox once across the whole curve (fill = how much of the arc the picture covers)
        ScreenTexture live = ScreenDraw.liveTexture(vm, be.getBlockPos());
        float fill = 1f;
        if (live != null) {
            float aspect = live.width() * partW / Math.max(1f, live.height());
            fill = Math.min(1f, aspect * height / width);
        }
        float margin = (1f - fill) / 2f;
        for (int i = 0; i < segments; i++) {
            float a0 = -arc / 2 + arc * i / segments;
            float a1 = -arc / 2 + arc * (i + 1) / segments;
            float am = (a0 + a1) / 2;
            float segW = 2 * radius * (float) Math.sin((a1 - a0) / 2);
            float cx = 0.5f - radius * (float) Math.sin(am);
            float cz = zFront - (radius - radius * (float) Math.cos(am));
            pose.pushPose();
            pose.translate(cx, 0, cz);
            pose.mulPose(Axis.YP.rotationDegrees((float) -Math.toDegrees(am)));
            // back plate sits well behind the panel plane, thin bezels above/below, so nothing pokes out in front of the next segment
            ScreenDraw.box(pose, buffers, -segW / 2, y0 - 0.04f, 0.03f, segW / 2, y0 + height + 0.03f, 0.09f, light, SHELL_COLOR);
            ScreenDraw.box(pose, buffers, -segW / 2, y0 - 0.05f, -0.004f, segW / 2, y0, 0.03f, light, BEZEL_COLOR);
            ScreenDraw.box(pose, buffers, -segW / 2, y0 + height, -0.004f, segW / 2, y0 + height + 0.02f, 0.03f, light, BEZEL_COLOR);
            // rear light bar (like the ring lights on gaming ultrawides)
            float ly = y0 + height * 0.62f;
            ScreenDraw.box(pose, buffers, -segW / 2, ly, 0.09f, segW / 2, ly + 0.025f, 0.096f, FULL_BRIGHT, ACCENT_COLOR);
            float s0 = (float) i / segments;
            float s1 = (float) (i + 1) / segments;
            float c0 = Math.max(0f, (s0 - margin) / fill);
            float c1 = Math.min(1f, (s1 - margin) / fill);
            if (live != null && c1 <= c0) {
                ScreenDraw.quad(buffers.getBuffer(HvxRenderTypes.screen(ScreenDraw.WHITE)), pose.last().pose(), segW / 2, y0, -segW / 2, y0 + height, 0f,
                        0f, 0f, 1f, 1f, 0xFF000000);
            } else if (live != null) {
                // edge segment that's only partly covered: black border + the visible slice of the picture
                float a = ((margin + c0 * fill) - s0) / (s1 - s0);
                float b = ((margin + c1 * fill) - s0) / (s1 - s0);
                float xa = segW / 2 - a * segW;
                float xb = segW / 2 - b * segW;
                if (a > 0.001f || b < 0.999f) {
                    ScreenDraw.quad(buffers.getBuffer(HvxRenderTypes.screen(ScreenDraw.WHITE)), pose.last().pose(), segW / 2, y0, -segW / 2, y0 + height,
                            0.0005f, 0f, 0f, 1f, 1f, 0xFF000000);
                }
                ScreenDraw.screen(pose, buffers, vm, be.getBlockPos(), xa + (a <= 0.001f ? 0.0015f : 0f), y0, xb - (b >= 0.999f ? 0.0015f : 0f), y0 + height,
                        0f, part0 + partW * c0, part0 + partW * c1, false);
            } else {
                // overlap segments by a hair so you can't see the shell through cracks between quads
                float ov = 0.0015f;
                ScreenDraw.screen(pose, buffers, vm, be.getBlockPos(), segW / 2 + ov, y0, -segW / 2 - ov, y0 + height, 0f,
                        part0 + partW * s0, part0 + partW * s1, false);
            }
            pose.popPose();
        }
        // stand neck goes up to the hinge behind the panel
        ScreenDraw.box(pose, buffers, 7.2f * PX, 9f * PX, 8.6f * PX, 8.8f * PX, y0 + 0.36f, 9.8f * PX, light, 0xFF383D46);
    }

    /** Two or three panels on a desk arm. On the triple, the outer two angle in toward you. */
    private void arm(MonitorBlockEntity be, PoseStack pose, MultiBufferSource buffers, UUID vm, int light, int panels, float part0, float partW) {
        final float w = 0.92f;
        final float h = 0.53f;
        final float y0 = 6.5f * PX;
        final float z = 7f * PX;
        for (int i = 0; i < panels; i++) {
            // panel 0 is the viewer's left (+X in north-facing model space)
            float offset = (panels - 1) / 2f - i;
            float angle = 0f;
            float cx = 0.5f + offset * (w + 0.02f);
            float cz = z;
            if (panels == 3 && i != 1) {
                angle = 25f * Math.signum(offset);
                float edge = 0.5f + Math.signum(offset) * (w / 2 + 0.01f);
                double rad = Math.toRadians(angle);
                cx = edge + (float) (Math.signum(offset) * Math.cos(rad) * w / 2);
                cz = z - (float) (Math.sin(Math.abs(rad)) * w / 2);
            }
            pose.pushPose();
            pose.translate(cx, 0, cz);
            pose.mulPose(Axis.YP.rotationDegrees(angle));
            ScreenDraw.box(pose, buffers, -w / 2 - 0.012f, y0 - 0.03f, -0.004f, w / 2 + 0.012f, y0, 0.03f, light, BEZEL_COLOR);
            ScreenDraw.box(pose, buffers, -w / 2 - 0.012f, y0 + h, -0.004f, w / 2 + 0.012f, y0 + h + 0.012f, 0.03f, light, BEZEL_COLOR);
            ScreenDraw.box(pose, buffers, -w / 2 - 0.012f, y0, -0.004f, -w / 2, y0 + h, 0.03f, light, BEZEL_COLOR);
            ScreenDraw.box(pose, buffers, w / 2, y0, -0.004f, w / 2 + 0.012f, y0 + h, 0.03f, light, BEZEL_COLOR);
            ScreenDraw.box(pose, buffers, -w / 2 - 0.012f, y0 - 0.03f, 0.03f, w / 2 + 0.012f, y0 + h + 0.012f, 0.06f, light, SHELL_COLOR);
            ScreenDraw.box(pose, buffers, -0.09f, y0 + h / 2 - 0.09f, 0.06f, 0.09f, y0 + h / 2 + 0.09f, 0.1f, light, 0xFF1B1D22);
            // a spanned (extra-wide) desktop gets sliced across the panels, a normal one is mirrored on each
            ScreenTexture live = ScreenDraw.liveTexture(vm, be.getBlockPos());
            boolean slice = live != null && live.width() * partW / (float) live.height() >= (w / h) * (panels - 0.5f);
            float u0 = slice ? part0 + partW * i / panels : part0;
            float u1 = slice ? part0 + partW * (i + 1) / panels : part0 + partW;
            ScreenDraw.screen(pose, buffers, vm, be.getBlockPos(), w / 2, y0, -w / 2, y0 + h, 0f, u0, u1);
            pose.popPose();
        }
    }

    @Override
    public boolean shouldRenderOffScreen(MonitorBlockEntity be) {
        MonitorType t = be.type();
        return t == MonitorType.ULTRAWIDE || t == MonitorType.DUAL_ARM || t == MonitorType.TRIPLE_ARM;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public boolean shouldRender(MonitorBlockEntity be, Vec3 camera) {
        return Vec3.atCenterOf(be.getBlockPos()).closerThan(camera, getViewDistance());
    }

    @Override
    public AABB renderBounds(MonitorBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(2.0, 1.0, 2.0);
    }
}
