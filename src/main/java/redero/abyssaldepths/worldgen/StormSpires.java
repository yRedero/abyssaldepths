package redero.abyssaldepths.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * Storm crystal spires, the landmarks of the silt dunes: a pillar of storm crystal 3 to 5 blocks high growing out of
 * the silt (its root 1 or 2 blocks down in it) (now and then a shorter one leaning on it), small storm crystals scattered round its foot. Dull most of the
 * time; a dune storm charges them (see {@code StormCrystals}). One in some cells of a grid over the dunes; each chunk
 * places its own part.
 */
final class StormSpires {
    static final int CELL = 72, REACH = 5;
    private static final double CHANCE = 0.45;

    private final WorldGenLevel level;
    private final TunnelLayer layer;
    private final Biomes biomes;
    private final int x0, z0;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final TunnelLayer.Column col = new TunnelLayer.Column();

    StormSpires(WorldGenLevel level, TunnelLayer layer, Biomes biomes, int x0, int z0) {
        this.level = level;
        this.layer = layer;
        this.biomes = biomes;
        this.x0 = x0;
        this.z0 = z0;
    }

    void apply() {
        for (int gx = Math.floorDiv(x0 - REACH, CELL); gx <= Math.floorDiv(x0 + 15 + REACH, CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - REACH, CELL); gz <= Math.floorDiv(z0 + 15 + REACH, CELL); gz++) {
                if (layer.random(gx, gz, 8801) >= CHANCE) {
                    continue;
                }
                final int x = gx * CELL + 8 + (int) (layer.random(gx, gz, 8802) * (CELL - 16));
                final int z = gz * CELL + 8 + (int) (layer.random(gx, gz, 8803) * (CELL - 16));
                if (x + REACH < x0 || x - REACH > x0 + 15 || z + REACH < z0 || z - REACH > z0 + 15) {
                    continue;
                }
                if (biomes.at(x, z) != Biomes.DUNES || !layer.column(x, z, col) || col.ceiling - col.floor < 12) {
                    continue;
                }
                spire(gx, gz, x, z);
            }
        }
    }

    private boolean inChunk(int x, int z) {
        return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
    }

    /** The top of the ground in a column near the hall's floor (on the dunes, the silt's top), or MIN_VALUE. */
    private int ground(int x, int z, int floor) {
        for (int y = floor + 22; y >= floor - 3; y--) {
            final BlockState here = level.getBlockState(pos.set(x, y, z)), above = level.getBlockState(pos.set(x, y + 1, z));
            if (!here.isAir() && here.getFluidState().isEmpty() && here.isCollisionShapeFullBlock(level, pos.set(x, y, z)) && above.isAir()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private void spire(int gx, int gz, int x, int z) {
        final BlockState crystal = AbyssalDepths.STORM_CRYSTAL.get().defaultBlockState();
        final int height = 3 + (int) (layer.random(gx, gz, 8804) * 3);
        column(x, z, height, crystal);
        if (layer.random(gx, gz, 8805) < 0.45) { // a shorter one beside it
            final int side = (int) (layer.random(gx, gz, 8806) * 4);
            final int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            column(x + d[side][0], z + d[side][1], Math.max(1, height - 2), crystal);
        }
        final int count = 4 + (int) (layer.random(gx, gz, 8807) * 5);
        for (int k = 0; k < count; k++) { // small crystals round its foot
            final double a = layer.random(gx * 7 + k, gz, 8808) * Math.PI * 2, r = 1.6 + layer.random(gx, gz * 7 + k, 8809) * 2.6;
            final int cx = x + (int) Math.round(Math.cos(a) * r), cz = z + (int) Math.round(Math.sin(a) * r);
            if (!inChunk(cx, cz)) {
                continue;
            }
            final int g = ground(cx, cz, col.floor);
            if (g != Integer.MIN_VALUE && level.getBlockState(pos.set(cx, g + 1, cz)).isAir()) {
                level.setBlock(pos, AbyssalDepths.STORM_CLUSTER.get().defaultBlockState().setValue(AmethystClusterBlock.FACING, Direction.UP), 2);
            }
        }
    }

    private void column(int x, int z, int height, BlockState crystal) {
        if (!inChunk(x, z)) {
            return;
        }
        final int g = ground(x, z, col.floor);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        final int root = 1 + (int) (layer.random(x, z, 8810) * 2); // it grew out of the ground: 1 or 2 blocks of it down in it
        for (int k = 1 - root; k <= 0; k++) {
            level.setBlock(pos.set(x, g + k, z), crystal, 2);
        }
        for (int k = 1; k <= height; k++) {
            if (!level.getBlockState(pos.set(x, g + k, z)).isAir()) {
                break;
            }
            level.setBlock(pos, crystal, 2);
        }
    }
}
