package net.hypervisorx.fabric.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.hypervisorx.client.screen.SettingsScreen;

/** Mod Menu's "configure" button, opens our settings screen. */
public final class HvxModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SettingsScreen::new;
    }
}
