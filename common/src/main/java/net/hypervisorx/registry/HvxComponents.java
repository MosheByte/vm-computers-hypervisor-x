package net.hypervisorx.registry;

import net.hypervisorx.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.UUID;
import java.util.function.Supplier;

/** Item data components. */
public final class HvxComponents {
    /** Which VM a case/laptop/rack unit belongs to (survives breaking the block). */
    public static final Supplier<DataComponentType<UUID>> VM_LINK = register("vm_link",
            () -> DataComponentType.<UUID>builder().persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC).build());
    /** Parts that were inside a picked-up computer. */
    public static final Supplier<DataComponentType<ItemContainerContents>> INSTALLED_PARTS = register("installed_parts",
            () -> DataComponentType.<ItemContainerContents>builder().persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC).cacheEncoding().build());
    /** Laptop's remote desktop target. */
    public static final Supplier<DataComponentType<UUID>> REMOTE_TARGET = register("remote_target",
            () -> DataComponentType.<UUID>builder().persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC).build());
    /** A USB flash drive's disk image. */
    public static final Supplier<DataComponentType<UUID>> DRIVE_ID = register("drive_id",
            () -> DataComponentType.<UUID>builder().persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC).build());
    /** First end picked with an HDMI cable. */
    public static final Supplier<DataComponentType<BlockPos>> LINK_SOURCE = register("link_source",
            () -> DataComponentType.<BlockPos>builder().persistent(BlockPos.CODEC).networkSynchronized(BlockPos.STREAM_CODEC).build());

    /** Rack VM slot the HDMI cable is carrying. */
    public static final Supplier<DataComponentType<Integer>> LINK_SLOT = register("link_slot",
            () -> DataComponentType.<Integer>builder().persistent(com.mojang.serialization.Codec.INT)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT).build());

    private HvxComponents() {
    }

    private static <T> Supplier<DataComponentType<T>> register(String name, Supplier<DataComponentType<T>> factory) {
        return Services.PLATFORM.register(BuiltInRegistries.DATA_COMPONENT_TYPE, name, factory);
    }

    public static void init() {
    }
}
