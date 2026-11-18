package redero.abyssaldepths.worldgen;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * The tidal lake: a rare set piece of the thalan depths, a real ebb-and-flow spring. Seep water from the overworld's
 * groundwater drips through a crack in the hall's ceiling (dripstone hangs there) and gathers in a low basin. A
 * natural siphon, a rock channel with a bend, runs from the lake down to a lower cave: once the water rises over the
 * bend the siphon starts and draws the lake down fast, gurgling in the rock, until air gets in and breaks it; then the
 * seep slowly fills it again. At low water the lakebed lies bare, and its higher ridge is a path to the island, a rock
 * knob where the siphon's throat opens. Where the throat churns with every cycle, minerals settle round grains: cave
 * pearls, found in the throat's basin when the water is low.
 * <p>
 * Everything is decided from the grid cell (each chunk lays its own part); the water is moved at run time by
 * {@link Runtime} for the lakes near players, from the world's time, one layer of the lake each tick.
 */
final class TideLake {
    static final int CELL = 460, REACH = 34;
    private static final double CHANCE = 0.55;
    private static final double ISLAND = 3.6;
    /** One cycle (ticks): draining (fast), low water, filling again (slow); and how many layers the water falls. */
    static final int CYCLE = 3000, DRAIN = 200, LOW = 900, DROP = 2;

    /** A lake: its middle (x, z), the floor there, its radius, and where its path leaves the shore (an angle). */
    record Spot(int x, int z, int floor, double lake, double path) {
        int surface() {
            return floor - 1;
        }
    }

    private final WorldGenLevel level;
    private final TunnelLayer layer;
    private final Biomes biomes;
    private final LayerFormations formations;
    private final int x0, z0;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final TunnelLayer.Column col = new TunnelLayer.Column();

    TideLake(WorldGenLevel level, TunnelLayer layer, Biomes biomes, LayerFormations formations, int x0, int z0) {
        this.level = level;
        this.layer = layer;
        this.biomes = biomes;
        this.formations = formations;
        this.x0 = x0;
        this.z0 = z0;
    }

    /**
     * The lake of a cell, if it has one: a few places in the cell are tried, the first where a broad open hall lies
     * level all round and clear of formations (a lake among formations would be lost between them) takes it.
     */
    static Spot spot(TunnelLayer layer, Biomes biomes, LayerFormations formations, int gx, int gz) {
        if (layer.random(gx, gz, 8701) >= CHANCE) {
            return null;
        }
        final double lake = 14.0 + layer.random(gx, gz, 8704) * 6.0;
        final TunnelLayer.Column c = new TunnelLayer.Column();
        for (int tryAt = 0; tryAt < 6; tryAt++) {
            final int x = gx * CELL + 60 + (int) (layer.random(gx, gz, 8702 + 10 * tryAt) * (CELL - 120));
            final int z = gz * CELL + 60 + (int) (layer.random(gx, gz, 8703 + 10 * tryAt) * (CELL - 120));
            if (open(layer, biomes, x, z, lake, c)) {
                final int reach = (int) (lake * 1.3);
                if (formations.near(x - reach, z - reach, x + reach, z + reach).isEmpty()) {
                    layer.column(x, z, c);
                    return new Spot(x, z, c.floor, lake, layer.random(gx, gz, 8705) * Math.PI * 2);
                }
            }
        }
        return null;
    }

    /** Whether a broad open hall of the depths lies round a place, its floor about level. */
    private static boolean open(TunnelLayer layer, Biomes biomes, int x, int z, double lake, TunnelLayer.Column c) {
        if (biomes.at(x, z) != Biomes.DEPTHS || !layer.column(x, z, c) || c.ceiling - c.floor < 12) {
            return false;
        }
        final int floor = c.floor;
        for (int k = 0; k < 8; k++) {
            final double a = k * Math.PI / 4;
            final int px = x + (int) (Math.cos(a) * lake * 0.85), pz = z + (int) (Math.sin(a) * lake * 0.85);
            if (biomes.at(px, pz) != Biomes.DEPTHS || !layer.column(px, pz, c) || c.ceiling - c.floor < 8 || Math.abs(c.floor - floor) > 3) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ its shape (the same at generation and at run time)

    /** How far out a column lies, as a share of the shore there (below 1: in the lake). */
    static double reach(TunnelLayer layer, Spot s, int x, int z) {
        final double d = Math.hypot(x - s.x(), z - s.z());
        final double shape = 1.0 + 0.3 * layer.noise3(x / 11.0, 0.3, z / 11.0, 24) + 0.12 * layer.noise3(x / 4.0, 0.6, z / 4.0, 27);
        return d / (s.lake() * shape);
    }

    /** The lakebed's top under a column in the lake (before the ridge and the island). */
    static int bed(TunnelLayer layer, Spot s, int x, int z) {
        final double e = reach(layer, s, x, z);
        final int depth = Math.max(1, Math.min(5, (int) Math.round(1.4 + 3.4 * (1.0 - e) + 0.9 * layer.noise3(x / 5.0, 0.2, z / 5.0, 28))));
        return s.surface() - depth;
    }

    /** How far a column lies from the island's middle, as a share of its edge (below 1: the island). */
    static double island(TunnelLayer layer, Spot s, int x, int z) {
        return Math.hypot(x - s.x(), z - s.z()) / (ISLAND * (1.0 + 0.3 * layer.noise3(x / 3.0, 0.1, z / 3.0, 25)));
    }

    /** The island's top at a column of it. */
    static int islandTop(TunnelLayer layer, Spot s, int x, int z, double f) {
        return s.surface() + (int) Math.round(2.4 * (1.0 - f * f) - 0.3 + 0.6 * layer.noise3(x / 2.0, 0.7, z / 2.0, 29));
    }

    /**
     * The ridge across the lakebed, from the shore to the island: walked from the shore towards the middle, its
     * heading wandering as it goes, so it winds the way a ridge does, not a drawn line. Points every half block.
     */
    static double[][] ridge(TunnelLayer layer, Spot s) {
        final int gx = Math.floorDiv(s.x(), CELL), gz = Math.floorDiv(s.z(), CELL);
        final java.util.List<double[]> points = new java.util.ArrayList<>();
        double x = s.x() + 0.5 + Math.cos(s.path()) * (s.lake() + 1.5), z = s.z() + 0.5 + Math.sin(s.path()) * (s.lake() + 1.5);
        for (int k = 0; k < 400; k++) {
            points.add(new double[] {x, z});
            final double dx = s.x() + 0.5 - x, dz = s.z() + 0.5 - z;
            if (Math.hypot(dx, dz) < ISLAND) {
                break;
            }
            final double heading = Math.atan2(dz, dx) + 0.9 * layer.noise3(k * 0.04, gx * 3.1, gz * 3.1, 26);
            x += Math.cos(heading) * 0.5;
            z += Math.sin(heading) * 0.5;
        }
        return points.toArray(new double[0][]);
    }

    /** How far a column lies from the ridge. */
    static double offRidge(double[][] ridge, int x, int z) {
        double near = Double.MAX_VALUE;
        for (double[] p : ridge) {
            near = Math.min(near, Math.hypot(x + 0.5 - p[0], z + 0.5 - p[1]));
        }
        return near;
    }

    /** The ridge's top where it passes (its crest a block under the full water, so bare at low water), or MIN_VALUE off it. */
    static int ridgeTop(TunnelLayer layer, Spot s, double off, int x, int z) {
        if (off >= 2.4) {
            return Integer.MIN_VALUE;
        }
        final int dip = layer.random(x, z, 8711) < 0.12 ? 1 : 0; // here and there a low spot: a step through a puddle
        return off < 1.1 ? s.surface() - 1 - dip : off < 1.8 ? s.surface() - 2 : s.surface() - 3; // (the crest bare at low water, its shoulders awash)
    }

    /** Where the siphon's throat opens on the island (its basin), and where the seep drips from the ceiling. */
    static BlockPos throat(TunnelLayer layer, Spot s) {
        return new BlockPos(s.x(), islandTop(layer, s, s.x(), s.z(), 0) , s.z());
    }

    static BlockPos seep(TunnelLayer layer, Spot s) {
        final double a = s.path() + Math.PI * 0.7;
        return new BlockPos(s.x() + (int) (Math.cos(a) * s.lake() * 0.55), s.surface(), s.z() + (int) (Math.sin(a) * s.lake() * 0.55));
    }

    // ------------------------------------------------------------------ generation

    void apply() {
        for (int gx = Math.floorDiv(x0 - REACH, CELL); gx <= Math.floorDiv(x0 + 15 + REACH, CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - REACH, CELL); gz <= Math.floorDiv(z0 + 15 + REACH, CELL); gz++) {
                final Spot s = spot(layer, biomes, formations, gx, gz);
                if (s == null || s.x() + REACH < x0 || s.x() - REACH > x0 + 15 || s.z() + REACH < z0 || s.z() - REACH > z0 + 15) {
                    continue;
                }
                lake(s, ridge(layer, s));
                crack(s);
            }
        }
    }

    /**
     * The lake: the hall's own floor filled to a level, its shore ragged and lobed, its bed uneven; whatever stands on
     * the floor stays and stands in the water. The ridge across the bed, the island with the siphon's throat.
     */
    private void lake(Spot s, double[][] ridge) {
        final int surface = s.surface();
        final BlockState rock = AbyssalDepths.THALAN.get().defaultBlockState();
        final int r = (int) Math.ceil(s.lake() * 1.45) + 3;
        for (int x = Math.max(x0, s.x() - r); x <= Math.min(x0 + 15, s.x() + r); x++) {
            for (int z = Math.max(z0, s.z() - r); z <= Math.min(z0 + 15, s.z() + r); z++) {
                final double e = reach(layer, s, x, z);
                if (e >= 1.0 + 2.5 / s.lake() || !layer.column(x, z, col)) {
                    continue; // beyond its shore, or a pillar: it stands in the water as it is
                }
                if (e >= 1.0) { // the shore holds it in: rock wherever the ground was open below the water's level
                    for (int y = surface - 5; y <= surface; y++) {
                        final BlockState here = level.getBlockState(pos.set(x, y, z));
                        if (here.isAir() || !here.getFluidState().isEmpty()) {
                            level.setBlock(pos, rock, 2);
                        }
                    }
                    continue;
                }
                final int bed = bed(layer, s, x, z);
                for (int y = bed - 1; y <= bed; y++) {
                    final BlockState here = level.getBlockState(pos.set(x, y, z));
                    if (here.isAir() || !here.getFluidState().isEmpty()) {
                        level.setBlock(pos, rock, 2);
                    }
                }
                final double f = island(layer, s, x, z);
                if (f < 1.0) {
                    island(s, x, z, f, bed);
                    continue;
                }
                final int top = Math.max(bed, ridgeTop(layer, s, offRidge(ridge, x, z), x, z));
                for (int y = bed + 1; y <= top; y++) { // the ridge
                    level.setBlock(pos.set(x, y, z), rock, 2);
                }
                for (int y = top + 1; y <= surface; y++) { // the water: the ground below the level gives way to it
                    level.setBlock(pos.set(x, y, z), Blocks.WATER.defaultBlockState(), 2);
                }
            }
        }
    }

    /** The island: a rough knob of thalan and cobbles up to two blocks over the water, the siphon's throat in its middle. */
    private void island(Spot s, int x, int z, double f, int bed) {
        final int surface = s.surface();
        final int top = islandTop(layer, s, x, z, f);
        final BlockPos throat = throat(layer, s);
        final int dx = x - throat.getX(), dz = z - throat.getZ();
        final boolean basin = dx >= -1 && dx <= 1 && dz >= -1 && dz <= 1, hole = dx == 0 && dz == 0;
        for (int y = bed + 1; y <= Math.max(top, surface); y++) {
            final boolean wet = y > top;
            final BlockState rock = layer.random(x, z, 8720 + y) < 0.25 ? AbyssalDepths.COBBLED_THALAN.get().defaultBlockState()
                    : AbyssalDepths.THALAN.get().defaultBlockState();
            final boolean open = basin && y >= throat.getY() - 1 && y <= throat.getY() || hole && y < throat.getY();
            level.setBlock(pos.set(x, y, z), open || wet ? (wet && y <= surface || hole && y <= surface ? Blocks.WATER : Blocks.AIR).defaultBlockState() : rock, 2);
        }
        if (top >= surface && !basin && layer.random(x, z, 8730) < 0.3 && level.getBlockState(pos.set(x, top + 1, z)).isAir()) {
            level.setBlock(pos, AbyssalDepths.GLOAM_MOSS.get().defaultBlockState() // a little moss, kept by the damp
                    .setValue(net.minecraft.world.level.block.MultifaceBlock.getFaceProperty(Direction.DOWN), true), 2);
        }
    }

    /** The crack in the ceiling the seep comes through: dripstone hanging from it. */
    private void crack(Spot s) {
        final BlockPos at = seep(layer, s);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                final int x = at.getX() + dx, z = at.getZ() + dz;
                if (x < x0 || x > x0 + 15 || z < z0 || z > z0 + 15 || !layer.column(x, z, col) || layer.random(x, z, 8740) > 0.6) {
                    continue;
                }
                final int ceiling = col.ceiling;
                final int length = 1 + (int) (layer.random(x, z, 8741) * 3);
                for (int k = 0; k < length; k++) {
                    final DripstoneThickness t = k == length - 1 ? DripstoneThickness.TIP : k == 0 && length > 2 ? DripstoneThickness.BASE
                            : length == 2 && k == 0 ? DripstoneThickness.FRUSTUM : DripstoneThickness.MIDDLE;
                    level.setBlock(pos.set(x, ceiling - 1 - k, z), Blocks.POINTED_DRIPSTONE.defaultBlockState()
                            .setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN).setValue(PointedDripstoneBlock.THICKNESS, t), 2);
                }
            }
        }
    }

    /** The nearest lake to a place (its middle and the floor there), or null. */
    static BlockPos nearest(TunnelLayer layer, Biomes biomes, LayerFormations formations, int x, int z, int cells) {
        BlockPos best = null;
        double near = Double.MAX_VALUE;
        final int cx = Math.floorDiv(x, CELL), cz = Math.floorDiv(z, CELL);
        for (int gx = cx - cells; gx <= cx + cells; gx++) {
            for (int gz = cz - cells; gz <= cz + cells; gz++) {
                final Spot s = spot(layer, biomes, formations, gx, gz);
                if (s != null && Math.hypot(s.x() - x, s.z() - z) < near) {
                    near = Math.hypot(s.x() - x, s.z() - z);
                    best = new BlockPos(s.x(), s.floor(), s.z());
                }
            }
        }
        return best;
    }

    /** A shift of every lake's cycle (a test command sets it to show low or high water now). */
    static long shift;

    /** Where in its cycle a lake is at a time (each lake its own, from its place). */
    static long phase(Spot s, long time) {
        return Math.floorMod(time + shift + (long) (s.x() * 31 + s.z() * 17), (long) CYCLE);
    }

    /** Sets the shift so the lake at a place is at a point of its cycle now: "low" (just drained) or "high" (just filling up). */
    static void tide(long time, int x, int z, boolean low) {
        final long want = low ? DRAIN + 40 : CYCLE - 500;
        shift = Math.floorMod(want - (time + (long) (x * 31 + z * 17)), (long) CYCLE);
    }

    /** Where the water stands at a time in the cycle (the highest layer of water). */
    static int level(Spot s, long time) {
        final long t = phase(s, time);
        final int full = s.surface(), low = full - DROP;
        if (t < DRAIN) {
            return full - (int) ((DROP + 1) * t / DRAIN); // the siphon runs: down fast
        }
        if (t < DRAIN + LOW) {
            return low; // air broke it: low water
        }
        return Math.min(full, low + 1 + (int) ((long) DROP * (t - DRAIN - LOW) / (CYCLE - DRAIN - LOW))); // the seep fills it again
    }

    // ------------------------------------------------------------------ the water moving (run time)

    /**
     * Moves the water of the lakes near players: each tick one layer of a lake is set right for the time (water up to
     * the level, air above it, only where the lake's water stands: nothing else is touched), so the whole lake follows
     * the cycle within a few ticks, also land loaded after. With the sounds, the seep's drips and the cave pearls.
     */
    @EventBusSubscriber(modid = AbyssalDepths.MODID)
    public static final class Runtime {
        private static final Map<Long, Spot> CELLS = new HashMap<>();
        private static final Map<Spot, double[][]> RIDGES = new HashMap<>();
        private static final Spot NONE = new Spot(0, 0, 0, 0, 0);
        private static int layerAt;

        private Runtime() {
        }

        @SubscribeEvent
        public static void onTick(ServerTickEvent.Post event) {
            final ServerLevel level = event.getServer().overworld();
            final List<ServerPlayer> players = level.players();
            if (players.isEmpty()) {
                return;
            }
            final TunnelFeature.Context c = TunnelFeature.Context.of(level);
            final java.util.Set<Spot> near = new java.util.HashSet<>();
            for (ServerPlayer p : players) {
                if (p.getY() > TunnelLayer.LAYER_TOP_Y + 8) {
                    continue;
                }
                final int cx = Math.floorDiv(p.getBlockX(), CELL), cz = Math.floorDiv(p.getBlockZ(), CELL);
                for (int gx = cx - 1; gx <= cx + 1; gx++) {
                    for (int gz = cz - 1; gz <= cz + 1; gz++) {
                        final Spot s = CELLS.computeIfAbsent(((long) gx << 32) ^ (gz & 0xffffffffL), k -> {
                            final Spot found = spot(c.layer, c.biomes, c.formations, (int) (k >> 32), (int) (long) k);
                            return found == null ? NONE : found;
                        });
                        if (s != NONE && Math.hypot(s.x() - p.getX(), s.z() - p.getZ()) < 120) {
                            near.add(s);
                        }
                    }
                }
            }
            layerAt++;
            for (Spot s : near) {
                move(level, c.layer, s);
            }
        }

        private static void move(ServerLevel level, TunnelLayer layer, Spot s) {
            final long time = level.getGameTime();
            final int water = level(s, time);
            final int y = s.surface() - DROP + Math.floorMod(layerAt, DROP + 1); // the layer set right this tick
            final double[][] ridge = RIDGES.computeIfAbsent(s, k -> ridge(layer, k));
            final int r = (int) Math.ceil(s.lake() * 1.45) + 1;
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = s.x() - r; x <= s.x() + r; x++) {
                for (int z = s.z() - r; z <= s.z() + r; z++) {
                    if (reach(layer, s, x, z) >= 1.0 || !level.isLoaded(pos.set(x, y, z))) {
                        continue;
                    }
                    final double f = island(layer, s, x, z);
                    int floor = bed(layer, s, x, z);
                    if (f < 1.0) {
                        floor = Math.max(floor, islandTop(layer, s, x, z, f));
                    } else {
                        floor = Math.max(floor, ridgeTop(layer, s, offRidge(ridge, x, z), x, z));
                    }
                    if (y <= floor) {
                        continue;
                    }
                    final BlockState here = level.getBlockState(pos);
                    if (y <= water && here.isAir()) {
                        level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
                    } else if (y > water && here.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && here.getBlock() == Blocks.WATER) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            effects(level, layer, s, time);
        }


        /** The seep dripping, the siphon gurgling while it runs and gulping when the air breaks it, the pearls. */
        private static void effects(ServerLevel level, TunnelLayer layer, Spot s, long time) {
            final long t = phase(s, time);
            final BlockPos throat = throat(layer, s), seep = seep(layer, s);
            if (time % 3 == 0) { // the seep: drops falling from the crack
                final TunnelLayer.Column col = new TunnelLayer.Column();
                if (layer.column(seep.getX(), seep.getZ(), col)) {
                    level.sendParticles(ParticleTypes.FALLING_WATER, seep.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 2, col.ceiling - 1.5,
                            seep.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 2, 1, 0, 0, 0, 0);
                }
            }
            if (t < DRAIN && t % 15 == 0) { // the siphon runs
                level.playSound(null, throat, SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_INSIDE, SoundSource.AMBIENT, 2.5F, 0.5F);
                level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, throat.getX() + 0.5, throat.getY() - 1.0, throat.getZ() + 0.5, 12, 0.4, 0.3, 0.4, 0.02);
            }
            if (t == DRAIN) { // air gets in: it breaks with a gulp, and the churned throat gives up its pearls
                level.playSound(null, throat, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.AMBIENT, 2.5F, 0.4F);
                final AABB basin = new AABB(throat).inflate(1.5, 1.0, 1.5);
                if (level.getEntitiesOfClass(ItemEntity.class, basin, e -> e.getItem().is(AbyssalDepths.CAVE_PEARL.get())).size() < 3) {
                    final ItemEntity pearl = new ItemEntity(level, throat.getX() + 1.5, throat.getY() - 0.5, throat.getZ() + 0.5, // (beside the hole)
                            new ItemStack(AbyssalDepths.CAVE_PEARL.get(), level.random.nextInt(3) == 0 ? 2 : 1));
                    pearl.setDeltaMovement(0, 0.15, 0);
                    pearl.setExtendedLifetime();
                    level.addFreshEntity(pearl);
                }
            }
            if (t > DRAIN && t < DRAIN + LOW && time % 80 == 0) { // low water: the throat murmurs
                level.playSound(null, throat, SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.AMBIENT, 1.0F, 0.5F);
            }
        }
    }
}
