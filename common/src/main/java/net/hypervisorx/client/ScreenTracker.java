package net.hypervisorx.client;

import net.hypervisorx.config.HvxClientConfig;
import net.hypervisorx.network.C2S;
import net.hypervisorx.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks which VM screens actually get drawn and keeps the server's watch list in sync. Only
 * visible screens get streamed, so the ones behind you don't eat bandwidth.
 */
public final class ScreenTracker {
    private static final Map<UUID, Long> SEEN = new HashMap<>();
    private static final Map<UUID, BlockPos> POSITIONS = new HashMap<>();
    private static final Set<UUID> WATCHING = new HashSet<>();
    private static long lastKeepAlive;

    private ScreenTracker() {
    }

    /** Render thread. A screen of {@code vm} just got drawn at {@code pos} (null for HUD/GUI). */
    public static void markVisible(UUID vm, BlockPos pos) {
        if (vm == null) return;
        SEEN.put(vm, System.currentTimeMillis());
        if (pos != null) {
            Minecraft mc = Minecraft.getInstance();
            BlockPos old = POSITIONS.get(vm);
            if (old == null || mc.player == null || old.distSqr(mc.player.blockPosition()) > pos.distSqr(mc.player.blockPosition())) {
                POSITIONS.put(vm, pos.immutable());
            }
        }
    }

    public static boolean withinRenderDistance(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        double d = HvxClientConfig.get().screenRenderDistance;
        return mc.player.blockPosition().distSqr(pos) <= d * d;
    }

    /** Closest known spot where this VM is on screen. Audio gets placed there. */
    public static BlockPos position(UUID vm) {
        return POSITIONS.get(vm);
    }

    /** Client tick. Sends watch adds/removes, plus a keep-alive every 5s. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        long now = System.currentTimeMillis();
        List<UUID> add = new ArrayList<>();
        List<UUID> remove = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : SEEN.entrySet()) {
            if (now - e.getValue() < 1500 && WATCHING.add(e.getKey())) add.add(e.getKey());
        }
        for (UUID id : new ArrayList<>(WATCHING)) {
            Long seen = SEEN.get(id);
            if (seen == null || now - seen >= 1500) {
                WATCHING.remove(id);
                remove.add(id);
                POSITIONS.remove(id);
            }
        }
        SEEN.values().removeIf(t -> now - t > 10_000);
        if (now - lastKeepAlive > 5000 && !WATCHING.isEmpty()) {
            lastKeepAlive = now;
            add.clear();
            add.addAll(WATCHING);
        }
        if (!add.isEmpty() || !remove.isEmpty()) {
            Services.PLATFORM.sendToServer(new C2S.Watch(add, remove));
        }
    }

    public static void clear() {
        SEEN.clear();
        POSITIONS.clear();
        WATCHING.clear();
    }
}
