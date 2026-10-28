package net.hypervisorx.fabric.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.hypervisorx.client.HvxClient;
import net.hypervisorx.client.HvxKeys;
import net.hypervisorx.client.screen.ComputerScreen;
import net.hypervisorx.network.HvxNetwork;
import net.hypervisorx.registry.HvxMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class HyperVisorXFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HvxClient.init();
        for (HvxNetwork.S2CDef<?> def : HvxNetwork.S2C_PAYLOADS) registerReceiver(def);
        HvxKeys.ALL.forEach(KeyBindingHelper::registerKeyBinding);
        ModelLoadingPlugin.register(ctx -> ctx.addModels(HvxClient.PART_MODELS));
        HvxClient.partModels = id -> Minecraft.getInstance().getModelManager().getModel(id);
        MenuScreens.register(HvxMenus.COMPUTER.get(), ComputerScreen::new);
        HvxClient.registerRenderers(new HvxClient.RendererSink() {
            @Override
            public <T extends BlockEntity> void blockEntity(BlockEntityType<? extends T> type, BlockEntityRendererProvider<T> provider) {
                registerBer(type, provider);
            }

            @Override
            public <T extends Entity> void entity(EntityType<? extends T> type, EntityRendererProvider<T> provider) {
                EntityRendererRegistry.register(type, provider);
            }
        });
        for (Block b : HvxClient.translucentBlocks()) BlockRenderLayerMap.INSTANCE.putBlock(b, RenderType.translucent());
        for (Block b : HvxClient.cutoutBlocks()) BlockRenderLayerMap.INSTANCE.putBlock(b, RenderType.cutout());
        CoreShaderRegistrationCallback.EVENT.register(ctx ->
                ctx.register(HvxClient.SCREEN_SHADER, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, HvxClient::onShaderLoaded));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> HvxClient.onClientTick());
        WorldRenderEvents.START.register(ctx -> HvxClient.onFrameStart());
        HudRenderCallback.EVENT.register(HvxClient::onHud);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> HvxClient.onDisconnect());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends BlockEntity> void registerBer(BlockEntityType<? extends T> type, BlockEntityRendererProvider<T> provider) {
        BlockEntityRendererRegistry.register((BlockEntityType) type, (BlockEntityRendererProvider) provider);
    }

    private static <T extends CustomPacketPayload> void registerReceiver(HvxNetwork.S2CDef<T> def) {
        ClientPlayNetworking.registerGlobalReceiver(def.type(), (payload, ctx) -> def.handler().accept(payload));
    }
}
