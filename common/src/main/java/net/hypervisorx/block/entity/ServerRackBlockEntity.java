package net.hypervisorx.block.entity;

import net.hypervisorx.block.ServerRackBlock;
import net.hypervisorx.hardware.BuiltSpec;
import net.hypervisorx.hardware.FormFactor;
import net.hypervisorx.registry.HvxBlockEntities;
import net.hypervisorx.vm.VmRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One unit of a modular server rack. Stack them up and they form a cluster, the bottom one is the
 * controller: it pools every blade (cores + RAM) and drive (RAID 0) in the column and runs up to
 * {@link #MAX_VMS} VMs at once.
 */
public class ServerRackBlockEntity extends HostBlockEntity {
    public static final int MAX_VMS = 4;
    public static final int MAX_HEIGHT = 8;

    private final PartsContainer parts;
    private final UUID[] vmIds = new UUID[MAX_VMS];

    public ServerRackBlockEntity(BlockPos pos, BlockState state) {
        super(HvxBlockEntities.SERVER_RACK.get(), pos, state);
        this.parts = new PartsContainer(FormFactor.RACK, this::onPartsChanged,
                p -> level != null && level.getBlockEntity(worldPosition) == this && p.distanceToSqr(worldPosition.getCenter()) < 64);
    }

    private void onPartsChanged() {
        markChangedAndSync();
    }

    public PartsContainer parts() {
        return parts;
    }

    /** Every unit in this column, bottom first. */
    public List<ServerRackBlockEntity> column() {
        List<ServerRackBlockEntity> list = new ArrayList<>();
        if (level == null) return List.of(this);
        BlockPos p = controllerPos();
        for (int i = 0; i < MAX_HEIGHT; i++) {
            if (!(level.getBlockEntity(p) instanceof ServerRackBlockEntity be) || be.facing() != facing()) break;
            list.add(be);
            p = p.above();
        }
        return list;
    }

    public BlockPos controllerPos() {
        if (level == null) return worldPosition;
        BlockPos p = worldPosition;
        for (int i = 0; i < MAX_HEIGHT; i++) {
            BlockPos below = p.below();
            BlockState s = level.getBlockState(below);
            if (!(s.getBlock() instanceof ServerRackBlock) || s.getValue(ServerRackBlock.FACING) != facing()) break;
            p = below;
        }
        return p;
    }

    public boolean isController() {
        return controllerPos().equals(worldPosition);
    }

    public ServerRackBlockEntity controller() {
        if (level != null && level.getBlockEntity(controllerPos()) instanceof ServerRackBlockEntity be) return be;
        return this;
    }

    @Override
    public VmRecord.HostKind hostKind() {
        return VmRecord.HostKind.RACK;
    }

    @Override
    public int vmSlots() {
        return MAX_VMS;
    }

    @Override
    public UUID vmId(int slot) {
        return slot >= 0 && slot < MAX_VMS ? vmIds[slot] : null;
    }

    @Override
    public void setVmId(int slot, UUID id) {
        if (slot < 0 || slot >= MAX_VMS) return;
        vmIds[slot] = id;
        markChangedAndSync();
    }

    /** Hands the cluster's VMs over to another unit (when the controller changes). */
    public void transferVmsTo(ServerRackBlockEntity other) {
        for (int i = 0; i < MAX_VMS; i++) {
            if (vmIds[i] != null && other.vmIds[i] == null) {
                other.vmIds[i] = vmIds[i];
                vmIds[i] = null;
            }
        }
        if (other.owner == null) {
            other.owner = owner;
            other.ownerName = ownerName;
        }
        markChangedAndSync();
        other.markChangedAndSync();
        other.bindRecords();
    }

    @Override
    public BuiltSpec builtSpec() {
        List<ItemStack> all = new ArrayList<>();
        for (ServerRackBlockEntity be : column()) {
            for (int i = 0; i < be.parts.getContainerSize(); i++) all.add(be.parts.getItem(i));
        }
        return BuiltSpec.fromItems(FormFactor.RACK, all);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        NonNullList<ItemStack> items = NonNullList.withSize(parts.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < items.size(); i++) items.set(i, parts.getItem(i));
        ContainerHelper.saveAllItems(tag, items, true, registries);
        for (int i = 0; i < MAX_VMS; i++) {
            if (vmIds[i] != null) tag.putUUID("Vm" + i, vmIds[i]);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        NonNullList<ItemStack> items = NonNullList.withSize(parts.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        for (int i = 0; i < items.size(); i++) parts.getItems().set(i, items.get(i));
        for (int i = 0; i < MAX_VMS; i++) vmIds[i] = tag.hasUUID("Vm" + i) ? tag.getUUID("Vm" + i) : null;
    }
}
