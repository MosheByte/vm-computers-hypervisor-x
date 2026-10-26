package net.hypervisorx.client.screen;

import net.hypervisorx.client.ClientVmCache;
import net.hypervisorx.client.HvxKeys;
import net.hypervisorx.client.ScreenTracker;
import net.hypervisorx.client.render.ScreenTexture;
import net.hypervisorx.client.render.ScreenTextureManager;
import net.hypervisorx.config.HvxClientConfig;
import net.hypervisorx.item.LaptopItem;
import net.hypervisorx.network.S2C;
import net.hypervisorx.registry.HvxComponents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** picture-in-picture of a running laptop while you hold it, so you can keep an eye on it while walking around */
public final class LaptopHud {
    private LaptopHud() {
    }

    public static UUID heldLaptopVm(Minecraft mc) {
        if (mc.player == null) return null;
        for (ItemStack stack : new ItemStack[]{mc.player.getMainHandItem(), mc.player.getOffhandItem()}) {
            if (stack.getItem() instanceof LaptopItem) {
                UUID remote = stack.get(HvxComponents.REMOTE_TARGET.get());
                return remote != null ? remote : stack.get(HvxComponents.VM_LINK.get());
            }
        }
        return null;
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        HvxClientConfig cfg = HvxClientConfig.get();
        if (!cfg.laptopPictureInPicture || mc.screen != null || mc.options.hideGui) return;
        UUID vm = heldLaptopVm(mc);
        if (vm == null) return;
        S2C.VmStatus status = ClientVmCache.get(vm);
        if (status == null || !ClientVmCache.isRunning(vm)) return;
        ScreenTracker.markVisible(vm, null);
        ScreenTexture tex = ScreenTextureManager.get(vm);
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int w = Math.round(sw * cfg.pipScale);
        float aspect = tex != null && tex.height() > 0 ? tex.width() / (float) tex.height() : 16f / 9f;
        int h = Math.round(w / aspect);
        int margin = 6;
        int x = (cfg.pipCorner == 1 || cfg.pipCorner == 3) ? margin : sw - w - margin;
        int y = cfg.pipCorner >= 2 ? sh - h - margin - 40 : margin + 12;
        g.fill(x - 3, y - 13, x + w + 3, y + h + 3, 0xD0101319);
        g.fill(x - 3, y + h + 2, x + w + 3, y + h + 3, UiKit.ACCENT);
        g.drawString(mc.font, UiKit.ellipsize(mc.font, status.name(), w - 60), x, y - 10, UiKit.TEXT, false);
        if (status.batteryPercent() >= 0) {
            int pct = status.batteryPercent();
            int color = pct > 50 ? UiKit.OK : pct > 15 ? UiKit.WARN : UiKit.ERROR;
            String b = "⚡" + pct + "%";
            g.drawString(mc.font, b, x + w - mc.font.width(b), y - 10, color, false);
        }
        if (tex != null && tex.hasContent()) {
            GuiTex.draw(g, tex.location(), x, y, x + w, y + h);
        } else {
            g.fill(x, y, x + w, y + h, 0xFF000000);
            g.drawCenteredString(mc.font, Component.translatable("hypervisorx.screen.booting"), x + w / 2, y + h / 2 - 4, UiKit.MUTED);
        }
        g.drawString(mc.font, Component.translatable("hypervisorx.hud.open", HvxKeys.FOCUS.getTranslatedKeyMessage()), x, y + h + 5, 0xA0FFFFFF, false);
    }
}
