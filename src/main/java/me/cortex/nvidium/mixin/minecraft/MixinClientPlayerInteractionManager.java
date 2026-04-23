package me.cortex.nvidium.mixin.minecraft;

import me.cortex.nvidium.Nvidium;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public class MixinClientPlayerInteractionManager {
    @Shadow
    @Final
    private MinecraftClient client;

    @Shadow
    @Final
    private ClientPlayNetworkHandler networkHandler;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void skipSelectedSlotSyncWithoutPlayer(CallbackInfo ci) {
        if (!Nvidium.IS_ENABLED || this.client.world == null || this.client.player != null) {
            return;
        }

        // Keep the login connection alive until the local player exists.
        ClientConnection connection = this.networkHandler.getConnection();
        if (connection.isOpen()) {
            connection.tick();
        } else {
            connection.handleDisconnection();
        }
        ci.cancel();
    }
}
