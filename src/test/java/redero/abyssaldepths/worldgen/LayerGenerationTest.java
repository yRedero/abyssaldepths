package redero.abyssaldepths.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleBinaryOperator;

import org.junit.jupiter.api.Test;

import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * Offline checks of the layer generation (no game needed): things that went wrong once and must not
 * come back. Run with {@code ./gradlew test}.
 */
class LayerGenerationTest {
    private static final long SEED = 12345L;

    private static TunnelLayer layer(Biomes biomes) {
        return new TunnelLayer(SEED, null, DuneOpenness.of(biomes, new ConcurrentHashMap<>()));
    }

    /** Dunes east of x = 0, shale flats west of it. */
    private static final Biomes HALF_DUNES = (x, z) -> x > 0 ? Biomes.DUNES : Biomes.FLATS;

    @Test
    void duneOpennessIsContinuous() {
        final DoubleBinaryOperator open = DuneOpenness.of(HALF_DUNES, new ConcurrentHashMap<>());
        for (int z = -200; z < 200; z += 7) {
            for (int x = -40; x < 40; x++) {
                final double step = Math.abs(open.applyAsDouble(x + 1.5, z + 0.5) - open.applyAsDouble(x + 0.5, z + 0.5));
                assertTrue(step < 0.08, "openness jumps by " + step + " at x=" + x + ", z=" + z);
            }
        }
        assertEquals(0.0, open.applyAsDouble(-60.5, 0.5), 1e-9, "far in the flats: closed");
        assertEquals(1.0, open.applyAsDouble(60.5, 0.5), 1e-9, "far in the dunes: open");
    }

    /**
     * Once the opening of the dunes jumped per 4 block cell and cut flat, grid-aligned walls into the
     * rock at their border. Natural walls fall on a grid line about a quarter of the time.
     */
    @Test
    void duneBorderHasNoGridAlignedWalls() {
        final TunnelLayer layer = layer(HALF_DUNES);
        final TunnelLayer.Column c = new TunnelLayer.Column();
        int steps = 0, onGrid = 0;
        for (int z = -1500; z < 1500; z++) {
            int prev = Integer.MIN_VALUE;
            for (int x = -30; x <= 30; x++) {
                final int room = layer.column(x, z, c) ? c.ceiling - c.floor : 0;
                if (prev != Integer.MIN_VALUE && Math.abs(room - prev) > 2) {
                    steps++;
                    if (Math.floorMod(x, 4) == 0) {
                        onGrid++;
                    }
                }
                prev = room;
            }
        }
        final double share = onGrid / (double) steps;
        assertTrue(share < 0.31, String.format("%.1f %% of the steps at the dune border lie on the 4 block grid", 100 * share));
    }

    @Test
    void floorIsAlwaysWalkable() {
        final TunnelLayer layer = layer((x, z) -> Biomes.DEPTHS);
        for (int z = 0; z < 512; z += 3) {
            for (int x = 0; x < 512; x++) {
                final int step = Math.abs(layer.floorY(x + 1.5, z + 0.5) - layer.floorY(x + 0.5, z + 0.5));
                assertTrue(step <= 1, "floor step of " + step + " at x=" + x + ", z=" + z);
            }
        }
    }

    /** The per-cell cache must hand out exactly what building the formations fresh would. */
    @Test
    void formationsAreDeterministic() {
        final Biomes biomes = (x, z) -> (Math.floorDiv(x, 160) + Math.floorDiv(z, 160)) % 3 + 1; // all three kinds
        final LayerFormations first = new LayerFormations(layer(biomes), biomes);
        final LayerFormations second = new LayerFormations(layer(biomes), biomes);
        for (int i = 0; i < 6; i++) {
            final int x0 = i * 97 - 300, z0 = i * 61 - 200;
            final String a = describe(first.near(x0, z0, x0 + 15, z0 + 15));
            first.near(x0 - 40, z0 - 40, x0 + 55, z0 + 55); // fill the cache around it
            assertEquals(a, describe(first.near(x0, z0, x0 + 15, z0 + 15)), "cached result differs");
            assertEquals(a, describe(second.near(x0, z0, x0 + 15, z0 + 15)), "another instance differs");
        }
    }

    private static String describe(List<LayerFormations.Formation> formations) {
        final StringBuilder out = new StringBuilder();
        formations.forEach(f -> out.append(f.getClass().getSimpleName()).append('@').append(f.cx).append(',').append(f.cz).append(';'));
        return out.toString();
    }

    /**
     * The shale flats are full of shards, also under low ceilings; only paths and small clearings stay
     * open. Once whole low stretches were empty.
     */
    @Test
    void shardsFillTheFlats() {
        final Biomes flats = (x, z) -> Biomes.FLATS;
        final TunnelLayer layer = layer(flats);
        final List<LayerFormations.Formation> shards = new LayerFormations(layer, flats).near(0, 0, 383, 383);
        final TunnelLayer.Column c = new TunnelLayer.Column();
        int open = 0, far = 0;
        for (int z = 0; z < 384; z += 2) {
            for (int x = 0; x < 384; x += 2) {
                if (!layer.column(x, z, c)) {
                    continue;
                }
                open++;
                final int px = x, pz = z;
                if (shards.stream().noneMatch(f -> Math.hypot(f.cx - px, f.cz - pz) < 14)) {
                    far++;
                }
            }
        }
        final double share = far / (double) open;
        assertTrue(share < 0.15, String.format("%.1f %% of the open flats are more than 14 blocks from any shard", 100 * share));
    }
}
