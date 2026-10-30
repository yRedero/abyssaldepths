package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import redero.abyssaldepths.client.ShadeView;

/** No particles are added while {@link ShadeView#quietParticles} (what this client does not see makes them). */
@Mixin(ParticleEngine.class)
public abstract class ShadeQuietParticlesMixin {
    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$quiet(Particle particle, CallbackInfo ci) {
        if (ShadeView.quietParticles) {
            ci.cancel();
        }
    }
}
