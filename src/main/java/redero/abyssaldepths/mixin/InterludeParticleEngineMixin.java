package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import redero.abyssaldepths.client.SlowedParticle;

/**
 * Around every particle's tick (here rather than in {@code Particle.tick}, which many particles replace
 * without calling it): the Interlude slowing of {@link InterludeParticleMixin}.
 */
@Mixin(ParticleEngine.class)
public abstract class InterludeParticleEngineMixin {
    @Inject(method = "tickParticle", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$slowBefore(Particle particle, CallbackInfo ci) {
        if (((SlowedParticle) particle).abyssaldepths$skip()) {
            ci.cancel();
        }
    }

    @Inject(method = "tickParticle", at = @At("RETURN"))
    private void abyssaldepths$slowAfter(Particle particle, CallbackInfo ci) {
        ((SlowedParticle) particle).abyssaldepths$after();
    }
}
