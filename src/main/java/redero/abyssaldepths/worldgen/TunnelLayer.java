package redero.abyssaldepths.worldgen;

/**
 * Deterministic, infinite tunnel system for the layer y=-65 .. y=-128.
 * <p>
 * Pure function of (seed, x, z): no state, no Minecraft dependencies, so it can be unit tested
 * and used from any chunk in any order.
 * <p>
 * How it works:
 * <ul>
 * <li><b>Network:</b> Voronoi diagram of a jittered point grid, bent by a smooth domain warp. The
 * tunnels follow the cell borders. The border graph of a Voronoi diagram is always connected, so
 * the system has no isolated parts and never ends.</li>
 * <li><b>Width:</b> a column is a tunnel where the distance to the nearest cell border is smaller
 * than a (varying) half width.</li>
 * <li><b>Caverns:</b> some grid points get a round cavern. Its centre is the midpoint between
 * the point and its nearest neighbour. That midpoint always lies on a Voronoi edge (a third
 * point inside the circle with that diameter would be closer than the neighbour), so every
 * cavern is attached to the network. Caverns only add space, connectivity is kept.</li>
 * <li><b>Height:</b> the floor is a smooth height field of (x, z) only, mostly flat, with a
 * bounded slope (never more than one block per block), so it is continuous across junctions and
 * always walkable. The ceiling is a dome above the floor with at least 3 blocks of air.</li>
 * </ul>
 */
public final class TunnelLayer {
    /** Lowest block of the world (bedrock). */
    public static final int BOTTOM_Y = -128;
    /** Lowest block that can be air. */
    public static final int MIN_AIR_Y = -127;
    /** Highest block that can be air, keeps a solid roof below the vanilla caves at y>=-64. */
    public static final int MAX_AIR_Y = -68;
    /** Lowest / highest possible floor (first air block of a column). */
    public static final int MIN_FLOOR_Y = -125;
    public static final int MAX_FLOOR_Y = -92;

    // network
    private static final double CELL = 88.0;
    private static final double JITTER_MIN = 0.10;
    private static final double JITTER_SPAN = 0.80;
    private static final int WINDOW = 2; // look at (2*WINDOW+1)^2 grid cells around a position
    private static final double WARP_SCALE = 100.0;
    private static final double WARP_AMP = 14.0;

    // tunnels
    private static final double HALF_WIDTH_MIN = 2.8;
    private static final double HALF_WIDTH_VAR = 1.8;
    private static final double WALL_ROUGHNESS = 0.8;
    private static final double TUNNEL_HEIGHT_MIN = 4.0;
    private static final double TUNNEL_HEIGHT_VAR = 2.0;
    /** Air height at the very edge of any open area. Needs to be >= 3 for 1 block steps. */
    private static final double EDGE_AIR = 3.0;

    // caverns
    private static final double CAVERN_CHANCE = 0.45;
    private static final double CAVERN_RADIUS_MIN = 12.0;
    private static final double CAVERN_RADIUS_VAR = 22.0;
    private static final double CAVERN_HEIGHT_MIN = 7.0;
    private static final double CAVERN_HEIGHT_VAR = 9.0;
    private static final double CAVERN_WOBBLE = 2.5;
    private static final double CAVERN_WOBBLE_SCALE = 18.0;
    private static final double CAVERN_REACH = CAVERN_RADIUS_MIN + CAVERN_RADIUS_VAR + 0.8 * CELL;

    // floor
    private static final double FLOOR_SCALE = 400.0;
    private static final double FLOOR_BASE = -122.0;
    private static final double FLOOR_RANGE = 30.0;
    private static final double FLOOR_DETAIL_SCALE = 40.0;
    private static final double FLOOR_DETAIL_AMP = 3.0;

    // salts for the individual noises
    private static final int S_SITE_X = 1, S_SITE_Z = 2, S_WARP_X = 3, S_WARP_Z = 4, S_WIDTH = 5,
            S_FLOOR = 7, S_DETAIL = 8, S_HEIGHT = 9, S_ROUGH_A = 10, S_ROUGH_B = 11,
            S_CAV_ON = 12, S_CAV_R = 13, S_CAV_H = 14, S_CAV_WOBBLE = 15;

    private final long seed;

    public TunnelLayer(long seed) {
        this.seed = seed;
    }

    /** Result of a column query. {@code floor} / {@code ceiling} are the first / last air block. */
    public static final class Column {
        public boolean open;
        public int floor;
        public int ceiling;
    }

    /** Fills {@code out} for the column and returns whether it contains any air. */
    public boolean column(int blockX, int blockZ, Column out) {
        final double px = blockX + 0.5;
        final double pz = blockZ + 0.5;

        // domain warp makes the straight Voronoi borders wind. It is continuous with a slope
        // well below 1, so it is a homeomorphism: it cannot tear the network apart.
        final double qx = px + (valueNoise(px, pz, WARP_SCALE, S_WARP_X) - 0.5) * 2.0 * WARP_AMP;
        final double qz = pz + (valueNoise(px, pz, WARP_SCALE, S_WARP_Z) - 0.5) * 2.0 * WARP_AMP;

        double dist = borderDistance(qx, qz);
        dist += (valueNoise(px, pz, 12.0, S_ROUGH_A) - 0.5) * 2.0 * WALL_ROUGHNESS * 0.7;
        dist += (valueNoise(px, pz, 5.0, S_ROUGH_B) - 0.5) * 2.0 * WALL_ROUGHNESS * 0.3;

        final double halfWidth = HALF_WIDTH_MIN + HALF_WIDTH_VAR * valueNoise(px, pz, 90.0, S_WIDTH);

        double air = 0.0; // air height of the column, 0 = solid
        if (dist < halfWidth) {
            final double u = Math.max(0.0, dist) / halfWidth;
            final double maxHeight = TUNNEL_HEIGHT_MIN + TUNNEL_HEIGHT_VAR * valueNoise(px, pz, 70.0, S_HEIGHT);
            air = dome(u, maxHeight);
        }
        air = Math.max(air, cavernAir(px, pz, qx, qz));

        if (air <= 0.0) {
            out.open = false;
            out.floor = 0;
            out.ceiling = -1;
            return false;
        }

        final int floor = floorY(px, pz);
        out.open = true;
        out.floor = floor;
        out.ceiling = Math.min(floor + (int) Math.round(air) - 1, MAX_AIR_Y);
        return true;
    }

    public boolean isAir(int x, int y, int z, Column scratch) {
        return column(x, z, scratch) && y >= scratch.floor && y <= scratch.ceiling;
    }

    /** Dome shaped cross section: EDGE_AIR blocks at u=1, maxHeight in the middle (u=0). */
    private static double dome(double u, double maxHeight) {
        return EDGE_AIR + (maxHeight - EDGE_AIR) * Math.sqrt(Math.max(0.0, 1.0 - u * u));
    }

    /** Floor height (first air block) at a position. Smooth, mostly flat, slope always below 1. */
    public int floorY(double px, double pz) {
        final double t = valueNoise(px, pz, FLOOR_SCALE, S_FLOOR);
        // flat plateau around the middle, steeper only towards the extremes
        final double c = t - 0.5;
        final double g = 0.5 + 4.0 * c * c * c;
        final double detail = (valueNoise(px, pz, FLOOR_DETAIL_SCALE, S_DETAIL) - 0.5) * FLOOR_DETAIL_AMP;
        final long y = Math.round(FLOOR_BASE + FLOOR_RANGE * g + detail);
        return (int) Math.max(MIN_FLOOR_Y, Math.min(MAX_FLOOR_Y, y));
    }

    /**
     * Air height contributed by caverns at a position, 0 if it is in none. Caverns are circles in
     * the warped (q) space, so they stay attached to the warped network.
     */
    private double cavernAir(double px, double pz, double qx, double qz) {
        final int cx = (int) Math.floor(qx / CELL);
        final int cz = (int) Math.floor(qz / CELL);
        double best = 0.0;
        for (int dz = -WINDOW; dz <= WINDOW; dz++) {
            for (int dx = -WINDOW; dx <= WINDOW; dx++) {
                final int gx = cx + dx;
                final int gz = cz + dz;
                if (unit(hash(gx, gz, S_CAV_ON)) >= CAVERN_CHANCE) {
                    continue;
                }
                final double sx = siteX(gx, gz);
                final double sz = siteZ(gx, gz);
                if ((sx - qx) * (sx - qx) + (sz - qz) * (sz - qz) > CAVERN_REACH * CAVERN_REACH) {
                    continue;
                }
                // nearest neighbour of this site (within +-WINDOW cells it is always the true one)
                double nd = Double.MAX_VALUE;
                double nx = sx;
                double nz = sz;
                for (int ez = -WINDOW; ez <= WINDOW; ez++) {
                    for (int ex = -WINDOW; ex <= WINDOW; ex++) {
                        if (ex == 0 && ez == 0) {
                            continue;
                        }
                        final double ox = siteX(gx + ex, gz + ez);
                        final double oz = siteZ(gx + ex, gz + ez);
                        final double d2 = (ox - sx) * (ox - sx) + (oz - sz) * (oz - sz);
                        if (d2 < nd) {
                            nd = d2;
                            nx = ox;
                            nz = oz;
                        }
                    }
                }
                final double mx = 0.5 * (sx + nx);
                final double mz = 0.5 * (sz + nz);
                final double rnd = unit(hash(gx, gz, S_CAV_R));
                final double radius = CAVERN_RADIUS_MIN + CAVERN_RADIUS_VAR * rnd * rnd;
                final double wobble = (valueNoise(px, pz, CAVERN_WOBBLE_SCALE, S_CAV_WOBBLE) - 0.5) * 2.0 * CAVERN_WOBBLE;
                final double r = Math.sqrt((qx - mx) * (qx - mx) + (qz - mz) * (qz - mz)) + wobble;
                if (r >= radius) {
                    continue;
                }
                final double height = CAVERN_HEIGHT_MIN + CAVERN_HEIGHT_VAR * unit(hash(gx, gz, S_CAV_H));
                best = Math.max(best, dome(Math.max(0.0, r) / radius, height));
            }
        }
        return best;
    }

    /**
     * Exact distance from the point to the border of its Voronoi cell, i.e. to the closest tunnel
     * centre line. The cell is convex, so this is the minimum over all bisector lines.
     */
    public double borderDistance(double x, double z) {
        final int cx = (int) Math.floor(x / CELL);
        final int cz = (int) Math.floor(z / CELL);

        final int n = 2 * WINDOW + 1;
        final double[] sx = new double[n * n];
        final double[] sz = new double[n * n];
        double best = Double.MAX_VALUE;
        int bi = 0;
        int i = 0;
        for (int dz = -WINDOW; dz <= WINDOW; dz++) {
            for (int dx = -WINDOW; dx <= WINDOW; dx++, i++) {
                sx[i] = siteX(cx + dx, cz + dz);
                sz[i] = siteZ(cx + dx, cz + dz);
                final double d2 = (sx[i] - x) * (sx[i] - x) + (sz[i] - z) * (sz[i] - z);
                if (d2 < best) {
                    best = d2;
                    bi = i;
                }
            }
        }

        double min = Double.MAX_VALUE;
        for (int k = 0; k < sx.length; k++) {
            if (k == bi) {
                continue;
            }
            final double d2 = (sx[k] - x) * (sx[k] - x) + (sz[k] - z) * (sz[k] - z);
            final double sep = Math.sqrt((sx[k] - sx[bi]) * (sx[k] - sx[bi]) + (sz[k] - sz[bi]) * (sz[k] - sz[bi]));
            final double d = (d2 - best) / (2.0 * sep);
            if (d < min) {
                min = d;
            }
        }
        return min;
    }

    private double siteX(int gx, int gz) {
        return (gx + JITTER_MIN + JITTER_SPAN * unit(hash(gx, gz, S_SITE_X))) * CELL;
    }

    private double siteZ(int gx, int gz) {
        return (gz + JITTER_MIN + JITTER_SPAN * unit(hash(gx, gz, S_SITE_Z))) * CELL;
    }

    // ---- noise helpers --------------------------------------------------------------------

    private double valueNoise(double x, double z, double scale, int salt) {
        final double fx = x / scale;
        final double fz = z / scale;
        final int ix = (int) Math.floor(fx);
        final int iz = (int) Math.floor(fz);
        final double tx = fade(fx - ix);
        final double tz = fade(fz - iz);
        final double v00 = unit(hash(ix, iz, salt));
        final double v10 = unit(hash(ix + 1, iz, salt));
        final double v01 = unit(hash(ix, iz + 1, salt));
        final double v11 = unit(hash(ix + 1, iz + 1, salt));
        final double a = v00 + (v10 - v00) * tx;
        final double b = v01 + (v11 - v01) * tx;
        return a + (b - a) * tz;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private long hash(int x, int z, int salt) {
        long h = mix(seed ^ (salt * 0x9E3779B97F4A7C15L));
        h = mix(h + x * 0xD6E8FEB86659FD93L);
        h = mix(h + z * 0xCA5A826395121157L);
        return h;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
