package net.hypervisorx.client;

import net.hypervisorx.client.audio.VmAudioPlayer;
import net.hypervisorx.client.render.ScreenTextureManager;
import net.hypervisorx.client.screen.FocusScreen;
import net.hypervisorx.client.screen.HvxScreens;
import net.hypervisorx.network.S2C;
import net.minecraft.client.Minecraft;

/** Client packet handlers (called from {@link net.hypervisorx.network.HvxNetwork}). */
public final class ClientNetwork {
    private ClientNetwork() {
    }

    /** Thread-safe, can run on the network thread. */
    public static void screenReset(S2C.ScreenReset p) {
        ScreenTextureManager.onReset(p);
    }

    /** Thread-safe, the decoding happens on the VM's decoder thread. */
    public static void screenTiles(S2C.ScreenTiles p) {
        ScreenTextureManager.onTiles(p);
    }

    public static void audio(S2C.Audio p) {
        VmAudioPlayer.onAudio(p);
    }

    public static void status(S2C.VmStatus p) {
        ClientVmCache.put(p);
    }

    public static void openFocus(S2C.OpenFocus p) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new FocusScreen(p.vm(), p.laptop())));
    }

    public static void hostInfo(S2C.HostInfo p) {
        ClientVmCache.hostInfo(p);
        HvxScreens.onHostInfo(p);
    }

    public static void library(S2C.Library p) {
        ClientVmCache.library(p);
        HvxScreens.onLibrary(p);
    }

    public static void orderResult(S2C.OrderResult p) {
        HvxScreens.onOrderResult(p);
    }
}
