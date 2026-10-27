package redero.abyssaldepths.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Dust of a dune storm. Unlike vanilla dust it keeps the speed it is given, so the storm drives it
 * past fast; it flies through walls (no collisions, cheaper) and glows faintly so a storm can be seen
 * in the dark. Two kinds: small grains close by, and big faint puffs of haze that show the storm from
 * further away.
 */
public class StormDustParticle extends TextureSheetParticle {
    StormDustParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites, boolean haze) {
        super(level, x, y, z);
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.friction = 0.99F;
        this.lifetime = haze ? 40 + random.nextInt(30) : 20 + random.nextInt(25);
        this.quadSize = haze ? 1.5F + random.nextFloat() * 1.5F : 0.04F + random.nextFloat() * 0.06F;
        final float shade = 0.85F + random.nextFloat() * 0.15F;
        setColor(0.62F * shade, 0.57F * shade, 0.49F * shade);
        this.alpha = haze ? 0.14F : 0.85F;
        pickSprite(sprites);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    protected int getLightColor(float partialTick) {
        final int light = super.getLightColor(partialTick);
        return LightTexture.pack(Math.max(LightTexture.block(light), 7), LightTexture.sky(light));
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Providers {
        @SubscribeEvent
        public static void register(RegisterParticleProvidersEvent event) {
            event.registerSpriteSet(AbyssalDepths.STORM_DUST.get(), sprites -> provider(sprites, false));
            event.registerSpriteSet(AbyssalDepths.STORM_HAZE.get(), sprites -> provider(sprites, true));
        }

        private static ParticleProvider<SimpleParticleType> provider(SpriteSet sprites, boolean haze) {
            return (type, level, x, y, z, xd, yd, zd) -> new StormDustParticle(level, x, y, z, xd, yd, zd, sprites, haze);
        }
    }
}
