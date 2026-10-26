package net.hypervisorx.store;

import net.hypervisorx.config.HvxConfig;
import net.hypervisorx.entity.SupplyPodEntity;
import net.hypervisorx.network.C2S;
import net.hypervisorx.network.S2C;
import net.hypervisorx.platform.Services;
import net.hypervisorx.registry.HvxSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** server side checkout: checks the cart, takes the credits, sends the supply pod. */
public final class StoreService {
    private static final int MAX_ITEMS_PER_ORDER = 27 * 64;

    private StoreService() {
    }

    public static void order(ServerPlayer player, C2S.StoreOrder order) {
        HvxConfig cfg = HvxConfig.get();
        if (!cfg.storeEnabled) {
            reply(player, false, Component.translatable("hypervisorx.store.disabled"));
            return;
        }
        if (order.products().size() != order.quantities().size() || order.products().isEmpty() || order.products().size() > 64) {
            reply(player, false, Component.translatable("hypervisorx.store.empty"));
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos at = player.blockPosition();
        if (!level.canSeeSky(at.above()) && level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()) > at.getY() + 1) {
            reply(player, false, Component.translatable("hypervisorx.store.no_signal"));
            return;
        }
        long total = 0;
        int itemCount = 0;
        List<ItemStack> cargo = new ArrayList<>();
        for (int i = 0; i < order.products().size(); i++) {
            StoreCatalog.Product p = StoreCatalog.get(order.products().get(i));
            int qty = order.quantities().get(i);
            if (p == null || qty <= 0 || qty > 64) {
                reply(player, false, Component.translatable("hypervisorx.store.invalid"));
                return;
            }
            total += (long) p.price() * qty;
            for (StoreCatalog.Line line : p.contents()) {
                int n = line.count() * qty;
                itemCount += n;
                Item item = line.item().get();
                while (n > 0) {
                    ItemStack s = new ItemStack(item, Math.min(n, item.getDefaultMaxStackSize()));
                    n -= s.getCount();
                    cargo.add(s);
                }
            }
        }
        if (itemCount > MAX_ITEMS_PER_ORDER) {
            reply(player, false, Component.translatable("hypervisorx.store.too_big"));
            return;
        }
        boolean free = cfg.freeStore || player.getAbilities().instabuild;
        if (!free) {
            long available = credits(player.getInventory());
            if (available < total) {
                reply(player, false, Component.translatable("hypervisorx.store.too_poor", total, available));
                return;
            }
            pay(player.getInventory(), total);
        }
        double ox = (level.random.nextDouble() - 0.5) * 6;
        double oz = (level.random.nextDouble() - 0.5) * 6;
        double y = Math.min(level.getMaxBuildHeight() - 3, Math.max(at.getY() + 90, level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()) + 60));
        SupplyPodEntity pod = SupplyPodEntity.create(level, new Vec3(at.getX() + 0.5 + ox, y, at.getZ() + 0.5 + oz), cargo, player.getUUID());
        level.addFreshEntity(pod);
        level.playSound(null, at, HvxSounds.ORDER_CONFIRMED.get(), SoundSource.PLAYERS, 0.8f, 1f);
        reply(player, true, free ? Component.translatable("hypervisorx.store.ordered_free")
                : Component.translatable("hypervisorx.store.ordered", total));
    }

    private static void reply(ServerPlayer player, boolean ok, Component msg) {
        Services.PLATFORM.sendToPlayer(player, new S2C.OrderResult(ok, msg));
    }

    public static long credits(Inventory inv) {
        long sum = 0;
        for (Map.Entry<Item, Integer> c : StoreCatalog.CURRENCY) sum += (long) inv.countItem(c.getKey()) * c.getValue();
        return sum;
    }

    /** pays with the cheapest coins first. change goes back in the inventory (dropped if it's full). */
    private static void pay(Inventory inv, long amount) {
        long remaining = amount;
        for (Map.Entry<Item, Integer> c : StoreCatalog.CURRENCY) {
            if (remaining <= 0) break;
            int value = c.getValue();
            for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.is(c.getKey())) continue;
                long need = (remaining + value - 1) / value;
                int take = (int) Math.min(need, s.getCount());
                s.shrink(take);
                remaining -= (long) take * value;
            }
        }
        inv.setChanged();
    }
}
