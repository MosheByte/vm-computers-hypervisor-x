package net.hypervisorx.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Finds desktop speakers near a computer. */
public final class SpeakerBlock {
    private SpeakerBlock() {
    }

    public static BlockPos findNear(Level level, BlockPos center, int radius) {
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -2, -radius), center.offset(radius, 2, radius))) {
            if (level.getBlockState(p).getBlock() instanceof PeripheralBlock pb && pb.kind() == PeripheralBlock.Kind.SPEAKERS) {
                return p.immutable();
            }
        }
        return null;
    }
}
