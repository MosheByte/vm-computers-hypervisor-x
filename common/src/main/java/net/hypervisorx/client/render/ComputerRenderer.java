package net.hypervisorx.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.block.HorizontalEntityBlock;
import net.hypervisorx.block.entity.ComputerBlockEntity;
import net.hypervisorx.client.HvxClient;
import net.hypervisorx.hardware.ComponentType;
import net.hypervisorx.hardware.FormFactor;
import net.hypervisorx.item.HardwareItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Builds the inside of the gaming tower from whatever's installed: GPU under the CPU cooler, RAM
 * sticks in the DIMM slots, add-in cards, and a 2.5" drive on the PSU shroud. Every part is its own
 * block model, in the same coordinate space as the tower model.
 */
public class ComputerRenderer implements BlockEntityRenderer<ComputerBlockEntity> {
    private static final float PX = 1f / 16f;
    private static final ResourceLocation RAM = HyperVisorX.id("block/part/ram");
    private static final ResourceLocation COOLER = HyperVisorX.id("block/part/cooler");
    private static final ResourceLocation CARD = HyperVisorX.id("block/part/card");
    private static final ResourceLocation DRIVE = HyperVisorX.id("block/part/drive");

    public ComputerRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(ComputerBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (be.form() != FormFactor.TOWER) return;
        Direction facing = be.getBlockState().getValue(HorizontalEntityBlock.FACING);
        pose.pushPose();
        ScreenDraw.faceNorth(pose, facing);
        int ram = 0;
        boolean card = false;
        boolean drive = false;
        for (int i = 0; i < be.parts().getContainerSize(); i++) {
            ItemStack s = be.parts().getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof HardwareItem hw)) continue;
            ComponentType t = hw.spec().type();
            switch (t) {
                case CPU -> draw(pose, buffers, COOLER, light, overlay, 0);
                case RAM -> draw(pose, buffers, RAM, light, overlay, 4.6f + 0.55f * ram++);
                case GPU -> {
                    String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
                    if (id.endsWith("titan")) draw(pose, buffers, HyperVisorX.id("block/part/gpu_titan"), light, overlay, 0);
                    else if (id.endsWith("_gx")) draw(pose, buffers, HyperVisorX.id("block/part/gpu_gx"), light, overlay, 0);
                    else if (id.endsWith("_gt")) draw(pose, buffers, HyperVisorX.id("block/part/gpu_gt"), light, overlay, 0);
                }
                case EXPANSION -> {
                    if (!card) draw(pose, buffers, CARD, light, overlay, 0);
                    card = true;
                }
                case STORAGE -> {
                    if (!drive) draw(pose, buffers, DRIVE, light, overlay, 0);
                    drive = true;
                }
                default -> {
                }
            }
        }
        pose.popPose();
    }

    private static void draw(PoseStack pose, MultiBufferSource buffers, ResourceLocation id, int light, int overlay, float zOffset) {
        BakedModel model = HvxClient.partModels.apply(id);
        if (model == null) return;
        pose.pushPose();
        pose.translate(0, 0, zOffset * PX);
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(RenderType.cutout()), null,
                model, 1f, 1f, 1f, light, overlay);
        pose.popPose();
    }

    @Override
    public int getViewDistance() {
        return 48;
    }
}
