package me.cortex.nvidium.mixin.minecraft;

import me.cortex.nvidium.Nvidium;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Shadow
    private MinecraftClient client;

    private boolean shouldSkipJoinTimeRender() {
        return Nvidium.IS_ENABLED
                && this.client.world != null
                && (this.client.player == null || this.client.getCameraEntity() == null);
    }

    @Inject(method = "getFarPlaneDistance", at = @At("HEAD"), cancellable = true)
    public void method_32796(CallbackInfoReturnable<Float> cir) {
        if (Nvidium.IS_ENABLED) {
            cir.setReturnValue(16 * 512f);
            cir.cancel();
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void skipRenderWithoutCamera(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (this.shouldSkipJoinTimeRender()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderWorld", at = @At("HEAD"), cancellable = true)
    private void skipWorldRenderWithoutCamera(RenderTickCounter tickCounter, CallbackInfo ci) {
        if (this.shouldSkipJoinTimeRender()) {
            ci.cancel();
        }
    }
}
