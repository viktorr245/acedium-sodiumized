package me.cortex.nvidium.mixin.minecraft;

import me.cortex.nvidium.util.RegionKeepDistance;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.util.TerrainFogState;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WorldRenderer.class)
public class MixinWorldRenderer {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F"), require = 0)
    private float redirectMax(float a, float b) {
        if (Nvidium.IS_ENABLED) {
            return a;
        }
        return Math.max(a, b);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;getViewDistance()F"))
    private float changeRD(GameRenderer instance) {
        float viewDistance = instance.getViewDistance();
        if (Nvidium.IS_ENABLED) {
            return RegionKeepDistance.fogDistance(Nvidium.config.region_keep_distance, viewDistance);
        }
        return viewDistance;
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/BackgroundRenderer;applyFog(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/BackgroundRenderer$FogType;FZF)V", ordinal = 1))
    private void captureTerrainFog(Camera camera, BackgroundRenderer.FogType fogType, float viewDistance, boolean thickFog, float tickDelta) {
        BackgroundRenderer.applyFog(camera, fogType, viewDistance, thickFog, tickDelta);
        if (Nvidium.IS_ENABLED) {
            TerrainFogState.captureFromRenderSystem();
        }
    }
}
