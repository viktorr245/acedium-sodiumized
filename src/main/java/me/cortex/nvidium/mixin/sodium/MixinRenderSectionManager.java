package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.compat.SecondaryRenderContext;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.managers.AsyncOcclusionTracker;
import me.cortex.nvidium.sodiumCompat.*;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.render.Camera;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.OcclusionSectionCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

@Mixin(value = RenderSectionManager.class, remap = false, priority = 1500)
public class MixinRenderSectionManager implements INvidiumWorldRendererGetter {
    @Unique
    private static final String SODIUM_API_SPRITE_UTIL = "net.caffeinemc.mods.sodium.api.texture.SpriteUtil";
    @Unique
    private static final String SODIUM_CLIENT_SPRITE_UTIL = "net.caffeinemc.mods.sodium.client.render.texture.SpriteUtil";

    @Shadow @Final private RenderRegionManager regions;
    @Shadow @Final private Long2ReferenceMap<RenderSection> sectionByPosition;
    @Shadow private @NotNull Map<TaskQueueType, ArrayDeque<RenderSection>> taskLists;
    @Shadow @Final private int renderDistance;
    @Shadow @Final private SortBehavior sortBehavior;
    @Shadow private SectionCollector sectionCollector;
    @Unique private NvidiumWorldRenderer renderer;
    @Unique private Viewport viewport;

    @Unique
    private static void updateNvidiumIsEnabled() {
        if (!Nvidium.IS_COMPATIBLE) {
            Nvidium.setRendererState(false, Nvidium.RendererDisableReason.MISSING_GL_CAPABILITIES);
        } else if (Nvidium.FORCE_DISABLE) {
            Nvidium.setRendererState(false, Nvidium.RendererDisableReason.FORCE_DISABLED);
        } else if (IrisCheck.isShaderPackInUse()) {
            Nvidium.setRendererState(false, Nvidium.RendererDisableReason.IRIS_SHADER_PACK);
        } else {
            Nvidium.setRendererState(true, Nvidium.RendererDisableReason.NONE);
        }
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void init(ClientWorld world, int renderDistance, SortBehavior sortBehavior, CommandList commandList, CallbackInfo ci) {
        updateNvidiumIsEnabled();
        if (Nvidium.IS_ENABLED) {
            if (renderer != null)
                throw new IllegalStateException("Cannot have multiple world renderers");
            renderer = new NvidiumWorldRenderer(Nvidium.config.async_bfs?new AsyncOcclusionTracker(renderDistance, sectionByPosition, world, taskLists, sortBehavior):null);
            ((INvidiumWorldRendererSetter)regions).setWorldRenderer(renderer);
        }
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkBuilder;<init>(Lnet/minecraft/client/world/ClientWorld;Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V", remap = true), index = 1)
    private ChunkVertexType modifyVertexType(ChunkVertexType vertexType) {
        updateNvidiumIsEnabled();
        if (Nvidium.IS_ENABLED) {
            return NvidiumCompactChunkVertex.INSTANCE;
        }
        return vertexType;
    }


    @Inject(method = "destroy", at = @At("TAIL"))
    private void destroy(CallbackInfo ci) {
        if (renderer != null) {
            ((INvidiumWorldRendererSetter)regions).setWorldRenderer(null);
            renderer.delete();
            renderer = null;
        }
    }

    @Redirect(method = "onSectionRemoved", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;delete()V"))
    private void deleteSection(RenderSection section) {
        if (renderer != null) {
            if (Nvidium.config.region_keep_distance == 32) {
                renderer.deleteSection(section);
            }
        }
        section.delete();
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void trackViewport(Camera camera, Viewport viewport, boolean spectator, CallbackInfo ci) {
        SecondaryRenderContext.isolate(this, () -> {
            Viewport previous = this.viewport;
            return () -> this.viewport = previous;
        });
        this.viewport = viewport;
    }

    @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true)
    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z, CallbackInfo ci) {
        if (Nvidium.IS_ENABLED && renderer != null) {
            ci.cancel();
            pass.startDrawing();
            if (pass == DefaultTerrainRenderPasses.SOLID) {
                renderer.renderFrame(viewport, matrices, x, y, z);
            } else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
                renderer.renderTranslucent();
            }
            pass.endDrawing();
        }
    }

    @Inject(method = "getDebugStrings", at = @At("HEAD"), cancellable = true)
    private void redirectDebug(CallbackInfoReturnable<Collection<String>> cir) {
        if (Nvidium.IS_ENABLED && renderer != null) {
            var debugStrings = new ArrayList<String>();
            renderer.addDebugInfo(debugStrings);
            cir.setReturnValue(debugStrings);
            cir.cancel();
        }
    }

    @Override
    public NvidiumWorldRenderer getRenderer() {
        return renderer;
    }

    @Inject(method = "createTerrainRenderList", at = @At("HEAD"), cancellable = true)
    private void redirectTerrainRenderList(Camera camera, Viewport viewport, int frame, boolean spectator, CallbackInfoReturnable<Boolean> cir) {
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            var importantRebuildQueueType = SodiumClientMod.options().performance.chunkBuildDeferMode.getImportantRebuildQueueType();
            var importantSortQueueType = this.sortBehavior.getDeferMode().getImportantRebuildQueueType();
            this.sectionCollector = new OcclusionSectionCollector(frame, importantRebuildQueueType, importantSortQueueType);
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @Redirect(method = "submitSectionTask", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;clearPendingUpdate()V"))
    private void injectEnqueueFalse(RenderSection instance) {
        instance.clearPendingUpdate();
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            //We need to reset the fact that its been submitted to the rebuild queue from the build queue
            ((IRenderSectionExtension) instance).isSubmittedRebuild(false);
        }
    }

    @Unique
    private boolean isSectionVisibleBfs(RenderSection section) {
        //The reason why this is done is that since the bfs search is async it could be updating the frame counter with the next frame
        // while some sections that arnt updated/ticked yet still have the old frame id
        if (renderer == null) {
            return false;
        }
        int delta = Math.abs(section.getLastVisibleFrame() - renderer.getAsyncFrameId());
        return delta <= 1;
    }

    @Unique
    private static void markSpriteActive(Sprite sprite) {
        try {
            var apiClass = Class.forName(SODIUM_API_SPRITE_UTIL);
            var instance = apiClass.getField("INSTANCE").get(null);
            apiClass.getMethod("markSpriteActive", Sprite.class).invoke(instance, sprite);
            return;
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            var clientClass = Class.forName(SODIUM_CLIENT_SPRITE_UTIL);
            clientClass.getMethod("markSpriteActive", Sprite.class).invoke(null, sprite);
            return;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to mark Sodium animated sprites as active", e);
        }
    }

    @Inject(method = "isSectionVisible", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;getLastVisibleFrame()I", shift = At.Shift.BEFORE), cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD)
    private void redirectIsSectionVisible(int x, int y, int z, CallbackInfoReturnable<Boolean> cir, RenderSection render) {
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            cir.setReturnValue(isSectionVisibleBfs(render));
        }
    }

    @Inject(method = "tickVisibleRenders", at = @At("HEAD"), cancellable = true)
    private void redirectAnimatedSpriteUpdates(CallbackInfo ci) {
        if (Nvidium.IS_ENABLED && renderer != null && Nvidium.config.async_bfs && SodiumClientMod.options().performance.animateOnlyVisibleTextures) {
            ci.cancel();
            var sprites = renderer.getAnimatedSpriteSet();
            if (sprites == null) {
                return;
            }
            for (var sprite : sprites) {
                markSpriteActive(sprite);
            }
        }
    }

    @Inject(method = "getVisibleChunkCount", at = @At("HEAD"), cancellable = true)
    private void injectVisibilityCount(CallbackInfoReturnable<Integer> cir) {
        if (Nvidium.IS_ENABLED && renderer != null && Nvidium.config.async_bfs) {
            cir.setReturnValue(this.renderer.getAsyncBfsVisibilityCount());
        }
    }
}
