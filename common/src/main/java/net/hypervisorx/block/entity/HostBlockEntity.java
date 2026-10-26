package net.hypervisorx.block.entity;

import net.hypervisorx.vm.VmHost;
import net.hypervisorx.vm.VmManager;
import net.hypervisorx.vm.VmRecord;
import net.hypervisorx.vm.VmSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.UUID;

/** Owner, GPIO and client sync stuff shared by every block that hosts VMs. */
public abstract class HostBlockEntity extends BlockEntity implements VmHost {
    protected UUID owner;
    protected String ownerName = "";
    protected final int[] gpioOut = new int[6];
    protected final int[] gpioIn = new int[6];
    /** Client only: last known state per slot (comes in through the update tag). */
    protected String[] clientStates = new String[0];

    protected HostBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public Direction facing() {
        BlockState s = getBlockState();
        return s.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? s.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
    }

    @Override
    public Level hostLevel() {
        return level;
    }

    @Override
    public BlockPos hostPos() {
        return worldPosition;
    }

    @Override
    public UUID ownerId() {
        return owner;
    }

    @Override
    public String ownerName() {
        return ownerName;
    }

    @Override
    public void setOwner(UUID owner, String name) {
        this.owner = owner;
        this.ownerName = name == null ? "" : name;
        markChangedAndSync();
    }

    public String clientState(int slot) {
        return slot >= 0 && slot < clientStates.length && clientStates[slot] != null ? clientStates[slot] : "STOPPED";
    }

    /** Points all of this host's VM records at where it is now (after placing it or a chunk load). */
    public void bindRecords() {
        VmManager m = VmManager.get();
        if (m == null || level == null || level.isClientSide) return;
        for (int i = 0; i < vmSlots(); i++) {
            VmRecord r = m.record(vmId(i));
            if (r != null && (!worldPosition.equals(r.hostPos) || r.dimension != level.dimension())) {
                r.hostPos = worldPosition.immutable();
                r.dimension = level.dimension();
                m.data().changed();
            }
        }
    }

    @Override
    public void onVmStateChanged(int slot, VmSession.State state) {
        if (level == null || level.isClientSide) return;
        BlockState bs = getBlockState();
        if (bs.hasProperty(BlockStateProperties.POWERED)) {
            boolean anyOn = false;
            VmManager m = VmManager.get();
            for (int i = 0; i < vmSlots(); i++) {
                VmSession s = m == null ? null : m.session(vmId(i));
                if (s != null && s.isActive() && !(i == slot && !isActive(state))) anyOn = true;
            }
            if (isActive(state)) anyOn = true;
            if (bs.getValue(BlockStateProperties.POWERED) != anyOn) {
                level.setBlock(worldPosition, bs.setValue(BlockStateProperties.POWERED, anyOn), Block.UPDATE_ALL);
            }
        }
        markChangedAndSync();
    }

    private static boolean isActive(VmSession.State s) {
        return s == VmSession.State.STARTING || s == VmSession.State.RUNNING || s == VmSession.State.STOPPING;
    }

    // ------------------------------------------------------------ GPIO

    @Override
    public int gpioOutput(Direction side) {
        return gpioOut[side.get3DDataValue()];
    }

    @Override
    public void setGpioOutput(Direction side, int value) {
        int v = Math.max(0, Math.min(15, value));
        if (gpioOut[side.get3DDataValue()] == v || level == null) return;
        gpioOut[side.get3DDataValue()] = v;
        setChanged();
        Block block = getBlockState().getBlock();
        level.updateNeighborsAt(worldPosition, block);
        level.neighborChanged(worldPosition.relative(side), block, worldPosition);
        level.updateNeighborsAtExceptFromFacing(worldPosition.relative(side), block, side.getOpposite());
    }

    @Override
    public int gpioInput(Direction side) {
        return gpioIn[side.get3DDataValue()];
    }

    /** Re-reads incoming redstone. Ignores our own output echoing back through dust. */
    public void refreshInputs() {
        if (level == null) return;
        for (Direction d : Direction.values()) {
            int raw = level.getSignal(worldPosition.relative(d), d);
            int own = gpioOut[d.get3DDataValue()];
            gpioIn[d.get3DDataValue()] = own > 0 && raw <= own ? 0 : raw;
        }
    }

    // ------------------------------------------------------------ persistence

    protected void markChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putString("OwnerName", ownerName);
        tag.putIntArray("GpioOut", gpioOut);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ownerName = tag.getString("OwnerName");
        int[] g = tag.getIntArray("GpioOut");
        System.arraycopy(g, 0, gpioOut, 0, Math.min(6, g.length));
        if (tag.contains("States")) {
            clientStates = tag.getString("States").split(",", -1);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        StringBuilder sb = new StringBuilder();
        VmManager m = VmManager.get();
        for (int i = 0; i < vmSlots(); i++) {
            VmSession s = m == null ? null : m.session(vmId(i));
            if (i > 0) sb.append(',');
            sb.append(s == null ? "STOPPED" : s.state().name());
        }
        tag.putString("States", sb.toString());
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
