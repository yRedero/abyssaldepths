package redero.abyssaldepths.client;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.DeepPressure;

/**
 * Client side of {@link DeepPressure}: tunnel vision growing with the pressure, muffled hearing,
 * heartbeat and ear ringing, and in the last stage hallucinations (footsteps behind the player,
 * mobs and digging that are not there, a shadow at the edge of the view that vanishes when looked at).
 * Everything follows the effect timer, so it also fades away again while the timer counts down.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class DeepPressureClient {
    private static final ResourceLocation VIGNETTE =
            ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "textures/misc/deep_pressure_vignette.png");
    private static final RandomSource RANDOM = RandomSource.create();
    /** Client-only entity id of the shadow, far away from the ids the server hands out. */
    private static final int SHADOW_ID = -0x5EEDBEEF;

    private static int heartbeatCooldown;
    private static Ringing ringing;
    private static int hallucinationCooldown = 600;
    /** Delayed hallucination sounds, run once the player's tick count reaches {@code at}. */
    private static final ArrayDeque<Delayed> SCHEDULED = new ArrayDeque<>();
    private static EnderMan shadow;
    private static int shadowAge, shadowSeen;

    private DeepPressureClient() {
    }

    private record Delayed(int at, Runnable action) {
    }

    // ---- tunnel vision ----------------------------------------------------------------

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layers {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS,
                    ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "deep_pressure_vignette"),
                    (graphics, delta) -> {
                        final LocalPlayer player = Minecraft.getInstance().player;
                        if (player == null || DeepPressure.stage(player) == 0) {
                            return;
                        }
                        // noticeable right away, closing in up to the last stage
                        final float alpha = 0.3F + 0.65F * DeepPressure.pressure(player);
                        RenderSystem.disableDepthTest();
                        RenderSystem.depthMask(false);
                        RenderSystem.enableBlend();
                        RenderSystem.defaultBlendFunc();
                        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
                        final int w = graphics.guiWidth(), h = graphics.guiHeight();
                        graphics.blit(VIGNETTE, 0, 0, -90, 0.0F, 0.0F, w, h, w, h);
                        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
                        RenderSystem.disableBlend();
                        RenderSystem.depthMask(true);
                        RenderSystem.enableDepthTest();
                    });
        }
    }

    // ---- muffled hearing ----------------------------------------------------------------

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        final LocalPlayer player = Minecraft.getInstance().player;
        final SoundInstance sound = event.getSound();
        if (player == null || sound == null || sound instanceof TickableSoundInstance || DeepPressure.stage(player) < 2) {
            return; // tickable sounds must stay what they are to keep ticking
        }
        if (sound.getSource() == SoundSource.MASTER || sound.getSource() == SoundSource.MUSIC
                || sound.getLocation().getNamespace().equals(AbyssalDepths.MODID)) {
            return;
        }
        // from 5 minutes on, down to half the volume at full pressure
        event.setSound(new Muffled(sound, 1.0F - 0.5F * DeepPressure.pressure(player)));
    }

    /** Plays another sound at a lower volume. */
    private record Muffled(SoundInstance inner, float factor) implements SoundInstance {
        @Override public ResourceLocation getLocation() { return inner.getLocation(); }
        @Override public WeighedSoundEvents resolve(SoundManager manager) { return inner.resolve(manager); }
        @Override public Sound getSound() { return inner.getSound(); }
        @Override public SoundSource getSource() { return inner.getSource(); }
        @Override public boolean isLooping() { return inner.isLooping(); }
        @Override public boolean isRelative() { return inner.isRelative(); }
        @Override public int getDelay() { return inner.getDelay(); }
        @Override public float getVolume() { return inner.getVolume() * factor; }
        @Override public float getPitch() { return inner.getPitch(); }
        @Override public double getX() { return inner.getX(); }
        @Override public double getY() { return inner.getY(); }
        @Override public double getZ() { return inner.getZ(); }
        @Override public Attenuation getAttenuation() { return inner.getAttenuation(); }
        @Override public boolean canStartSilent() { return inner.canStartSilent(); }
        @Override public boolean canPlaySound() { return inner.canPlaySound(); }
        @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary library, Sound sound, boolean looping) {
            return inner.getStream(library, sound, looping);
        }
    }

    // ---- heartbeat, ringing, hallucinations ------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            removeShadow(mc.level);
            SCHEDULED.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        final int stage = DeepPressure.stage(player);

        if (stage >= 2 && --heartbeatCooldown <= 0) {
            heartbeatCooldown = stage == 2 ? 30 : stage == 3 ? 22 : 15; // faster with the pressure
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.WARDEN_HEARTBEAT, 0.9F, 0.2F + 0.1F * stage));
        }

        if (stage >= 3 && (ringing == null || ringing.isStopped())) {
            ringing = new Ringing(player);
            mc.getSoundManager().play(ringing);
        }

        tickScheduled();
        tickShadow(mc.level, player);
        if (stage >= 4 && --hallucinationCooldown <= 0) {
            hallucinationCooldown = 600 + RANDOM.nextInt(900); // every 30 to 75 seconds
            hallucinate(mc.level, player);
        } else if (stage < 4) {
            removeShadow(mc.level);
        }
    }

    /** Thin, high ringing, louder with the pressure, fades out below stage 3. */
    private static final class Ringing extends AbstractTickableSoundInstance {
        private final LocalPlayer player;
        private float fade;

        Ringing(LocalPlayer player) {
            super(AbyssalDepths.PRESSURE_RINGING.get(), SoundSource.MASTER, SoundInstance.createUnseededRandom());
            this.player = player;
            this.looping = true;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0.01F;
        }

        @Override
        public void tick() {
            final boolean on = !player.isRemoved() && DeepPressure.stage(player) >= 3;
            fade = Mth.clamp(fade + (on ? 0.01F : -0.02F), 0.0F, 1.0F);
            final float strength = 0.4F + 0.6F * (DeepPressure.pressure(player) - 0.66F) / 0.34F;
            volume = Math.max(0.01F, fade * Mth.clamp(strength, 0.4F, 1.0F));
            if (fade <= 0.0F && !on) {
                stop();
            }
        }
    }

    private static void hallucinate(ClientLevel level, LocalPlayer player) {
        final Vec3 look = player.getViewVector(1.0F).multiply(1.0, 0.0, 1.0).normalize();
        switch (RANDOM.nextInt(4)) {
            case 0 -> { // footsteps coming closer from behind
                for (int i = 0; i < 5; i++) {
                    final double dist = 11.0 - i * 1.6;
                    final Vec3 at = player.position().subtract(look.scale(dist));
                    schedule(i * 8, () -> playAt(SoundEvents.STONE_STEP, SoundSource.HOSTILE, at, 0.45F, 0.9F));
                }
            }
            case 1 -> { // a mob somewhere out in the dark
                final SoundEvent[] mobs = {SoundEvents.ZOMBIE_AMBIENT, SoundEvents.SKELETON_AMBIENT,
                        SoundEvents.SPIDER_AMBIENT, SoundEvents.ENDERMAN_AMBIENT};
                playAt(mobs[RANDOM.nextInt(mobs.length)], SoundSource.HOSTILE, around(player, 14.0, 24.0), 0.8F,
                        0.8F + RANDOM.nextFloat() * 0.2F);
            }
            case 2 -> { // someone digging nearby
                final Vec3 at = around(player, 6.0, 10.0);
                for (int i = 0; i < 3; i++) {
                    schedule(i * 14, () -> playAt(SoundEvents.STONE_BREAK, SoundSource.BLOCKS, at, 0.5F, 0.8F));
                }
            }
            default -> spawnShadow(level, player, look);
        }
    }

    private static Vec3 around(LocalPlayer player, double min, double max) {
        final double angle = RANDOM.nextDouble() * 2.0 * Math.PI, dist = min + RANDOM.nextDouble() * (max - min);
        return player.position().add(Math.cos(angle) * dist, 1.0, Math.sin(angle) * dist);
    }

    private static void playAt(SoundEvent sound, SoundSource source, Vec3 at, float volume, float pitch) {
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sound, source, volume, pitch, RANDOM, at.x, at.y, at.z));
    }

    private static void schedule(int delay, Runnable action) {
        SCHEDULED.add(new Delayed(Minecraft.getInstance().player.tickCount + delay, action));
    }

    private static void tickScheduled() {
        final int now = Minecraft.getInstance().player.tickCount;
        SCHEDULED.removeIf(d -> {
            if (now < d.at()) {
                return false;
            }
            d.action().run();
            return true;
        });
    }

    /** A dark figure standing on the floor off to the side, just at the edge of the view. */
    private static void spawnShadow(ClientLevel level, LocalPlayer player, Vec3 look) {
        removeShadow(level);
        final double side = Math.toRadians((RANDOM.nextBoolean() ? 1 : -1) * (65 + RANDOM.nextInt(30)));
        final double cos = Math.cos(side), sin = Math.sin(side);
        final Vec3 dir = new Vec3(look.x * cos - look.z * sin, 0.0, look.x * sin + look.z * cos);
        final Vec3 target = player.position().add(dir.scale(14.0 + RANDOM.nextDouble() * 8.0));
        final BlockPos ground = findGround(level, BlockPos.containing(target), player.getBlockY());
        if (ground == null) {
            return;
        }
        final EnderMan figure = EntityType.ENDERMAN.create(level);
        if (figure == null) {
            return;
        }
        final double dx = player.getX() - (ground.getX() + 0.5), dz = player.getZ() - (ground.getZ() + 0.5);
        final float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        figure.setId(SHADOW_ID);
        figure.setSilent(true);
        figure.setNoAi(true);
        figure.moveTo(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, yaw, 0.0F);
        figure.setYHeadRot(yaw);
        figure.yBodyRot = yaw;
        level.addEntity(figure);
        shadow = figure;
        shadowAge = 0;
        shadowSeen = 0;
    }

    /** First air block above solid ground near the given height, with room for a 3 block figure. */
    private static BlockPos findGround(ClientLevel level, BlockPos around, int y) {
        for (int dy = 6; dy >= -10; dy--) {
            final BlockPos feet = new BlockPos(around.getX(), y + dy, around.getZ());
            if (!level.getBlockState(feet.below()).isAir() && level.getBlockState(feet).isAir()
                    && level.getBlockState(feet.above()).isAir() && level.getBlockState(feet.above(2)).isAir()) {
                return feet;
            }
        }
        return null;
    }

    /** Gone when looked at for a moment, or after a few seconds anyway. */
    private static void tickShadow(ClientLevel level, LocalPlayer player) {
        if (shadow == null) {
            return;
        }
        if (shadow.level() != level || ++shadowAge > 160) {
            removeShadow(level);
            return;
        }
        final Vec3 to = shadow.position().add(0.0, 1.5, 0.0).subtract(player.getEyePosition()).normalize();
        if (player.getViewVector(1.0F).dot(to) > 0.96 && ++shadowSeen > 3) {
            removeShadow(level);
        }
    }

    private static void removeShadow(ClientLevel level) {
        if (shadow != null && level != null && shadow.level() == level) {
            level.removeEntity(SHADOW_ID, Entity.RemovalReason.DISCARDED);
        }
        shadow = null;
    }
}
