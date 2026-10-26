package net.hypervisorx.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.network.HvxNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class HyperVisorXFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        HyperVisorX.init();
        for (HvxNetwork.S2CDef<?> def : HvxNetwork.S2C_PAYLOADS) registerS2CType(def);
        for (HvxNetwork.C2SDef<?> def : HvxNetwork.C2S_PAYLOADS) registerC2S(def);

        ServerLifecycleEvents.SERVER_STARTED.register(HyperVisorX::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(HyperVisorX::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(HyperVisorX::onServerTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> HyperVisorX.onPlayerLeft(handler.player));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> HyperVisorX.registerCommands(dispatcher));
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerS2CType(HvxNetwork.S2CDef<T> def) {
        PayloadTypeRegistry.playS2C().register(def.type(), (StreamCodec<RegistryFriendlyByteBuf, T>) def.codec());
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerC2S(HvxNetwork.C2SDef<T> def) {
        PayloadTypeRegistry.playC2S().register(def.type(), (StreamCodec<RegistryFriendlyByteBuf, T>) def.codec());
        ServerPlayNetworking.registerGlobalReceiver(def.type(), (payload, ctx) -> def.handler().accept(payload, ctx.player()));
    }
}
