package net.hypervisorx.block;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/** Takes shapes modelled facing north (front at z = 0) and spins them to all four horizontal facings. */
public final class Shapes {
    private Shapes() {
    }

    public static VoxelShape box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return net.minecraft.world.level.block.Block.box(x0, y0, z0, x1, y1, z1);
    }

    public static VoxelShape or(VoxelShape... shapes) {
        VoxelShape s = net.minecraft.world.phys.shapes.Shapes.empty();
        for (VoxelShape v : shapes) s = net.minecraft.world.phys.shapes.Shapes.joinUnoptimized(s, v, BooleanOp.OR);
        return s.optimize();
    }

    public static VoxelShape rotate(VoxelShape north, Direction facing) {
        if (facing == Direction.NORTH) return north;
        VoxelShape out = net.minecraft.world.phys.shapes.Shapes.empty();
        for (AABB b : north.toAabbs()) {
            double x0 = b.minX, x1 = b.maxX, z0 = b.minZ, z1 = b.maxZ;
            AABB r = switch (facing) {
                case EAST -> new AABB(1 - z1, b.minY, x0, 1 - z0, b.maxY, x1);
                case SOUTH -> new AABB(1 - x1, b.minY, 1 - z1, 1 - x0, b.maxY, 1 - z0);
                case WEST -> new AABB(z0, b.minY, 1 - x1, z1, b.maxY, 1 - x0);
                default -> b;
            };
            out = net.minecraft.world.phys.shapes.Shapes.joinUnoptimized(out, net.minecraft.world.phys.shapes.Shapes.create(r), BooleanOp.OR);
        }
        return out.optimize();
    }

    public static Map<Direction, VoxelShape> allFacings(VoxelShape north) {
        Map<Direction, VoxelShape> m = new EnumMap<>(Direction.class);
        for (Direction d : Direction.Plane.HORIZONTAL) m.put(d, rotate(north, d));
        return m;
    }
}
