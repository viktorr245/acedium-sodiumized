package me.cortex.nvidium.mixin.minecraft;

import me.cortex.nvidium.util.RegionKeepDistance;

import me.cortex.nvidium.Nvidium;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(BackgroundRenderer.class)

public class MixinBackgroundRenderer {
    @ModifyConstant(method = "applyFog", constant = @Constant(floatValue = 192.0F))
    private static float changeFog(float fog) {
        if (Nvidium.IS_ENABLED && RegionKeepDistance.retainsUnloadedSections(Nvidium.config.region_keep_distance, MinecraftClient.getInstance().options.getClampedViewDistance())) {
            return 9999999f;
        } else {
            return fog;
        }
    }
}
