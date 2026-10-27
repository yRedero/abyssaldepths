package redero.abyssaldepths.worldgen;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleBinaryOperator;

import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * How much the silt dunes surround a position, 0 .. 1: the dunes open up into one big hall
 * ({@link TunnelLayer} lets rock masses and massifs fade out by it), fading out over about 20 blocks at
 * their border. Pure geometry, no Minecraft dependencies, so it can be tested offline.
 * <p>
 * The share of dunes (7 x 7 samples 6 blocks apart) is worked out at the corners of a 4 block grid and
 * blended smoothly in between: a value that jumps from cell to cell would cut flat, grid-aligned walls
 * into the rock at the border of the dunes.
 */
public final class DuneOpenness {
    private static final int MAX_CORNERS = 1_000_000;

    private DuneOpenness() {
    }

    /** The openness for a biome map; {@code corners} keeps the corner values (shared, thread-safe). */
    public static DoubleBinaryOperator of(Biomes biomes, ConcurrentHashMap<Long, Double> corners) {
        return (px, pz) -> {
            final double gx = px / 4.0, gz = pz / 4.0;
            final int qx = (int) Math.floor(gx), qz = (int) Math.floor(gz);
            final double fx = gx - qx, fz = gz - qz;
            final double a = corner(biomes, corners, qx, qz), b = corner(biomes, corners, qx + 1, qz);
            final double c = corner(biomes, corners, qx, qz + 1), d = corner(biomes, corners, qx + 1, qz + 1);
            final double v = (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
            final double t = Math.max(0.0, Math.min(1.0, (v - 0.2) / 0.7));
            return t * t * (3.0 - 2.0 * t);
        };
    }

    private static double corner(Biomes biomes, ConcurrentHashMap<Long, Double> corners, int qx, int qz) {
        final long key = (long) qx << 32 | (qz & 0xFFFFFFFFL);
        final Double hit = corners.get(key);
        if (hit != null) {
            return hit;
        }
        if (corners.size() > MAX_CORNERS) {
            corners.clear(); // rarely: keeps memory bounded on long journeys
        }
        final int cx = qx << 2, cz = qz << 2;
        int dunes = 0;
        for (int i = -3; i <= 3; i++) {
            for (int j = -3; j <= 3; j++) {
                if (biomes.at(cx + i * 6, cz + j * 6) == Biomes.DUNES) {
                    dunes++;
                }
            }
        }
        final double share = dunes / 49.0;
        corners.putIfAbsent(key, share);
        return share;
    }
}
