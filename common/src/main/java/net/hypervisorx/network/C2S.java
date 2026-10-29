package net.hypervisorx.network;

import net.hypervisorx.HyperVisorX;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.UUID;

/** Client to server packets. Don't trust any of this, every handler re-checks permissions. */
public final class C2S {
    private C2S() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> makeType(String name) {
        return new CustomPacketPayload.Type<>(HyperVisorX.id(name));
    }

    /** Screens the client just started or stopped drawing. */
    public record Watch(List<UUID> add, List<UUID> remove) implements CustomPacketPayload {
        public static final Type<Watch> TYPE = makeType("watch");
        public static final StreamCodec<FriendlyByteBuf, Watch> CODEC = CustomPacketPayload.codec(Watch::write, Watch::new);

        Watch(FriendlyByteBuf b) {
            this(b.readList(x -> x.readUUID()), b.readList(x -> x.readUUID()));
        }

        void write(FriendlyByteBuf b) {
            b.writeCollection(add, (x, u) -> x.writeUUID(u));
            b.writeCollection(remove, (x, u) -> x.writeUUID(u));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Keyboard/mouse input going to a VM. */
    public record Input(UUID vm, int kind, int a, int b, int c, int d) implements CustomPacketPayload {
        public static final int KEY = 0, TEXT = 1, POINTER = 2, WHEEL = 3, CTRL_ALT_DEL = 4;
        public static final Type<Input> TYPE = makeType("input");
        public static final StreamCodec<FriendlyByteBuf, Input> CODEC = CustomPacketPayload.codec(Input::write, Input::new);

        Input(FriendlyByteBuf buf) {
            this(buf.readUUID(), buf.readByte(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        void write(FriendlyByteBuf buf) {
            buf.writeUUID(vm);
            buf.writeByte(kind);
            buf.writeVarInt(a);
            buf.writeVarInt(b);
            buf.writeVarInt(c);
            buf.writeVarInt(d);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Power and management buttons for one VM slot on a host block. */
    public record HostAction(BlockPos pos, int slot, int action) implements CustomPacketPayload {
        public static final int POWER_ON = 0, SHUTDOWN = 1, FORCE_OFF = 2, RESET = 3, SNAPSHOT = 4, CTRL_ALT_DEL = 5,
                ADD_VM = 6, DELETE_VM = 7, FOCUS = 8, EJECT = 9, INSERT_MEDIA = 10, REOPEN_MENU = 11, INSTALL_TOOLS = 12;
        public static final Type<HostAction> TYPE = makeType("host_action");
        public static final StreamCodec<FriendlyByteBuf, HostAction> CODEC = CustomPacketPayload.codec(HostAction::write, HostAction::new);

        HostAction(FriendlyByteBuf b) {
            this(b.readBlockPos(), b.readVarInt(), b.readVarInt());
        }

        void write(FriendlyByteBuf b) {
            b.writeBlockPos(pos);
            b.writeVarInt(slot);
            b.writeVarInt(action);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Power actions by VM id (focus screen, laptop HUD). Uses the same action codes as {@link HostAction}. */
    public record VmAction(UUID vm, int action) implements CustomPacketPayload {
        public static final Type<VmAction> TYPE = makeType("vm_action");
        public static final StreamCodec<FriendlyByteBuf, VmAction> CODEC = CustomPacketPayload.codec(VmAction::write, VmAction::new);

        VmAction(FriendlyByteBuf b) {
            this(b.readUUID(), b.readVarInt());
        }

        void write(FriendlyByteBuf b) {
            b.writeUUID(vm);
            b.writeVarInt(action);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** VM settings saved from the computer screen. */
    public record ConfigureVm(BlockPos pos, int slot, String name, String backend, String guestOs, String image, String target,
                              String access, int span, int cores, int ramMb, int diskGb, boolean efi, boolean guestTools)
            implements CustomPacketPayload {
        public static final Type<ConfigureVm> TYPE = makeType("configure_vm");
        public static final StreamCodec<FriendlyByteBuf, ConfigureVm> CODEC = CustomPacketPayload.codec(ConfigureVm::write, ConfigureVm::new);

        ConfigureVm(FriendlyByteBuf b) {
            this(b.readBlockPos(), b.readVarInt(), b.readUtf(64), b.readUtf(32), b.readUtf(32), b.readUtf(1024), b.readUtf(256),
                    b.readUtf(32), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readBoolean());
        }

        void write(FriendlyByteBuf b) {
            b.writeBlockPos(pos);
            b.writeVarInt(slot);
            b.writeUtf(name, 64);
            b.writeUtf(backend, 32);
            b.writeUtf(guestOs, 32);
            b.writeUtf(image, 1024);
            b.writeUtf(target, 256);
            b.writeUtf(access, 32);
            b.writeVarInt(span);
            b.writeVarInt(cores);
            b.writeVarInt(ramMb);
            b.writeVarInt(diskGb);
            b.writeBoolean(efi);
            b.writeBoolean(guestTools);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Trust or untrust a player on a VM. */
    public record Trust(BlockPos pos, int slot, String player, boolean add) implements CustomPacketPayload {
        public static final Type<Trust> TYPE = makeType("trust");
        public static final StreamCodec<FriendlyByteBuf, Trust> CODEC = CustomPacketPayload.codec(Trust::write, Trust::new);

        Trust(FriendlyByteBuf b) {
            this(b.readBlockPos(), b.readVarInt(), b.readUtf(32), b.readBoolean());
        }

        void write(FriendlyByteBuf b) {
            b.writeBlockPos(pos);
            b.writeVarInt(slot);
            b.writeUtf(player, 32);
            b.writeBoolean(add);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Laptop in hand: open, power, toggle remote desktop. */
    public record Laptop(boolean mainHand, int action) implements CustomPacketPayload {
        public static final int OPEN = 0, POWER = 1, TOGGLE_REMOTE = 2, FORCE_OFF = 3;
        public static final Type<Laptop> TYPE = makeType("laptop");
        public static final StreamCodec<FriendlyByteBuf, Laptop> CODEC = CustomPacketPayload.codec(Laptop::write, Laptop::new);

        Laptop(FriendlyByteBuf b) {
            this(b.readBoolean(), b.readVarInt());
        }

        void write(FriendlyByteBuf b) {
            b.writeBoolean(mainHand);
            b.writeVarInt(action);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Focus whatever VM the block at {@code pos} is showing (monitor, wall, keyboard, laptop). */
    public record RequestFocus(BlockPos pos) implements CustomPacketPayload {
        public static final Type<RequestFocus> TYPE = makeType("request_focus");
        public static final StreamCodec<FriendlyByteBuf, RequestFocus> CODEC = CustomPacketPayload.codec(RequestFocus::write, RequestFocus::new);

        RequestFocus(FriendlyByteBuf b) {
            this(b.readBlockPos());
        }

        void write(FriendlyByteBuf b) {
            b.writeBlockPos(pos);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** OrbitTech store checkout. */
    public record StoreOrder(List<String> products, List<Integer> quantities) implements CustomPacketPayload {
        public static final Type<StoreOrder> TYPE = makeType("store_order");
        public static final StreamCodec<FriendlyByteBuf, StoreOrder> CODEC = CustomPacketPayload.codec(StoreOrder::write, StoreOrder::new);

        StoreOrder(FriendlyByteBuf b) {
            this(b.readList(x -> x.readUtf(64)), b.readList(FriendlyByteBuf::readVarInt));
        }

        void write(FriendlyByteBuf b) {
            b.writeCollection(products, (x, s) -> x.writeUtf(s, 64));
            b.writeCollection(quantities, FriendlyByteBuf::writeVarInt);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** OS library and backend buttons. */
    public record LibraryAction(int action, String arg) implements CustomPacketPayload {
        public static final int REFRESH = 0, DOWNLOAD = 1, CANCEL = 2, IMPORT = 3, DELETE = 4, RESCAN = 5;
        public static final Type<LibraryAction> TYPE = makeType("library_action");
        public static final StreamCodec<FriendlyByteBuf, LibraryAction> CODEC = CustomPacketPayload.codec(LibraryAction::write, LibraryAction::new);

        LibraryAction(FriendlyByteBuf b) {
            this(b.readVarInt(), b.readUtf(4096));
        }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(action);
            b.writeUtf(arg, 4096);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Swaps out the whole server config (ops or the singleplayer host only). */
    public record ServerConfig(String json) implements CustomPacketPayload {
        public static final Type<ServerConfig> TYPE = makeType("server_config");
        public static final StreamCodec<FriendlyByteBuf, ServerConfig> CODEC = CustomPacketPayload.codec(ServerConfig::write, ServerConfig::new);

        ServerConfig(FriendlyByteBuf b) {
            this(b.readUtf(1 << 15));
        }

        void write(FriendlyByteBuf b) {
            b.writeUtf(json, 1 << 15);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
