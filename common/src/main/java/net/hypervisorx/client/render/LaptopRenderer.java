package net.hypervisorx.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.hypervisorx.block.HorizontalEntityBlock;
import net.hypervisorx.block.entity.LaptopBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

import java.util.UUID;

/** Screen on the open lid of a placed laptop. The lid tilts back by {@link #LID_ANGLE}. */
public class LaptopRenderer implements BlockEntityRenderer<LaptopBlockEntity> {
    /** Has to match the lid's rotation in the laptop block models. */
    public static final float LID_ANGLE = 22.5f;
    private static final float PX = 1f / 16f;

    public LaptopRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(LaptopBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Direction facing = be.getBlockState().getValue(HorizontalEntityBlock.FACING);
        UUID vm = be.remoteTarget() != null ? be.remoteTarget() : be.vmId(0);
        pose.pushPose();
        ScreenDraw.faceNorth(pose, facing);
        pose.rotateAround(Axis.XP.rotationDegrees(LID_ANGLE), 8 * PX, 1 * PX, 12.4f * PX);
        boolean rugged = be.getBlockState().is(net.hypervisorx.registry.HvxBlocks.RUGGED_LAPTOP.get());
        float l = (rugged ? 13f : 13.5f) * PX, r = (rugged ? 3f : 2.5f) * PX;
        float b = (rugged ? 2f : 1.6f) * PX, t = (rugged ? 9f : 9.3f) * PX;
        if (vm == null) {
            ScreenDraw.quad(buffers.getBuffer(HvxRenderTypes.screen(ScreenDraw.WHITE)), pose.last().pose(), l, b, r, t, 11.9f * PX - 0.002f,
                    0f, 0f, 1f, 1f, 0xFF06080B);
        } else {
            ScreenDraw.screen(pose, buffers, vm, be.getBlockPos(), l, b, r, t, 11.9f * PX - 0.002f, 0f, 1f);
        }
        pose.popPose();
    }
}
