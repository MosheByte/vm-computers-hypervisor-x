package net.hypervisorx.block;

import net.minecraft.world.phys.shapes.VoxelShape;

/** Monitor models. Render-side screen geometry lives in the client's ScreenGeometry class. */
public enum MonitorType {
    FLAT("monitor", Shapes.box(0, 0, 6, 16, 12.5, 11), 1),
    CRT("crt_monitor", Shapes.box(1, 0, 2, 15, 13, 15), 1),
    ULTRAWIDE("curved_monitor", Shapes.box(3, 0, 5, 13, 10, 12), 1),
    DUAL_ARM("dual_monitor_arm", Shapes.box(3, 0, 6, 13, 13, 12), 2),
    TRIPLE_ARM("triple_monitor_arm", Shapes.box(3, 0, 6, 13, 13, 12), 3);

    private final String id;
    private final VoxelShape shape;
    private final int panels;

    MonitorType(String id, VoxelShape shape, int panels) {
        this.id = id;
        this.shape = shape;
        this.panels = panels;
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
