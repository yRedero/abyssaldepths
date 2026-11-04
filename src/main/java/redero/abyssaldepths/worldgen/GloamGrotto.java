package redero.abyssaldepths.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * The gloam grotto: a rare set piece of the thalan depths, the heart of the gloam. In a tall hall lies a still, dark
 * lake, deeper in its middle, held in by a rim of rock; spires of gloam crystal rise round it and out of it, the
 * ceiling over it is thick with crystal and hanging clusters, every wall round it carries moss, and gloamcaps stand on
 * its shore. At most one in a large grid cell, and only where the cell's spot lies in the depths with room overhead.
 * Everything is decided from the cell, so each chunk paints its own part of it.
 */
final class GloamGrotto {
    static final int CELL = 480, REACH = 34;
    private static final double CHANCE = 0.5;

    /** A grotto: its middle, the lake's surface height, the lake's radius. */
    record Spot(int x, int z, int floor, double lake) {
    }

    private final WorldGenLevel level;
    private final TunnelLayer layer;
    private final Biomes biomes;
    private final GloamPockets gloam;
    private final int x0, z0;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    GloamGrotto(WorldGenLevel level, TunnelLayer layer, Biomes biomes, GloamPockets gloam, int x0, int z0) {
        this.level = level;
        this.layer = layer;
        this.biomes = biomes;
        this.gloam = gloam;
        this.x0 = x0;
        this.z0 = z0;
    }

    /** The grotto of a cell, if it has one. */
    static Spot spot(TunnelLayer layer, Biomes biomes, int gx, int gz) {
        if (layer.random(gx, gz, 8101) >= CHANCE) {
            return null;
        }
        final int x = gx * CELL + 60 + (int) (layer.random(gx, gz, 8102) * (CELL - 120));
        final int z = gz * CELL + 60 + (int) (layer.random(gx, gz, 8103) * (CELL - 120));
        final TunnelLayer.Column c = new TunnelLayer.Column();
        if (biomes.at(x, z) != Biomes.DEPTHS || !layer.column(x, z, c) || c.ceiling - c.floor < 14) {
            return null; // not in the depths, or no tall hall there
        }
        return new Spot(x, z, c.floor, 13.0 + layer.random(gx, gz, 8104) * 5.0);
    }

    void apply() {
        for (int gx = Math.floorDiv(x0 - REACH, CELL); gx <= Math.floorDiv(x0 + 15 + REACH, CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - REACH, CELL); gz <= Math.floorDiv(z0 + 15 + REACH, CELL); gz++) {
                final Spot s = spot(layer, biomes, gx, gz);
                if (s == null || s.x() + REACH < x0 || s.x() - REACH > x0 + 15 || s.z() + REACH < z0 || s.z() - REACH > z0 + 15) {
                    continue;
                }
                lake(s);
                spires(s, gx, gz);
                gloam.rich = true;
                gloam.glow(s.x(), s.floor(), s.z(), 22.0);
                gloam.ceiling(s.x(), s.floor(), s.z(), 20.0);
                for (int k = 0; k < 4; k++) { // gloamcaps along the shore
                    final double a = (k + layer.random(gx, gz, 8110 + k) * 0.6) * Math.PI / 2;
                    final double d = s.lake() + 4.0 + layer.random(gx, gz, 8120 + k) * 4.0;
                    gloam.gloamcaps(gx * 7 + 9000 + k, gz * 7 + 9000, s.x() + (int) Math.round(Math.cos(a) * d), s.z() + (int) Math.round(Math.sin(a) * d), 10.5);
                }
                gloam.rich = false;
            }
        }
    }

    private boolean inChunk(int x, int z) {
        return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
    }

    /** The lake's edge at a column (a little ragged), as a share of its radius: below 1 is water. */
    private double edge(Spot s, int x, int z) {
        final double dx = x - s.x(), dz = z - s.z();
        return Math.sqrt(dx * dx + dz * dz) / (s.lake() * (1.0 + 0.15 * layer.noise3(x / 6.0, 0.3, z / 6.0, 15)));
    }

    /** The lake: water to the level of the floor at its middle, a bowl beneath, room above it, a rim of rock round it. */
    private void lake(Spot s) {
        final int surface = s.floor() - 1;
        final int r = (int) Math.ceil(s.lake() * 1.2) + 2;
        final BlockState rock = AbyssalDepths.THALAN.get().defaultBlockState();
        for (int x = Math.max(x0, s.x() - r); x <= Math.min(x0 + 15, s.x() + r); x++) {
            for (int z = Math.max(z0, s.z() - r); z <= Math.min(z0 + 15, s.z() + r); z++) {
                final double e = edge(s, x, z);
                if (e < 1.0) {
                    final int depth = 1 + (int) Math.round(3.0 * (1.0 - e));
                    for (int y = surface + 1; y <= surface + 4; y++) { // room above the water
                        if (!level.getBlockState(pos.set(x, y, z)).isAir() && y < s.floor() + 3) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                    for (int y = surface - depth + 1; y <= surface; y++) {
                        level.setBlock(pos.set(x, y, z), Blocks.WATER.defaultBlockState(), 2);
                    }
                    for (int y = surface - depth - 1; y <= surface - depth; y++) { // a bed under it
                        if (!level.getBlockState(pos.set(x, y, z)).isSolidRender(level, pos)) {
                            level.setBlock(pos, rock, 2);
                        }
                    }
                    // the bed set with gloam, thickest where it is deepest: the dark water glows from below
                    if (layer.random(x, z, 8170) < 0.15 + 0.6 * (1.0 - e)) {
                        level.setBlock(pos.set(x, surface - depth, z), AbyssalDepths.GLOAM_CRYSTAL.get().defaultBlockState(), 2);
                    }
                } else if (e < 1.0 + 2.5 / s.lake()) { // the rim: rock at the water's level and below, so it stays in
                    for (int y = surface - 4; y <= surface; y++) {
                        final BlockState here = level.getBlockState(pos.set(x, y, z));
                        if (here.isAir() || !here.getFluidState().isEmpty()) {
                            level.setBlock(pos, rock, 2);
                        }
                    }
                }
            }
        }
    }

    /** Spires of gloam crystal round the lake and out of it, narrowing as they rise, clusters at their tips. */
    private void spires(Spot s, int gx, int gz) {
        final int count = 3 + (int) (layer.random(gx, gz, 8130) * 3);
        for (int k = 0; k < count; k++) {
            final double a = layer.random(gx, gz, 8131 + k) * Math.PI * 2, d = layer.random(gx, gz, 8141 + k) * (s.lake() + 5.0);
            final int sx = s.x() + (int) Math.round(Math.cos(a) * d), sz = s.z() + (int) Math.round(Math.sin(a) * d);
            final int height = 6 + (int) (layer.random(gx, gz, 8151 + k) * 9);
            final double base = 1.4 + layer.random(gx, gz, 8161 + k) * 1.0;
            final int bottom = s.floor() - 4;
            for (int y = bottom; y <= s.floor() + height; y++) {
                final double t = (y - s.floor()) / (double) height;
                final double radius = y < s.floor() ? base + 0.4 : base * (1.0 - 0.8 * t) + 0.3;
                final int r = (int) Math.ceil(radius);
                for (int x = sx - r; x <= sx + r; x++) {
                    for (int z = sz - r; z <= sz + r; z++) {
                        if (!inChunk(x, z) || Math.hypot(x - sx, z - sz) > radius) {
                            continue;
                        }
                        final BlockState here = level.getBlockState(pos.set(x, y, z));
                        if (y < s.floor() && here.isSolidRender(level, pos)) {
                            continue; // its root runs into the rock
                        }
                        level.setBlock(pos, AbyssalDepths.GLOAM_CRYSTAL.get().defaultBlockState(), 2);
                    }
                }
            }
            final int tip = s.floor() + height + 1;
            if (inChunk(sx, sz) && level.getBlockState(pos.set(sx, tip, sz)).isAir()) {
                level.setBlock(pos, AbyssalDepths.GLOAM_CLUSTER.get().defaultBlockState().setValue(AmethystClusterBlock.FACING, Direction.UP), 2);
            }
            skirt(s, sx, sz, base, gx, gz, k);
        }
    }

    /**
     * The skirt round a spire's foot: clusters growing up from the ground (under water too, where it stands in the
     * lake), moss between them on the shore.
     */
    private void skirt(Spot s, int sx, int sz, double base, int gx, int gz, int k) {
        final int r = (int) Math.ceil(base + 3.5);
        for (int x = Math.max(x0, sx - r); x <= Math.min(x0 + 15, sx + r); x++) {
            for (int z = Math.max(z0, sz - r); z <= Math.min(z0 + 15, sz + r); z++) {
                final double d = Math.hypot(x - sx, z - sz);
                if (d <= base + 0.3 || d > base + 3.5) {
                    continue;
                }
                int y = s.floor() + 2; // down to the ground (the lake's bed, or the shore)
                while (y > s.floor() - 8) {
                    final BlockState here = level.getBlockState(pos.set(x, y - 1, z));
                    if (!here.isAir() && here.getFluidState().isEmpty() && !here.is(AbyssalDepths.GLOAM_MOSS.get())) {
                        break;
                    }
                    y--;
                }
                final BlockState above = level.getBlockState(pos.set(x, y, z));
                final double roll = layer.random(x, z, 8180 + k);
                final double near = 1.0 - (d - base) / 3.5; // thicker near the spire
                if (roll < 0.55 * near) {
                    final boolean wet = above.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
                    if (above.isAir() || wet || above.is(AbyssalDepths.GLOAM_MOSS.get())) {
                        level.setBlock(pos, AbyssalDepths.GLOAM_CLUSTER.get().defaultBlockState().setValue(AmethystClusterBlock.FACING, Direction.UP)
                                .setValue(AmethystClusterBlock.WATERLOGGED, wet), 2);
                    }
                } else if (roll < 0.85 * near && above.isAir()) {
                    level.setBlock(pos, AbyssalDepths.GLOAM_MOSS.get().defaultBlockState()
                            .setValue(net.minecraft.world.level.block.MultifaceBlock.getFaceProperty(Direction.DOWN), true), 2);
                }
            }
        }
    }

    /** The nearest grotto's middle to a place (searching the cells round it), or null. */
    static BlockPos nearest(TunnelLayer layer, Biomes biomes, int x, int z, int cells) {
        BlockPos best = null;
        double near = Double.MAX_VALUE;
        final int cx = Math.floorDiv(x, CELL), cz = Math.floorDiv(z, CELL);
        for (int gx = cx - cells; gx <= cx + cells; gx++) {
            for (int gz = cz - cells; gz <= cz + cells; gz++) {
                final Spot s = spot(layer, biomes, gx, gz);
                if (s != null) {
                    final double d = Math.hypot(s.x() - x, s.z() - z);
                    if (d < near) {
                        near = d;
                        best = new BlockPos(s.x(), s.floor(), s.z());
                    }
                }
            }
        }
        return best;
    }
}
