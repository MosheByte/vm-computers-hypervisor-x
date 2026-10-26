package net.hypervisorx.item;

import net.hypervisorx.ClientBridge;
import net.hypervisorx.registry.HvxSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** OrbitTech Store tablet: order parts from the satellite. Sneak-use opens the OS library. */
public class StoreTabletItem extends Item {
    public StoreTabletItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            if (player.isSecondaryUseActive()) ClientBridge.openLibrary();
            else ClientBridge.openStore();
        } else {
            level.playSound(null, player.blockPosition(), HvxSounds.TABLET_BEEP.get(), net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.2f);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hypervisorx.orbittech_tablet.tip1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.hypervisorx.orbittech_tablet.tip2").withStyle(ChatFormatting.DARK_GRAY));
    }
}
