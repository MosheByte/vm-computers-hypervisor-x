package net.hypervisorx.block.entity;

import net.hypervisorx.block.ComputerCaseBlock;
import net.hypervisorx.hardware.BuiltSpec;
import net.hypervisorx.hardware.FormFactor;
import net.hypervisorx.registry.HvxBlockEntities;
import net.hypervisorx.registry.HvxComponents;
import net.hypervisorx.vm.VmRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Retro PC / RGB gaming tower: parts inventory + one VM. */
public class ComputerBlockEntity extends HostBlockEntity {
    private final FormFactor form;
    private final PartsContainer parts;
    private UUID vmId;

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        super(HvxBlockEntities.COMPUTER.get(), pos, state);
        this.form = state.getBlock() instanceof ComputerCaseBlock c ? c.form() : FormFactor.RETRO;
        this.parts = new PartsContainer(form, this::markChangedAndSync,
                p -> level != null && level.getBlockEntity(worldPosition) == this && p.distanceToSqr(worldPosition.getCenter()) < 64);
    }

    public FormFactor form() {
        return form;
    }

    public PartsContainer parts() {
        return parts;
    }

    public List<ItemStack> partList() {
        List<ItemStack> l = new ArrayList<>(parts.getContainerSize());
        for (int i = 0; i < parts.getContainerSize(); i++) l.add(parts.getItem(i));
        return l;
    }

    @Override
    public VmRecord.HostKind hostKind() {
        return VmRecord.HostKind.CASE;
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
        return BuiltSpec.fromItems(form, partList());
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        NonNullList<ItemStack> items = NonNullList.withSize(parts.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < items.size(); i++) items.set(i, parts.getItem(i));
        ContainerHelper.saveAllItems(tag, items, true, registries);
        if (vmId != null) tag.putUUID("VmId", vmId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        NonNullList<ItemStack> items = NonNullList.withSize(parts.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        for (int i = 0; i < items.size(); i++) parts.getItems().set(i, items.get(i));
        vmId = tag.hasUUID("VmId") ? tag.getUUID("VmId") : null;
    }

    // picking it up keeps the parts and the VM (disk) on the item

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(HvxComponents.INSTALLED_PARTS.get(), ItemContainerContents.fromItems(partList()));
        if (vmId != null) components.set(HvxComponents.VM_LINK.get(), vmId);
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        ItemContainerContents contents = input.get(HvxComponents.INSTALLED_PARTS.get());
        if (contents != null) {
            NonNullList<ItemStack> items = NonNullList.withSize(parts.getContainerSize(), ItemStack.EMPTY);
            contents.copyInto(items);
            for (int i = 0; i < items.size(); i++) parts.getItems().set(i, items.get(i));
        }
        UUID id = input.get(HvxComponents.VM_LINK.get());
        if (id != null) vmId = id;
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        tag.remove("Items");
        tag.remove("VmId");
    }
}
