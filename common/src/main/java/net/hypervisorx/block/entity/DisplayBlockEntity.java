package net.hypervisorx.block.entity;

import net.hypervisorx.vm.VmHost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.UUID;

/**
 * Base for anything that shows a VM: monitors, monitor arms, video walls. You link it to a host block
 * (+ VM slot) with the HDMI cable, the server works out the VM id and syncs it to clients.
 */
public abstract class DisplayBlockEntity extends BlockEntity {
    protected BlockPos linkHost;
    protected int linkSlot;
    /** Which horizontal slice of the desktop we show: index of count (count 1 = whole desktop). */
    protected int partIndex;
    protected int partCount = 1;
    protected UUID displayedVm;
    private int resolveTimer;

    protected DisplayBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public UUID displayedVm() {
        return displayedVm;
    }

    public BlockPos linkHost() {
        return linkHost;
    }

    public int linkSlot() {
        return linkSlot;
    }

    public int partIndex() {
        return partIndex;
    }

    public int partCount() {
        return partCount;
    }

    public void link(BlockPos host, int slot) {
        linkHost = host.immutable();
        linkSlot = slot;
        resolve();
        sync();
    }

    public void unlink() {
        linkHost = null;
        displayedVm = null;
        sync();
    }

    /** Cycles full, halves, thirds. For stretching one desktop over a few monitors. */
    public String cyclePart() {
        if (partCount == 1) {
            partCount = 2;
            partIndex = 0;
        } else if (partIndex + 1 < partCount) {
            partIndex++;
        } else if (partCount == 2) {
            partCount = 3;
            partIndex = 0;
        } else {
            partCount = 1;
            partIndex = 0;
        }
        sync();
        return partCount == 1 ? "full" : (partIndex + 1) + "/" + partCount;
    }

    /** Server side: re-read the VM id from the linked host. */
    public void resolve() {
        if (level == null || level.isClientSide) return;
        UUID vm = null;
        if (linkHost != null && level.isLoaded(linkHost) && level.getBlockEntity(linkHost) instanceof VmHost host) {
            vm = host.vmId(Math.max(0, Math.min(host.vmSlots() - 1, linkSlot)));
        }
        if (!Objects.equals(vm, displayedVm)) {
            displayedVm = vm;
            sync();
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, DisplayBlockEntity be) {
        if (++be.resolveTimer >= 40) {
            be.resolveTimer = 0;
            be.resolve();
        }
    }

    protected void sync() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (linkHost != null) tag.put("LinkHost", NbtUtils.writeBlockPos(linkHost));
        tag.putInt("LinkSlot", linkSlot);
        tag.putInt("PartIndex", partIndex);
        tag.putInt("PartCount", partCount);
        if (displayedVm != null) tag.putUUID("Vm", displayedVm);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        linkHost = tag.contains("LinkHost") ? NbtUtils.readBlockPos(tag, "LinkHost").orElse(null) : null;
        linkSlot = tag.getInt("LinkSlot");
        partIndex = tag.getInt("PartIndex");
        partCount = Math.max(1, tag.getInt("PartCount"));
        displayedVm = tag.hasUUID("Vm") ? tag.getUUID("Vm") : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
