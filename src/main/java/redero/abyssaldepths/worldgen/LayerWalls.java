package redero.abyssaldepths.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * Walls and ceilings of the layer biomes: every thalan block facing the cavern gets the look of its
 * biome (thalan depths keep plain thalan).
 * <ul>
 * <li><b>Shale flats:</b> walls in warped horizontal strata, the buried layers: shale, fractured shale,
 * thalan and thin siltstone bands. Shale ceilings with thin shards hanging down.</li>
 * <li><b>Silt dunes:</b> sediment crusted against the foot of the walls (packed silt), banded
 * siltstone above, ceilings partly siltstone. Dry: nothing hangs down.</li>
 * <li><b>Thermal reaches:</b> the grey stone stays. Where the area is active: thermal rock along the
 * lower walls, a few rusty streaks where mineral water ran down, and rare drips on the ceiling.</li>
 * </ul>
 * Whether a block faces air is read from the world inside the chunk; outside it the neighbour chunk
 * may not be carved yet, so the cavern shape itself is asked there.
 */
final class LayerWalls {
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int STRATA = 8, STREAKS = 10, BANDS = 19;   // noise channels of TunnelLayer.surfaceNoise

    private final WorldGenLevel level;
    private final TunnelLayer layer;
    private final LayerSurface surface;
    private final int x0, z0;
    /** Layer columns of the chunk and one ring around it, index (dz + 1) * 18 + dx + 1. */
    private final TunnelLayer.Column[] ring;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    LayerWalls(WorldGenLevel level, TunnelLayer layer, LayerSurface surface, int x0, int z0, TunnelLayer.Column[] ring) {
        this.level = level;
        this.layer = layer;
        this.surface = surface;
        this.x0 = x0;
        this.z0 = z0;
        this.ring = ring;
    }

    private TunnelLayer.Column column(int x, int z) {
        return ring[(z - z0 + 1) * 18 + (x - x0 + 1)];
    }

    private boolean inChunk(int x, int z) {
        return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
    }

    private boolean air(int x, int y, int z) {
        if (inChunk(x, z)) {
            return level.getBlockState(pos.set(x, y, z)).isAir();
        }
        return layer.isAir(x, y, z, column(x, z));
    }

    void apply(int x, int z) {
        for (int y = TunnelLayer.MIN_AIR_Y - 1; y <= TunnelLayer.MAX_AIR_Y + 4; y++) {
            final BlockState state = level.getBlockState(pos.set(x, y, z));
            if (!state.is(AbyssalDepths.THALAN.get())) {
                continue;
            }
            final boolean ceiling = air(x, y - 1, z);
            int sideFloor = Integer.MIN_VALUE; // floor of the open column this wall faces
            for (int[] d : SIDES) {
                if (air(x + d[0], y, z + d[1])) {
                    final TunnelLayer.Column c = column(x + d[0], z + d[1]);
                    sideFloor = c.open ? c.floor : y;
                    break;
                }
            }
            if (!ceiling && sideFloor == Integer.MIN_VALUE) {
                continue;
            }
            final int style = surface.style(x, y, z);
            if (style == Biomes.DEPTHS) {
                continue;
            }
            if (!ceiling && layer.isPillar(x, y, z, column(x, z))) {
                pillar(style, x, y, z); // a pillar standing in the open hall
                continue;
            }
            if (ceiling) {
                ceiling(style, x, y, z);
            } else {
                wall(style, x, y, z, sideFloor);
            }
        }
    }

    private void wall(int style, int x, int y, int z, int sideFloor) {
        final double warped = y + 2.0 * layer.surfaceNoise(STRATA, x / 24.0, z / 24.0);
        switch (style) {
            case Biomes.FLATS -> {
                final int band = Math.floorMod((int) Math.floor(warped / 2.0), 6);
                final BlockState[] strata = {shale(), shale(), null, fractured(), shale(), siltstone()};
                if (strata[band] != null) {
                    set(x, y, z, strata[band]);
                }
            }
            case Biomes.DUNES -> {
                final int crust = 2 + (int) Math.round(2.0 * layer.surfaceNoise(STRATA, x / 9.0, z / 9.0));
                if (y < sideFloor + crust) {
                    set(x, y, z, AbyssalDepths.PACKED_SILT.get().defaultBlockState());
                } else if (Math.floorMod((int) Math.floor(warped / 3.0), 3) != 2) {
                    set(x, y, z, siltstone());
                }
            }
            case Biomes.THERMAL -> {
                final double act = surface.activity(x, z);
                final int up = y - sideFloor;
                if (act > 0.7 && layer.surfaceNoise(STREAKS, (x + z) / 5.0, (x - z) / 45.0) > 0.62) {
                    set(x, y, z, oxide()); // a rare streak where mineral water ran down
                } else if (act > 0.25 && up < 4 && layer.surfaceNoise(BANDS, x / 12.0, z / 12.0) > 0.35 - 0.3 * act) {
                    set(x, y, z, AbyssalDepths.THERMAL_ROCK.get().defaultBlockState()); // band of thermal rock along the foot
                }
            }
            default -> {
            }
        }
    }

    private void ceiling(int style, int x, int y, int z) {
        final double r = layer.patch(x, y, z, 5.0, 27);   // patches, not single blocks
        final double hang = layer.random(x, z, 3002);
        switch (style) {
            case Biomes.FLATS -> {
                set(x, y, z, r < 0.9 ? shale() : fractured()); // mostly shale, a few broken patches
                if (hang < 0.022) { // thin shards of slate hanging down
                    drip(x, y, z, 2 + (int) (layer.random(x, z, 3003) * 3), fractured(), fractured());
                }
            }
            case Biomes.DUNES -> {
                if (r < 0.55) {
                    set(x, y, z, siltstone());
                }
            }
            case Biomes.THERMAL -> {
                if (hang < 0.015 * surface.activity(x, z)) { // rare mineral drips, the ceiling stays grey
                    final BlockState rock = AbyssalDepths.THERMAL_ROCK.get().defaultBlockState();
                    drip(x, y, z, 1 + (int) (layer.random(x, z, 3003) * 3), rock, rock);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Pillars stay one material: the biome's rock, with veins of a second one that run up through the
     * whole pillar (a noise stretched far along y), never horizontal bands.
     */
    private void pillar(int style, int x, int y, int z) {
        final boolean vein = Math.abs(layer.noise3(x / 3.0, y / 40.0, z / 3.0, 11)) < 0.06;
        switch (style) {
            case Biomes.FLATS -> set(x, y, z, vein ? fractured() : shale());
            case Biomes.DUNES -> set(x, y, z, vein ? AbyssalDepths.THALAN.get().defaultBlockState() : siltstone());
            case Biomes.THERMAL -> {
                if (vein) {
                    set(x, y, z, AbyssalDepths.THERMAL_ROCK.get().defaultBlockState());
                }
            }
            default -> {
            }
        }
    }

    /** Hangs a thin column of up to {@code length} blocks below the ceiling block, the last one {@code tip}. */
    private void drip(int x, int y, int z, int length, BlockState body, BlockState tip) {
        for (int k = 1; k <= length; k++) {
            if (!level.getBlockState(pos.set(x, y - k, z)).isAir() || !level.getBlockState(pos.set(x, y - k - 1, z)).isAir()) {
                return; // keep it hanging: never down to the ground or onto something
            }
            set(x, y - k, z, k == length ? tip : body);
        }
    }

    private BlockState shale() {
        return AbyssalDepths.SHALE.get().defaultBlockState();
    }

    private BlockState fractured() {
        return AbyssalDepths.FRACTURED_SHALE.get().defaultBlockState();
    }

    private BlockState siltstone() {
        return AbyssalDepths.SILTSTONE.get().defaultBlockState();
    }

    private BlockState oxide() {
        return AbyssalDepths.OXIDE_DEPOSIT.get().defaultBlockState();
    }

    private void set(int x, int y, int z, BlockState state) {
        level.setBlock(pos.set(x, y, z), state, 2);
    }
}
