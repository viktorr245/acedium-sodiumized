package me.cortex.nvidium.config;

import me.cortex.nvidium.Nvidium;
import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;

public class NvidiumConfigStore implements OptionStorage<NvidiumConfig> {
    public static final NvidiumConfigStore INSTANCE = new NvidiumConfigStore();

    private NvidiumConfigStore() {
    }

    @Override
    public NvidiumConfig getData() {
        return Nvidium.config;
    }

    @Override
    public void save() {
        Nvidium.config.save();
    }
}
