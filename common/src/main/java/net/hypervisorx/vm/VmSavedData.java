package net.hypervisorx.vm;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** every VM record in the world, saved to the overworld's {@code data/hypervisorx_vms.dat}. */
public final class VmSavedData extends SavedData {
    private static final String NAME = "hypervisorx_vms";
    private final Map<UUID, VmRecord> records = new LinkedHashMap<>();

    public static VmSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(VmSavedData::new, VmSavedData::load, null), NAME);
    }

    private static VmSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        VmSavedData d = new VmSavedData();
        for (Tag t : tag.getList("Vms", Tag.TAG_COMPOUND)) {
            try {
                VmRecord r = VmRecord.load((CompoundTag) t);
                d.records.put(r.id, r);
            } catch (RuntimeException ignored) {
                // corrupt entry, skip it
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (VmRecord r : records.values()) list.add(r.save());
        tag.put("Vms", list);
        return tag;
    }

    public VmRecord get(UUID id) {
        return id == null ? null : records.get(id);
    }

    public Collection<VmRecord> all() {
        return records.values();
    }

    public void put(VmRecord r) {
        records.put(r.id, r);
        setDirty();
    }

    public void remove(UUID id) {
        if (records.remove(id) != null) setDirty();
    }

    public void changed() {
        setDirty();
    }
}
