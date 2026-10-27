package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.joml.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;

/**
 * Ambience of the whole thalan layer, client side:
 * <ul>
 * <li>Size of the space: {@link #openness()}, how open the hall around the camera is, from a few rays
 * cast every second. Narrow passages get closer, darker fog ({@link ThalanFog}) and a short echo, big
 * halls a long one ({@link LayerReverb}).</li>
 * <li>Biome fog tint: cold blue grey in the shale flats, dusty in the silt dunes (the thermal reaches
 * have their warmth from {@link ThermalClient}).</li>
 * <li>Air: dust motes floating around, grit, sand and water dripping from the ceiling (water on hot
 * ground ends in a puff of steam), now and then a gust of wind sweeping dust across the open
 * flats and dunes.</li>
 * <li>Sounds from somewhere in the halls: shale cracking, rockfall with grit falling from the ceiling,
 * silt sliding off a dune; rarely a deep groan of the earth or something large, far away.</li>
 * <li>Faint glowing motes drifting around the glimmering thalan, like small blind moths.</li>
 * </ul>
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class LayerAmbience {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int RAY_LENGTH = 32;
    private static final float[] DEPTHS_FOG = {0.035F, 0.035F, 0.045F}, FLATS_FOG = {0.032F, 0.04F, 0.058F},
            DUNES_FOG = {0.07F, 0.063F, 0.052F};

    private static float depth, openness = 0.5F, opennessTarget = 0.5F;
    private static final float[] tint = DEPTHS_FOG.clone();
    private static int measureCooldown, soundCooldown = 300, gustCooldown = 900, gustTicks, groanCooldown = 4000,
            creatureCooldown = 9000, glimmerScanCooldown;
    private static double gustX, gustZ;
    /** Set by the test command: what to play next instead of chance (creature 1 = roar, 2 = steps). */
    private static int creatureNext;
    private static boolean forceRockfall, forceGust;
    private static final List<BlockPos> GLIMMERS = new ArrayList<>();
    private static final List<Scheduled> LATER = new ArrayList<>();

    private record Scheduled(long at, Runnable action) {
    }

    private LayerAmbience() {
    }

    /** 0 above the layer, 1 inside it (fades in between y=-60 and y=-70, like the fog). */
    public static float depth() {
        return depth;
    }

    /** 0 (narrow passage) .. 1 (huge open hall), eased over a few seconds. */
    public static float openness() {
        return openness;
    }

    /** Fog colour of the layer biome around the camera, eased while walking from one to the next. */
    public static float tint(int channel) {
        return tint[channel];
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc.player;
        final ClientLevel level = mc.level;
        if (player == null || level == null || mc.isPaused()) {
            if (player == null) {
                LATER.clear();
                GLIMMERS.clear();
            }
            return;
        }
        depth = level.dimension() == Level.OVERWORLD ? Mth.clamp((float) ((-60.0 - player.getEyeY()) / 10.0), 0.0F, 1.0F) : 0.0F;
        runScheduled(level);
        if (depth <= 0.0F) {
            GLIMMERS.clear();
            return;
        }
        if (--measureCooldown <= 0) {
            measureCooldown = 20;
            opennessTarget = measureOpenness(level, player.getEyePosition());
        }
        openness += Mth.clamp(opennessTarget - openness, -0.01F, 0.01F);

        final BlockPos at = player.blockPosition();
        final Holder<Biome> biome = level.getBiome(at);
        final boolean flats = biome.is(AbyssalDepths.SHALE_FLATS), dunes = biome.is(AbyssalDepths.SILT_DUNES),
                thermal = biome.is(AbyssalDepths.THERMAL_REACHES);
        final float[] target = flats ? FLATS_FOG : dunes ? DUNES_FOG : DEPTHS_FOG;
        for (int i = 0; i < 3; i++) {
            tint[i] += Mth.clamp(target[i] - tint[i], -0.0004F, 0.0004F);
        }

        if (RANDOM.nextFloat() < ClientConfig.particles()) {
            dust(level, player, dunes || thermal);
            drips(level, player, flats, dunes, thermal);
            moths(level, player);
        }
        if (flats || dunes || forceGust || gustTicks > 0) {
            gusts(level, player, dunes);
        } else {
            gustTicks = 0;
        }
        if (ClientConfig.ambientSounds()) {
            hallSounds(level, player, flats, dunes);
            deepSounds(level, player);
        }
    }

    // ---- size of the space ----------------------------------------------------------------

    /** Average free distance along 8 horizontal rays and one up, relative to {@link #RAY_LENGTH}. */
    private static float measureOpenness(ClientLevel level, Vec3 eye) {
        float sum = 0.0F;
        int rays = 0;
        for (int k = 0; k < 9; k++) {
            final double a = k * Math.PI / 4;
            final double dx = k == 8 ? 0 : Math.cos(a), dy = k == 8 ? 1 : 0, dz = k == 8 ? 0 : Math.sin(a);
            int free = 0;
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            while (free < RAY_LENGTH && level.getBlockState(pos.set(eye.x + dx * (free + 1), eye.y + dy * (free + 1), eye.z + dz * (free + 1))).isAir()) {
                free++;
            }
            sum += free / (float) RAY_LENGTH;
            rays++;
        }
        return sum / rays;
    }

    // ---- particles ----------------------------------------------------------------

    /** Specks of dust floating in the air around the player (the dunes and thermal reaches have their own). */
    private static void dust(ClientLevel level, LocalPlayer player, boolean ownParticles) {
        if (ownParticles || RANDOM.nextInt(3) != 0) {
            return;
        }
        final double x = player.getX() + (RANDOM.nextDouble() - 0.5) * 20, y = player.getEyeY() + (RANDOM.nextDouble() - 0.4) * 10,
                z = player.getZ() + (RANDOM.nextDouble() - 0.5) * 20;
        if (level.getBlockState(BlockPos.containing(x, y, z)).isAir()) {
            level.addParticle(ParticleTypes.MYCELIUM, x, y, z, 0.0, 0.0, 0.0);
        }
    }

    /** Grit, sand or water dripping from the ceiling somewhere around; water on hot ground steams. */
    private static void drips(ClientLevel level, LocalPlayer player, boolean flats, boolean dunes, boolean thermal) {
        if (RANDOM.nextInt(5) != 0) {
            return;
        }
        final double x = player.getX() + (RANDOM.nextDouble() - 0.5) * 28, z = player.getZ() + (RANDOM.nextDouble() - 0.5) * 28;
        final BlockPos.MutableBlockPos pos = BlockPos.containing(x, player.getEyeY(), z).mutable();
        if (!level.getBlockState(pos).isAir()) {
            return;
        }
        int up = 0;
        while (up < 30 && level.getBlockState(pos.move(0, 1, 0)).isAir()) {
            up++;
        }
        if (up >= 30) {
            return;
        }
        final double top = pos.getY() - 0.05;
        if (flats) {
            level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, AbyssalDepths.SHALE.get().defaultBlockState()), x, top, z, 0, 0, 0);
        } else if (dunes) {
            level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, AbyssalDepths.FINE_SILT.get().defaultBlockState()), x, top, z, 0, 0, 0);
        } else {
            level.addParticle(ParticleTypes.DRIPPING_DRIPSTONE_WATER, x, top, z, 0, 0, 0);
            if (thermal) {
                // find where it lands; on hot ground it ends in a puff of steam (after hanging and falling)
                pos.move(0, -1, 0);
                int fall = 0;
                while (fall < 40 && level.getBlockState(pos.move(0, -1, 0)).isAir()) {
                    fall++;
                }
                final BlockState ground = level.getBlockState(pos);
                if (ground.is(AbyssalDepths.SMOULDERING_ROCK.get()) || ground.is(AbyssalDepths.THERMAL_VENT.get())
                        || ground.is(AbyssalDepths.THERMAL_ROCK.get())) {
                    final double gy = pos.getY() + 1.05;
                    later(level, 40 + (long) Math.sqrt(2.0 * fall / 0.06), () -> {
                        level.addParticle(ParticleTypes.WHITE_SMOKE, x, gy, z, 0.0, 0.03, 0.0);
                        level.playLocalSound(x, gy, z, AbyssalDepths.VENT_HISS.get(), SoundSource.AMBIENT, 0.15F, 1.6F, false);
                    });
                }
            }
        }
    }

    /** Now and then a gust of wind sweeps dust over the ground of the open flats and dunes. */
    private static void gusts(ClientLevel level, LocalPlayer player, boolean dunes) {
        if (gustTicks <= 0) {
            if (--gustCooldown > 0 && !forceGust) {
                return;
            }
            forceGust = false;
            gustCooldown = 900 + RANDOM.nextInt(1500);
            gustTicks = 70;
            final double a = RANDOM.nextDouble() * 2 * Math.PI;
            gustX = Math.cos(a);
            gustZ = Math.sin(a);
            play(AbyssalDepths.GUST.get(), player.getX() - gustX * 12, player.getEyeY(), player.getZ() - gustZ * 12, 1.0F,
                    0.8F + RANDOM.nextFloat() * 0.3F);
        }
        gustTicks--;
        final float strength = Mth.sin((70 - gustTicks) / 70.0F * Mth.PI); // swells and dies down
        final Vector3f colour = dunes ? new Vector3f(0.55F, 0.52F, 0.47F) : new Vector3f(0.28F, 0.29F, 0.32F);
        final ParticleOptions grain = new DustParticleOptions(colour, 0.9F);
        for (int i = 0; i < Math.round(5 * ClientConfig.particles()); i++) {
            final double x = player.getX() + (RANDOM.nextDouble() - 0.5) * 36, z = player.getZ() + (RANDOM.nextDouble() - 0.5) * 36;
            final BlockPos.MutableBlockPos pos = BlockPos.containing(x, player.getEyeY(), z).mutable();
            int down = 0;
            while (down < 16 && level.getBlockState(pos).isAir()) {
                pos.move(0, -1, 0);
                down++;
            }
            if (down == 0 || down >= 16) {
                continue;
            }
            final double speed = 0.4 * strength;
            level.addParticle(grain, x, pos.getY() + 1.1 + RANDOM.nextDouble() * 0.8, z, gustX * speed, 0.01, gustZ * speed);
        }
    }

    /** Faint glowing motes drifting around the glimmering thalan, like small blind moths. */
    private static void moths(ClientLevel level, LocalPlayer player) {
        if (--glimmerScanCooldown <= 0) {
            glimmerScanCooldown = 60;
            scanGlimmers(level, player.blockPosition());
        }
        for (BlockPos glimmer : GLIMMERS) {
            if (RANDOM.nextInt(160) == 0) {
                final double x = glimmer.getX() + 0.5 + (RANDOM.nextDouble() - 0.5) * 3.0, y = glimmer.getY() + 0.5 + (RANDOM.nextDouble() - 0.5) * 3.0,
                        z = glimmer.getZ() + 0.5 + (RANDOM.nextDouble() - 0.5) * 3.0;
                if (level.getBlockState(BlockPos.containing(x, y, z)).isAir()) {
                    level.addParticle(ParticleTypes.GLOW, x, y, z, (RANDOM.nextDouble() - 0.5) * 0.02, 0.005, (RANDOM.nextDouble() - 0.5) * 0.02);
                }
            }
        }
    }

    private static void scanGlimmers(ClientLevel level, BlockPos around) {
        GLIMMERS.clear();
        final int cx = SectionPos.blockToSectionCoord(around.getX()), cz = SectionPos.blockToSectionCoord(around.getZ());
        final int sy = level.getSectionIndex(around.getY());
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!level.hasChunk(cx + dx, cz + dz)) {
                    continue;
                }
                final LevelChunkSection[] sections = level.getChunk(cx + dx, cz + dz).getSections();
                for (int i = Math.max(0, sy - 1); i <= sy + 1 && i < sections.length; i++) {
                    final LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> s.is(AbyssalDepths.GLIMMERING_THALAN.get()))) {
                        continue;
                    }
                    final int bx = (cx + dx) << 4, by = level.getSectionYFromSectionIndex(i) << 4, bz = (cz + dz) << 4;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (section.getBlockState(x, y, z).is(AbyssalDepths.GLIMMERING_THALAN.get()) && GLIMMERS.size() < 64) {
                                    GLIMMERS.add(new BlockPos(bx + x, by + y, bz + z));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- sounds ----------------------------------------------------------------

    /** One of the rare sounds on demand, for the test command: groan, creature, steps, rockfall or gust. */
    static void trigger(String kind) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        switch (kind) {
            case "groan" -> groanCooldown = 0;
            case "creature" -> creatureNext = 1;
            case "steps" -> creatureNext = 2;
            case "rockfall" -> forceRockfall = true;
            case "gust" -> {
                gustCooldown = 0;
                gustTicks = 0;
                forceGust = true;
            }
            default -> {
                return;
            }
        }
        if (!kind.equals("gust")) {
            if (kind.equals("rockfall")) {
                soundCooldown = 0;
            } else if (!kind.equals("groan")) {
                creatureCooldown = 0;
            }
            hallSoundsOrDeep(mc.level, mc.player);
        }
    }

    private static void hallSoundsOrDeep(ClientLevel level, LocalPlayer player) {
        if (forceRockfall) {
            hallSounds(level, player, false, false);
        } else {
            deepSounds(level, player);
        }
    }

    /** A sound from somewhere in the hall every 10 to 25 seconds: cracking shale, sliding silt, rockfall. */
    private static void hallSounds(ClientLevel level, LocalPlayer player, boolean flats, boolean dunes) {
        if (--soundCooldown > 0) {
            return;
        }
        soundCooldown = 200 + RANDOM.nextInt(300);
        final double a = RANDOM.nextDouble() * 2 * Math.PI, d = 10 + RANDOM.nextDouble() * 25;
        final double x = player.getX() + Math.cos(a) * d, y = player.getEyeY() + (RANDOM.nextDouble() - 0.3) * 8, z = player.getZ() + Math.sin(a) * d;
        final boolean rockfall = forceRockfall || RANDOM.nextInt(flats || dunes ? 3 : 1) == 0;
        forceRockfall = false;
        if (!rockfall) {
            play(flats ? AbyssalDepths.FLATS_CRACK.get() : AbyssalDepths.DUNES_SLIDE.get(), x, y, z, 1.0F, 0.85F + RANDOM.nextFloat() * 0.3F);
            return;
        }
        play(AbyssalDepths.ROCKFALL.get(), x, y, z, 1.0F, 0.8F + RANDOM.nextFloat() * 0.3F);
        // grit trickles down from the ceiling there
        final BlockPos.MutableBlockPos pos = BlockPos.containing(x, y, z).mutable();
        int up = 0;
        while (up < 30 && level.getBlockState(pos).isAir()) {
            pos.move(0, 1, 0);
            up++;
        }
        if (up > 0 && up < 30) {
            final BlockState ceiling = level.getBlockState(pos);
            for (int i = 0; i < 12; i++) {
                later(level, RANDOM.nextInt(30), () -> level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, ceiling),
                        x + (RANDOM.nextDouble() - 0.5) * 2.5, pos.getY() - 0.05, z + (RANDOM.nextDouble() - 0.5) * 2.5, 0, 0, 0));
            }
        }
    }

    /** Rare deep groans of the earth, and more rarely something large moving far away in the dark. */
    private static void deepSounds(ClientLevel level, LocalPlayer player) {
        if (--groanCooldown <= 0) {
            groanCooldown = 3600 + RANDOM.nextInt(4800);
            final double a = RANDOM.nextDouble() * 2 * Math.PI;
            play(AbyssalDepths.DEEP_GROAN.get(), player.getX() + Math.cos(a) * 50, player.getEyeY() - 10, player.getZ() + Math.sin(a) * 50,
                    1.0F, 0.8F + RANDOM.nextFloat() * 0.3F);
        }
        if (--creatureCooldown <= 0) {
            creatureCooldown = 9000 + RANDOM.nextInt(12000);
            final double a = RANDOM.nextDouble() * 2 * Math.PI, walk = a + Math.PI / 2;
            final double x = player.getX() + Math.cos(a) * 60, y = player.getEyeY(), z = player.getZ() + Math.sin(a) * 60;
            final boolean roar = creatureNext == 0 ? RANDOM.nextBoolean() : creatureNext == 1;
            creatureNext = 0;
            if (roar) {
                play(AbyssalDepths.DISTANT_CREATURE.get(), x, y, z, 1.0F, 0.9F + RANDOM.nextFloat() * 0.2F);
            } else {
                // heavy steps passing by in the distance
                final int steps = 4 + RANDOM.nextInt(4);
                for (int i = 0; i < steps; i++) {
                    final double sx = x + Math.cos(walk) * i * 3, sz = z + Math.sin(walk) * i * 3;
                    later(level, i * 28L, () -> play(AbyssalDepths.DISTANT_STEPS.get(), sx, y, sz, 1.0F, 0.9F + RANDOM.nextFloat() * 0.15F));
                }
            }
        }
    }

    private static void play(SoundEvent sound, double x, double y, double z, float volume, float pitch) {
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.AMBIENT, volume, pitch, RANDOM, x, y, z));
    }

    // ---- helpers ----------------------------------------------------------------

    private static void later(ClientLevel level, long ticks, Runnable action) {
        if (LATER.size() < 256) {
            LATER.add(new Scheduled(level.getGameTime() + ticks, action));
        }
    }

    private static void runScheduled(ClientLevel level) {
        final long now = level.getGameTime();
        final List<Scheduled> due = new ArrayList<>();
        for (Iterator<Scheduled> it = LATER.iterator(); it.hasNext();) {
            final Scheduled s = it.next();
            if (s.at() <= now) {
                due.add(s);
                it.remove();
            }
        }
        due.forEach(s -> s.action().run());
    }
}
