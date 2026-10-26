package net.hypervisorx.block.entity;

import net.hypervisorx.registry.HvxBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

/** One bezel-less LED panel. In a rectangle the bottom-left panel is the master and does the rendering. */
public class VideoWallBlockEntity extends DisplayBlockEntity {
    private BlockPos master;
    private int gridX;
    private int gridY;
    private int gridW = 1;
    private int gridH = 1;

    public VideoWallBlockEntity(BlockPos pos, BlockState state) {
        super(HvxBlockEntities.VIDEO_WALL.get(), pos, state);
    }

    public boolean isMaster() {
        return master == null || master.equals(worldPosition);
    }

    public BlockPos master() {
        return master == null ? worldPosition : master;
    }

    public int gridX() {
        return gridX;
    }

    public int gridY() {
        return gridY;
    }

    public int gridW() {
        return gridW;
    }

    public int gridH() {
        return gridH;
    }

    public void setGrid(BlockPos master, int x, int y, int w, int h) {
        this.master = master.immutable();
        this.gridX = x;
        this.gridY = y;
        this.gridW = w;
        this.gridH = h;
        sync();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (master != null) tag.put("Master", NbtUtils.writeBlockPos(master));
        tag.putInt("GX", gridX);
        tag.putInt("GY", gridY);
        tag.putInt("GW", gridW);
        tag.putInt("GH", gridH);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        master = tag.contains("Master") ? NbtUtils.readBlockPos(tag, "Master").orElse(null) : null;
        gridX = tag.getInt("GX");
        gridY = tag.getInt("GY");
        gridW = Math.max(1, tag.getInt("GW"));
        gridH = Math.max(1, tag.getInt("GH"));
    }
}
