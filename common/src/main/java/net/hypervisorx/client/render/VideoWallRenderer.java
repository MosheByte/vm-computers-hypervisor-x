package net.hypervisorx.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.hypervisorx.block.HorizontalEntityBlock;
import net.hypervisorx.block.entity.VideoWallBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The video wall's master panel draws one picture across the whole rectangle, no seams between panels. */
public class VideoWallRenderer implements BlockEntityRenderer<VideoWallBlockEntity>, BoundedRenderer<VideoWallBlockEntity> {
    public VideoWallRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(VideoWallBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (!be.isMaster()) return;
        Direction facing = be.getBlockState().getValue(HorizontalEntityBlock.FACING);
        pose.pushPose();
        ScreenDraw.faceNorth(pose, facing);
        // master is the bottom-left panel from the viewer's side, the wall grows toward -X (viewer's right) and up
        float xLeft = 1f;
        float xRight = 1f - be.gridW();
        float z = 13f / 16f - 0.002f;
        float u0 = be.partIndex() / (float) be.partCount();
        ScreenDraw.screen(pose, buffers, be.displayedVm(), be.getBlockPos(), xLeft, 0f, xRight, be.gridH(), z, u0, u0 + 1f / be.partCount());
        pose.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(VideoWallBlockEntity be) {
        return be.isMaster() && (be.gridW() > 1 || be.gridH() > 1);
    }

    @Override
    public int getViewDistance() {
        return 192;
    }

    @Override
    public boolean shouldRender(VideoWallBlockEntity be, Vec3 camera) {
        return Vec3.atCenterOf(be.getBlockPos()).closerThan(camera, getViewDistance());
    }

    @Override
    public AABB renderBounds(VideoWallBlockEntity be) {
        BlockPos p = be.getBlockPos();
        Direction facing = be.getBlockState().getValue(HorizontalEntityBlock.FACING);
        Direction right = facing.getCounterClockWise();
        BlockPos far = p.relative(right, be.gridW() - 1).above(be.gridH() - 1);
        return new AABB(p).minmax(new AABB(far));
    }
}
