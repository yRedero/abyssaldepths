package redero.abyssaldepths.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * The lava fall: a rare set piece of the thermal reaches, where the heat comes up from below. Magma pushed up by that
 * heat has spread into a pocket in the rock over a tall hall (as rising magma spreads into weak layers) and broken
 * through the hall's roof: lava pours from the breach (the rock round it glowing) into a cup of smouldering rock
 * standing in a lake of seep water gathered at that low point; the lake steams round it. Where the lava meets the water
 * at the cup's lip it is quenched into fire-hardened slag. It only happens where the heat comes from below, so only in
 * the reaches. At most one in a large grid cell, under a tall ceiling. Everything is decided from the cell, so each
 * chunk lays its own part; the lava itself starts flowing where it is placed, like a spring.
 */
final class LavaFall {
    static final int CELL = 440, REACH = 22;
    private static final double CHANCE = 0.55;
    /** The cup: lava inside, its lip round it, the smouldering shore of the cup out to its edge. */
    private static final double POOL = 2.2, LIP = 3.6, CUP = 5.2;

    /** A fall: where it lands (x, z), the hall's floor there and its ceiling, the lake's radius. */
    record Spot(int x, int z, int floor, int ceiling, double lake, double dirX, double dirZ, int height) {
    }

    private final WorldGenLevel level;
    private final TunnelLayer layer;
    private final Biomes biomes;
    private final int x0, z0;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    LavaFall(WorldGenLevel level, TunnelLayer layer, Biomes biomes, int x0, int z0) {
        this.level = level;
        this.layer = layer;
        this.biomes = biomes;
        this.x0 = x0;
        this.z0 = z0;
    }

    static Spot spot(TunnelLayer layer, Biomes biomes, int gx, int gz) {
        if (layer.random(gx, gz, 8501) >= CHANCE) {
            return null;
        }
        final int x = gx * CELL + 50 + (int) (layer.random(gx, gz, 8502) * (CELL - 100));
        final int z = gz * CELL + 50 + (int) (layer.random(gx, gz, 8503) * (CELL - 100));
        final TunnelLayer.Column c = new TunnelLayer.Column();
        if (biomes.at(x, z) != Biomes.THERMAL || !layer.column(x, z, c) || c.ceiling - c.floor < 16) {
            return null; // not in the reaches, or no tall hall there
        }
        return new Spot(x, z, c.floor, c.ceiling, 10.0 + layer.random(gx, gz, 8504) * 5.0, 1, 0, c.ceiling);
    }

    void apply() {
        for (int gx = Math.floorDiv(x0 - REACH, CELL); gx <= Math.floorDiv(x0 + 15 + REACH, CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - REACH, CELL); gz <= Math.floorDiv(z0 + 15 + REACH, CELL); gz++) {
                final Spot s = spot(layer, biomes, gx, gz);
                if (s == null || s.x() + REACH < x0 || s.x() - REACH > x0 + 15 || s.z() + REACH < z0 || s.z() - REACH > z0 + 15) {
                    continue;
                }
                lake(s);
                source(s);
            }
        }
    }

    /** The lake with the cup in its middle: water at the floor's level in a bowl, a rim of rock round it. */
    private void lake(Spot s) {
        final int surface = s.floor() - 1;
        final int r = (int) Math.ceil(s.lake() * 1.2) + 3;
        final BlockState rock = AbyssalDepths.THERMAL_ROCK.get().defaultBlockState();
        final BlockState smoulder = AbyssalDepths.SMOULDERING_ROCK.get().defaultBlockState();
        for (int x = Math.max(x0, s.x() - r); x <= Math.min(x0 + 15, s.x() + r); x++) {
            for (int z = Math.max(z0, s.z() - r); z <= Math.min(z0 + 15, s.z() + r); z++) {
                final double d = Math.hypot(x - s.x(), z - s.z());
                final double e = d / (s.lake() * (1.0 + 0.15 * layer.noise3(x / 6.0, 0.6, z / 6.0, 21)));
                if (e >= 1.0 + 2.5 / s.lake()) {
                    continue;
                }
                if (e >= 1.0) { // the rim: rock at the water's level and below, so it stays in
                    for (int y = surface - 4; y <= surface; y++) {
                        final BlockState here = level.getBlockState(pos.set(x, y, z));
                        if (here.isAir() || !here.getFluidState().isEmpty()) {
                            level.setBlock(pos, rock, 2);
                        }
                    }
                    continue;
                }
                for (int y = surface + 1; y <= surface + 3; y++) { // room above the water (and the cup)
                    if (!level.getBlockState(pos.set(x, y, z)).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                final int depth = 1 + (int) Math.round(2.0 * (1.0 - e));
                for (int y = surface - depth - 1; y < surface - depth + 1; y++) { // a bed under it all
                    if (!level.getBlockState(pos.set(x, y, z)).isSolidRender(level, pos)) {
                        level.setBlock(pos, rock, 2);
                    }
                }
                if (d < POOL) { // the lava the fall lands in
                    for (int y = surface - depth + 1; y <= surface; y++) {
                        level.setBlock(pos.set(x, y, z), y == surface ? Blocks.LAVA.defaultBlockState() : smoulder, 2);
                    }
                } else if (d < LIP) { // the lip: a block over the water, so the lava never reaches it; quenched into slag where both met
                    final BlockState slag = AbyssalDepths.FIRE_HARDENED_SLAG.get().defaultBlockState();
                    for (int y = surface - depth + 1; y <= surface + 1; y++) {
                        level.setBlock(pos.set(x, y, z), y >= surface && layer.random(x, z, 8530 + y) < 0.4 ? slag : smoulder, 2);
                    }
                } else if (d < CUP + 0.8 * layer.noise3(x / 3.0, 0.2, z / 3.0, 22)) { // its shore, flush with the water
                    for (int y = surface - depth + 1; y <= surface; y++) {
                        level.setBlock(pos.set(x, y, z), y == surface ? smoulder : rock, 2);
                    }
                } else {
                    for (int y = surface - depth + 1; y <= surface; y++) {
                        level.setBlock(pos.set(x, y, z), Blocks.WATER.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /**
     * The breach it pours from: in the wall, at the height the magma broke through, a lava source set into the rock
     * (closed in on every side but towards the hall), the wall round it glowing; the fall already falling from it into
     * the cup below.
     */
    private void source(Spot s) {
        // the breach: in the roof over the cup, where the pocket of magma above the hall broke through
        final boolean found = false;
        int top = s.floor() + 6; // the roof over the cup
        while (top <= TunnelLayer.MAX_AIR_Y + 4 && !solidAt(s.x(), top, s.z())) {
            top++;
        }
        final int y = top, wx = s.x(), wz = s.z(), fx = s.x(), fz = s.z();
        final int r = 3;
        for (int x = wx - r; x <= wx + r; x++) {
            for (int z = wz - r; z <= wz + r; z++) {
                for (int yy = y - r; yy <= y + r; yy++) {
                    if (x < x0 || x > x0 + 15 || z < z0 || z > z0 + 15) {
                        continue;
                    }
                    final double d = Math.sqrt((x - wx) * (x - wx) + (z - wz) * (z - wz) + (yy - y) * (yy - y));
                    final BlockState here = level.getBlockState(pos.set(x, yy, z));
                    if (d <= r && !here.isAir() && here.getFluidState().isEmpty() && layer.random(x, z, 8510 + yy) < 0.75 * (1.0 - d / r) + 0.15) {
                        level.setBlock(pos, d < 1.8 ? Blocks.MAGMA_BLOCK.defaultBlockState() : AbyssalDepths.SMOULDERING_ROCK.get().defaultBlockState(), 2);
                    }
                }
            }
        }
        if (wx < x0 || wx > x0 + 15 || wz < z0 || wz > z0 + 15) {
            return;
        }
        for (int[] o : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}}) {
            final int ox = wx + o[0], oy = y + o[1], oz = wz + o[2];
            final boolean out = found ? ox == fx && oz == fz && oy == y : o[1] == -1; // its open side: towards the fall
            if (out || ox < x0 || ox > x0 + 15 || oz < z0 || oz > z0 + 15) {
                continue;
            }
            final BlockState around = level.getBlockState(pos.set(ox, oy, oz));
            if (around.isAir() || !around.getFluidState().isEmpty()) {
                level.setBlock(pos, AbyssalDepths.THERMAL_ROCK.get().defaultBlockState(), 2);
            }
        }
        level.setBlock(pos.set(wx, y, wz), Blocks.LAVA.defaultBlockState(), 2);
        level.scheduleTick(pos, Fluids.LAVA, 0);
        // the fall itself, already falling from the face (lava would take half a minute to find its way by itself)
        if (fx >= x0 && fx <= x0 + 15 && fz >= z0 && fz <= z0 + 15) {
            final BlockState falling = Blocks.LAVA.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 8);
            for (int yy = found ? y : y - 1; yy > s.floor() - 1 && !level.getBlockState(pos.set(fx, yy, fz)).is(Blocks.LAVA) && level.getBlockState(pos).getFluidState().isEmpty(); yy--) {
                level.setBlock(pos, falling, 2);
                level.scheduleTick(pos, Fluids.LAVA, 1);
            }
        }
    }

    /** Whether the layer's rock stands at a place (from its shape, the same in every chunk; formations aside). */
    private boolean solidAt(int x, int y, int z) {
        final TunnelLayer.Column c = new TunnelLayer.Column();
        return !layer.column(x, z, c) || y > c.ceiling || y < c.floor;
    }

    /** The nearest fall to a place (where it lands and the floor there), or null. */
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
