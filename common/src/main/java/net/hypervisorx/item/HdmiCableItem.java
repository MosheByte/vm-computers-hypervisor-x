package net.hypervisorx.item;

import net.hypervisorx.block.VideoWallBlock;
import net.hypervisorx.block.entity.DisplayBlockEntity;
import net.hypervisorx.block.entity.ServerRackBlockEntity;
import net.hypervisorx.block.entity.VideoWallBlockEntity;
import net.hypervisorx.registry.HvxComponents;
import net.hypervisorx.server.HostService;
import net.hypervisorx.vm.VmHost;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * Hooks a display up to a computer. Use it on the computer (or rack / laptop), then on the monitor
 * or video wall. On a rack, sneak-use cycles which of its VMs the cable carries.
 */
public class HdmiCableItem extends Item {
    public static final int MAX_DISTANCE = 32;

    public HdmiCableItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(ctx.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        ItemStack stack = ctx.getItemInHand();
        BlockPos pos = ctx.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);

        if (be instanceof ServerRackBlockEntity rack) {
            BlockPos controller = rack.controllerPos();
            int current = stack.getOrDefault(HvxComponents.LINK_SLOT.get(), 0);
            int slot = player.isSecondaryUseActive() && controller.equals(stack.get(HvxComponents.LINK_SOURCE.get()))
                    ? (current + 1) % ServerRackBlockEntity.MAX_VMS : current;
            stack.set(HvxComponents.LINK_SOURCE.get(), controller);
            stack.set(HvxComponents.LINK_SLOT.get(), slot);
            player.displayClientMessage(Component.translatable("hypervisorx.msg.cable_source_rack", slot + 1).withStyle(ChatFormatting.AQUA), true);
            level.playSound(null, pos, SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 0.8f, 1.4f);
            return InteractionResult.CONSUME;
        }
        if (be instanceof VmHost) {
            stack.set(HvxComponents.LINK_SOURCE.get(), pos.immutable());
            stack.remove(HvxComponents.LINK_SLOT.get());
            player.displayClientMessage(Component.translatable("hypervisorx.msg.cable_source").withStyle(ChatFormatting.AQUA), true);
            level.playSound(null, pos, SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 0.8f, 1.4f);
            return InteractionResult.CONSUME;
        }
        if (be instanceof DisplayBlockEntity display) {
            DisplayBlockEntity target = display;
            if (display instanceof VideoWallBlockEntity) {
                VideoWallBlockEntity m = VideoWallBlock.master(level, pos);
                if (m != null) target = m;
            }
            if (!HostService.mayEditDisplay(player, target)) return InteractionResult.FAIL;
            BlockPos src = stack.get(HvxComponents.LINK_SOURCE.get());
            if (src == null) {
                if (player.isSecondaryUseActive() && target.linkHost() != null) {
                    target.unlink();
                    player.displayClientMessage(Component.translatable("hypervisorx.msg.cable_unlinked"), true);
                    return InteractionResult.CONSUME;
                }
                player.displayClientMessage(Component.translatable("hypervisorx.msg.cable_no_source").withStyle(ChatFormatting.YELLOW), true);
                return InteractionResult.FAIL;
            }
            if (src.distSqr(pos) > (double) MAX_DISTANCE * MAX_DISTANCE) {
                player.displayClientMessage(Component.translatable("hypervisorx.msg.cable_too_far", MAX_DISTANCE).withStyle(ChatFormatting.RED), true);
                return InteractionResult.FAIL;
            }
            if (!(level.getBlockEntity(src) instanceof VmHost)) {
                stack.remove(HvxComponents.LINK_SOURCE.get());
                player.displayClientMessage(Component.translatable("hypervisorx.msg.cable_source_gone").withStyle(ChatFormatting.RED), true);
                return InteractionResult.FAIL;
            }
            target.link(src, stack.getOrDefault(HvxComponents.LINK_SLOT.get(), 0));
            stack.remove(HvxComponents.LINK_SOURCE.get());
            stack.remove(HvxComponents.LINK_SLOT.get());
            player.displayClientMessage(Component.translatable("hypervisorx.msg.cable_linked").withStyle(ChatFormatting.GREEN), true);
            level.playSound(null, pos, SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 0.8f, 1.8f);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(HvxComponents.LINK_SOURCE.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        BlockPos src = stack.get(HvxComponents.LINK_SOURCE.get());
        if (src != null) {
            tooltip.add(Component.translatable("hypervisorx.tooltip.cable_from", src.getX(), src.getY(), src.getZ()).withStyle(ChatFormatting.AQUA));
        } else {
            tooltip.add(Component.translatable("hypervisorx.tooltip.cable_help").withStyle(ChatFormatting.GRAY));
        }
    }
}
