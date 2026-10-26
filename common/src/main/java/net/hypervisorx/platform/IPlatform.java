package net.hypervisorx.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Inventory;

import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Loader abstraction, one impl for NeoForge and one for Fabric (picked up via ServiceLoader). */
public interface IPlatform {
    String loaderName();

    boolean isClientEnvironment();

    boolean isDevelopment();

    Path gameDirectory();

    Path configDirectory();

    /** Registers something. Don't call the returned supplier until the registry's populated. */
    <R, T extends R> Supplier<T> register(Registry<R> registry, String name, Supplier<T> factory);

    CreativeModeTab.Builder creativeTabBuilder();

    <T extends BlockEntity> BlockEntityType<T> blockEntityType(BiFunction<BlockPos, BlockState, T> factory, Block... blocks);

    @FunctionalInterface
    interface PosMenuFactory<M extends AbstractContainerMenu> {
        M create(int containerId, Inventory inventory, BlockPos pos);
    }

    /** Menu type whose client-side constructor gets the block pos that {@link #openMenu} wrote. */
    <M extends AbstractContainerMenu> MenuType<M> menuType(PosMenuFactory<M> factory);

    void openMenu(ServerPlayer player, MenuProvider provider, BlockPos pos);

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /** client only */
    void sendToServer(CustomPacketPayload payload);
}
