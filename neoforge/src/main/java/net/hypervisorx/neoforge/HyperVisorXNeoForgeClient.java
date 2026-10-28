package net.hypervisorx.neoforge;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.client.HvxClient;
import net.hypervisorx.client.HvxKeys;
import net.hypervisorx.client.render.BoundedRenderer;
import net.hypervisorx.client.screen.ComputerScreen;
import net.hypervisorx.client.screen.SettingsScreen;
import net.hypervisorx.registry.HvxMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;

final class HyperVisorXNeoForgeClient {
    private HyperVisorXNeoForgeClient() {
    }

    static void init(IEventBus modBus, ModContainer container) {
        HvxClient.init();
        container.registerExtensionPoint(IConfigScreenFactory.class, (IConfigScreenFactory) (mc, parent) -> new SettingsScreen(parent));
        modBus.addListener((RegisterKeyMappingsEvent e) -> HvxKeys.ALL.forEach(e::register));
        modBus.addListener((RegisterMenuScreensEvent e) -> e.register(HvxMenus.COMPUTER.get(), ComputerScreen::new));
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> HvxClient.registerRenderers(new HvxClient.RendererSink() {
            @Override
            public <T extends BlockEntity> void blockEntity(BlockEntityType<? extends T> type, BlockEntityRendererProvider<T> provider) {
                e.registerBlockEntityRenderer(type, ctx -> wrap(provider.create(ctx)));
            }

            @Override
            public <T extends Entity> void entity(EntityType<? extends T> type, EntityRendererProvider<T> provider) {
                e.registerEntityRenderer(type, provider);
            }
        }));
        modBus.addListener((RegisterShadersEvent e) -> {
            try {
                e.registerShader(new ShaderInstance(e.getResourceProvider(), HvxClient.SCREEN_SHADER, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP),
                        HvxClient::onShaderLoaded);
            } catch (IOException ex) {
                HyperVisorX.LOG.error("Could not load the sharp-bilinear screen shader, falling back to vanilla text shader", ex);
            }
        });
        modBus.addListener((ModelEvent.RegisterAdditional e) -> HvxClient.PART_MODELS.forEach(id -> e.register(ModelResourceLocation.standalone(id))));
        HvxClient.partModels = id -> Minecraft.getInstance().getModelManager().getModel(ModelResourceLocation.standalone(id));
        modBus.addListener((RegisterGuiLayersEvent e) -> e.registerAboveAll(HyperVisorX.id("laptop_pip"), HvxClient::onHud));

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> HvxClient.onClientTick());
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e) -> HvxClient.onFrameStart());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> HvxClient.onDisconnect());
    }

    /** Renderers that draw outside their block need NeoForge's render bounding box, this bolts it on. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends BlockEntity> BlockEntityRenderer<T> wrap(BlockEntityRenderer<T> inner) {
        if (!(inner instanceof BoundedRenderer bounded)) return inner;
        return new BlockEntityRenderer<>() {
            @Override
            public void render(T be, float partialTick, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
                               int light, int overlay) {
                inner.render(be, partialTick, pose, buffers, light, overlay);
            }

            @Override
            public boolean shouldRenderOffScreen(T be) {
                return inner.shouldRenderOffScreen(be);
            }

            @Override
            public int getViewDistance() {
                return inner.getViewDistance();
            }

            @Override
            public boolean shouldRender(T be, Vec3 camera) {
                return inner.shouldRender(be, camera);
            }

            @Override
            public AABB getRenderBoundingBox(T be) {
                return ((BoundedRenderer<T>) bounded).renderBounds(be);
            }
        };
    }
}
