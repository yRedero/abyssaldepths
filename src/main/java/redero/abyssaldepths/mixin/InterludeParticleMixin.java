package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.artifact.InterludeField;
import redero.abyssaldepths.client.SlowedParticle;

/**
 * Particles in an Interlude field run at its pace (client config): they get only some of their ticks, and,
 * as with creatures, the one real step is spread over the ticks around it so they glide instead of
 * stopping and jumping. Called around every particle tick by {@link InterludeParticleEngineMixin}.
 */
@Mixin(Particle.class)
public abstract class InterludeParticleMixin implements SlowedParticle {
    @Shadow
    protected double x, y, z, xo, yo, zo;
    @Shadow
    protected ClientLevel level;

    /** The rest of the last real step, still to be taken, and whether the running tick is a real one. */
    @Unique
    private double abyssaldepths$restX, abyssaldepths$restY, abyssaldepths$restZ;
    @Unique
    private float abyssaldepths$pace = 1.0F;

    @Override
    public boolean abyssaldepths$skip() {
        abyssaldepths$pace = ClientConfig.interludeParticles() ? InterludeField.clientPaceAt(level, x, y, z) : 1.0F;
        if (abyssaldepths$pace >= 1.0F) {
            return false;
        }
        final long t = level.getGameTime();
        if (Math.floor((t + 1) * abyssaldepths$pace) > Math.floor(t * abyssaldepths$pace)) {
            return false; // a real tick
        }
        xo = x;
        yo = y;
        zo = z;
        x += abyssaldepths$restX;
        y += abyssaldepths$restY;
        z += abyssaldepths$restZ;
        abyssaldepths$restX = abyssaldepths$restY = abyssaldepths$restZ = 0.0;
        return true;
    }

    @Override
    public void abyssaldepths$after() {
        if (abyssaldepths$pace >= 1.0F) {
            return;
        }
        final double dx = x - xo, dy = y - yo, dz = z - zo;
        x = xo + dx * abyssaldepths$pace;
        y = yo + dy * abyssaldepths$pace;
        z = zo + dz * abyssaldepths$pace;
        abyssaldepths$restX = dx - (x - xo);
        abyssaldepths$restY = dy - (y - yo);
        abyssaldepths$restZ = dz - (z - zo);
    }
}
