package net.hypervisorx.client.render;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/** For renderers that draw outside their own block. NeoForge culls using this box. */
public interface BoundedRenderer<T extends BlockEntity> {
    AABB renderBounds(T blockEntity);
}
