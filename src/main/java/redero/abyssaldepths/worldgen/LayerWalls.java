package redero.abyssaldepths.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * Walls and ceilings of the layer biomes: every thalan block facing the cavern gets the look of its
 * biome (thalan depths keep plain thalan).
 * <ul>
 * <li><b>Shale flats:</b> shale walls and ceilings, thin shards of fractured shale hanging down.</li>
 * <li><b>Silt dunes:</b> siltstone walls and ceilings. Dry: nothing hangs down.</li>
 * <li><b>Thermal reaches:</b> the grey stone stays. Where the area is active: a few rusty streaks where
 * mineral water ran down, and rare drips on the ceiling.</li>
 * </ul>
 * Never horizontal bands of different blocks: each biome has one wall block. Everywhere a few bare
 * thalan blocks glimmer faintly, and the pillars of the dunes carry patches of glow lichen.
 * Whether a block faces air is read from the world inside the chunk; outside it the neighbour chunk
 * may not be carved yet, so the cavern shape itself is asked there.
 */
final class LayerWalls {
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int STREAKS = 10;
    /** Share of bare thalan wall and ceiling blocks that glimmer. */
    private static final double GLIMMER = 0.004;   // noise channel of TunnelLayer.surfaceNoise

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
            if (style != Biomes.DEPTHS) {
                if (!ceiling && layer.isPillar(x, y, z, column(x, z))) {
                    pillar(style, x, y, z); // a pillar standing in the open hall
                } else if (ceiling) {
                    ceiling(style, x, y, z);
                } else {
                    wall(style, x, y, z, sideFloor);
                }
            }
            // faint glowing specks scattered over the bare thalan that is left, points of light in the dark
            if (layer.random(x, z, 5000 + y) < GLIMMER && level.getBlockState(pos.set(x, y, z)).is(AbyssalDepths.THALAN.get())) {
                set(x, y, z, AbyssalDepths.GLIMMERING_THALAN.get().defaultBlockState());
            }
        }
    }

    private void wall(int style, int x, int y, int z, int sideFloor) {
        switch (style) {
            case Biomes.FLATS -> set(x, y, z, shale());
            case Biomes.DUNES -> set(x, y, z, siltstone());
            case Biomes.THERMAL -> {
                final double act = surface.activity(x, z);
                if (act > 0.7 && layer.surfaceNoise(STREAKS, (x + z) / 5.0, (x - z) / 45.0) > 0.62) {
                    set(x, y, z, oxide()); // a rare streak where mineral water ran down
                }
            }
            default -> {
            }
        }
    }

    private void ceiling(int style, int x, int y, int z) {
        final double hang = layer.random(x, z, 3002);
        switch (style) {
            case Biomes.FLATS -> {
                set(x, y, z, shale());
                if (hang < 0.022) { // thin shards of slate hanging down
                    drip(x, y, z, 2 + (int) (layer.random(x, z, 3003) * 3), fractured(), fractured());
                }
            }
            case Biomes.DUNES -> {
                set(x, y, z, siltstone());
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
            case Biomes.DUNES -> {
                set(x, y, z, vein ? AbyssalDepths.THALAN.get().defaultBlockState() : siltstone());
                if (!vein && layer.patch(x, y, z, 3.0, 46) < 0.06) {
                    lichen(x, y, z); // patches of faintly glowing lichen on the pillars of the dunes
                }
            }
            case Biomes.THERMAL -> {
                if (vein) {
                    set(x, y, z, AbyssalDepths.THERMAL_ROCK.get().defaultBlockState());
                }
            }
            default -> {
            }
        }
    }

    /** Glow lichen on the open sides of a block, only inside this chunk. */
    private void lichen(int x, int y, int z) {
        for (int[] d : SIDES) {
            if (inChunk(x + d[0], z + d[1]) && level.getBlockState(pos.set(x + d[0], y, z + d[1])).isAir()) {
                set(x + d[0], y, z + d[1], Blocks.GLOW_LICHEN.defaultBlockState()
                        .setValue(MultifaceBlock.getFaceProperty(Direction.fromDelta(-d[0], 0, -d[1])), true));
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
