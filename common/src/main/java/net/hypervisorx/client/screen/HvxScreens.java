package net.hypervisorx.client.screen;

import net.hypervisorx.network.S2C;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

/** hands server replies to whichever HyperVisor X screen is open */
public final class HvxScreens {
    private HvxScreens() {
    }

    public static void onHostInfo(S2C.HostInfo info) {
        if (Minecraft.getInstance().screen instanceof ComputerScreen cs) cs.onHostInfo(info);
    }

    public static void onLibrary(S2C.Library lib) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof ComputerScreen cs) cs.onLibrary(lib);
        if (screen instanceof SettingsScreen ss) ss.onLibrary();
        if (screen instanceof LibraryScreen ls) ls.onLibrary(lib);
    }

    public static void onOrderResult(S2C.OrderResult r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof StoreScreen ss) {
            ss.onOrderResult(r);
        } else if (mc.player != null) {
            mc.player.displayClientMessage(r.message().copy().withStyle(r.ok() ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        }
    }
}
