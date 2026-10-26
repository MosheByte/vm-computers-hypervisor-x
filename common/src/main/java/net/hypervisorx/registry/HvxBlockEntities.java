package net.hypervisorx.registry;

import net.hypervisorx.block.entity.ComputerBlockEntity;
import net.hypervisorx.block.entity.CrateBlockEntity;
import net.hypervisorx.block.entity.LaptopBlockEntity;
import net.hypervisorx.block.entity.MonitorBlockEntity;
import net.hypervisorx.block.entity.ServerRackBlockEntity;
import net.hypervisorx.block.entity.VideoWallBlockEntity;
import net.hypervisorx.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Supplier;

public final class HvxBlockEntities {
    public static final Supplier<BlockEntityType<ComputerBlockEntity>> COMPUTER = register("computer",
            () -> Services.PLATFORM.blockEntityType(ComputerBlockEntity::new, HvxBlocks.RETRO_PC.get(), HvxBlocks.GAMING_TOWER.get()));
    public static final Supplier<BlockEntityType<ServerRackBlockEntity>> SERVER_RACK = register("server_rack",
            () -> Services.PLATFORM.blockEntityType(ServerRackBlockEntity::new, HvxBlocks.SERVER_RACK.get()));
    public static final Supplier<BlockEntityType<MonitorBlockEntity>> MONITOR = register("monitor",
            () -> Services.PLATFORM.blockEntityType(MonitorBlockEntity::new, HvxBlocks.MONITOR.get(), HvxBlocks.CRT_MONITOR.get(),
                    HvxBlocks.CURVED_MONITOR.get(), HvxBlocks.DUAL_MONITOR_ARM.get(), HvxBlocks.TRIPLE_MONITOR_ARM.get()));
    public static final Supplier<BlockEntityType<VideoWallBlockEntity>> VIDEO_WALL = register("video_wall",
            () -> Services.PLATFORM.blockEntityType(VideoWallBlockEntity::new, HvxBlocks.VIDEO_WALL.get()));
    public static final Supplier<BlockEntityType<LaptopBlockEntity>> LAPTOP = register("laptop",
            () -> Services.PLATFORM.blockEntityType(LaptopBlockEntity::new, HvxBlocks.PRO_LAPTOP.get(), HvxBlocks.RUGGED_LAPTOP.get()));
    public static final Supplier<BlockEntityType<CrateBlockEntity>> CRATE = register("delivery_crate",
            () -> Services.PLATFORM.blockEntityType(CrateBlockEntity::new, HvxBlocks.DELIVERY_CRATE.get()));

    private HvxBlockEntities() {
    }

    private static <T extends BlockEntityType<?>> Supplier<T> register(String name, Supplier<T> factory) {
        return Services.PLATFORM.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, name, factory);
    }

    public static void init() {
    }
}
