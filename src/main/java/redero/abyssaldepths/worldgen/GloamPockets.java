package redero.abyssaldepths.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * Gloam pockets: where the gloam (the glow that is the layer's lifeblood) breaks out of the rock. Round a spot on the cavern floor,
 * crystal seams glow in the rock near the middle (clusters growing out of them up from the floor and down from the
 * ceiling), glow moss grows on everything round them, thinning out towards the edge; about half the pockets lie
 * round a shallow pool where water gathered. One spot per grid cell at most, rare (about one cell in eight); in the thalan
 * depths (the coldest part) the gloam is fainter. Everything is decided from the cell, so a pocket spanning chunks
 * comes out whole. In the richest (largest) pockets grow gloamcaps, giant fungi: a pale stalk (the layer's only
 * wood), a dark glowing cap with gloam clusters hanging under it like gills.
 */
final class GloamPockets {
    private static final int CELL = 40, MAX_RADIUS = 12;
    private static final double CHANCE = 0.12;
    private static final Direction[] DIRS = Direction.values();

    private final WorldGenLevel level;
    private final TunnelLayer layer;
    private final LayerSurface surface;
    private final int x0, z0;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final TunnelLayer.Column scratch = new TunnelLayer.Column();
    /** For the grotto: the gloam at its richest, wherever it is (also in the depths). */
    boolean rich;

    GloamPockets(WorldGenLevel level, TunnelLayer layer, LayerSurface surface, int x0, int z0) {
        this.level = level;
        this.layer = layer;
        this.surface = surface;
        this.x0 = x0;
        this.z0 = z0;
    }

    void apply() {
        final int pad = MAX_RADIUS + 1;
        for (int gx = Math.floorDiv(x0 - pad, CELL); gx <= Math.floorDiv(x0 + 15 + pad, CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - pad, CELL); gz <= Math.floorDiv(z0 + 15 + pad, CELL); gz++) {
                if (layer.random(gx, gz, 7101) >= CHANCE) {
                    continue;
                }
                final int px = gx * CELL + 8 + (int) (layer.random(gx, gz, 7102) * (CELL - 16));
                final int pz = gz * CELL + 8 + (int) (layer.random(gx, gz, 7103) * (CELL - 16));
                if (!layer.column(px, pz, scratch) || scratch.ceiling - scratch.floor < 5) {
                    continue; // no open floor there
                }
                final int floor = scratch.floor;
                if (surface.style(px, floor, pz) == Biomes.DUNES) {
                    continue; // the silt dunes have no gloam: the wind and the silt keep it buried
                }
                final double radius = 6.5 + layer.random(gx, gz, 7104) * 5.0;
                if (px + radius < x0 - 1 || px - radius > x0 + 16 || pz + radius < z0 - 1 || pz - radius > z0 + 16) {
                    continue;
                }
                if (layer.random(gx, gz, 7105) < 0.5) {
                    pool(px, floor, pz, 2.0 + layer.random(gx, gz, 7106) * 2.2);
                }
                glow(px, floor, pz, radius);
                ceiling(px, floor, pz, radius * 0.85);
                if (radius > 9.0) { // the richest pockets: gloamcaps grow in them
                    gloamcaps(gx, gz, px, pz, radius);
                }
            }
        }
    }

    private boolean inChunk(int x, int z) {
        return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
    }

    /** Whether the block is rock (not air, not fluid), here or (outside the chunk) as the cavern shape has it. */
    private boolean solid(int x, int y, int z) {
        if (inChunk(x, z)) {
            final BlockState state = level.getBlockState(pos.set(x, y, z));
            return !state.isAir() && state.getFluidState().isEmpty();
        }
        final TunnelLayer.Column c = new TunnelLayer.Column();
        layer.column(x, z, c);
        return !layer.isAir(x, y, z, c);
    }

    private int floorAt(int x, int z) {
        final TunnelLayer.Column c = new TunnelLayer.Column();
        return layer.column(x, z, c) ? c.floor : Integer.MIN_VALUE;
    }

    /** A shallow pool in the floor round the middle: water one block deep, only where it is held in on every side. */
    private void pool(int px, int floor, int pz, double radius) {
        final int y = floor - 1;
        final int r = (int) Math.ceil(radius);
        for (int x = Math.max(x0, px - r); x <= Math.min(x0 + 15, px + r); x++) {
            for (int z = Math.max(z0, pz - r); z <= Math.min(z0 + 15, pz + r); z++) {
                if (!inPool(x, z, px, floor, pz, radius) || !solid(x, y, z) || !solid(x, y - 1, z)) {
                    continue;
                }
                boolean held = true;
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    final int nx = x + d[0], nz = z + d[1];
                    if (!inPool(nx, nz, px, floor, pz, radius) && !solid(nx, y, nz)) {
                        held = false;
                        break;
                    }
                }
                if (held) {
                    level.setBlock(pos.set(x, y, z), Blocks.WATER.defaultBlockState(), 2);
                }
            }
        }
    }

    private boolean inPool(int x, int z, int px, int floor, int pz, double radius) {
        final double dx = x - px, dz = z - pz;
        return dx * dx + dz * dz < radius * radius && floorAt(x, z) == floor && solid(x, floor - 2, z);
    }

    /** The gloam itself: crystal seams near the middle with clusters growing out of them, moss round about. */
    void glow(int px, int floor, int pz, double radius) {
        final int r = (int) Math.ceil(radius) + 1;
        final int cy = floor + 1;
        for (int x = Math.max(x0, px - r); x <= Math.min(x0 + 15, px + r); x++) {
            for (int z = Math.max(z0, pz - r); z <= Math.min(z0 + 15, pz + r); z++) {
                for (int y = floor - 2; y <= Math.min(floor + r + 2, TunnelLayer.MAX_AIR_Y + 4); y++) {
                    final double dx = x - px, dy = (y - cy) * 1.3, dz = z - pz;
                    final double ragged = 1.6 * layer.noise3(x / 4.0, y / 4.0, z / 4.0, 13);
                    final double f = (Math.sqrt(dx * dx + dy * dy + dz * dz) + ragged) / radius;
                    if (f > 1.0) {
                        continue;
                    }
                    final BlockState state = level.getBlockState(pos.set(x, y, z));
                    if (state.isAir() || !state.getFluidState().isEmpty() || !state.isSolidRender(level, pos)) {
                        continue;
                    }
                    boolean open = false;
                    for (Direction d : DIRS) {
                        if (inChunk(x + d.getStepX(), z + d.getStepZ()) && level.getBlockState(pos.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())).isAir()) {
                            open = true;
                            break;
                        }
                    }
                    if (!open) {
                        continue; // only what faces the cavern
                    }
                    final double faint = rich ? 1.2 : surface.style(x, y, z) == Biomes.DEPTHS ? 0.55 : 1.0; // the depths: the gloam faintest
                    final double roll = layer.random(x, z, 7200 + y);
                    if (f < 0.45 && roll < 0.7 * faint) {
                        level.setBlock(pos.set(x, y, z), AbyssalDepths.GLOAM_CRYSTAL.get().defaultBlockState(), 2);
                        final double grow = layer.random(x, z, 7300 + y);
                        if (grow < 0.45 && air(x, y + 1, z)) {
                            cluster(x, y + 1, z, Direction.UP);
                        } else if (grow < 0.5 && air(x, y - 1, z)) {
                            cluster(x, y - 1, z, Direction.DOWN);
                        }
                    } else if (roll < (1.0 - 0.7 * f) * 0.95 * faint) {
                        for (Direction d : DIRS) {
                            moss(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ(), d.getOpposite());
                        }
                    }
                }
            }
        }
    }

    /**
     * The gloam on the ceiling over the pocket (seen from afar: a hall lit from above): crystal seams with clusters
     * hanging down near the middle, moss round them.
     */
    void ceiling(int px, int floor, int pz, double radius) {
        final int r = (int) Math.ceil(radius);
        for (int x = Math.max(x0, px - r); x <= Math.min(x0 + 15, px + r); x++) {
            for (int z = Math.max(z0, pz - r); z <= Math.min(z0 + 15, pz + r); z++) {
                final double dx = x - px, dz = z - pz;
                final double f = (Math.sqrt(dx * dx + dz * dz) + 1.4 * layer.noise3(x / 4.0, 0.5, z / 4.0, 14)) / radius;
                if (f > 1.0) {
                    continue;
                }
                int y = floor + 3;
                while (y <= TunnelLayer.MAX_AIR_Y + 4 && level.getBlockState(pos.set(x, y, z)).isAir()) {
                    y++;
                }
                final BlockState state = level.getBlockState(pos.set(x, y, z));
                if (y > TunnelLayer.MAX_AIR_Y + 4 || y < floor + 6 || state.isAir() || !state.isSolidRender(level, pos)) {
                    continue; // no ceiling over it, or one too low to be the hall's
                }
                final double faint = rich ? 1.2 : surface.style(x, y, z) == Biomes.DEPTHS ? 0.55 : 1.0;
                final double roll = layer.random(x, z, 7400 + y);
                if (f < 0.5 && roll < 0.55 * faint) {
                    level.setBlock(pos.set(x, y, z), AbyssalDepths.GLOAM_CRYSTAL.get().defaultBlockState(), 2);
                    if (layer.random(x, z, 7500 + y) < 0.45 && air(x, y - 1, z)) {
                        cluster(x, y - 1, z, Direction.DOWN);
                    }
                } else if (roll < (1.0 - 0.6 * f) * 0.8 * faint) {
                    moss(x, y - 1, z, Direction.UP);
                }
            }
        }
    }

    /** One to three gloamcaps near the middle of a pocket: the biggest first, with a thick stalk in the largest pockets. */
    void gloamcaps(int gx, int gz, int px, int pz, double radius) {
        final int count = 1 + (int) (layer.random(gx, gz, 7110) * (radius > 10.5 ? 3 : 2));
        for (int k = 0; k < count; k++) {
            final double angle = layer.random(gx, gz, 7111 + k) * Math.PI * 2, dist = k == 0 ? 0.0 : 3.0 + layer.random(gx, gz, 7121 + k) * radius * 0.35;
            final int sx = px + (int) Math.round(Math.cos(angle) * dist), sz = pz + (int) Math.round(Math.sin(angle) * dist);
            final TunnelLayer.Column c = new TunnelLayer.Column();
            if (!layer.column(sx, sz, c) || c.ceiling - c.floor < 10 || !solid(sx, c.floor - 1, sz)) {
                continue; // no room for it, or it would stand in the pool
            }
            final boolean thick = k == 0 && radius > 10.5 && layer.random(gx, gz, 7131) < 0.6;
            final int t = thick ? 2 : 1;
            final int height = Math.min(5 + (int) (layer.random(gx, gz, 7141 + k) * 7), c.ceiling - c.floor - 5);
            final int capR = (thick ? 3 : 2) + (layer.random(gx, gz, 7151 + k) < 0.5 ? 1 : 0);
            final BlockState stalk = AbyssalDepths.GLOAM_STALK.get().defaultBlockState(), cap = AbyssalDepths.GLOAMCAP.get().defaultBlockState();
            for (int dy = 0; dy < height; dy++) {
                for (int dx = 0; dx < t; dx++) {
                    for (int dz = 0; dz < t; dz++) {
                        grow(sx + dx, c.floor + dy, sz + dz, stalk);
                    }
                }
            }
            for (int dx = 0; dx < t; dx++) { // rooted: every column of the stalk reaches down to rock, over a dip or into water
                for (int dz = 0; dz < t; dz++) {
                    root(sx + dx, c.floor - 1, sz + dz, stalk);
                }
            }
            final int top = c.floor + height;
            final double cx = sx + t / 2.0, cz = sz + t / 2.0;
            for (int x = sx - capR - 1; x <= sx + capR + t; x++) {
                for (int z = sz - capR - 1; z <= sz + capR + t; z++) {
                    final double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
                    if (d <= capR - 0.8) {
                        grow(x, top + 1, z, cap); // the crown
                    }
                    if (d <= capR + 0.3) {
                        grow(x, top, z, cap);
                    }
                    if (d > capR - 0.7 && d <= capR + 0.3) {
                        grow(x, top - 1, z, cap); // the rim hanging down
                    } else if (d < capR - 0.7 && d > t * 0.75 && layer.random(x, z, 7160 + top) < 0.3 && inChunk(x, z)
                            && level.getBlockState(pos.set(x, top - 1, z)).isAir()) {
                        cluster(x, top - 1, z, Direction.DOWN); // gills: gloam hanging under the cap
                    }
                }
            }
        }
    }

    /** A stalk's column carried down from y until it stands on rock (at most a few blocks). */
    private void root(int x, int y, int z, BlockState stalk) {
        if (!inChunk(x, z)) {
            return;
        }
        for (int k = 0; k < 12; k++) {
            final BlockState here = level.getBlockState(pos.set(x, y - k, z));
            if (!(here.isAir() || !here.getFluidState().isEmpty() || here.is(AbyssalDepths.GLOAM_MOSS.get()) || here.is(AbyssalDepths.GLOAM_CLUSTER.get()))) {
                return; // on rock
            }
            level.setBlock(pos, stalk, 2);
        }
    }

    /** Sets a block of a gloamcap where there is room (air, or the moss, which it grows over). */
    private void grow(int x, int y, int z, BlockState state) {
        if (!inChunk(x, z)) {
            return;
        }
        final BlockState here = level.getBlockState(pos.set(x, y, z));
        if (here.isAir() || here.is(AbyssalDepths.GLOAM_MOSS.get()) || here.is(AbyssalDepths.GLOAM_CLUSTER.get())) {
            level.setBlock(pos, state, 2);
        }
    }

    private boolean air(int x, int y, int z) {
        return inChunk(x, z) && level.getBlockState(pos.set(x, y, z)).isAir();
    }

    private void cluster(int x, int y, int z, Direction facing) {
        level.setBlock(pos.set(x, y, z), AbyssalDepths.GLOAM_CLUSTER.get().defaultBlockState().setValue(AmethystClusterBlock.FACING, facing), 2);
    }

    /** Glow moss in the air block at x, y, z, on its face towards the rock (added to moss already there). */
    private void moss(int x, int y, int z, Direction towardsRock) {
        if (!inChunk(x, z)) {
            return;
        }
        final BlockState here = level.getBlockState(pos.set(x, y, z));
        final BlockState moss = AbyssalDepths.GLOAM_MOSS.get().defaultBlockState();
        if (here.isAir()) {
            level.setBlock(pos, moss.setValue(MultifaceBlock.getFaceProperty(towardsRock), true), 2);
        } else if (here.is(AbyssalDepths.GLOAM_MOSS.get())) {
            level.setBlock(pos, here.setValue(MultifaceBlock.getFaceProperty(towardsRock), true), 2);
        }
    }
}
