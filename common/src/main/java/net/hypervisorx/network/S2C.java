package net.hypervisorx.network;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.vm.display.EncodedTile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server to client packets. */
public final class S2C {
    private S2C() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> makeType(String name) {
        return new CustomPacketPayload.Type<>(HyperVisorX.id(name));
    }

    /** Tells a viewer to (re)create its texture for {@code vm} at this size. */
    public record ScreenReset(UUID vm, int epoch, int width, int height) implements CustomPacketPayload {
        public static final Type<ScreenReset> TYPE = makeType("screen_reset");
        public static final StreamCodec<FriendlyByteBuf, ScreenReset> CODEC = CustomPacketPayload.codec(ScreenReset::write, ScreenReset::new);

        ScreenReset(FriendlyByteBuf b) {
            this(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readVarInt());
        }

        void write(FriendlyByteBuf b) {
            b.writeUUID(vm);
            b.writeVarInt(epoch);
            b.writeVarInt(width);
            b.writeVarInt(height);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Dirty tiles of one VM display. */
    public record ScreenTiles(UUID vm, int epoch, List<EncodedTile> tiles) implements CustomPacketPayload {
        public static final Type<ScreenTiles> TYPE = makeType("screen_tiles");
        public static final StreamCodec<FriendlyByteBuf, ScreenTiles> CODEC = CustomPacketPayload.codec(ScreenTiles::write, ScreenTiles::new);
        private static final int MAX_TILE_BYTES = 64 * 64 * 4 + 1024;

        ScreenTiles(FriendlyByteBuf b) {
            this(b.readUUID(), b.readVarInt(), readTiles(b));
        }

        private static List<EncodedTile> readTiles(FriendlyByteBuf b) {
            int n = b.readVarInt();
            if (n < 0 || n > 65536) throw new IllegalArgumentException("bad tile count " + n);
            List<EncodedTile> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int x = b.readVarInt();
                int y = b.readVarInt();
                int w = b.readUnsignedByte();
                int h = b.readUnsignedByte();
                int mode = b.readUnsignedByte();
                byte[] data = b.readByteArray(MAX_TILE_BYTES);
                list.add(new EncodedTile(x, y, w, h, mode, data));
            }
            return list;
        }

        void write(FriendlyByteBuf b) {
            b.writeUUID(vm);
            b.writeVarInt(epoch);
            b.writeVarInt(tiles.size());
            for (EncodedTile t : tiles) {
                b.writeVarInt(t.x());
                b.writeVarInt(t.y());
                b.writeByte(t.w());
                b.writeByte(t.h());
                b.writeByte(t.mode());
                b.writeByteArray(t.data());
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Live VM status for screens, HUDs and tooltips. */
    public record VmStatus(UUID vm, String name, String state, String message, String backend, int width, int height,
                           float fps, int batteryPercent, boolean canControl) implements CustomPacketPayload {
        public static final Type<VmStatus> TYPE = makeType("vm_status");
        public static final StreamCodec<FriendlyByteBuf, VmStatus> CODEC = CustomPacketPayload.codec(VmStatus::write, VmStatus::new);

        VmStatus(FriendlyByteBuf b) {
            this(b.readUUID(), b.readUtf(256), b.readUtf(32), b.readUtf(1024), b.readUtf(32), b.readVarInt(), b.readVarInt(),
                    b.readFloat(), b.readVarInt(), b.readBoolean());
        }

        void write(FriendlyByteBuf b) {
            b.writeUUID(vm);
            b.writeUtf(name, 256);
            b.writeUtf(state, 32);
            b.writeUtf(message.length() > 1000 ? message.substring(0, 1000) : message, 1024);
            b.writeUtf(backend, 32);
            b.writeVarInt(width);
            b.writeVarInt(height);
            b.writeFloat(fps);
            b.writeVarInt(batteryPercent);
            b.writeBoolean(canControl);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Mono s16le PCM, 22050 Hz. {@code origin} is null for handheld (laptop) audio. */
    public record Audio(UUID vm, BlockPos origin, byte[] pcm) implements CustomPacketPayload {
        public static final Type<Audio> TYPE = makeType("audio");
        public static final StreamCodec<FriendlyByteBuf, Audio> CODEC = CustomPacketPayload.codec(Audio::write, Audio::new);

        Audio(FriendlyByteBuf b) {
            this(b.readUUID(), b.readBoolean() ? b.readBlockPos() : null, b.readByteArray(1 << 18));
        }

        void write(FriendlyByteBuf b) {
            b.writeUUID(vm);
            b.writeBoolean(origin != null);
            if (origin != null) b.writeBlockPos(origin);
            b.writeByteArray(pcm);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Opens the fullscreen focus screen for a VM. */
    public record OpenFocus(UUID vm, boolean laptop) implements CustomPacketPayload {
        public static final Type<OpenFocus> TYPE = makeType("open_focus");
        public static final StreamCodec<FriendlyByteBuf, OpenFocus> CODEC = CustomPacketPayload.codec(OpenFocus::write, OpenFocus::new);

        OpenFocus(FriendlyByteBuf b) {
            this(b.readUUID(), b.readBoolean());
        }

        void write(FriendlyByteBuf b) {
            b.writeUUID(vm);
            b.writeBoolean(laptop);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One VM slot of a host, the way the computer screen shows it. */
    public record VmSlotInfo(int slot, UUID vm, String name, String state, String message, String backend, String guestOs,
                             String image, String target, String access, int span, int cores, int ramMb, int diskGb,
                             boolean efi, boolean guestTools, boolean canControl, boolean owner) {
        static VmSlotInfo read(FriendlyByteBuf b) {
            return new VmSlotInfo(b.readVarInt(), b.readBoolean() ? b.readUUID() : null, b.readUtf(), b.readUtf(), b.readUtf(),
                    b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf(), b.readVarInt(), b.readVarInt(),
                    b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean());
        }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(slot);
            b.writeBoolean(vm != null);
            if (vm != null) b.writeUUID(vm);
            b.writeUtf(name);
            b.writeUtf(state);
            b.writeUtf(message.length() > 2000 ? message.substring(0, 2000) : message);
            b.writeUtf(backend);
            b.writeUtf(guestOs);
            b.writeUtf(image);
            b.writeUtf(target);
            b.writeUtf(access);
            b.writeVarInt(span);
            b.writeVarInt(cores);
            b.writeVarInt(ramMb);
            b.writeVarInt(diskGb);
            b.writeBoolean(efi);
            b.writeBoolean(guestTools);
            b.writeBoolean(canControl);
            b.writeBoolean(owner);
        }
    }

    /** Everything the computer/rack screen shows apart from the item slots. */
    public record HostInfo(BlockPos pos, boolean rack, int cores, int ramMb, int width, int height, int displays, int diskGb,
                           boolean nvme, boolean gpio, boolean wifi, List<Component> problems, List<VmSlotInfo> slots,
                           int poolCoresFree, int poolRamFree, int poolDiskFree) implements CustomPacketPayload {
        public static final Type<HostInfo> TYPE = makeType("host_info");
        public static final StreamCodec<RegistryFriendlyByteBuf, HostInfo> CODEC = StreamCodec.of((b, v) -> v.write(b), HostInfo::read);

        static HostInfo read(RegistryFriendlyByteBuf b) {
            BlockPos pos = b.readBlockPos();
            boolean rack = b.readBoolean();
            int cores = b.readVarInt(), ram = b.readVarInt(), w = b.readVarInt(), h = b.readVarInt(), d = b.readVarInt(), disk = b.readVarInt();
            boolean nvme = b.readBoolean(), gpio = b.readBoolean(), wifi = b.readBoolean();
            int np = b.readVarInt();
            List<Component> problems = new ArrayList<>();
            for (int i = 0; i < np; i++) problems.add(ComponentSerialization.TRUSTED_STREAM_CODEC.decode(b));
            int ns = b.readVarInt();
            List<VmSlotInfo> slots = new ArrayList<>();
            for (int i = 0; i < ns; i++) slots.add(VmSlotInfo.read(b));
            return new HostInfo(pos, rack, cores, ram, w, h, d, disk, nvme, gpio, wifi, problems, slots, b.readVarInt(), b.readVarInt(), b.readVarInt());
        }

        void write(RegistryFriendlyByteBuf b) {
            b.writeBlockPos(pos);
            b.writeBoolean(rack);
            b.writeVarInt(cores);
            b.writeVarInt(ramMb);
            b.writeVarInt(width);
            b.writeVarInt(height);
            b.writeVarInt(displays);
            b.writeVarInt(diskGb);
            b.writeBoolean(nvme);
            b.writeBoolean(gpio);
            b.writeBoolean(wifi);
            b.writeVarInt(problems.size());
            for (Component c : problems) ComponentSerialization.TRUSTED_STREAM_CODEC.encode(b, c);
            b.writeVarInt(slots.size());
            for (VmSlotInfo s : slots) s.write(b);
            b.writeVarInt(poolCoresFree);
            b.writeVarInt(poolRamFree);
            b.writeVarInt(poolDiskFree);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BackendEntry(String id, String name, boolean available, String version, String message, boolean accelerated) {
    }

    public record ImageEntry(String name, long size, String kind, boolean linked) {
    }

    public record DownloadEntry(String id, String name, long done, long total, String state) {
    }

    /** Backends, images, downloads and (ops only) the server config, for the library/settings screens. */
    public record Library(List<BackendEntry> backends, List<ImageEntry> images, List<DownloadEntry> downloads,
                          List<String> hostWindows, boolean canEdit, boolean singleplayerHost, String configJson,
                          long hostRamMb, int hostCpus) implements CustomPacketPayload {
        public static final Type<Library> TYPE = makeType("library");
        public static final StreamCodec<FriendlyByteBuf, Library> CODEC = CustomPacketPayload.codec(Library::write, Library::read);

        static Library read(FriendlyByteBuf b) {
            List<BackendEntry> be = b.readList(x -> new BackendEntry(x.readUtf(), x.readUtf(), x.readBoolean(), x.readUtf(), x.readUtf(), x.readBoolean()));
            List<ImageEntry> im = b.readList(x -> new ImageEntry(x.readUtf(), x.readVarLong(), x.readUtf(), x.readBoolean()));
            List<DownloadEntry> dl = b.readList(x -> new DownloadEntry(x.readUtf(), x.readUtf(), x.readVarLong(), x.readLong(), x.readUtf()));
            List<String> win = b.readList(FriendlyByteBuf::readUtf);
            return new Library(be, im, dl, win, b.readBoolean(), b.readBoolean(), b.readUtf(1 << 16), b.readVarLong(), b.readVarInt());
        }

        void write(FriendlyByteBuf b) {
            b.writeCollection(backends, (x, e) -> {
                x.writeUtf(e.id());
                x.writeUtf(e.name());
                x.writeBoolean(e.available());
                x.writeUtf(e.version());
                x.writeUtf(e.message());
                x.writeBoolean(e.accelerated());
            });
            b.writeCollection(images, (x, e) -> {
                x.writeUtf(e.name());
                x.writeVarLong(e.size());
                x.writeUtf(e.kind());
                x.writeBoolean(e.linked());
            });
            b.writeCollection(downloads, (x, e) -> {
                x.writeUtf(e.id());
                x.writeUtf(e.name());
                x.writeVarLong(e.done());
                x.writeLong(e.total());
                x.writeUtf(e.state());
            });
            b.writeCollection(hostWindows, FriendlyByteBuf::writeUtf);
            b.writeBoolean(canEdit);
            b.writeBoolean(singleplayerHost);
            b.writeUtf(configJson, 1 << 16);
            b.writeVarLong(hostRamMb);
            b.writeVarInt(hostCpus);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Store checkout result. */
    public record OrderResult(boolean ok, Component message) implements CustomPacketPayload {
        public static final Type<OrderResult> TYPE = makeType("order_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, OrderResult> CODEC = StreamCodec.of(
                (b, v) -> {
                    b.writeBoolean(v.ok());
                    ComponentSerialization.TRUSTED_STREAM_CODEC.encode(b, v.message());
                },
                b -> new OrderResult(b.readBoolean(), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(b)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
