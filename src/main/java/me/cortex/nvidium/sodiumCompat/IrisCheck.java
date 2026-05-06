package me.cortex.nvidium.sodiumCompat;

import me.cortex.nvidium.Nvidium;
import net.neoforged.fml.loading.FMLLoader;

public class IrisCheck {
    private static final String IRIS_API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
    public static final boolean IRIS_LOADED = FMLLoader.getLoadingModList().getModFileById("iris") != null;

    public static boolean isShaderPackInUse() {
        if (!IRIS_LOADED) {
            return false;
        }

        try {
            var apiClass = Class.forName(IRIS_API_CLASS);
            var api = apiClass.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(apiClass.getMethod("isShaderPackInUse").invoke(api));
        } catch (ReflectiveOperationException | LinkageError e) {
            Nvidium.LOGGER.warn("Could not query Iris shader state; disabling the Acedium Sodiumized renderer", e);
            return true;
        }
    }

}
