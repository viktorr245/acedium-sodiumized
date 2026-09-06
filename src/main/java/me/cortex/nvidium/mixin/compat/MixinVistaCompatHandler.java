package me.cortex.nvidium.mixin.compat;

import me.cortex.nvidium.compat.SecondaryRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional Vista integration; does not load or require Vista on other installations. */
@Pseudo
@Mixin(targets = "net.mehvahdjukaar.vista.integration.CompatHandler", remap = false)
public abstract class MixinVistaCompatHandler {
    @Inject(method = "decorateRenderer(Ljava/lang/Runnable;)Ljava/lang/Runnable;", at = @At("RETURN"), cancellable = true)
    private static void isolateCamera(Runnable task, CallbackInfoReturnable<Runnable> cir) {
        Runnable decorated = cir.getReturnValue();
        cir.setReturnValue(() -> SecondaryRenderContext.run(decorated));
    }
}
