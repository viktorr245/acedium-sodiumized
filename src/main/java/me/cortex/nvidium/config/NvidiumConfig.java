package me.cortex.nvidium.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import me.cortex.nvidium.Nvidium;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class NvidiumConfig {
    //The options
    public int extra_rd = 100;
    public boolean enable_temporal_coherence = true;
    public int max_geometry_memory = 2048;
    public boolean automatic_memory = true;

    public boolean async_bfs = true;

    public int region_keep_distance = 32;

    public TranslucencySortingLevel translucency_sorting_level = TranslucencySortingLevel.QUADS;

    public StatisticsLoggingLevel statistics_level = StatisticsLoggingLevel.NONE;


    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    private NvidiumConfig() {}
    public static NvidiumConfig loadOrCreate() {
        var path = getConfigPath();
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                var config = GSON.fromJson(reader, NvidiumConfig.class);
                if (config != null) {
                    return config;
                }
                Nvidium.LOGGER.warn("Config file {} was empty, using defaults", path);
            } catch (JsonParseException e) {
                Nvidium.LOGGER.error("Could not parse config file {}, using defaults", path, e);
            } catch (IOException e) {
                Nvidium.LOGGER.error("Could not read config file {}, using defaults", path, e);
            }
        }
        return new NvidiumConfig();
    }

    public void save() {
        var path = getConfigPath();
        Path tempPath = null;
        try {
            var directory = path.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
                tempPath = Files.createTempFile(directory, path.getFileName().toString(), ".tmp");
            } else {
                tempPath = Files.createTempFile(path.getFileName().toString(), ".tmp");
            }

            Files.writeString(tempPath, GSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Nvidium.LOGGER.error("Failed to write config file {}", path, e);
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException e) {
                    Nvidium.LOGGER.warn("Failed to remove temporary config file {}", tempPath, e);
                }
            }
        }
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve("nvidium-config.json");
    }
}
