package net.hypervisorx.vm;

import net.hypervisorx.backend.GuestOs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** one VM's saved setup (lives in the world's saved data). */
public final class VmRecord {
    public enum HostKind { CASE, RACK, LAPTOP }

    public enum Access {
        /** only owner + trusted players can watch and use it. */
        PRIVATE,
        /** anyone can watch, only owner + trusted players can use it. */
        SHARED_VIEW,
        /** anyone can watch and use it. */
        PUBLIC;

        public static Access parse(String s) {
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return PRIVATE;
            }
        }
    }

    public final UUID id;
    public String name;
    public UUID owner;
    public String ownerName = "";
    public HostKind kind;
    /** backend id the player picked, or "auto". */
    public String backend = "auto";
    /** backend that made the existing disks ("auto" sticks with it). */
    public String lastBackend = "";
    public GuestOs guestOs = GuestOs.LINUX;
    /** image file name in the images library, or an absolute path the singleplayer host imported. */
    public String image = "";
    /** window title (host capture) or kernel variant (embedded). */
    public String target = "";
    public boolean efi;
    public Access access = Access.PRIVATE;
    /** concurrent set, input packets read it off the server thread. */
    public final Set<UUID> trusted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** how many monitors the desktop spans (multiplies the horizontal res). */
    public int spanMonitors = 1;
    /** rack VMs only: what they get from the cluster pool. */
    public int allocCores = 2;
    public int allocRamMb = 4096;
    public int allocDiskGb = 64;
    /** laptop battery, in ticks. */
    public int battery = -1;
    public String laptopModel = "";
    public ResourceKey<Level> dimension;
    public BlockPos hostPos;
    public long createdAt = System.currentTimeMillis();
    public long lastBoot;
    /** last time the computer with this VM was loaded in the world or carried around by a player. */
    public long lastSeen = System.currentTimeMillis();

    public VmRecord(UUID id, HostKind kind, String name, UUID owner) {
        this.id = id;
        this.kind = kind;
        this.name = name;
        this.owner = owner;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);
        t.putString("Name", name);
        if (owner != null) t.putUUID("Owner", owner);
        t.putString("OwnerName", ownerName);
        t.putString("Kind", kind.name());
        t.putString("Backend", backend);
        t.putString("LastBackend", lastBackend);
        t.putString("GuestOs", guestOs.name());
        t.putString("Image", image);
        t.putString("Target", target);
        t.putBoolean("Efi", efi);
        t.putString("Access", access.name());
        ListTag tr = new ListTag();
        for (UUID u : trusted) tr.add(StringTag.valueOf(u.toString()));
        t.put("Trusted", tr);
        t.putInt("Span", spanMonitors);
        t.putInt("AllocCores", allocCores);
        t.putInt("AllocRam", allocRamMb);
        t.putInt("AllocDisk", allocDiskGb);
        t.putInt("Battery", battery);
        t.putString("LaptopModel", laptopModel);
        if (dimension != null) t.putString("Dim", dimension.location().toString());
        if (hostPos != null) t.put("HostPos", NbtUtils.writeBlockPos(hostPos));
        t.putLong("Created", createdAt);
        t.putLong("LastBoot", lastBoot);
        t.putLong("LastSeen", lastSeen);
        return t;
    }

    public static VmRecord load(CompoundTag t) {
        VmRecord r = new VmRecord(t.getUUID("Id"), parseKind(t.getString("Kind")), t.getString("Name"),
                t.hasUUID("Owner") ? t.getUUID("Owner") : null);
        r.ownerName = t.getString("OwnerName");
        r.backend = t.getString("Backend").isEmpty() ? "auto" : t.getString("Backend");
        r.lastBackend = t.getString("LastBackend");
        r.guestOs = GuestOs.parse(t.getString("GuestOs"));
        r.image = t.getString("Image");
        r.target = t.getString("Target");
        r.efi = t.getBoolean("Efi");
        r.access = Access.parse(t.getString("Access"));
        for (Tag e : t.getList("Trusted", Tag.TAG_STRING)) {
            try {
                r.trusted.add(UUID.fromString(e.getAsString()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        r.spanMonitors = Math.max(1, t.getInt("Span"));
        r.allocCores = t.getInt("AllocCores");
        r.allocRamMb = t.getInt("AllocRam");
        r.allocDiskGb = t.getInt("AllocDisk");
        r.battery = t.contains("Battery") ? t.getInt("Battery") : -1;
        r.laptopModel = t.getString("LaptopModel");
        if (t.contains("Dim")) {
            ResourceLocation rl = ResourceLocation.tryParse(t.getString("Dim"));
            if (rl != null) r.dimension = ResourceKey.create(Registries.DIMENSION, rl);
        }
        if (t.contains("HostPos")) r.hostPos = NbtUtils.readBlockPos(t, "HostPos").orElse(null);
        r.createdAt = t.getLong("Created");
        r.lastBoot = t.getLong("LastBoot");
        r.lastSeen = t.contains("LastSeen") ? t.getLong("LastSeen") : System.currentTimeMillis();
        return r;
    }

    private static HostKind parseKind(String s) {
        try {
            return HostKind.valueOf(s);
        } catch (RuntimeException e) {
            return HostKind.CASE;
        }
    }
}
