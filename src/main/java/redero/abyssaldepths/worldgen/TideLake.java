package redero.abyssaldepths.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.block.TideStoneBlock;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * The tide lake: a rare set piece of the thalan depths. The hall's floor is flooded to a level with still, black water,
 * its shore ragged, its bed uneven; what stood on the floor stands in it. A spine of rock winds out under the water
 * from the shore to a rough island in the middle, gloam grown over it. Once every two minutes the tide goes out and
 * the spine's crest comes up stone after stone from the shore, thalan and glowing gloam, a broken way to the island;
 * later it goes under again in the same order (see {@link TideStoneBlock}). At most one in a large grid cell, only in
 * a broad open hall of the depths. Everything is decided from the cell, so each chunk lays its own part.
 */
final class TideLake {
    static final int CELL = 460, REACH = 34;
    private static final double CHANCE = 0.55;
    private static final double ISLAND = 3.6;

    /** A lake: its middle (x, z), the floor there, its radius, and where its path leaves the shore (an angle). */
    record Spot(int x, int z, int floor, double lake, double path) {
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

    void apply() {
        for (int gx = Math.floorDiv(x0 - REACH, CELL); gx <= Math.floorDiv(x0 + 15 + REACH, CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - REACH, CELL); gz <= Math.floorDiv(z0 + 15 + REACH, CELL); gz++) {
                final Spot s = spot(layer, biomes, formations, gx, gz);
                if (s == null || s.x() + REACH < x0 || s.x() - REACH > x0 + 15 || s.z() + REACH < z0 || s.z() - REACH > z0 + 15) {
                    continue;
                }
                lake(s, spine(s, gx, gz));
            }
        }
    }

    /**
     * The spine of rock under the water, from the shore to the island: walked from the shore towards the middle, its
     * heading wandering as it goes, so it winds the way a ridge does, not a drawn line. Points every half block.
     */
    private double[][] spine(Spot s, int gx, int gz) {
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

    /** How far a column lies from the spine, and how far along it (0 at the shore, 1 at the island) its nearest point is. */
    private double[] nearSpine(double[][] spine, int x, int z) {
        double near = Double.MAX_VALUE;
        int at = 0;
        for (int k = 0; k < spine.length; k++) {
            final double d = Math.hypot(x + 0.5 - spine[k][0], z + 0.5 - spine[k][1]);
            if (d < near) {
                near = d;
                at = k;
            }
        }
        return new double[] {near, at / (double) Math.max(1, spine.length - 1)};
    }

    /**
     * The lake: the hall's own floor filled to a level, its shore ragged and lobed, its bed uneven; whatever stands on
     * the floor (shelves, shards) stays and stands in the water. The spine under it, its crest broken into stones that
     * come up at low tide; the island a rough mound with gloam grown on it.
     */
    private void lake(Spot s, double[][] spine) {
        final int surface = s.floor() - 1;
        final BlockState rock = AbyssalDepths.THALAN.get().defaultBlockState();
        final int r = (int) Math.ceil(s.lake() * 1.45) + 3;
        for (int x = Math.max(x0, s.x() - r); x <= Math.min(x0 + 15, s.x() + r); x++) {
            for (int z = Math.max(z0, s.z() - r); z <= Math.min(z0 + 15, s.z() + r); z++) {
                final double d = Math.hypot(x - s.x(), z - s.z());
                final double shape = 1.0 + 0.3 * layer.noise3(x / 11.0, 0.3, z / 11.0, 24) + 0.12 * layer.noise3(x / 4.0, 0.6, z / 4.0, 27);
                final double e = d / (s.lake() * shape);
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
                final int depth = Math.max(1, Math.min(4, (int) Math.round(1.0 + 2.2 * (1.0 - e) + 0.9 * layer.noise3(x / 5.0, 0.2, z / 5.0, 28))));
                final int bed = surface - depth;
                for (int y = bed - 1; y <= bed; y++) {
                    final BlockState here = level.getBlockState(pos.set(x, y, z));
                    if (here.isAir() || !here.getFluidState().isEmpty()) {
                        level.setBlock(pos, rock, 2);
                    }
                }
                final double island = ISLAND * (1.0 + 0.3 * layer.noise3(x / 3.0, 0.1, z / 3.0, 25));
                if (d < island) {
                    island(s, x, z, d / island, bed);
                    continue;
                }
                for (int y = bed + 1; y <= surface; y++) { // the water: the ground below the level gives way to it
                    level.setBlock(pos.set(x, y, z), Blocks.WATER.defaultBlockState(), 2);
                }
                final double[] near = nearSpine(spine, x, z);
                if (near[0] < 2.4) {
                    spineAt(s, x, z, near[0], near[1], bed);
                }
            }
        }
    }

    /** The spine where it passes a column: its shoulders under the water, its crest just under, broken into stones. */
    private void spineAt(Spot s, int x, int z, double off, double along, int bed) {
        final int surface = s.floor() - 1;
        final int top = off < 1.0 ? surface - 1 : off < 1.7 ? surface - 2 : surface - 3;
        for (int y = bed + 1; y <= top; y++) {
            level.setBlock(pos.set(x, y, z), AbyssalDepths.THALAN.get().defaultBlockState(), 2);
        }
        if (top == surface - 1 && layer.random(x, z, 8711) < 0.8) { // a stone of the crest (the rest are gaps)
            final boolean gloam = layer.random(x, z, 8712) < 0.3;
            level.setBlock(pos.set(x, top, z), AbyssalDepths.TIDE_STONE.get().defaultBlockState()
                    .setValue(TideStoneBlock.STEP, (int) Math.min(15, along * 16)).setValue(TideStoneBlock.GLOAM, gloam), 2);
            level.scheduleTick(pos, AbyssalDepths.TIDE_STONE.get(), 20 + (int) (layer.random(x, z, 8710) * 40));
        } else if (top == surface - 1) {
            level.setBlock(pos.set(x, top, z), Blocks.WATER.defaultBlockState(), 2);
        }
    }

    /** The island: a rough mound of thalan and cobbles up to two blocks over the water, gloam grown over its top. */
    private void island(Spot s, int x, int z, double f, int bed) {
        final int surface = s.floor() - 1;
        final int top = surface + (int) Math.round(2.4 * (1.0 - f * f) - 0.3 + 0.6 * layer.noise3(x / 2.0, 0.7, z / 2.0, 29));
        for (int y = bed + 1; y <= Math.max(top, surface); y++) {
            final boolean wet = y > top;
            final BlockState rock = layer.random(x, z, 8720 + y) < 0.25 ? AbyssalDepths.COBBLED_THALAN.get().defaultBlockState()
                    : AbyssalDepths.THALAN.get().defaultBlockState();
            level.setBlock(pos.set(x, y, z), wet ? Blocks.WATER.defaultBlockState() : rock, 2);
        }
        if (top < surface) {
            return;
        }
        final double roll = layer.random(x, z, 8730);
        if (f < 0.45 && roll < 0.5) { // gloam broken out at its heart
            level.setBlock(pos.set(x, top, z), AbyssalDepths.GLOAM_CRYSTAL.get().defaultBlockState(), 2);
            if (layer.random(x, z, 8731) < 0.5 && level.getBlockState(pos.set(x, top + 1, z)).isAir()) {
                level.setBlock(pos, AbyssalDepths.GLOAM_CLUSTER.get().defaultBlockState(), 2);
            }
        } else if (roll < 0.45 && level.getBlockState(pos.set(x, top + 1, z)).isAir()) { // moss over the rest
            level.setBlock(pos, AbyssalDepths.GLOAM_MOSS.get().defaultBlockState()
                    .setValue(net.minecraft.world.level.block.MultifaceBlock.getFaceProperty(net.minecraft.core.Direction.DOWN), true), 2);
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
}
