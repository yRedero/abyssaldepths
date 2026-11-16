package redero.abyssaldepths.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Telling that the worm is coming, and from where (the server tells each player where its nearest part and its head
 * are, from up to 400 blocks away). The nearer, the more of it (blocks from its nearest part):
 * <ul>
 * <li>from 300: a deep growl from the direction of its head, more often the nearer (faint until within 40);</li>
 * <li>from 150: dust and grit trickling from the rock above, thicker and thicker;</li>
 * <li>from 120: stone crackling from the direction of its nearest part, faint and deep at first, then louder and more often;</li>
 * <li>from 60: stones breaking in the rock round the player, more and louder the nearer;</li>
 * <li>from 90: pebbles skittering on the ground round the player;</li>
 * <li>from 70: the ground trembling, harder the nearer;</li>
 * <li>within 18 (really close): the ground heaving, stone grinding all round, the edges of the view closing in dark.</li>
 * </ul>
 * Hunted by it, the player hears their own heart pounding (faster the nearer it is) and the edges of the view darken
 * and pulse with it.
 * <p>
 * Far off the sounds are played from its direction, set off as far as their loudness lets them carry.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class WormRumble {
    private static final double HEAR = 400.0, GROWL = 300.0, DUST = 150.0, CRACK = 120.0, BREAK = 60.0, PEBBLES = 90.0, TREMBLE = 70.0, NEAR = 40.0,
            CLOSE = 18.0, HUNT = 64.0;
    private static final SoundEvent[] BREAKS = {SoundEvents.STONE_BREAK, SoundEvents.DEEPSLATE_BREAK, SoundEvents.TUFF_BREAK,
            SoundEvents.GRAVEL_BREAK, SoundEvents.DEEPSLATE_BRICKS_BREAK, SoundEvents.BASALT_BREAK};
    private static final SoundEvent[] CRACKS = {SoundEvents.STONE_BREAK, SoundEvents.DEEPSLATE_BREAK, SoundEvents.TUFF_BREAK,
            SoundEvents.DEEPSLATE_HIT, SoundEvents.POINTED_DRIPSTONE_LAND};
    private static final SoundEvent[] GROWLS = {SoundEvents.RAVAGER_AMBIENT, SoundEvents.RAVAGER_STUNNED, SoundEvents.HOGLIN_ANGRY,
            SoundEvents.POLAR_BEAR_WARNING};

    /** Where it was last heard (its nearest part, its head), how far, and when; whether it hunts the player, how near. */
    private static Vec3 at, head;
    private static double dist = Double.MAX_VALUE, huntDist = Double.MAX_VALUE;
    private static long heardAt, huntedUntil, nextCrack, nextGrowl, nextBeat, nextBreak;
    private static float shake, dark, darkO, beat;

    private WormRumble() {
    }

    /** From the server: "x/y/z/distance/headX/headY/headZ". */
    public static void near(String data) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        final String[] p = data.split("/");
        at = new Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
        dist = Double.parseDouble(p[3]);
        head = new Vec3(Double.parseDouble(p[4]), Double.parseDouble(p[5]), Double.parseDouble(p[6]));
        heardAt = mc.level.getGameTime();
    }

    /** From the server: it hunts this player, so far from them. */
    public static void chased(double distance) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        huntDist = distance;
        huntedUntil = mc.level.getGameTime() + 15;
    }

    /**
     * Plays a sound from the direction of a place, as loud as asked (0..1): set off in that direction, the quieter the
     * further (a sound from the real place would not carry that far), but never nearer than the place itself.
     */
    private static void play(ClientLevel level, Vec3 ear, Vec3 to, SoundEvent sound, float loud, float pitch) {
        final Vec3 d = to.subtract(ear);
        final double off = Math.min(d.length(), Math.max(6.0, 40.0 * (1.0 - Mth.clamp(loud, 0, 1))));
        final Vec3 from = d.lengthSqr() < 1.0E-4 ? to : ear.add(d.normalize().scale(off));
        level.playLocalSound(from.x, from.y, from.z, sound, SoundSource.HOSTILE, 2.5F, pitch, false); // (heard to 40 blocks)
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        darkO = dark;
        if (level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        final long now = level.getGameTime();
        if (now - heardAt > 40) {
            dist = Double.MAX_VALUE;
        }
        final boolean hunted = now < huntedUntil;
        shake = Math.max(0.0F, shake - 0.05F);
        beat = Math.max(0.0F, beat - 0.08F);
        float wantDark = 0.0F;
        final Vec3 ear = mc.player.getEyePosition();
        if (dist < HEAR) {
            final float close = (float) (1.0 - dist / HEAR);
            // crackling: only once it is near enough to be heard through the rock, faint and deep at first
            if (dist < CRACK && now >= nextCrack) {
                final float c = (float) (1.0 - dist / CRACK);
                nextCrack = now + (long) Mth.lerp(c * c, 80.0F, 5.0F) + level.random.nextInt(8);
                play(level, ear, at, CRACKS[level.random.nextInt(CRACKS.length)], 0.03F + 0.9F * c * c * c, 0.3F + 0.3F * c + level.random.nextFloat() * 0.15F);
            }
            // growls, from its head: faint unless it is really near
            if (dist < GROWL && now >= nextGrowl) {
                final float g = (float) (1.0 - dist / GROWL);
                nextGrowl = now + (long) Mth.lerp(g, 240.0F, 35.0F) + level.random.nextInt(50);
                final float loud = dist < NEAR ? 0.6F + 0.4F * (float) (1.0 - dist / NEAR) : 0.05F + 0.25F * g * g;
                play(level, ear, head, GROWLS[level.random.nextInt(GROWLS.length)], loud, 0.3F + level.random.nextFloat() * 0.15F);
            }
            // the rock round the player giving: stones breaking, more and louder the nearer (the plainest sign of how near)
            if (dist < BREAK && now >= nextBreak) {
                final float b = (float) (1.0 - dist / BREAK);
                nextBreak = now + (long) Mth.lerp(b * b, 45.0F, 3.0F) + level.random.nextInt(4);
                final Vec3 rock = ear.add((level.random.nextDouble() - 0.5) * 10, (level.random.nextDouble() - 0.3) * 8, (level.random.nextDouble() - 0.5) * 10)
                        .add(at.subtract(ear).normalize().scale(4)); // (on its side)
                level.playLocalSound(rock.x, rock.y, rock.z, BREAKS[level.random.nextInt(BREAKS.length)], SoundSource.HOSTILE, 0.25F + 0.9F * b,
                        0.5F + level.random.nextFloat() * 0.4F, false);
            }
            // dust from above: thicker and thicker
            if (dist < DUST) {
                final float d = (float) (1.0 - dist / DUST);
                final float rate = d * d * 6.0F; // dust falls per tick
                dust(mc, level, (int) rate + (level.random.nextFloat() < rate - (int) rate ? 1 : 0));
            }
            // pebbles
            if (dist < PEBBLES && level.random.nextFloat() < (float) (1.0 - dist / PEBBLES) * 0.7F) {
                pebbles(mc, level);
            }
            // trembling; really close, heaving
            if (dist < TREMBLE) {
                shake = Math.max(shake, (float) (1.0 - dist / TREMBLE) * (0.35F + 0.25F * level.random.nextFloat()));
            }
            if (dist < CLOSE) {
                final float c = (float) (1.0 - dist / CLOSE);
                shake = Math.max(shake, 0.8F + 0.7F * c);
                wantDark = Math.max(wantDark, 0.35F + 0.4F * c);
                if (now % 4 == 0) {
                    level.playLocalSound(at.x, at.y, at.z, level.random.nextBoolean() ? SoundEvents.GRAVEL_BREAK : SoundEvents.DEEPSLATE_BREAK,
                            SoundSource.HOSTILE, 1.2F + c, 0.45F + level.random.nextFloat() * 0.2F, false);
                }
                if (now % 30 == 0) {
                    level.playLocalSound(ear.x, ear.y - 2, ear.z, SoundEvents.WARDEN_DIG, SoundSource.HOSTILE, 1.5F, 0.5F, false);
                }
            }
        }
        // hunted: the heart pounding, the view darkening with it
        if (hunted) {
            final float h = (float) Mth.clamp(1.0 - huntDist / HUNT, 0.0, 1.0);
            if (now >= nextBeat) {
                nextBeat = now + (long) Mth.lerp(h, 22.0F, 7.0F);
                level.playLocalSound(ear.x, ear.y, ear.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 0.8F + 0.8F * h, 0.9F + 0.2F * h, false);
                beat = 1.0F;
            }
            wantDark = Math.max(wantDark, 0.15F + 0.35F * h + 0.2F * beat);
        }
        dark += (wantDark - dark) * 0.15F;
    }

    private static void dust(Minecraft mc, ClientLevel level, int times) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int t = 0; t < times; t++) {
            final double x = mc.player.getX() + (level.random.nextDouble() - 0.5) * 20, z = mc.player.getZ() + (level.random.nextDouble() - 0.5) * 20;
            for (int up = 2; up < 24; up++) {
                pos.set(x, mc.player.getY() + up, z);
                final BlockState above = level.getBlockState(pos);
                if (above.isCollisionShapeFullBlock(level, pos)) {
                    for (int i = 0; i < 2 + level.random.nextInt(4); i++) {
                        level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, above), x + level.random.nextGaussian() * 0.4,
                                pos.getY() - 0.05, z + level.random.nextGaussian() * 0.4, 0, 0, 0);
                    }
                    if (level.random.nextInt(3) == 0) { // a bit of the rock
                        level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, above), x, pos.getY() - 0.1, z, 0, -0.2, 0);
                    }
                    break;
                }
            }
        }
    }

    private static void pebbles(Minecraft mc, ClientLevel level) {
        final BlockPos under = BlockPos.containing(mc.player.getX() + (level.random.nextDouble() - 0.5) * 8, mc.player.getY() - 0.5,
                mc.player.getZ() + (level.random.nextDouble() - 0.5) * 8);
        final BlockState ground = level.getBlockState(under);
        if (!ground.isAir()) {
            for (int i = 0; i < 3; i++) {
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), under.getX() + level.random.nextDouble(), under.getY() + 1.05,
                        under.getZ() + level.random.nextDouble(), 0, 0.12, 0);
            }
        }
    }

    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        if (shake > 0.01F) {
            final float t = (float) (net.minecraft.Util.getMillis() / 40.0);
            event.setYaw(event.getYaw() + Mth.sin(t * 1.7F) * 0.6F * shake);
            event.setPitch(event.getPitch() + Mth.sin(t * 2.3F + 1.0F) * 0.6F * shake);
            event.setRoll(event.getRoll() + Mth.sin(t * 1.3F + 2.0F) * 0.4F * shake);
        }
    }

    /** The edges of the view closing in: near black, a little red. */
    static void draw(GuiGraphics g, float partial) {
        final float d = Mth.lerp(partial, darkO, dark);
        if (d < 0.01F) {
            return;
        }
        final int w = g.guiWidth(), h = g.guiHeight();
        final int edge = (int) (Math.min(w, h) * (0.18F + 0.22F * d));
        final int c = (int) (Mth.clamp(d, 0, 1) * 235) << 24 | 0x140306;
        final int none = 0x00140306;
        g.fillGradient(0, 0, w, edge, c, none);
        g.fillGradient(0, h - edge, w, h, none, c);
        // the sides: in steps (gradients run top to bottom only)
        final int steps = 24;
        for (int i = 0; i < steps; i++) {
            final float f = 1.0F - (float) i / steps;
            final int a = (int) (Mth.clamp(d, 0, 1) * 235 * f * f) << 24 | 0x140306;
            final int x0 = edge * i / steps, x1 = edge * (i + 1) / steps;
            g.fill(x0, 0, x1, h, a);
            g.fill(w - x1, 0, w - x0, h, a);
        }
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layer {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "worm_dread"),
                    (graphics, delta) -> draw(graphics, delta.getGameTimeDeltaPartialTick(true)));
        }
    }
}
