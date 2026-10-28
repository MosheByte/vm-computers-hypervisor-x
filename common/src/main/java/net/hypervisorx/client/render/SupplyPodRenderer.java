package net.hypervisorx.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.hypervisorx.entity.SupplyPodEntity;
import net.hypervisorx.registry.HvxBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** Draws the falling supply pod with its block model, spinning slowly on the way down. */
public class SupplyPodRenderer extends EntityRenderer<SupplyPodEntity> {
    public SupplyPodRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        shadowRadius = 0.6f;
    }

    @Override
    public void render(SupplyPodEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        float spin = entity.landed() ? 0 : (entity.tickCount + partialTick) * 6f;
        pose.mulPose(Axis.YP.rotationDegrees(Mth.wrapDegrees(spin)));
        pose.translate(-0.5, 0, -0.5);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(HvxBlocks.SUPPLY_POD.get().defaultBlockState(), pose, buffers,
                light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(SupplyPodEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
