package me.cortex.nvidium.config;

import me.cortex.nvidium.Nvidium;

public class NvidiumConfigStore {
    public static final NvidiumConfigStore INSTANCE = new NvidiumConfigStore();

    private NvidiumConfigStore() {
    }

    public NvidiumConfig getData() {
        return Nvidium.config;
    }

    public void save() {
        Nvidium.config.save();
    }
}
