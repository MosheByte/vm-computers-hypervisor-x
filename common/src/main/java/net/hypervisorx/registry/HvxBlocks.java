package net.hypervisorx.registry;

import net.hypervisorx.block.ComputerCaseBlock;
import net.hypervisorx.block.DeliveryCrateBlock;
import net.hypervisorx.block.LaptopBlock;
import net.hypervisorx.block.MonitorBlock;
import net.hypervisorx.block.MonitorType;
import net.hypervisorx.block.PeripheralBlock;
import net.hypervisorx.block.ServerRackBlock;
import net.hypervisorx.block.VideoWallBlock;
import net.hypervisorx.hardware.FormFactor;
import net.hypervisorx.hardware.LaptopModel;
import net.hypervisorx.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.MapColor;

import java.util.function.Supplier;

public final class HvxBlocks {
    public static final Supplier<ComputerCaseBlock> RETRO_PC = register("retro_pc",
            () -> new ComputerCaseBlock(machine(MapColor.SAND, 6), FormFactor.RETRO));
    public static final Supplier<ComputerCaseBlock> GAMING_TOWER = register("gaming_tower",
            () -> new ComputerCaseBlock(machine(MapColor.COLOR_BLACK, 11), FormFactor.TOWER));
    public static final Supplier<ServerRackBlock> SERVER_RACK = register("server_rack",
            () -> new ServerRackBlock(machine(MapColor.COLOR_GRAY, 8).strength(3.0f, 8.0f)));

    public static final Supplier<MonitorBlock> MONITOR = register("monitor", () -> new MonitorBlock(display(), MonitorType.FLAT));
    public static final Supplier<MonitorBlock> CRT_MONITOR = register("crt_monitor", () -> new MonitorBlock(display(), MonitorType.CRT));
    public static final Supplier<MonitorBlock> CURVED_MONITOR = register("curved_monitor", () -> new MonitorBlock(display(), MonitorType.ULTRAWIDE));
    public static final Supplier<MonitorBlock> DUAL_MONITOR_ARM = register("dual_monitor_arm", () -> new MonitorBlock(display(), MonitorType.DUAL_ARM));
    public static final Supplier<MonitorBlock> TRIPLE_MONITOR_ARM = register("triple_monitor_arm", () -> new MonitorBlock(display(), MonitorType.TRIPLE_ARM));
    public static final Supplier<VideoWallBlock> VIDEO_WALL = register("video_wall_panel", () -> new VideoWallBlock(display().lightLevel(s -> 0)));

    public static final Supplier<LaptopBlock> PRO_LAPTOP = register("pro_laptop", () -> new LaptopBlock(LaptopBlock.defaultProperties().sound(SoundType.METAL), LaptopModel.PRO));
    public static final Supplier<LaptopBlock> RUGGED_LAPTOP = register("rugged_laptop", () -> new LaptopBlock(LaptopBlock.defaultProperties().sound(SoundType.METAL), LaptopModel.RUGGED));

    public static final Supplier<PeripheralBlock> KEYBOARD = register("mechanical_keyboard", () -> new PeripheralBlock(small(), PeripheralBlock.Kind.KEYBOARD));
    public static final Supplier<PeripheralBlock> MOUSE = register("gaming_mouse", () -> new PeripheralBlock(small(), PeripheralBlock.Kind.MOUSE));
    public static final Supplier<PeripheralBlock> SPEAKERS = register("desktop_speakers", () -> new PeripheralBlock(small(), PeripheralBlock.Kind.SPEAKERS));

    public static final Supplier<DeliveryCrateBlock> DELIVERY_CRATE = register("delivery_crate",
            () -> new DeliveryCrateBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY).strength(1.5f).sound(SoundType.METAL)));
    /** Render-only, just for the falling supply pod entity. */
    public static final Supplier<Block> SUPPLY_POD = register("supply_pod",
            () -> new Block(BlockBehaviour.Properties.of().noOcclusion().noLootTable().strength(-1f)));

    private HvxBlocks() {
    }

    private static BlockBehaviour.Properties machine(MapColor color, int light) {
        return BlockBehaviour.Properties.of().mapColor(color).strength(2.0f, 6.0f).sound(SoundType.METAL).noOcclusion()
                .lightLevel(s -> s.hasProperty(BlockStateProperties.POWERED) && s.getValue(BlockStateProperties.POWERED) ? light : 0);
    }

    private static BlockBehaviour.Properties display() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(1.5f, 4.0f).sound(SoundType.METAL).noOcclusion();
    }

    private static BlockBehaviour.Properties small() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(0.5f).sound(SoundType.STONE).noOcclusion();
    }

    private static <T extends Block> Supplier<T> register(String name, Supplier<T> factory) {
        return Services.PLATFORM.register(BuiltInRegistries.BLOCK, name, factory);
    }

    public static void init() {
    }
}
