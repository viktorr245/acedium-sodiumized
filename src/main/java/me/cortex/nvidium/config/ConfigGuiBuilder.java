package me.cortex.nvidium.config;

import me.cortex.nvidium.util.RegionKeepDistance;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.mixin.sodium.SodiumWorldRendererAccessor;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class ConfigGuiBuilder implements ConfigEntryPoint {
    private static final Identifier REGION_KEEP_DISTANCE = id("region_keep_distance");
    private static final Identifier ENABLE_TEMPORAL_COHERENCE = id("enable_temporal_coherence");
    private static final Identifier ASYNC_BFS = id("async_bfs");
    private static final Identifier AUTOMATIC_MEMORY_LIMIT = id("automatic_memory_limit");
    private static final Identifier MAX_GPU_MEMORY = id("max_gpu_memory");
    private static final Identifier TRANSLUCENCY_SORTING = id("translucency_sorting");
    private static final Identifier STATISTICS_LEVEL = id("statistics_level");

    private final NvidiumConfigStore store = NvidiumConfigStore.INSTANCE;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
                .setNonTintedIcon(Identifier.of("acedium", "acedium-logo.png"))
                .addPage(builder.createOptionPage()
                        .setName(Text.translatable("nvidium.options.pages.nvidium"))
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(builder.createBooleanOption(id("force_disable"))
                                        .setName(Text.literal("Disable Acedium Sodiumized"))
                                        .setTooltip(Text.literal("Used to disable Acedium Sodiumized (does not save, will re-enable after a relaunch)"))
                                        .setStorageHandler(() -> {})
                                        .setBinding(value -> Nvidium.FORCE_DISABLE = value, () -> Nvidium.FORCE_DISABLE)
                                        .setDefaultValue(false)
                                        .setImpact(OptionImpact.HIGH)
                                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)))
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(builder.createIntegerOption(REGION_KEEP_DISTANCE)
                                        .setName(Text.translatable("nvidium.options.region_keep_distance.name"))
                                        .setTooltip(Text.translatable("nvidium.options.region_keep_distance.tooltip"))
                                        .setStorageHandler(this.store::save)
                                        .setBinding(value -> this.store.getData().region_keep_distance = value, () -> this.store.getData().region_keep_distance)
                                        .setDefaultValue(32)
                                        .setRange(32, 256, 1)
                                        .setValueFormatter(value -> Text.literal(value == RegionKeepDistance.KEEP_ALL ? "Keep All" : (!RegionKeepDistance.retainsUnloadedSections(value, MinecraftClient.getInstance().options.getClampedViewDistance()) ? "Vanilla" : value + " chunks")))
                                        .setEnabled(Nvidium.IS_ENABLED)
                                        .setImpact(OptionImpact.VARIES)
                                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD))
                                .addOption(builder.createBooleanOption(ENABLE_TEMPORAL_COHERENCE)
                                        .setName(Text.translatable("nvidium.options.enable_temporal_coherence.name"))
                                        .setTooltip(Text.translatable("nvidium.options.enable_temporal_coherence.tooltip"))
                                        .setStorageHandler(this.store::save)
                                        .setBinding(value -> this.store.getData().enable_temporal_coherence = value, () -> this.store.getData().enable_temporal_coherence)
                                        .setDefaultValue(true)
                                        .setEnabled(Nvidium.IS_ENABLED)
                                        .setImpact(OptionImpact.MEDIUM)
                                        .setApplyHook(ConfigGuiBuilder::reloadNvidiumShaders))
                                .addOption(builder.createBooleanOption(ASYNC_BFS)
                                        .setName(Text.translatable("nvidium.options.async_bfs.name"))
                                        .setTooltip(Text.translatable("nvidium.options.async_bfs.tooltip"))
                                        .setStorageHandler(this.store::save)
                                        .setBinding(value -> this.store.getData().async_bfs = value, () -> this.store.getData().async_bfs)
                                        .setDefaultValue(true)
                                        .setEnabled(Nvidium.IS_ENABLED)
                                        .setImpact(OptionImpact.HIGH)
                                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                                        .setApplyHook(ConfigGuiBuilder::reloadNvidiumShaders))
                                .addOption(builder.createBooleanOption(AUTOMATIC_MEMORY_LIMIT)
                                        .setName(Text.translatable("nvidium.options.automatic_memory_limit.name"))
                                        .setTooltip(Text.translatable("nvidium.options.automatic_memory_limit.tooltip"))
                                        .setStorageHandler(this.store::save)
                                        .setBinding(value -> this.store.getData().automatic_memory = value, () -> this.store.getData().automatic_memory)
                                        .setDefaultValue(true)
                                        .setEnabled(Nvidium.IS_ENABLED)
                                        .setImpact(OptionImpact.VARIES)
                                        .setApplyHook(ConfigGuiBuilder::reloadNvidiumShaders))
                                .addOption(builder.createIntegerOption(MAX_GPU_MEMORY)
                                        .setName(Text.translatable("nvidium.options.max_gpu_memory.name"))
                                        .setTooltip(Text.translatable("nvidium.options.max_gpu_memory.tooltip"))
                                        .setStorageHandler(this.store::save)
                                        .setBinding(value -> this.store.getData().max_geometry_memory = value, () -> this.store.getData().max_geometry_memory)
                                        .setDefaultValue(2048)
                                        .setRange(2048, 32768, 512)
                                        .setValueFormatter(value -> Text.translatable("nvidium.options.mb", value))
                                        .setEnabledProvider(state -> Nvidium.IS_ENABLED && !state.readBooleanOption(AUTOMATIC_MEMORY_LIMIT), AUTOMATIC_MEMORY_LIMIT)
                                        .setImpact(OptionImpact.VARIES)
                                        .setFlags(Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER ? new OptionFlag[0] : new OptionFlag[]{OptionFlag.REQUIRES_RENDERER_RELOAD})
                                        .setApplyHook(ConfigGuiBuilder::reloadNvidiumShaders))
                                .addOption(builder.createEnumOption(TRANSLUCENCY_SORTING, TranslucencySortingLevel.class)
                                        .setName(Text.translatable("nvidium.options.translucency_sorting.name"))
                                        .setTooltip(Text.translatable("nvidium.options.translucency_sorting.tooltip"))
                                        .setStorageHandler(this.store::save)
                                        .setBinding(value -> this.store.getData().translucency_sorting_level = value, () -> this.store.getData().translucency_sorting_level)
                                        .setDefaultValue(TranslucencySortingLevel.QUADS)
                                        .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                                                Text.translatable("nvidium.options.translucency_sorting.none"),
                                                Text.translatable("nvidium.options.translucency_sorting.sections"),
                                                Text.translatable("nvidium.options.translucency_sorting.quads")))
                                        .setEnabled(Nvidium.IS_ENABLED)
                                        .setImpact(OptionImpact.MEDIUM)
                                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                                        .setApplyHook(ConfigGuiBuilder::reloadNvidiumShaders))
                                .addOption(builder.createEnumOption(STATISTICS_LEVEL, StatisticsLoggingLevel.class)
                                        .setName(Text.translatable("nvidium.options.statistics_level.name"))
                                        .setTooltip(Text.translatable("nvidium.options.statistics_level.tooltip"))
                                        .setStorageHandler(this.store::save)
                                        .setBinding(value -> this.store.getData().statistics_level = value, () -> this.store.getData().statistics_level)
                                        .setDefaultValue(StatisticsLoggingLevel.NONE)
                                        .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                                                Text.translatable("nvidium.options.statistics_level.none"),
                                                Text.translatable("nvidium.options.statistics_level.frustum"),
                                                Text.translatable("nvidium.options.statistics_level.regions"),
                                                Text.translatable("nvidium.options.statistics_level.sections"),
                                                Text.translatable("nvidium.options.statistics_level.quads")))
                                        .setEnabled(Nvidium.IS_ENABLED)
                                        .setImpact(OptionImpact.LOW)
                                        .setApplyHook(ConfigGuiBuilder::reloadNvidiumShaders))));
    }

    private static Identifier id(String path) {
        return Identifier.of("acedium", path);
    }

    private static void reloadNvidiumShaders(ConfigState state) {
        if (MinecraftClient.getInstance().world == null) {
            return;
        }

        SodiumWorldRenderer swr = SodiumWorldRenderer.instanceNullable();
        if (swr == null) {
            return;
        }

        NvidiumWorldRenderer pipeline = ((INvidiumWorldRendererGetter) ((SodiumWorldRendererAccessor) swr).getRenderSectionManager()).getRenderer();
        if (pipeline != null) {
            pipeline.reloadShaders();
        }
    }
}
