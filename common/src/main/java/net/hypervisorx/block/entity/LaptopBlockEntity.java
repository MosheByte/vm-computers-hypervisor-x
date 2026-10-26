package net.hypervisorx.block.entity;

import net.hypervisorx.block.LaptopBlock;
import net.hypervisorx.hardware.BuiltSpec;
import net.hypervisorx.hardware.LaptopModel;
import net.hypervisorx.registry.HvxBlockEntities;
import net.hypervisorx.registry.HvxComponents;
import net.hypervisorx.vm.VmManager;
import net.hypervisorx.vm.VmRecord;
import net.hypervisorx.vm.VmSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/** Laptop sitting open on a desk: runs its own VM and charges while it's docked. */
public class LaptopBlockEntity extends HostBlockEntity {
    private UUID vmId;
    private UUID remoteTarget;

    public LaptopBlockEntity(BlockPos pos, BlockState state) {
        super(HvxBlockEntities.LAPTOP.get(), pos, state);
    }

    public LaptopModel model() {
        return getBlockState().getBlock() instanceof LaptopBlock b ? b.model() : LaptopModel.PRO;
    }

    public UUID remoteTarget() {
        return remoteTarget;
    }

    @Override
    public VmRecord.HostKind hostKind() {
        return VmRecord.HostKind.LAPTOP;
    }

    @Override
    public int vmSlots() {
        return 1;
    }

    @Override
    public UUID vmId(int slot) {
        return slot == 0 ? vmId : null;
    }

    @Override
    public void setVmId(int slot, UUID id) {
        if (slot != 0) return;
        vmId = id;
        markChangedAndSync();
    }

    @Override
    public BuiltSpec builtSpec() {
        return model().spec();
    }

    /** Tops up the battery of a docked laptop that's powered off. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, LaptopBlockEntity be) {
        if (level.getGameTime() % 20 != 0 || be.vmId == null) return;
        VmManager m = VmManager.get();
        if (m == null) return;
        VmRecord r = m.record(be.vmId);
        if (r == null) return;
        VmSession s = m.session(be.vmId);
        if (s != null && s.isActive()) return; // running ones get charged in VmManager
        int max = VmManager.batteryMax(r);
        if (r.battery < 0) r.battery = max;
        if (r.battery < max) {
            r.battery = Math.min(max, r.battery + 80);
            m.data().changed();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (vmId != null) tag.putUUID("VmId", vmId);
        if (remoteTarget != null) tag.putUUID("Remote", remoteTarget);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        vmId = tag.hasUUID("VmId") ? tag.getUUID("VmId") : null;
        remoteTarget = tag.hasUUID("Remote") ? tag.getUUID("Remote") : null;
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (vmId != null) components.set(HvxComponents.VM_LINK.get(), vmId);
        if (remoteTarget != null) components.set(HvxComponents.REMOTE_TARGET.get(), remoteTarget);
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        vmId = input.get(HvxComponents.VM_LINK.get());
        remoteTarget = input.get(HvxComponents.REMOTE_TARGET.get());
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        tag.remove("VmId");
        tag.remove("Remote");
    }
}
