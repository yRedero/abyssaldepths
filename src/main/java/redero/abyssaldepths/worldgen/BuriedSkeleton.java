package redero.abyssaldepths.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * The dead worm: a rare set piece of the silt dunes. A thalan worm died here long ago (why stays open). A worm has no
 * bones; what lasts of it is what was hard: the armoured skin it wore (and sheds when hurt) and its fangs. So it lies
 * as a hollow shell of hardened skin, half sunk in the silt where it last lay, curving the way it crawled: its upper
 * half arches out of the dunes, ringed where its segments met, broken and fallen in along stretches; its tail runs on
 * under the silt; at its head the jaw plates gape and its fangs lie in the silt round them. The wind buries it and the
 * storms lay it bare again, more or less each time (see {@link Live}): its fangs, under the silt, only then. The dry
 * silt kept the shell; little lives in the layer to break it down. At most one in a large grid cell, only in the dunes
 * under a tall ceiling. Everything is decided from the cell, so each chunk lays its own part. (The class keeps its old
 * name for older worlds' sake: it was a buried skeleton.)
 */
final class BuriedSkeleton {
    static final int CELL = 520, REACH = 48;
    private static final double CHANCE = 0.5;

    /** A dead worm: the middle of its body (x, z), the floor there, the way it lay (unit vector from tail to head), its length, its girth. */
    record Spot(int x, int z, int floor, double dirX, double dirZ, double length, double rise) {
        /** Its shell's radius. */
        double radius() {
            return 3.2 + rise * 0.06;
        }
    }

    private final WorldGenLevel level;
    private final TunnelLayer layer;
    private final Biomes biomes;
    private final int x0, z0;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    BuriedSkeleton(WorldGenLevel level, TunnelLayer layer, Biomes biomes, int x0, int z0) {
        this.level = level;
        this.layer = layer;
        this.biomes = biomes;
        this.x0 = x0;
        this.z0 = z0;
    }

    static Spot spot(TunnelLayer layer, Biomes biomes, int gx, int gz) {
        if (layer.random(gx, gz, 8301) >= CHANCE) {
            return null;
        }
        final int x = gx * CELL + 70 + (int) (layer.random(gx, gz, 8302) * (CELL - 140));
        final int z = gz * CELL + 70 + (int) (layer.random(gx, gz, 8303) * (CELL - 140));
        final TunnelLayer.Column c = new TunnelLayer.Column();
        if (biomes.at(x, z) != Biomes.DUNES || !layer.column(x, z, c) || c.ceiling - c.floor < 18) {
            return null; // not in the dunes, or no tall hall there
        }
        final double a = layer.random(gx, gz, 8304) * Math.PI * 2;
        final double rise = Math.min(12.0, c.ceiling - c.floor - 6.0) * (0.75 + layer.random(gx, gz, 8306) * 0.25);
        return new Spot(x, z, c.floor, Math.cos(a), Math.sin(a), 44.0 + layer.random(gx, gz, 8305) * 18.0, rise);
    }

    void apply() {
        for (int gx = Math.floorDiv(x0 - REACH, CELL); gx <= Math.floorDiv(x0 + 15 + REACH, CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - REACH, CELL); gz <= Math.floorDiv(z0 + 15 + REACH, CELL); gz++) {
                final Spot s = spot(layer, biomes, gx, gz);
                if (s == null || s.x() + REACH < x0 || s.x() - REACH > x0 + 15 || s.z() + REACH < z0 || s.z() - REACH > z0 + 15) {
                    continue;
                }
                scour(s);
                shell(s, gx, gz);
                head(s, gx, gz);
            }
        }
    }

    // ------------------------------------------------------------------ its shape

    /** The body's middle line: sideways off the straight (it lay curving, as it crawled) at u along it (head at +half). */
    static double sway(TunnelLayer layer, Spot s, double u) {
        return 4.0 * Math.sin(u / 9.0 + layer.random(s.x(), s.z(), 8307) * 6.0);
    }

    /** The body's axis height at u: at the silt's level along most of it, sinking under the silt towards the tail. */
    static double axisY(Spot s, double u) {
        final double t = u / (s.length() / 2); // -1 at the tail, 1 at the head
        return s.floor() + 1.0 - (t < -0.4 ? (-0.4 - t) * 10.0 : 0.0); // (its upper half arching out of the silt)
    }

    /** Its girth at u: full along the body, narrowing into the tail (as a living worm's). */
    static double girth(Spot s, double u) {
        final double t = u / (s.length() / 2);
        return s.radius() * (t < -0.3 ? Math.max(0.35, 1.0 - (-0.3 - t) * 0.9) : 1.0);
    }

    /** Where a column lies in the body's frame: u along it, v off its middle line. */
    static double[] frame(TunnelLayer layer, Spot s, double x, double z) {
        final double dx = x - s.x() - 0.5, dz = z - s.z() - 0.5;
        final double u = dx * s.dirX() + dz * s.dirZ();
        return new double[] {u, -dx * s.dirZ() + dz * s.dirX() - sway(layer, s, u)};
    }

    private boolean inChunk(int x, int z) {
        return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
    }

    /** Only into air and silt (it lies in the silt, not in the rock). */
    private void put(int x, int y, int z, BlockState state) {
        if (!inChunk(x, z)) {
            return;
        }
        final BlockState here = level.getBlockState(pos.set(x, y, z));
        if (here.isAir() || here.is(AbyssalDepths.FINE_SILT.get()) || here.is(AbyssalDepths.PACKED_SILT.get()) || here.is(AbyssalDepths.QUICKSILT.get())) {
            level.setBlock(pos, state, 2);
        }
    }

    /**
     * The hollow the wind scoured round it: the dunes lie low along the body (the silt no higher than a block over the
     * floor), rising back to their own height over a few blocks round it, so the shell stands clear.
     */
    private void scour(Spot s) {
        final double half = s.length() / 2, clear = s.radius() + 5.0;
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                final double[] f = frame(layer, s, x + 0.5, z + 0.5);
                final double along = f[0] < -half - 4 ? -half - 4 - f[0] : f[0] > half + 8 ? f[0] - half - 8 : 0.0;
                final double d = Math.hypot(along, f[1]) / clear; // 0 on the body, 1 at the hollow's edge
                if (d >= 1.6) {
                    continue;
                }
                final double cap = s.floor() + 1 + (d < 1.0 ? 0.0 : (d - 1.0) / 0.6 * 6.0) + 1.5 * layer.noise3(x / 5.0, 0.4, z / 5.0, 17);
                for (int y = s.floor() + 20; y > cap; y--) {
                    if (level.getBlockState(pos.set(x, y, z)).is(AbyssalDepths.FINE_SILT.get())) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /**
     * The shell: a hollow tube of hardened skin round the axis, a ring standing out where each segment met, silt drifted
     * into its lower half; broken and fallen in along stretches (its top gone there).
     */
    private void shell(Spot s, int gx, int gz) {
        final BlockState shell = AbyssalDepths.WORM_SHELL.get().defaultBlockState();
        final double half = s.length() / 2;
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                final double[] f = frame(layer, s, x + 0.5, z + 0.5);
                final double u = f[0], v = f[1];
                if (u < -half || u > half) {
                    continue;
                }
                final double r = girth(s, u), ay = axisY(s, u);
                if (Math.abs(v) > r + 1.2) {
                    continue;
                }
                final boolean ring = Math.floorMod((int) Math.floor(u), 3) == 0; // where the segments met
                final double thick = ring ? 1.6 : 1.0;
                final boolean broken = layer.noise3(u / 7.0, gx * 0.7, gz * 0.7, 18) > 0.5; // fallen in along this stretch
                for (int y = (int) Math.floor(ay - r) - 1; y <= (int) Math.ceil(ay + r) + 1; y++) {
                    final double dy = y + 0.5 - ay;
                    final double d = Math.hypot(v, dy);
                    if (d <= r && d > r - thick) {
                        if (broken && dy > r * 0.15) {
                            continue; // the top fell in here
                        }
                        put(x, y, z, shell);
                    } else if (d <= r - thick && y < s.floor()) {
                        put(x, y, z, AbyssalDepths.FINE_SILT.get().defaultBlockState()); // silt drifted inside
                    } else if (d <= r - thick && inChunk(x, z) && level.getBlockState(pos.set(x, y, z)).is(AbyssalDepths.FINE_SILT.get())) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2); // hollow above it
                    }
                }
            }
        }
    }

    /** Its head: the jaw plates gaping (top and bottom) at the front, its fangs lying sunk in the silt round them. */
    private void head(Spot s, int gx, int gz) {
        final BlockState shell = AbyssalDepths.WORM_SHELL.get().defaultBlockState();
        final double half = s.length() / 2, r = s.radius();
        final double ay = axisY(s, half);
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                final double[] f = frame(layer, s, x + 0.5, z + 0.5);
                final double w = f[0] - half; // past the end of the body: the plates
                if (w < 0 || w > r * 1.6 || Math.abs(f[1]) > r) {
                    continue;
                }
                final double open = w * 0.55; // the plates part as they reach forward
                final int top = (int) Math.floor(ay + r * 0.6 + open), bottom = (int) Math.floor(ay - r * 0.6 - open * 0.5);
                if (Math.abs(f[1]) < r * (1.0 - w / (r * 2.2))) {
                    put(x, top, z, shell);
                    put(x, bottom, z, shell);
                }
            }
        }
        // the fangs: a ring of them round the mouth, fallen into the silt below it
        for (int k = 0; k < 6; k++) {
            final double a = k * Math.PI / 3 + layer.random(gx, gz, 8340 + k) * 0.5;
            final double u = half + 1.0 + layer.random(gx, gz, 8350 + k) * r, v = Math.cos(a) * r * 0.9;
            final double wx = s.x() + 0.5 + u * s.dirX() - (v + sway(layer, s, u)) * s.dirZ();
            final double wz = s.z() + 0.5 + u * s.dirZ() + (v + sway(layer, s, u)) * s.dirX();
            put((int) Math.floor(wx), s.floor() - 1 - (int) (layer.random(gx, gz, 8360 + k) * 2), (int) Math.floor(wz),
                    AbyssalDepths.WORM_FANG.get().defaultBlockState());
        }
    }

    /** The nearest dead worm to a place (its middle and the floor there), or null. */
    static BlockPos nearest(TunnelLayer layer, Biomes biomes, int x, int z, int cells) {
        BlockPos best = null;
        double near = Double.MAX_VALUE;
        final int cx = Math.floorDiv(x, CELL), cz = Math.floorDiv(z, CELL);
        for (int gx = cx - cells; gx <= cx + cells; gx++) {
            for (int gz = cz - cells; gz <= cz + cells; gz++) {
                final Spot s = spot(layer, biomes, gx, gz);
                if (s != null && Math.hypot(s.x() - x, s.z() - z) < near) {
                    near = Math.hypot(s.x() - x, s.z() - z);
                    best = new BlockPos(s.x(), s.floor(), s.z());
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the wind and the storms (run time)

    /**
     * What the weather does to it: while a dune storm rages the wind strips the silt off it (more each storm, never
     * below its own level), laying the shell and, at its mouth, the fangs bare; in calm the drifting silt slowly covers
     * its lower parts again. Called for dead worms near players.
     */
    static final class Live {
        private Live() {
        }

        static void tick(net.minecraft.server.level.ServerLevel level, TunnelLayer layer, Spot s) {
            final boolean storm = redero.abyssaldepths.DuneStorm.remaining() > 0;
            final long time = level.getGameTime();
            if (time % (storm ? 4 : 30) != 0) {
                return;
            }
            final net.minecraft.util.RandomSource random = level.random;
            final double half = s.length() / 2;
            final double u = -half + random.nextDouble() * (s.length() + s.radius() * 2.5), v = (random.nextDouble() - 0.5) * 2 * (s.radius() + 3);
            final double sv = v + sway(layer, s, u);
            final int x = (int) Math.floor(s.x() + 0.5 + u * s.dirX() - sv * s.dirZ()), z = (int) Math.floor(s.z() + 0.5 + u * s.dirZ() + sv * s.dirX());
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, s.floor() + 12, z);
            if (!level.isLoaded(pos)) {
                return;
            }
            while (pos.getY() > s.floor() - 4 && level.getBlockState(pos).isAir()) {
                pos.move(0, -1, 0);
            }
            final BlockState top = level.getBlockState(pos);
            if (storm) { // the wind strips a layer of silt off what lies under it
                if (top.is(AbyssalDepths.FINE_SILT.get()) && pos.getY() >= s.floor() - 3 && hasRemains(level, pos)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                }
            } else if ((top.is(AbyssalDepths.WORM_SHELL.get()) || top.is(AbyssalDepths.WORM_FANG.get())) && pos.getY() < s.floor()
                    && level.getBlockState(pos.above()).isAir()) { // calm: silt drifts over its low parts again
                level.setBlock(pos.above(), AbyssalDepths.FINE_SILT.get().defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
        }

        /** Whether the worm's remains lie under a place (within a few blocks down). */
        private static boolean hasRemains(net.minecraft.server.level.ServerLevel level, BlockPos at) {
            for (int k = 1; k <= 4; k++) {
                final BlockState below = level.getBlockState(at.below(k));
                if (below.is(AbyssalDepths.WORM_SHELL.get()) || below.is(AbyssalDepths.WORM_FANG.get())) {
                    return true;
                }
            }
            return false;
        }
    }
}
