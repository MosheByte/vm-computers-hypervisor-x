package net.hypervisorx.fabric.client;

import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.hypervisorx.HyperVisorX;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

/**
 * Fabric's take on the {@code neoforge_data} emissive elements. Quads using the RGB, fan, keyboard,
 * LCD or rack LED textures render full-bright so they glow at night.
 */
final class EmissiveModel extends ForwardingBakedModel {
    private static RenderMaterial emissive;

    private EmissiveModel(BakedModel wrapped) {
        this.wrapped = wrapped;
    }

    static BakedModel wrap(BakedModel model) {
        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        if (renderer == null || model == null) return model;
        if (emissive == null) emissive = renderer.materialFinder().emissive(true).find();
        return new EmissiveModel(model);
    }

    static boolean glows(TextureAtlasSprite sprite) {
        if (sprite == null || !sprite.contents().name().getNamespace().equals(HyperVisorX.MOD_ID)) return false;
        String p = sprite.contents().name().getPath();
        return p.startsWith("block/fx/") && (p.contains("rgb_strip") || p.contains("rgb_gold") || p.contains("fan_rgb") || p.contains("aio_lcd")
                || p.equals("block/fx/keyboard_keys") || p.matches("block/fx/rack_front_\\d+"));
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitBlockQuads(BlockAndTintGetter level, BlockState state, BlockPos pos, Supplier<RandomSource> random, RenderContext context) {
        SpriteFinder finder = SpriteFinder.get(Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS));
        context.pushTransform(quad -> {
            if (glows(finder.find(quad))) quad.material(emissive);
            return true;
        });
        super.emitBlockQuads(level, state, pos, random, context);
        context.popTransform();
    }
}
