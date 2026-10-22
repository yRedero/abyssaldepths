package redero.abyssaldepths.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.TunnelLayer;

/**
 * Quiet cave ambience in the thalan layer: a low loop that fades in and out with the layer, plus a
 * water drip somewhere around the player now and then. The sounds are set in assets/abyssaldepths/sounds.json.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class ThalanAmbience {
    private static final RandomSource RANDOM = RandomSource.create();
    private static Loop loop;
    private static int dripCooldown = 200;

    private ThalanAmbience() {
    }

    static boolean inLayer(LocalPlayer player) {
        return player.getBlockY() <= TunnelLayer.LAYER_TOP_Y;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc.player;
        if (player == null || mc.isPaused() || !inLayer(player)) {
            return;
        }
        if (loop == null || loop.isStopped()) {
            loop = new Loop(player);
            mc.getSoundManager().play(loop);
        }
        if (--dripCooldown <= 0) {
            dripCooldown = 160 + RANDOM.nextInt(400); // every 8 to 28 seconds
            final double angle = RANDOM.nextDouble() * 2.0 * Math.PI, dist = 4.0 + RANDOM.nextDouble() * 10.0;
            mc.getSoundManager().play(new SimpleSoundInstance(AbyssalDepths.AMBIENT_DRIP.get(), SoundSource.AMBIENT,
                    1.0F, 0.8F + RANDOM.nextFloat() * 0.3F, RANDOM, player.getX() + Math.cos(angle) * dist,
                    player.getEyeY() + 2.0 + RANDOM.nextDouble() * 6.0, player.getZ() + Math.sin(angle) * dist));
        }
    }

    /** The loop, fading in over 3 seconds when entering the layer and out over 3 seconds when leaving. */
    private static final class Loop extends AbstractTickableSoundInstance {
        private static final float FADE_STEP = 1.0F / 60.0F;
        private final LocalPlayer player;
        private float fade;

        Loop(LocalPlayer player) {
            super(AbyssalDepths.AMBIENT_LOOP.get(), SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.player = player;
            this.looping = true;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0.01F;
        }

        @Override
        public void tick() {
            if (player.isRemoved()) {
                stop();
                return;
            }
            fade = Math.max(0.0F, Math.min(1.0F, fade + (inLayer(player) ? FADE_STEP : -FADE_STEP)));
            volume = Math.max(0.01F, fade);
            if (fade <= 0.0F) {
                stop();
            }
        }
    }
}
