package net.hypervisorx.network;

import net.hypervisorx.client.ClientNetwork;
import net.hypervisorx.server.HostService;
import net.hypervisorx.server.LaptopService;
import net.hypervisorx.server.LibraryService;
import net.hypervisorx.store.StoreService;
import net.hypervisorx.vm.VmManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * All the mod's packets with their codecs and handlers. The loaders loop over {@link #S2C_PAYLOADS} and
 * {@link #C2S_PAYLOADS} to register them. Client handlers are in {@link ClientNetwork}, which only
 * gets touched once a client actually handles a packet.
 */
public final class HvxNetwork {
    /**
     * @param anyThread handler is thread-safe, so it's fine to run it on the network thread (NeoForge)
     */
    public record S2CDef<T extends CustomPacketPayload>(CustomPacketPayload.Type<T> type,
                                                        StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                                                        Consumer<T> handler, boolean anyThread) {
    }

    public record C2SDef<T extends CustomPacketPayload>(CustomPacketPayload.Type<T> type,
                                                        StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                                                        BiConsumer<T, ServerPlayer> handler, boolean anyThread) {
    }

    public static final List<S2CDef<?>> S2C_PAYLOADS = List.of(
            new S2CDef<>(S2C.ScreenReset.TYPE, S2C.ScreenReset.CODEC, p -> ClientNetwork.screenReset(p), true),
            new S2CDef<>(S2C.ScreenTiles.TYPE, S2C.ScreenTiles.CODEC, p -> ClientNetwork.screenTiles(p), true),
            new S2CDef<>(S2C.Audio.TYPE, S2C.Audio.CODEC, p -> ClientNetwork.audio(p), true),
            new S2CDef<>(S2C.VmStatus.TYPE, S2C.VmStatus.CODEC, p -> ClientNetwork.status(p), false),
            new S2CDef<>(S2C.OpenFocus.TYPE, S2C.OpenFocus.CODEC, p -> ClientNetwork.openFocus(p), false),
            new S2CDef<>(S2C.HostInfo.TYPE, S2C.HostInfo.CODEC, p -> ClientNetwork.hostInfo(p), false),
            new S2CDef<>(S2C.Library.TYPE, S2C.Library.CODEC, p -> ClientNetwork.library(p), false),
            new S2CDef<>(S2C.OrderResult.TYPE, S2C.OrderResult.CODEC, p -> ClientNetwork.orderResult(p), false),
            new S2CDef<>(S2C.CursorShape.TYPE, S2C.CursorShape.CODEC, p -> ClientNetwork.cursorShape(p), false),
            new S2CDef<>(S2C.CursorWarp.TYPE, S2C.CursorWarp.CODEC, p -> ClientNetwork.cursorWarp(p), false)
    );

    public static final List<C2SDef<?>> C2S_PAYLOADS = List.of(
            new C2SDef<>(C2S.Watch.TYPE, C2S.Watch.CODEC, HvxNetwork::onWatch, false),
            new C2SDef<>(C2S.Input.TYPE, C2S.Input.CODEC, HvxNetwork::onInput, true),
            new C2SDef<>(C2S.HostAction.TYPE, C2S.HostAction.CODEC, (p, player) -> HostService.action(player, p), false),
            new C2SDef<>(C2S.VmAction.TYPE, C2S.VmAction.CODEC, (p, player) -> HostService.vmAction(player, p), false),
            new C2SDef<>(C2S.ConfigureVm.TYPE, C2S.ConfigureVm.CODEC, (p, player) -> HostService.configure(player, p), false),
            new C2SDef<>(C2S.Trust.TYPE, C2S.Trust.CODEC, (p, player) -> HostService.trust(player, p), false),
            new C2SDef<>(C2S.Laptop.TYPE, C2S.Laptop.CODEC, (p, player) -> LaptopService.handle(player, p), false),
            new C2SDef<>(C2S.RequestFocus.TYPE, C2S.RequestFocus.CODEC, (p, player) -> HostService.requestFocus(player, p.pos()), false),
            new C2SDef<>(C2S.StoreOrder.TYPE, C2S.StoreOrder.CODEC, (p, player) -> StoreService.order(player, p), false),
            new C2SDef<>(C2S.LibraryAction.TYPE, C2S.LibraryAction.CODEC, (p, player) -> LibraryService.action(player, p), false),
            new C2SDef<>(C2S.ServerConfig.TYPE, C2S.ServerConfig.CODEC, (p, player) -> LibraryService.applyConfig(player, p), false)
    );

    private HvxNetwork() {
    }

    private static void onWatch(C2S.Watch w, ServerPlayer player) {
        VmManager m = VmManager.get();
        if (m == null) return;
        List<UUID> add = w.add().size() > 64 ? w.add().subList(0, 64) : w.add();
        m.watch(player, add, w.remove());
    }

    private static void onInput(C2S.Input in, ServerPlayer player) {
        VmManager m = VmManager.get();
        if (m != null) m.input(player, in);
    }
}
