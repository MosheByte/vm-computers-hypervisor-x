package net.hypervisorx.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.hypervisorx.block.HorizontalEntityBlock;
import net.hypervisorx.block.entity.ComputerBlockEntity;
import net.hypervisorx.hardware.ComponentType;
import net.hypervisorx.hardware.FormFactor;
import net.hypervisorx.item.HardwareItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Shows the installed parts through the tempered-glass side of the gaming tower: GPU at the
 * bottom, RAM sticks next to the CPU block, drives in the front bay.
 */
public class ComputerRenderer implements BlockEntityRenderer<ComputerBlockEntity> {
    private static final float PX = 1f / 16f;
    private final ItemRenderer items;

    public ComputerRenderer(BlockEntityRendererProvider.Context ctx) {
        this.items = ctx.getItemRenderer();
    }

    @Override
    public void render(ComputerBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (be.form() != FormFactor.TOWER) return;
        Direction facing = be.getBlockState().getValue(HorizontalEntityBlock.FACING);
        pose.pushPose();
        ScreenDraw.faceNorth(pose, facing);
        int ram = 0;
        int storage = 0;
        for (int i = 0; i < be.parts().getContainerSize(); i++) {
            ItemStack s = be.parts().getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof HardwareItem hw)) continue;
            ComponentType t = hw.spec().type();
            pose.pushPose();
            switch (t) {
                case GPU -> place(pose, 8, 5.2f, 8, 0.42f, 90);
                case CPU -> place(pose, 8, 11.5f, 9.5f, 0.22f, 90);
                case RAM -> place(pose, 8.6f, 12f, 6.4f + ram++ * 0.9f, 0.20f, 0);
                case STORAGE -> place(pose, 8, 2.3f + storage++ * 1.6f, 3.2f, 0.22f, 90);
                case EXPANSION -> place(pose, 8, 7.2f, 8, 0.32f, 90);
                default -> {
                    pose.popPose();
                    continue;
                }
            }
            items.renderStatic(s, ItemDisplayContext.FIXED, light, overlay, pose, buffers, be.getLevel(), (int) be.getBlockPos().asLong() + i);
            pose.popPose();
        }
        pose.popPose();
    }

    private static void place(PoseStack pose, float x, float y, float z, float scale, float yaw) {
        pose.translate(x * PX, y * PX, z * PX);
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.scale(scale, scale, scale);
    }

    @Override
    public int getViewDistance() {
        return 32;
    }
}
