package net.hypervisorx.vm.gpio;

import net.hypervisorx.backend.SerialLink;
import net.hypervisorx.vm.VmHost;
import net.hypervisorx.vm.VmSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Redstone GPIO PCIe card. It's just a line protocol on the guest's first serial port (COM1 / ttyS0),
 * so any language works: write {@code SET north 15}, read the reply. Commands run on the server
 * thread with the normal world access rules.
 *
 * <pre>
 * HELLO                      -> OK HYPERVISORX-GPIO 1.0
 * SET &lt;side|all&gt; &lt;0-15&gt;       drive redstone output
 * PULSE &lt;side&gt; &lt;0-15&gt; &lt;ticks&gt; output for a number of game ticks, then 0
 * GET &lt;side&gt;                 -> IN &lt;side&gt; &lt;level&gt;
 * GETALL                     -> IN down=0 up=0 north=0 south=0 west=0 east=0
 * WATCH on|off               push "EVT &lt;side&gt; &lt;level&gt;" on input changes
 * INV &lt;side&gt;                 -> INV &lt;side&gt; &lt;size&gt; &lt;slot&gt;:&lt;item&gt;:&lt;count&gt; ...
 * MOVE &lt;from&gt; &lt;to&gt; [count]   move items between adjacent inventories
 * TIME | WEATHER | BEEP [0-24]
 * </pre>
 * Sides: down up north south west east, or front back left right (relative to facing), top bottom.
 */
public final class GpioBridge {
    private long lastBeep = Long.MIN_VALUE;
    private static final String[] SIDE_NAMES = {"down", "up", "north", "south", "west", "east"};

    private final VmSession session;
    private final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<>();
    private final StringBuilder line = new StringBuilder();
    private volatile SerialLink link;
    private boolean watch;
    private final int[] lastInputs = {-1, -1, -1, -1, -1, -1};
    private final List<int[]> pulses = new ArrayList<>(); // {sideOrdinal, ticksLeft}

    public GpioBridge(VmSession session) {
        this.session = session;
    }

    public void attach(SerialLink serial) {
        this.link = serial;
        if (serial != null) serial.setReceiver(this::onBytes);
    }

    public boolean attached() {
        return link != null;
    }

    private void onBytes(byte[] data) {
        synchronized (line) {
            for (byte b : data) {
                char c = (char) (b & 0xFF);
                if (c == '\n' || c == '\r') {
                    if (!line.isEmpty()) {
                        if (commands.size() < 256) commands.add(line.toString());
                        line.setLength(0);
                    }
                } else if (line.length() < 512) {
                    line.append(c);
                }
            }
        }
    }

    private void reply(String s) {
        SerialLink l = link;
        if (l != null) l.send((s + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Server thread. Ends pulses, runs up to 32 queued commands, then sends EVT lines if WATCH is on. */
    public void tick(VmHost host) {
        if (link == null || host == null) return;
        for (Iterator<int[]> it = pulses.iterator(); it.hasNext(); ) {
            int[] p = it.next();
            if (--p[1] <= 0) {
                host.setGpioOutput(Direction.from3DDataValue(p[0]), 0);
                it.remove();
            }
        }
        int budget = 32;
        String cmd;
        while (budget-- > 0 && (cmd = commands.poll()) != null) {
            try {
                execute(host, cmd.trim());
            } catch (RuntimeException e) {
                reply("ERR " + e.getMessage());
            }
        }
        if (watch) {
            for (Direction d : Direction.values()) {
                int v = host.gpioInput(d);
                if (lastInputs[d.get3DDataValue()] != v) {
                    if (lastInputs[d.get3DDataValue()] >= 0) reply("EVT " + SIDE_NAMES[d.get3DDataValue()] + " " + v);
                    lastInputs[d.get3DDataValue()] = v;
                }
            }
        }
    }

    private Direction side(VmHost host, String s) {
        Direction facing = host.facing();
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "down", "bottom" -> Direction.DOWN;
            case "up", "top" -> Direction.UP;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            case "east" -> Direction.EAST;
            case "front" -> facing;
            case "back" -> facing.getOpposite();
            case "left" -> facing.getCounterClockWise();
            case "right" -> facing.getClockWise();
            default -> throw new IllegalArgumentException("unknown side '" + s + "'");
        };
    }

    private static int level(String s) {
        int v = Integer.parseInt(s.trim());
        if (v < 0 || v > 15) throw new IllegalArgumentException("level must be 0-15");
        return v;
    }

    private void execute(VmHost host, String cmd) {
        if (cmd.isEmpty()) return;
        String[] a = cmd.split("\\s+");
        String op = a[0].toUpperCase(Locale.ROOT);
        switch (op) {
            case "HELLO", "INFO" -> reply("OK HYPERVISORX-GPIO 1.0 SIDES down up north south west east front back left right");
            case "SET" -> {
                need(a, 3);
                int v = level(a[2]);
                if (a[1].equalsIgnoreCase("all")) {
                    for (Direction d : Direction.values()) host.setGpioOutput(d, v);
                } else {
                    host.setGpioOutput(side(host, a[1]), v);
                }
                reply("OK");
            }
            case "PULSE" -> {
                need(a, 4);
                Direction d = side(host, a[1]);
                int ticks = Math.max(1, Math.min(1200, Integer.parseInt(a[3])));
                host.setGpioOutput(d, level(a[2]));
                pulses.add(new int[]{d.get3DDataValue(), ticks});
                reply("OK");
            }
            case "GET" -> {
                need(a, 2);
                Direction d = side(host, a[1]);
                reply("IN " + SIDE_NAMES[d.get3DDataValue()] + " " + host.gpioInput(d));
            }
            case "GETALL" -> {
                StringBuilder sb = new StringBuilder("IN");
                for (Direction d : Direction.values()) sb.append(' ').append(SIDE_NAMES[d.get3DDataValue()]).append('=').append(host.gpioInput(d));
                reply(sb.toString());
            }
            case "OUT" -> {
                StringBuilder sb = new StringBuilder("OUT");
                for (Direction d : Direction.values()) sb.append(' ').append(SIDE_NAMES[d.get3DDataValue()]).append('=').append(host.gpioOutput(d));
                reply(sb.toString());
            }
            case "WATCH" -> {
                watch = a.length < 2 || !a[1].equalsIgnoreCase("off");
                java.util.Arrays.fill(lastInputs, -1);
                reply("OK");
            }
            case "INV" -> {
                need(a, 2);
                Direction d = side(host, a[1]);
                Container c = container(host, d);
                if (c == null) {
                    reply("ERR no inventory on " + SIDE_NAMES[d.get3DDataValue()]);
                    return;
                }
                StringBuilder sb = new StringBuilder("INV ").append(SIDE_NAMES[d.get3DDataValue()]).append(' ').append(c.getContainerSize());
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (!s.isEmpty()) sb.append(' ').append(i).append(':').append(BuiltInRegistries.ITEM.getKey(s.getItem())).append(':').append(s.getCount());
                }
                reply(sb.toString());
            }
            case "MOVE" -> {
                need(a, 3);
                Direction from = side(host, a[1]);
                Direction to = side(host, a[2]);
                int count = a.length > 3 ? Math.max(1, Math.min(64 * 27, Integer.parseInt(a[3]))) : 64;
                Container src = container(host, from);
                Container dst = container(host, to);
                if (src == null || dst == null) {
                    reply("ERR inventory missing");
                    return;
                }
                if (locked(host, from) || locked(host, to)) {
                    reply("ERR inventory is locked");
                    return;
                }
                // getOpposite() = the neighbour's face that points back at the computer
                reply("OK moved " + move(src, from.getOpposite(), dst, to.getOpposite(), count));
            }
            case "TIME" -> {
                ServerLevel lvl = (ServerLevel) host.hostLevel();
                reply("TIME " + (lvl.getDayTime() % 24000) + " " + lvl.getGameTime());
            }
            case "WEATHER" -> {
                ServerLevel lvl = (ServerLevel) host.hostLevel();
                reply("WEATHER " + (lvl.isThundering() ? "thunder" : lvl.isRaining() ? "rain" : "clear"));
            }
            case "BEEP" -> {
                if (host.hostLevel().getGameTime() - lastBeep < 2) {
                    reply("OK");
                    return;
                }
                lastBeep = host.hostLevel().getGameTime();
                int note = a.length > 1 ? Math.max(0, Math.min(24, Integer.parseInt(a[1]))) : 12;
                float pitch = (float) Math.pow(2.0, (note - 12) / 12.0);
                host.hostLevel().playSound(null, host.hostPos(), SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.BLOCKS, 1f, pitch);
                reply("OK");
            }
            default -> reply("ERR unknown command " + op);
        }
    }

    private static void need(String[] a, int n) {
        if (a.length < n) throw new IllegalArgumentException("missing arguments");
    }

    private static Container container(VmHost host, Direction d) {
        BlockPos p = host.hostPos().relative(d);
        BlockEntity be = host.hostLevel().getBlockEntity(p);
        if (be instanceof ChestBlockEntity) {
            BlockState st = host.hostLevel().getBlockState(p);
            if (st.getBlock() instanceof ChestBlock chest) {
                Container c = ChestBlock.getContainer(chest, st, host.hostLevel(), p, true);
                if (c != null) return c;
            }
        }
        return be instanceof Container c ? c : null;
    }

    /** Hands off anything with a vanilla lock ("lock"/"Lock" key on the block entity). */
    private static boolean locked(VmHost host, Direction d) {
        BlockEntity be = host.hostLevel().getBlockEntity(host.hostPos().relative(d));
        if (!(be instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity)) return false;
        net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata(host.hostLevel().registryAccess());
        return tag.contains("lock") || tag.contains("Lock");
    }

    private static int[] slots(Container c, Direction face) {
        if (c instanceof net.minecraft.world.WorldlyContainer w) return w.getSlotsForFace(face);
        int[] all = new int[c.getContainerSize()];
        for (int i = 0; i < all.length; i++) all[i] = i;
        return all;
    }

    /**
     * Hopper-style move. Sided inventories (furnaces, brewing stands...) keep their per-face slot rules,
     * and stuff that can't nest (shulker boxes) never goes into a shulker box.
     */
    private static int move(Container src, Direction srcFace, Container dst, Direction dstFace, int count) {
        int moved = 0;
        boolean dstIsShulker = dst instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
        int[] dstSlots = slots(dst, dstFace);
        for (int i : slots(src, srcFace)) {
            if (moved >= count) break;
            ItemStack s = src.getItem(i);
            if (s.isEmpty()) continue;
            if (src instanceof net.minecraft.world.WorldlyContainer w && !w.canTakeItemThroughFace(i, s, srcFace)) continue;
            if (dstIsShulker && !s.getItem().canFitInsideContainerItems()) continue;
            for (int j : dstSlots) {
                if (moved >= count || s.isEmpty()) break;
                if (!dst.canPlaceItem(j, s)) continue;
                if (dst instanceof net.minecraft.world.WorldlyContainer w && !w.canPlaceItemThroughFace(j, s, dstFace)) continue;
                ItemStack d = dst.getItem(j);
                int limit = Math.min(dst.getMaxStackSize(), s.getMaxStackSize());
                if (d.isEmpty()) {
                    int n = Math.min(Math.min(limit, s.getCount()), count - moved);
                    dst.setItem(j, s.split(n));
                    moved += n;
                } else if (ItemStack.isSameItemSameComponents(d, s) && d.getCount() < limit) {
                    int n = Math.min(Math.min(limit - d.getCount(), s.getCount()), count - moved);
                    d.grow(n);
                    s.shrink(n);
                    moved += n;
                }
            }
            src.setItem(i, s);
        }
        if (moved > 0) {
            src.setChanged();
            dst.setChanged();
        }
        return moved;
    }
}
