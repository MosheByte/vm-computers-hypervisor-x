package net.hypervisorx.block;

import net.minecraft.world.phys.shapes.VoxelShape;

/** Monitor models. Render-side screen geometry lives in the client's ScreenGeometry class. */
public enum MonitorType {
    FLAT("monitor", Shapes.box(0, 0, 6, 16, 12.5, 11), null, 1),
    CRT("crt_monitor", Shapes.box(1, 0, 2, 15, 13, 15), null, 1),
    ULTRAWIDE("curved_monitor", Shapes.box(3, 0, 6.5, 13, 10.5, 12.5), Shapes.box(-8, 0, 3, 24, 18.5, 12.5), 1),
    DUAL_ARM("dual_monitor_arm", Shapes.box(6, 0, 8, 10, 13.2, 12), Shapes.box(-7, 0, 6.5, 23, 15.5, 12), 2),
    TRIPLE_ARM("triple_monitor_arm", Shapes.box(6, 0, 8, 10, 13.2, 12), Shapes.box(-13, 0, 0.5, 29, 15.5, 12), 3);

    private final String id;
    private final VoxelShape shape;
    private final VoxelShape outline;
    private final int panels;

    MonitorType(String id, VoxelShape shape, VoxelShape outline, int panels) {
        this.id = id;
        this.shape = shape;
        this.outline = outline == null ? shape : outline;
        this.panels = panels;
    }

    /** Selection outline, also covers panels that hang over into neighbouring blocks. */
    public VoxelShape outline() {
        return outline;
    }

    public String id() {
        return id;
    }

    public VoxelShape shape() {
        return shape;
    }

    /** How many physical panels. Multi-panel stands stretch one desktop across all of them. */
    public int panels() {
        return panels;
    }
}
