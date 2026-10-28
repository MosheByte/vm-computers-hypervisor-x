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
import net.minecraft.client.renderer.LightTexture;
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
        glass(pose, buffers, light);
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

    private static final net.minecraft.util.RandomSource RANDOM = net.minecraft.util.RandomSource.create();

    /**
     * Draws a part model with vanilla face shading and mipmaps. RGB, fan and LCD faces are emissive,
     * so the case glows at night like a real build.
     */
    private static void draw(PoseStack pose, MultiBufferSource buffers, ResourceLocation id, int light, int overlay, float zOffset) {
        BakedModel model = HvxClient.partModels.apply(id);
        if (model == null) return;
        pose.pushPose();
        pose.translate(0, 0, zOffset * PX);
        com.mojang.blaze3d.vertex.VertexConsumer vc = buffers.getBuffer(RenderType.cutoutMipped());
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        for (Direction dir : DIRECTIONS) {
            for (net.minecraft.client.renderer.block.model.BakedQuad q : model.getQuads(null, dir, RANDOM)) {
                float s = level == null ? 1f : level.getShade(q.getDirection(), q.isShade());
                String tex = q.getSprite().contents().name().getPath();
                boolean glow = tex.contains("fx/rgb") || tex.contains("fx/fan_rgb") || tex.contains("fx/aio_lcd");
                vc.putBulkData(pose.last(), q, glow ? 1f : s, glow ? 1f : s, glow ? 1f : s, 1f, glow ? LightTexture.FULL_BRIGHT : light, overlay);
            }
        }
        pose.popPose();
    }

    private static final Direction[] DIRECTIONS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, null};

    private static final ResourceLocation GLASS_FRONT = HyperVisorX.id("textures/block/fx/tower_glass_front.png");
    private static final ResourceLocation GLASS_SIDE = HyperVisorX.id("textures/block/fx/tower_glass_side.png");

    /** Panoramic tempered glass (front + left side). Drawn here so the case body itself can stay in the cutout layer. */
    private static void glass(PoseStack pose, MultiBufferSource buffers, int light) {
        // front pane: x 4.1..12.5, y 1.5..15, z 0.5, viewed from outside (north)
        ScreenDraw.overlay(pose, buffers, GLASS_FRONT, 12.5f * PX, 1.5f * PX, 4.1f * PX, 15f * PX, 0.5f * PX, 0xFFFFFFFF, light);
        // side pane on the +X face (x 12.5, z 0.6..14.5), turned to face east
        pose.pushPose();
        pose.translate(12.5f * PX, 0, 0);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-90));
        ScreenDraw.overlay(pose, buffers, GLASS_SIDE, 14.5f * PX, 1.5f * PX, 0.6f * PX, 15f * PX, 0f, 0xFFFFFFFF, light);
        pose.popPose();
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
