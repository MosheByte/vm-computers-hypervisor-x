package net.hypervisorx.block.entity;

import net.hypervisorx.block.MonitorBlock;
import net.hypervisorx.block.MonitorType;
import net.hypervisorx.registry.HvxBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class MonitorBlockEntity extends DisplayBlockEntity {
    public MonitorBlockEntity(BlockPos pos, BlockState state) {
        super(HvxBlockEntities.MONITOR.get(), pos, state);
    }

    public MonitorType type() {
        return getBlockState().getBlock() instanceof MonitorBlock m ? m.type() : MonitorType.FLAT;
    }
}
