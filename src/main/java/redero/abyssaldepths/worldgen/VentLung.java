package redero.abyssaldepths.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.block.ThermalVentBlock;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * The vent lung: a rare set piece of the thermal reaches. A shallow bowl in the floor, its ground thermal rock, set
 * with vents in rings round a cluster in its middle, smouldering rock cracked round each. The field breathes: every
 * so often all of it hisses, then the vents burst ring after ring from the middle outwards, a wave of steam rolling
 * across the bowl (the timing is in {@link ThermalVentBlock}: each vent knows only its ring). Crossing it is a
 * matter of listening for the hiss. At most one in a large grid cell, only in the reaches. Everything is decided from
 * the cell, so each chunk lays its own part.
 */
final class VentLung {
    static final int CELL = 400, REACH = 22;
    private static final double CHANCE = 0.55;
    private static final int RINGS = 8;
    private static final double RING_STEP = 2.2;

    /** A lung: its middle (x, z), the floor there, the bowl's radius. */
    record Spot(int x, int z, int floor, double radius) {
    }

    private final WorldGenLevel level;
    private final TunnelLayer layer;
    private final Biomes biomes;
    private final int x0, z0;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    VentLung(WorldGenLevel level, TunnelLayer layer, Biomes biomes, int x0, int z0) {
        this.level = level;
        this.layer = layer;
        this.biomes = biomes;
        this.x0 = x0;
        this.z0 = z0;
    }

    static Spot spot(TunnelLayer layer, Biomes biomes, int gx, int gz) {
        if (layer.random(gx, gz, 8601) >= CHANCE) {
            return null;
        }
        final int x = gx * CELL + 50 + (int) (layer.random(gx, gz, 8602) * (CELL - 100));
        final int z = gz * CELL + 50 + (int) (layer.random(gx, gz, 8603) * (CELL - 100));
        final TunnelLayer.Column c = new TunnelLayer.Column();
        if (biomes.at(x, z) != Biomes.THERMAL || !layer.column(x, z, c) || c.ceiling - c.floor < 10) {
            return null;
        }
        return new Spot(x, z, c.floor, RINGS * RING_STEP + 2.5);
    }

    void apply() {
        for (int gx = Math.floorDiv(x0 - REACH, CELL); gx <= Math.floorDiv(x0 + 15 + REACH, CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - REACH, CELL); gz <= Math.floorDiv(z0 + 15 + REACH, CELL); gz++) {
                final Spot s = spot(layer, biomes, gx, gz);
                if (s == null || s.x() + REACH < x0 || s.x() - REACH > x0 + 15 || s.z() + REACH < z0 || s.z() - REACH > z0 + 15) {
                    continue;
                }
                bowl(s);
                vents(s, gx, gz);
            }
        }
    }

    /** The ground of the bowl at a distance from its middle (the top block's height). */
    private int ground(Spot s, double d) {
        final double f = Math.min(1.0, d / s.radius());
        return s.floor() - 1 - (int) Math.round(2.5 * (1.0 - f * f));
    }

    /** The bowl: the floor sunk a little towards the middle, thermal rock its ground, its edge ragged. */
    private void bowl(Spot s) {
        final BlockState rock = AbyssalDepths.THERMAL_ROCK.get().defaultBlockState();
        final int r = (int) Math.ceil(s.radius()) + 2;
        for (int x = Math.max(x0, s.x() - r); x <= Math.min(x0 + 15, s.x() + r); x++) {
            for (int z = Math.max(z0, s.z() - r); z <= Math.min(z0 + 15, s.z() + r); z++) {
                final double d = Math.hypot(x - s.x(), z - s.z());
                if (d > s.radius() * (1.0 + 0.12 * layer.noise3(x / 5.0, 0.8, z / 5.0, 23))) {
                    continue;
                }
                final int g = ground(s, d);
                for (int y = g + 1; y <= s.floor() + 3; y++) { // sunk: whatever stood on the floor here is gone
                    final BlockState here = level.getBlockState(pos.set(x, y, z));
                    if (!here.isAir() && y < s.floor() + 3) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                for (int y = g - 1; y <= g; y++) {
                    level.setBlock(pos.set(x, y, z), rock, 2);
                }
            }
        }
    }

    /** The vents: a cluster in the middle, then rings, each vent with a crust of smouldering rock round it. */
    private void vents(Spot s, int gx, int gz) {
        place(s, s.x(), s.z(), 1);
        for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            place(s, s.x() + o[0], s.z() + o[1], 1);
        }
        for (int k = 1; k <= RINGS; k++) {
            final double radius = k * RING_STEP;
            final int count = (int) Math.round(2 * Math.PI * radius / 3.2);
            final double turn = layer.random(gx * 13 + k, gz, 8610) * Math.PI * 2;
            for (int i = 0; i < count; i++) {
                if (layer.random(gx * 13 + k, gz * 7 + i, 8611) < 0.15) {
                    continue; // a gap
                }
                final double a = turn + i * Math.PI * 2 / count;
                place(s, s.x() + (int) Math.round(Math.cos(a) * radius), s.z() + (int) Math.round(Math.sin(a) * radius), k + 1);
            }
        }
    }

    private void place(Spot s, int x, int z, int wave) {
        final double d = Math.hypot(x - s.x(), z - s.z());
        final int g = ground(s, d);
        for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) { // the crust round it
            final int cx = x + o[0], cz = z + o[1];
            if (inChunk(cx, cz) && layer.random(cx, cz, 8620) < 0.6
                    && level.getBlockState(pos.set(cx, ground(s, Math.hypot(cx - s.x(), cz - s.z())), cz)).is(AbyssalDepths.THERMAL_ROCK.get())) {
                level.setBlock(pos, AbyssalDepths.SMOULDERING_ROCK.get().defaultBlockState(), 2);
            }
        }
        if (!inChunk(x, z)) {
            return;
        }
        level.setBlock(pos.set(x, g, z), AbyssalDepths.THERMAL_VENT.get().defaultBlockState().setValue(ThermalVentBlock.WAVE, wave), 2);
        // in time with the breath from the start (a random tick would only catch up after a minute or so)
        level.scheduleTick(pos, AbyssalDepths.THERMAL_VENT.get(), 20 + (int) (layer.random(x, z, 8621) * ThermalVentBlock.BREATH));
    }

    private boolean inChunk(int x, int z) {
        return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
    }

    /** The nearest lung to a place (its middle and the floor there), or null. */
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
