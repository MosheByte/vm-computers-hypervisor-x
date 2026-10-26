package net.hypervisorx.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.hypervisorx.config.HvxClientConfig;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full-bright opaque render type for VM screens. Uses the sharp-bilinear core shader if it loaded
 * and the player has it turned on, otherwise falls back to the vanilla text shader.
 */
public final class HvxRenderTypes extends RenderType {
    private static final Map<ResourceLocation, RenderType> SCREENS = new ConcurrentHashMap<>();
    private static volatile ShaderInstance screenShader;

    private HvxRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sort, Runnable setup, Runnable clear) {
        super(name, format, mode, size, crumbling, sort, setup, clear);
    }

    public static void setScreenShader(ShaderInstance shader) {
        screenShader = shader;
    }

    public static ShaderInstance screenShader() {
        return screenShader;
    }

    private static ShaderInstance currentShader() {
        ShaderInstance s = screenShader;
        return s != null && HvxClientConfig.get().sharpUpscaling ? s : GameRenderer.getRendertypeTextShader();
    }

    public static RenderType screen(ResourceLocation texture) {
        return SCREENS.computeIfAbsent(texture, tex -> {
            RenderStateShard[] shards = {
                    new ShaderStateShard(HvxRenderTypes::currentShader),
                    new TextureStateShard(tex, false, false),
                    NO_TRANSPARENCY,
                    LEQUAL_DEPTH_TEST,
                    NO_CULL,
                    LIGHTMAP,
                    POLYGON_OFFSET_LAYERING,
                    COLOR_DEPTH_WRITE
            };
            return new HvxRenderTypes("hypervisorx_screen", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS,
                    1536, false, false,
                    () -> {
                        for (RenderStateShard s : shards) s.setupRenderState();
                    },
                    () -> {
                        for (int i = shards.length - 1; i >= 0; i--) shards[i].clearRenderState();
                    });
        });
    }

    public static void forget(ResourceLocation texture) {
        SCREENS.remove(texture);
    }
}
