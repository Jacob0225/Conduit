package com.jacob0225.conduit.client.compat;

import com.jacob0225.conduit.client.ui.ConduitSettingsScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Exposes Conduit's client settings through Mod Menu's Configure button. */
public final class ConduitModMenuApi implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConduitSettingsScreen::new;
    }
}
