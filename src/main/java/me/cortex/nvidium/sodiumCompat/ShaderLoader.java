package me.cortex.nvidium.sodiumCompat;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.config.StatisticsLoggingLevel;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Pattern;

public class ShaderLoader {
    private static final Pattern IMPORT_PATTERN = Pattern.compile("#import <(?<namespace>.*):(?<path>.*)>");

    public static String parse(Identifier path) {
        return parse(path, false);
    }

    public static String parse(Identifier path, boolean secondaryView) {
        var builder = ShaderConstants.builder();
        if (Nvidium.IS_DEBUG) {
            builder.add("DEBUG");
        }

        for (int i = 1; i <= Nvidium.config.statistics_level.ordinal(); i++) {
            builder.add("STATISTICS_"+StatisticsLoggingLevel.values()[i].name());
        }


        for (int i = 1; i <= (secondaryView ? 0 : Nvidium.config.translucency_sorting_level.ordinal()); i++) {
            builder.add("TRANSLUCENCY_SORTING_"+TranslucencySortingLevel.values()[i].name());
        }

        builder.add("TEXTURE_MAX_SCALE", String.valueOf(NvidiumCompactChunkVertex.TEXTURE_MAX_VALUE));
        builder.add("SUB_TEXEL_PRECISION", "256");

        return ShaderParser.parseShader(resolveImports(loadShaderSource(path)), builder.build()).src();
    }

    private static String resolveImports(String source) {
        var lines = new ArrayList<String>();

        try (var reader = new BufferedReader(new StringReader(source))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#import")) {
                    lines.add(line);
                    continue;
                }

                var matcher = IMPORT_PATTERN.matcher(line);
                if (!matcher.matches()) {
                    throw new IllegalArgumentException("Malformed shader import: " + line);
                }

                var importedPath = Identifier.of(matcher.group("namespace"), matcher.group("path"));
                lines.add(resolveImports(loadShaderSource(importedPath)));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to resolve shader imports", e);
        }

        return String.join("\n", lines);
    }

    private static String loadShaderSource(Identifier path) {
        var resourcePath = Identifier.of(path.getNamespace(), "shaders/" + path.getPath());
        var client = MinecraftClient.getInstance();

        if (client != null) {
            try (var input = client.getResourceManager().open(resourcePath)) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Fall back to a direct classpath lookup for early initialization paths.
            }
        }

        var classpathPath = "/assets/%s/shaders/%s".formatted(path.getNamespace(), path.getPath());
        try (var input = ShaderLoader.class.getResourceAsStream(classpathPath)) {
            if (input == null) {
                throw new RuntimeException("Shader not found: " + classpathPath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader source: " + classpathPath, e);
        }
    }
}
