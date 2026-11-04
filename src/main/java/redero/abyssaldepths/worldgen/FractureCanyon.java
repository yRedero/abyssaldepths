package redero.abyssaldepths.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * The fracture canyon: a rare set piece of the shale flats, the rock the heat cracked. One long crack runs zigzag through
 * the floor of the plain, widest in its middle and closing at both ends, narrowing as it goes down so its walls lean in;
 * its walls are fractured shale. At its bottom the heat that cracked it still glows: magma along the floor and, in its
 * deepest stretch, a thin seam of lava sunk in a groove. Here and there a bridge of shale was left standing across it.
 * At most one in a large grid cell, only in the flats. Everything is decided from the cell, so each chunk cuts its own
 * part.
 */
final class FractureCanyon {
    static final int CELL = 260, REACH = 64;
    private static final double CHANCE = 0.75;
    /** The lowest the canyon reaches: a few blocks above the layer's bottom. */
    private static final int LOWEST = TunnelLayer.BOTTOM_Y + 4;

    /** A canyon: its middle (x, z), the floor there, the way it runs (unit vector), its length, width and depth. */
    record Spot(int x, int z, int floor, double dirX, double dirZ, double length, double width, double depth) {
    }

    private final WorldGenLevel level;
    private final TunnelLayer layer;
    private final Biomes biomes;
    private final int x0, z0;
    private final TunnelLayer.Column col = new TunnelLayer.Column();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    FractureCanyon(WorldGenLevel level, TunnelLayer layer, Biomes biomes, int x0, int z0) {
        this.level = level;
        this.layer = layer;
        this.biomes = biomes;
        this.x0 = x0;
        this.z0 = z0;
    }

    static Spot spot(TunnelLayer layer, Biomes biomes, int gx, int gz) {
        if (layer.random(gx, gz, 8401) >= CHANCE) {
            return null;
        }
        final int x = gx * CELL + 80 + (int) (layer.random(gx, gz, 8402) * (CELL - 160));
        final int z = gz * CELL + 80 + (int) (layer.random(gx, gz, 8403) * (CELL - 160));
        final TunnelLayer.Column c = new TunnelLayer.Column();
        if (biomes.at(x, z) != Biomes.FLATS || !layer.column(x, z, c)) {
            return null;
        }
        final double a = layer.random(gx, gz, 8404) * Math.PI;
        final double length = 70.0 + layer.random(gx, gz, 8405) * 40.0;
        final double dx = Math.cos(a), dz = Math.sin(a);
        for (int end = -1; end <= 1; end += 2) { // the plain must carry it to both ends
            if (biomes.at(x + (int) (dx * end * length * 0.45), z + (int) (dz * end * length * 0.45)) != Biomes.FLATS) {
                return null;
            }
        }
        final double depth = Math.min(18.0, c.floor - LOWEST);
        if (depth < 8) {
            return null; // no room below for it
        }
        return new Spot(x, z, c.floor, dx, dz, length, 3.0 + layer.random(gx, gz, 8406) * 2.0, depth);
    }

    void apply() {
        for (int gx = Math.floorDiv(x0 - REACH, CELL); gx <= Math.floorDiv(x0 + 15 + REACH, CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - REACH, CELL); gz <= Math.floorDiv(z0 + 15 + REACH, CELL); gz++) {
                final Spot s = spot(layer, biomes, gx, gz);
                if (s == null || s.x() + REACH < x0 || s.x() - REACH > x0 + 15 || s.z() + REACH < z0 || s.z() - REACH > z0 + 15) {
                    continue;
                }
                cut(s, gx, gz);
            }
        }
    }

    private double u(Spot s, double x, double z) {
        return (x - s.x()) * s.dirX() + (z - s.z()) * s.dirZ();
    }

    private double v(Spot s, double x, double z) {
        return -(x - s.x()) * s.dirZ() + (z - s.z()) * s.dirX();
    }

    /** Where the crack runs across at u: a zigzag (straight stretches breaking at angles) with a slow wander. */
    private double middle(Spot s, double u) {
        final double zig = u / 9.0;
        final double tri = Math.abs(zig - Math.floor(zig) - 0.5) * 4.0 - 1.0; // -1 .. 1, straight stretches
        return 2.2 * tri + 5.0 * layer.noise3(u / 40.0, 0.2, s.x() * 0.001, 18);
    }

    /** How far from 0 (its ends) to 1 (its middle) the crack is open at u. */
    private double open(Spot s, double u) {
        final double t = Math.abs(u) / (s.length() / 2);
        return t >= 1.0 ? 0.0 : Math.sqrt(1.0 - t * t);
    }

    /** The depth below the floor the crack reaches at u. */
    private double depthAt(Spot s, double u) {
        return s.depth() * Math.pow(open(s, u), 0.7) * (0.85 + 0.15 * layer.noise3(u / 7.0, 0.5, 3.0, 19));
    }

    /** Whether the crack is open at a place (d blocks below the floor of the middle, d may be negative: above it). */
    private boolean inside(Spot s, double u, double v, int d) {
        final double o = open(s, u);
        if (o <= 0.0) {
            return false;
        }
        final double deep = depthAt(s, u);
        if (d > deep) {
            return false;
        }
        final double down = Math.max(0.0, d) / Math.max(1.0, deep); // 0 at the top, 1 at the bottom
        final double half = s.width() * o * (1.0 - 0.65 * down) * (1.0 + 0.25 * layer.noise3(u / 3.0, d / 4.0, v * 0.1, 20)) + 0.4;
        return Math.abs(v - middle(s, u)) < half;
    }

    /** The bridges: a few short spans of shale left standing across the crack, just under its top. */
    private boolean bridge(Spot s, int gx, int gz, double u, int d) {
        if (d < 1 || d > 3) {
            return false;
        }
        final int count = 1 + (int) (layer.random(gx, gz, 8410) * 3);
        for (int k = 0; k < count; k++) {
            final double at = (layer.random(gx, gz, 8411 + k) - 0.5) * s.length() * 0.7;
            final double thick = d == 1 ? 1.4 : d == 2 ? 1.0 : 0.6; // arched: thicker at its top
            if (Math.abs(u - at) < thick) {
                return true;
            }
        }
        return false;
    }

    private void cut(Spot s, int gx, int gz) {
        final BlockState fractured = AbyssalDepths.FRACTURED_SHALE.get().defaultBlockState();
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                final double u = u(s, x + 0.5, z + 0.5), v = v(s, x + 0.5, z + 0.5);
                if (Math.abs(u) > s.length() / 2 + 2 || Math.abs(v) > s.width() + 10) {
                    continue;
                }
                if (!layer.column(x, z, col) || biomes.at(x, z) != Biomes.FLATS) {
                    continue; // under a pillar, or off the plain
                }
                final int top = col.floor + 8; // over the plateaus of the plain
                for (int y = top; y >= LOWEST; y--) {
                    final int d = s.floor() - y;
                    final BlockState here = level.getBlockState(pos.set(x, y, z));
                    if (here.isAir()) {
                        continue;
                    }
                    if (inside(s, u, v, d) && !bridge(s, gx, gz, u, d)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        continue;
                    }
                    // a wall: solid next to the open crack
                    if (d >= -8 && (inside(s, u + 1, v, d) || inside(s, u - 1, v, d) || inside(s, u, v + 1, d) || inside(s, u, v - 1, d)
                            || inside(s, u, v, d - 1))) {
                        level.setBlock(pos, fractured, 2);
                    }
                }
                bottom(s, u, v, x, z);
            }
        }
    }

    /** Its bottom: magma scattered along it, and in its deepest stretch a thin seam of lava in a groove of rock. */
    private void bottom(Spot s, double u, double v, int x, int z) {
        final double deep = depthAt(s, u);
        if (!inside(s, u, v, (int) Math.floor(deep))) {
            return;
        }
        final int y = s.floor() - (int) Math.floor(deep) - 1; // the block under the lowest air
        if (!level.getBlockState(pos.set(x, y + 1, z)).isAir() || level.getBlockState(pos.set(x, y, z)).isAir()) {
            return;
        }
        final boolean seam = open(s, u) > 0.75 && Math.abs(v - middle(s, u)) < 0.6;
        if (seam && !level.getBlockState(pos.set(x, y - 1, z)).isAir()) {
            level.setBlock(pos.set(x, y, z), Blocks.LAVA.defaultBlockState(), 2);
        } else if (layer.random(x, z, 8420) < 0.35) {
            level.setBlock(pos.set(x, y, z), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
        }
    }

    /**
     * Where to stand to see the nearest canyon: over its end, looking down its length ({x, y, z, yaw}), or null.
     */
    static double[] view(TunnelLayer layer, Biomes biomes, int x, int z, int cells) {
        final BlockPos at = nearest(layer, biomes, x, z, cells);
        if (at == null) {
            return null;
        }
        final Spot s = spot(layer, biomes, Math.floorDiv(at.getX(), CELL), Math.floorDiv(at.getZ(), CELL));
        final float yaw = (float) Math.toDegrees(Math.atan2(-s.dirX(), s.dirZ()));
        final TunnelLayer.Column c = new TunnelLayer.Column();
        for (double back = s.length() / 2 + 4; back > 0; back -= 2) { // from its end inwards, to where there is room to stand
            final double vx = s.x() + 0.5 - s.dirX() * back, vz = s.z() + 0.5 - s.dirZ() * back;
            if (layer.column((int) Math.floor(vx), (int) Math.floor(vz), c) && c.ceiling - c.floor > 8) {
                return new double[] {vx, Math.max(c.floor, s.floor()) + 6, vz, yaw};
            }
        }
        return new double[] {s.x() + 0.5, s.floor() + 6, s.z() + 0.5, yaw};
    }

    /** The nearest canyon to a place (its middle and the floor there), or null. */
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
}
