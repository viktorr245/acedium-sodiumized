package me.cortex.nvidium;

import me.cortex.nvidium.config.NvidiumConfig;
import net.minecraft.util.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.fml.loading.FMLLoader;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;


//NOTE: with sodium async bfs, just reimplement the bfs dont try to convert sodiums bfs into async
@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT, modid = Nvidium.MOD_ID)
@Mod(Nvidium.MOD_ID)
public class Nvidium {
    public static final String MOD_ID = "acedium";
    public static String MOD_VERSION;
    public static final Logger LOGGER = LoggerFactory.getLogger("Acedium");
    public static boolean IS_COMPATIBLE = false;
    public static boolean IS_ENABLED = false;
    public static RendererDisableReason DISABLE_REASON = RendererDisableReason.NOT_CHECKED;
    public static boolean IS_DEBUG = System.getProperty("nvidium.isDebug", "false").equals("TRUE");
    public static boolean SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = true;
    public static boolean FORCE_DISABLE = false;

    public static NvidiumConfig config;

    public enum RendererDisableReason {
        NONE("renderer is enabled"),
        NOT_CHECKED("OpenGL capabilities have not been checked yet"),
        MISSING_GL_CAPABILITIES("required OpenGL capabilities are missing"),
        FORCE_DISABLED("disabled from the Sodium options screen"),
        IRIS_SHADER_PACK("Iris has a shader pack enabled");

        private final String message;

        RendererDisableReason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    public Nvidium() {

    }

    @SubscribeEvent
    public static void modLoading(FMLConstructModEvent event) {
        config = NvidiumConfig.loadOrCreate();
        config.save();
        MOD_VERSION = FMLLoader.getLoadingModList().getModFileById(MOD_ID).versionString();
    }

    //TODO: basicly have the terrain be a virtual geometry buffer
    // once it gets too full, start culling via a callback task system
    // which executes a task on the gpu and calls back once its done
    // use this to then do a rasterizing check on the terrain and remove
    // the oldest regions and sections

    //TODO: ADD LODS

    public static void checkSystemIsCapable() {
        var cap = GL.getCapabilities();
        var missingCapabilities = getMissingCapabilities(cap);
        IS_COMPATIBLE = missingCapabilities.isEmpty();
        if (!IS_COMPATIBLE) {
            LOGGER.warn("Acedium Sodiumized renderer disabled: missing OpenGL capabilities: {}", String.join(", ", missingCapabilities));
            setRendererState(false, RendererDisableReason.MISSING_GL_CAPABILITIES);
            return;
        }

        LOGGER.info("Acedium Sodiumized OpenGL requirements met");
        if (IS_COMPATIBLE && Util.getOperatingSystem() == Util.OperatingSystem.LINUX) {
            LOGGER.warn("Linux currently uses fallback terrain buffer due to driver inconsistencies, expect increased VRAM usage");
            SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = false;
        }

        setRendererState(true, RendererDisableReason.NONE);
    }

    public static void setRendererState(boolean enabled, RendererDisableReason disableReason) {
        var nextReason = enabled ? RendererDisableReason.NONE : disableReason;
        if (IS_ENABLED == enabled && DISABLE_REASON == nextReason) {
            return;
        }

        IS_ENABLED = enabled;
        DISABLE_REASON = nextReason;
        if (enabled) {
            LOGGER.info("Enabling Acedium Sodiumized renderer");
        } else {
            LOGGER.info("Acedium Sodiumized renderer disabled: {}", nextReason.message());
        }
    }

    private static List<String> getMissingCapabilities(GLCapabilities cap) {
        var missing = new ArrayList<String>();
        if (!cap.GL_NV_mesh_shader) {
            missing.add("GL_NV_mesh_shader");
        }
        if (!cap.GL_NV_uniform_buffer_unified_memory) {
            missing.add("GL_NV_uniform_buffer_unified_memory");
        }
        if (!cap.GL_NV_vertex_buffer_unified_memory) {
            missing.add("GL_NV_vertex_buffer_unified_memory");
        }
        if (!cap.GL_NV_representative_fragment_test) {
            missing.add("GL_NV_representative_fragment_test");
        }
        if (!cap.GL_ARB_sparse_buffer) {
            missing.add("GL_ARB_sparse_buffer");
        }
        if (!cap.GL_NV_bindless_multi_draw_indirect) {
            missing.add("GL_NV_bindless_multi_draw_indirect");
        }
        return missing;
    }
}
