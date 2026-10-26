package net.hypervisorx.item;

import net.hypervisorx.hardware.LaptopModel;
import net.hypervisorx.registry.HvxComponents;
import net.hypervisorx.server.LaptopService;
import net.hypervisorx.vm.VmHost;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * A laptop that actually works. Use it in the air (or hit the laptop key) to open it while walking,
 * use it on a surface to set it down open (it charges there), or sneak-use it on a computer with a
 * Wi-Fi card to pair it for in-game remote desktop.
 */
public class LaptopItem extends BlockItem {
    /** Battery lookup the client plugs in here (fed by VM status packets). */
    public static volatile Function<UUID, Integer> clientBattery = id -> -1;
    private final LaptopModel model;

    public LaptopItem(Block block, Properties properties, LaptopModel model) {
        super(block, properties);
        this.model = model;
    }

    public LaptopModel model() {
        return model;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) LaptopService.open(sp, hand);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player != null && player.isSecondaryUseActive() && ctx.getLevel().getBlockEntity(ctx.getClickedPos()) instanceof VmHost) {
            if (!ctx.getLevel().isClientSide && player instanceof ServerPlayer sp) {
                LaptopService.pair(sp, ctx.getHand(), ctx.getClickedPos());
            }
            return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
        }
        return super.useOn(ctx);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        var s = model.spec();
        tooltip.add(Component.translatable("hypervisorx.tooltip.laptop_spec", s.cores(), s.ramMb() / 1024, s.width(), s.height())
                .withStyle(ChatFormatting.GRAY));
        UUID vm = stack.get(HvxComponents.VM_LINK.get());
        if (vm != null) {
            int battery = clientBattery.apply(vm);
            if (battery >= 0) {
                ChatFormatting c = battery > 50 ? ChatFormatting.GREEN : battery > 15 ? ChatFormatting.YELLOW : ChatFormatting.RED;
                tooltip.add(Component.translatable("hypervisorx.tooltip.battery", battery).withStyle(c));
            }
        }
        if (stack.has(HvxComponents.REMOTE_TARGET.get())) {
            tooltip.add(Component.translatable("hypervisorx.tooltip.remote_paired").withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.translatable("hypervisorx.tooltip.laptop_help").withStyle(ChatFormatting.DARK_GRAY));
    }
}
