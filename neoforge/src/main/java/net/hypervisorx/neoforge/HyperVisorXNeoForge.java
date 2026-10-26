package net.hypervisorx.neoforge;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.network.HvxNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(HyperVisorX.MOD_ID)
public final class HyperVisorXNeoForge {
    public HyperVisorXNeoForge(IEventBus modBus, ModContainer container) {
        HyperVisorX.init();
        NeoForgePlatform.registerAll(modBus);
        modBus.addListener(HyperVisorXNeoForge::registerPayloads);

        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> HyperVisorX.onServerStarted(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> HyperVisorX.onServerStopping(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> HyperVisorX.onServerTick(e.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) HyperVisorX.onPlayerLeft(sp);
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> HyperVisorX.registerCommands(e.getDispatcher()));

        if (FMLEnvironment.dist.isClient()) {
            HyperVisorXNeoForgeClient.init(modBus, container);
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar main = event.registrar("1");
        PayloadRegistrar network = main.executesOn(HandlerThread.NETWORK);
        for (HvxNetwork.S2CDef<?> def : HvxNetwork.S2C_PAYLOADS) registerS2C(def.anyThread() ? network : main, def);
        for (HvxNetwork.C2SDef<?> def : HvxNetwork.C2S_PAYLOADS) registerC2S(def.anyThread() ? network : main, def);
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerS2C(PayloadRegistrar r, HvxNetwork.S2CDef<T> def) {
        r.playToClient(def.type(), (StreamCodec<? super RegistryFriendlyByteBuf, T>) def.codec(),
                (T payload, IPayloadContext ctx) -> def.handler().accept(payload));
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerC2S(PayloadRegistrar r, HvxNetwork.C2SDef<T> def) {
        r.playToServer(def.type(), (StreamCodec<? super RegistryFriendlyByteBuf, T>) def.codec(),
                (T payload, IPayloadContext ctx) -> {
                    if (ctx.player() instanceof ServerPlayer sp) def.handler().accept(payload, sp);
                });
    }
}
