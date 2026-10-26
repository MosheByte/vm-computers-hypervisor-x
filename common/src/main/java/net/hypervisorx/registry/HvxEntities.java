package net.hypervisorx.registry;

import net.hypervisorx.entity.SupplyPodEntity;
import net.hypervisorx.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.function.Supplier;

public final class HvxEntities {
    public static final Supplier<EntityType<SupplyPodEntity>> SUPPLY_POD = Services.PLATFORM.register(BuiltInRegistries.ENTITY_TYPE, "supply_pod",
            () -> EntityType.Builder.<SupplyPodEntity>of(SupplyPodEntity::new, MobCategory.MISC)
                    .sized(1.2f, 1.8f).clientTrackingRange(16).updateInterval(1).fireImmune()
                    .build("hypervisorx:supply_pod"));

    private HvxEntities() {
    }

    public static void init() {
    }
}
