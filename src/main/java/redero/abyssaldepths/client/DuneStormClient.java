package redero.abyssaldepths.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;

/**
 * What a player sees of a storm ({@link redero.abyssaldepths.DuneStorm}): dust driven fast through the
 * air in one direction wherever the silt dunes are, so a storm can be seen coming from outside them;
 * inside, the fog closes in to a few blocks and turns sandy and the wind howls. Rises and settles over
 * ten seconds; fades in and out when walking into or out of the dunes.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class DuneStormClient {
    private static final RandomSource RANDOM = RandomSource.create();
    private static int remaining;
    private static float storm, inDunes, strength;
    /** Share of the area around the player that is dunes, while a storm blows. */
    private static float near;
    private static double windX = 1.0, windZ;
    private static Howl howl;

    private DuneStormClient() {
    }

    static void set(int ticks) {
        if (remaining <= 0 && ticks > 0) {
            final double a = RANDOM.nextDouble() * 2 * Math.PI; // a new storm, a new wind direction
            windX = Math.cos(a);
            windZ = Math.sin(a);
        }
        remaining = ticks;
    }

    /** 0 .. 1: how much of a storm the camera is in right now. */
    public static float strength() {
        return strength;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc.player;
        final ClientLevel level = mc.level;
        if (player == null || level == null) {
            remaining = 0;
            storm = inDunes = strength = 0.0F;
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        if (remaining > 0) {
            remaining--;
        }
        storm += Mth.clamp((remaining > 0 ? 1.0F : 0.0F) - storm, -0.005F, 0.005F);
        final boolean dunes = level.getBiome(player.blockPosition()).is(AbyssalDepths.SILT_DUNES);
        inDunes += Mth.clamp((dunes ? 1.0F : 0.0F) - inDunes, -0.02F, 0.02F);
        final float blowing = ClientConfig.duneStorms() ? storm * LayerAmbience.depth() : 0.0F;
        strength = blowing * inDunes;
        if (blowing <= 0.01F) {
            near = 0.0F;
            return;
        }
        // dust driven fast through the air wherever the dunes are around: close by small grains, further
        // out (up to 56 blocks, also from outside the dunes) big puffs of haze that show the storm coming
        final int count = (int) (blowing * 40 * ClientConfig.particles());
        int hits = 0;
        for (int i = 0; i < count; i++) {
            final boolean far = (i & 1) == 1;
            final double a = RANDOM.nextDouble() * 2 * Math.PI, d = far ? 16 + RANDOM.nextDouble() * 40 : RANDOM.nextDouble() * 16;
            final double x = player.getX() + Math.cos(a) * d - windX * 8, z = player.getZ() + Math.sin(a) * d - windZ * 8;
            final double y = player.getEyeY() + (RANDOM.nextDouble() - 0.6) * (far ? 16 : 10);
            final BlockPos pos = BlockPos.containing(x, y, z);
            if (!level.getBiome(pos).is(AbyssalDepths.SILT_DUNES)) {
                continue;
            }
            hits++;
            if (!level.getBlockState(pos).isAir()) {
                continue;
            }
            final double speed = far ? 0.6 + RANDOM.nextDouble() * 0.3 : 1.0 + RANDOM.nextDouble() * 0.7;
            level.addParticle(far ? AbyssalDepths.STORM_HAZE.get() : AbyssalDepths.STORM_DUST.get(), x, y, z,
                    windX * speed, (RANDOM.nextDouble() - 0.5) * 0.08, windZ * speed);
        }
        // the wind is loudest inside the dunes, heard faintly from nearby
        near += Mth.clamp((count == 0 ? 0.0F : hits / (float) count) - near, -0.02F, 0.02F);
        if (Math.max(strength, 0.4F * blowing * near) > 0.01F && (howl == null || !mc.getSoundManager().isActive(howl))) {
            // not "stopped" only: a howl blocked or cut off (the shadow) never says so, and would never come back
            howl = new Howl();
            mc.getSoundManager().play(howl);
        }
    }

    /** The wind of the storm, loud as long as the storm is. */
    private static final class Howl extends AbstractTickableSoundInstance {
        Howl() {
            super(AbyssalDepths.DUNE_STORM.get(), SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0.01F;
        }

        @Override
        public void tick() {
            final float loud = Math.max(strength, 0.4F * storm * near);
            volume = Math.max(0.01F, loud);
            if (loud <= 0.01F) {
                stop();
            }
        }
    }
}
