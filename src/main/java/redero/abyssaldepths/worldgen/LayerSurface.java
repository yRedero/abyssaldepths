package redero.abyssaldepths.worldgen;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.block.ThermalVentBlock;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * Biome surfaces of the thalan layer, laid over the carved cavern floor of one chunk. The cavern
 * itself is the same in every biome; this only changes the ground, the bigger shapes are
 * {@link LayerFormations} placed afterwards:
 * <ul>
 * <li><b>Shale flats:</b> shale ground, broad shale plateaus 3 or 6 blocks high, broad rubble patches in the surface. The
 * shards are formations, standing on the plateaus.</li>
 * <li><b>Silt dunes:</b> all fine silt: the ground, and rolling dunes, mostly a few blocks high and here
 * and there 10 to 20, piled up against formations and missing in the basins; rare quicksilt pools in
 * the dips.</li>
 * <li><b>Thermal reaches:</b> the ground is mostly a skin of thermal rock over the thalan, oxide only in
 * rare small blobs. Activity varies by region (about 200 blocks): active areas get more thermal rock,
 * broad ridges, and dry fissures 2 to 4 blocks wide with a crust of thermal rock and glowing
 * smouldering rock at the bottom, some with a steaming vent.</li>
 * </ul>
 * Each biome's share of the surroundings is a weight, so the surfaces fade into each other over
 * about ten blocks instead of ending at the biome border.
 */
final class LayerSurface {
    private static final int REACH = 12;      // blocks around the chunk that are sampled
    private static final int STEP = 4;        // sample spacing (biomes are stored per 4 blocks)
    private static final int RADIUS = 10;     // blend radius
    private static final int SIZE = (16 + 2 * REACH) / STEP + 1;
    private static final int HEADROOM = 4;    // air kept above the dunes

    // noise channels of TunnelLayer.surfaceNoise (11 is shared with LayerFormations.fractureField)
    private static final int DUNE_ROLL = 0, DUNE_CREST = 1, DUNE_WARP = 2, DUNE_BIG = 3,
            RIDGE_MASK = 13, RIDGES = 14, ACTIVITY = 16, THERMAL_FISSURE = 17;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final WorldGenLevel level;
    private final TunnelLayer layer;
    private final List<LayerFormations.Formation> formations;
    private final int x0, z0;
    private final int[][] samples = new int[SIZE][SIZE];
    private final double windCos, windSin;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    LayerSurface(WorldGenLevel level, TunnelLayer layer, List<LayerFormations.Formation> formations, int x0, int z0, Biomes biomes) {
        this.level = level;
        this.layer = layer;
        this.formations = formations;
        this.x0 = x0;
        this.z0 = z0;
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                samples[i][j] = biomes.at(x0 - REACH + j * STEP, z0 - REACH + i * STEP);
            }
        }
        final double wind = 2.0 * Math.PI * layer.random(0, 0, 0); // one wind direction per world
        windCos = Math.cos(wind);
        windSin = Math.sin(wind);
    }

    /** Share of the biome in the blend radius around the column, 0 .. 1. */
    double weight(int x, int z, int biome) {
        int hit = 0, all = 0;
        for (int i = 0; i < SIZE; i++) {
            final int sz = z0 - REACH + i * STEP;
            if (Math.abs(sz - z) > RADIUS) {
                continue;
            }
            for (int j = 0; j < SIZE; j++) {
                final int sx = x0 - REACH + j * STEP;
                if (Math.abs(sx - x) <= RADIUS) {
                    all++;
                    if (samples[i][j] == biome) {
                        hit++;
                    }
                }
            }
        }
        return all == 0 ? 0.0 : hit / (double) all;
    }

    /** The layer biome that shapes a block here, or DEPTHS: the strongest one, dithered at the borders. */
    int style(int x, int y, int z) {
        int best = Biomes.DEPTHS;
        double strongest = 0.0;
        for (int biome = Biomes.FLATS; biome <= Biomes.THERMAL; biome++) {
            final double w = weight(x, z, biome);
            if (w > strongest) {
                strongest = w;
                best = biome;
            }
        }
        return layer.patch(x, y, z, 5.0, 21) < strongest ? best : Biomes.DEPTHS; // blobs at the borders, no speckles
    }

    /** Shapes the ground of one column. {@code col} is the layer column, the cavern is already carved. */
    void apply(int x, int z, TunnelLayer.Column col) {
        if (!col.open) {
            return;
        }
        final int floor = col.floor;
        if (!air(x, floor, z) || air(x, floor - 1, z)) {
            return; // pillar foot or a tunnel below: leave it
        }
        final int room = col.ceiling - floor + 1;
        final double dunes = weight(x, z, Biomes.DUNES);
        if (dunes > 0.02 && dunes(x, z, floor, room, dunes)) {
            return; // silt lies here, nothing may dig below it
        }
        // flats and thermal reaches both dig into the ground: the stronger one shapes it
        final double flats = weight(x, z, Biomes.FLATS), thermal = weight(x, z, Biomes.THERMAL);
        if (flats > 0.02 && flats >= thermal) {
            flats(x, z, floor, room, flats);
        } else if (thermal > 0.02) {
            thermal(x, z, floor, room, thermal);
        }
    }

    // ---- silt dunes ----------------------------------------------------------------

    /** Dune height above the floor in blocks (may be negative: bare ground). */
    private double duneHeight(int x, int z, double weight) {
        final double u = x * windCos + z * windSin, v = -x * windSin + z * windCos; // along / across the wind
        final double roll = 0.5 + 0.5 * clamp(1.6 * layer.surfaceNoise(DUNE_ROLL, u / 60.0, v / 110.0), -1, 1);
        final double warp = 0.6 * layer.surfaceNoise(DUNE_WARP, u / 50.0, v / 50.0);
        double crest = 1.0 - 2.0 * Math.abs(layer.surfaceNoise(DUNE_CREST, u / 26.0 + warp, v / 80.0));
        crest = clamp(crest, 0, 1);
        crest = Math.pow(crest, 1.5);
        final double big = smoothstep(0.12, 0.42, layer.surfaceNoise(DUNE_BIG, x / 230.0, z / 230.0));
        // ~18 % bare, most 1-4 blocks, ~5 % 5-9, ~1 % big dunes up to ~18
        double h = 1.75 + 4.0 * crest * roll + 15.0 * big * crest * roll;
        // sediment piles up against the formations, and has settled away in the basins
        h = h * LayerFormations.duneDamp(formations, x, z) + LayerFormations.duneBoost(formations, x, z);
        return h * smoothstep(0.0, 1.0, weight) - 0.8;
    }

    /** Returns whether silt was placed. */
    private boolean dunes(int x, int z, int floor, int room, double weight) {
        final double h = Math.min(duneHeight(x, z, weight), room - HEADROOM);
        final int height = Math.max(0, (int) Math.floor(h));
        final BlockState silt = AbyssalDepths.FINE_SILT.get().defaultBlockState();
        // the ground is fine silt everywhere, also between the dunes (the border fades in blobs)
        if (layer.patch(x, 0, z, 6.0, 45) < weight) {
            set(x, floor - 1, z, silt);
            set(x, floor - 2, z, silt);
        }
        // rare, broad quicksilt pools in the dips between the dunes, flush with the surface and three blocks deep
        if (weight > 0.7 && h < 2.0 && room > 6 && layer.patch(x, 0, z, 9.0, 44) < 0.02) {
            for (int y = floor - 3; y < floor + height; y++) {
                set(x, y, z, AbyssalDepths.QUICKSILT.get().defaultBlockState());
            }
            return true;
        }
        if (height == 0) {
            return false;
        }
        for (int k = 0; k < height; k++) {
            if (!air(x, floor + k, z)) {
                break;
            }
            set(x, floor + k, z, silt);
        }
        return true;
    }

    // ---- shale flats ----------------------------------------------------------------

    private void flats(int x, int z, int floor, int room, double weight) {
        if (layer.patch(x, 0, z, 6.0, 22) >= weight) {
            return; // the border fades in blobs
        }
        final BlockState shale = AbyssalDepths.SHALE.get().defaultBlockState();

        // ground: shale, two blocks deep
        for (int d = 1; d <= 2; d++) {
            if (level.getBlockState(pos.set(x, floor - d, z)).is(AbyssalDepths.THALAN.get())) {
                set(x, floor - d, z, shale);
            }
        }
        // plateaus: the ground steps up in broad shelves of shale
        final int plateau = plateauAt(x, z, room);
        for (int k = 0; k < plateau; k++) {
            if (air(x, floor + k, z)) {
                set(x, floor + k, z, shale);
            }
        }
        // broad patches of rubble in place of the shale surface
        if (layer.patch(x, 0, z, 7.0, 23) < 0.02) {
            set(x, floor + plateau - 1, z, AbyssalDepths.SHALE_RUBBLE.get().defaultBlockState());
        }
    }

    /**
     * Height of the shale flats plateau on this column, 0, 3 or 6 blocks (broad shelves with steep
     * edges), only well inside the flats and only where the hall is tall enough. The formations stand on it.
     */
    int plateauAt(int x, int z, int room) {
        if (weight(x, z, Biomes.FLATS) < 0.6) {
            return 0;
        }
        return Math.max(0, Math.min(layer.plateau(x, z), room - 8));
    }

    // ---- thermal reaches ----------------------------------------------------------------

    /** How geothermally active the area is, 0 (quiet, plain dark rock) .. 1, varying over ~200 blocks. */
    double activity(int x, int z) {
        return smoothstep(-0.15, 0.35, layer.surfaceNoise(ACTIVITY, x / 220.0, z / 220.0));
    }

    private void thermal(int x, int z, int floor, int room, double weight) {
        if (layer.patch(x, 0, z, 6.0, 24) >= weight) {
            return; // the border fades in blobs
        }
        final BlockState rock = AbyssalDepths.THERMAL_ROCK.get().defaultBlockState();
        final BlockState oxide = AbyssalDepths.OXIDE_DEPOSIT.get().defaultBlockState();
        final double act = activity(x, z);

        // dry fissures, 2 to 4 blocks wide, deepest in the middle, glowing at the bottom, some breathing
        // steam from a vent, crusted edges
        final double fissure = Math.abs(layer.surfaceNoise(THERMAL_FISSURE, x / 32.0, z / 32.0));
        if (weight > 0.6 && act > 0.15 && fissure < 0.04) {
            final int depth = 2 + (int) Math.round(2.0 * (1.0 - fissure / 0.04));
            for (int d = 1; d <= depth; d++) {
                set(x, floor - d, z, AIR);
            }
            final BlockState bottom = level.getBlockState(pos.set(x, floor - depth - 1, z));
            if (!bottom.isAir() && bottom.getFluidState().isEmpty()) {
                final boolean vent = layer.random(x, z, 2020) < 0.05;
                // the bottom glows in long stretches, almost all along where it is active
                final boolean glow = layer.patch(x, floor, z, 6.0, 39) < 0.45 + 0.5 * act;
                set(x, floor - depth - 1, z, vent ? AbyssalDepths.THERMAL_VENT.get().defaultBlockState()
                        .setValue(ThermalVentBlock.ACTIVE, layer.random(x, z, 2021) < 0.25)
                        : glow ? AbyssalDepths.SMOULDERING_ROCK.get().defaultBlockState() : rock);
            }
            return;
        }
        if (weight > 0.6 && act > 0.15 && fissure < 0.07) {
            set(x, floor - 1, z, rock); // the crust along it
            return;
        }

        // broad ridges of oxidised rock, only where it is active
        final double ridge = 1.0 - Math.abs(layer.surfaceNoise(RIDGES, x / 50.0, z / 50.0)) / 0.12;
        if (weight > 0.6 && act > 0.55 && ridge > 0.0 && layer.surfaceNoise(RIDGE_MASK, x / 90.0, z / 90.0) > 0.15) {
            final int height = room > 6 ? (int) Math.round(2.8 * Math.pow(ridge, 0.8)) : 0;
            for (int k = 0; k < height; k++) {
                if (air(x, floor + k, z)) {
                    // thermal rock, oxidised in a few patches on top
                    set(x, floor + k, z, k == height - 1 && layer.patch(x, floor + k, z, 5.0, 43) < 0.12 ? oxide : rock);
                }
            }
            if (height == 0) {
                set(x, floor - 1, z, rock);
            }
            return;
        }

        // the ground is mostly a skin of thermal rock with patches of bare thalan (fewer where it is
        // active), oxide only in rare small blobs; below the skin it stays thalan
        if (layer.patch(x, 0, z, 6.0, 42) < 0.01 + 0.03 * act) {
            set(x, floor - 1, z, oxide);
        } else if (layer.patch(x, 0, z, 9.0, 41) < 0.55 + 0.35 * act) {
            set(x, floor - 1, z, rock);
        }
    }

    // ---- helpers ----------------------------------------------------------------

    private boolean air(int x, int y, int z) {
        return level.getBlockState(pos.set(x, y, z)).isAir();
    }

    private void set(int x, int y, int z, BlockState state) {
        level.setBlock(pos.set(x, y, z), state, 2);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double smoothstep(double a, double b, double v) {
        final double t = clamp((v - a) / (b - a), 0, 1);
        return t * t * (3.0 - 2.0 * t);
    }
}
