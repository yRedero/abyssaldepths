package redero.abyssaldepths.worldgen;

/**
 * Deterministic, infinite cave layer for y=-65 .. y=-128.
 * <p>
 * Pure function of (seed, x, y, z): no state, no Minecraft dependencies, so it can be unit tested
 * and used from any chunk in any order.
 * <p>
 * How it works:
 * <ul>
 * <li><b>Great cavern:</b> the whole layer is one open cavern without an outer edge. Its floor is
 * a gentle height field (slope well below one block per block, so it is always walkable). Its
 * height varies a lot: tall halls, and stretches where the ceiling comes down to a low crawl
 * space.</li>
 * <li><b>Rock masses:</b> blobs of solid rock where the ceiling comes down to the floor. Their
 * share changes by region (open plains vs. areas with narrow passages between the rock), but it
 * stays well below the percolation threshold, so they never wall off part of the cavern.</li>
 * <li><b>Massifs:</b> big solid rock blobs, one per cell of a jittered grid (most cells). The
 * jitter is large, so there are no straight corridors along the grid lines. Two massifs never
 * come closer than a corridor width, so they are islands and the cavern stays connected: you can
 * walk on forever, just not in a straight line.</li>
 * <li><b>Pillars:</b> one per cell of a jittered grid (most cells), slightly swaying, flared at
 * floor and ceiling like merged stalactites and stalagmites. Only in halls tall enough.</li>
 * <li><b>Ceiling:</b> stalactite clusters and 3D noise, so it is not a flat lid.</li>
 * <li><b>Connector tunnels</b> (rare, see {@link #connectorMask}): up-shafts that wind from the
 * cavern floor through the roof into the deepslate layer and end in a small chamber there, and
 * passes straight through some of the bigger massifs, opening up the areas behind them.</li>
 * </ul>
 */
public final class TunnelLayer {
    /** Lowest block of the world (bedrock). */
    public static final int BOTTOM_Y = -128;
    /** Highest block of the thalan layer, the deepslate layer starts above. */
    public static final int LAYER_TOP_Y = -65;
    /** Lowest block that can be air, keeps solid rock above the bedrock (which reaches up to y=-124). */
    public static final int MIN_AIR_Y = -120;
    /** Highest block that can be air, keeps a solid roof below the vanilla caves at y>=-64. */
    public static final int MAX_AIR_Y = -68;
    /** Lowest / highest possible cavern floor (first air block of a column). */
    public static final int MIN_FLOOR_Y = -120;
    public static final int MAX_FLOOR_Y = -96;
    /** Highest base ceiling of the cavern, the 3D ceiling noise can go a few blocks above. */
    private static final int MAX_CEILING_Y = -72;

    // floor
    private static final double FLOOR_BASE = -109.0;
    private static final double FLOOR_AMP = 10.0;
    private static final double FLOOR_SCALE = 340.0;
    private static final double FLOOR_DETAIL_AMP = 2.5;
    private static final double FLOOR_DETAIL_SCALE = 45.0;

    // cavern height
    private static final double HEIGHT_MIN = 5.0; // the low, thin stretches
    private static final double HEIGHT_VAR = 33.0;
    private static final double HEIGHT_SCALE = 240.0;
    /** Air height at the very edge of a rock mass. Needs to be >= 3 for 1 block steps. */
    private static final double EDGE_AIR = 3.0;

    // rock masses
    private static final double ROCK_SCALE = 58.0;
    private static final double ROCK_DETAIL_SCALE = 19.0;
    private static final double ROCK_REGION_SCALE = 520.0;
    private static final double ROCK_THRESHOLD_OPEN = 0.82;  // open plains: few rock masses
    private static final double ROCK_THRESHOLD_DENSE = 0.66; // dense regions: narrow passages
    private static final double ROCK_EDGE = 0.22;            // width of the sloped edge in noise units

    // massifs (sizes in blocks)
    private static final double MASSIF_CELL = 128.0;
    private static final double MASSIF_CHANCE = 0.9;
    private static final double MASSIF_JITTER_MIN = 0.12;
    private static final double MASSIF_JITTER_SPAN = 0.76;
    private static final double MASSIF_RADIUS_MIN = 30.0;
    private static final double MASSIF_RADIUS_VAR = 24.0;
    private static final double MASSIF_RADIUS_DROP = 10.0; // squeezed below this: no massif
    private static final double MASSIF_WOBBLE = 0.18;   // relative, the outline is not a circle
    private static final double MASSIF_CORRIDOR = 16.0; // min. gap between two massifs
    private static final double MASSIF_EDGE = 10.0;     // the ceiling comes down over this distance

    // pillars
    private static final double PILLAR_CELL = 21.0;
    private static final double PILLAR_CHANCE = 0.75;
    private static final double PILLAR_JITTER_MIN = 0.15;
    private static final double PILLAR_JITTER_SPAN = 0.70;
    private static final double PILLAR_RADIUS_MIN = 1.4;
    private static final double PILLAR_RADIUS_VAR = 2.3;
    private static final double PILLAR_FLARE = 0.9;     // relative extra radius at floor / ceiling
    private static final double PILLAR_FLARE_ADD = 1.0; // absolute extra radius at floor / ceiling
    private static final double PILLAR_SWAY = 1.6;
    private static final double PILLAR_MIN_HALL = 10.0; // no pillars in lower parts of the cavern

    // ceiling
    private static final double STALACTITE_SCALE = 5.0;
    private static final double STALACTITE_MAX = 7.0;
    private static final double CEILING_WOBBLE_MAX = 3.0;

    // connector tunnels (sizes in blocks)
    /** Highest block a connector can carve (top of the chamber at the end of an up-shaft). */
    public static final int CONNECTOR_MAX_Y = -26;
    private static final double SHAFT_CELL = 176.0;      // at most one up-shaft per cell
    private static final double SHAFT_CHANCE = 0.6;
    private static final double SHAFT_LENGTH_MIN = 70.0; // horizontal, keeps the climb walkable-ish
    private static final double SHAFT_LENGTH_VAR = 50.0;
    private static final double SHAFT_TOP_MIN = -44.0;   // end chamber centre
    private static final double SHAFT_TOP_VAR = 10.0;
    private static final double SHAFT_CHAMBER = 4.0;
    private static final double PASS_CHANCE = 0.4;       // share of the bigger massifs with a pass
    private static final double PASS_MIN_MASSIF = 20.0;
    private static final double TUNNEL_RADIUS = 2.1;
    private static final double TUNNEL_RADIUS_VAR = 0.5;
    private static final double TUNNEL_FLATTEN = 0.85;   // vertical radius relative to horizontal

    // salts for the individual noises
    private static final int S_FLOOR = 1, S_FLOOR_DETAIL = 2, S_HEIGHT = 3, S_ROCK = 4, S_ROCK_DETAIL = 5,
            S_ROCK_REGION = 6, S_PILLAR_ON = 7, S_PILLAR_X = 8, S_PILLAR_Z = 9, S_PILLAR_R = 10,
            S_PILLAR_SWAY_X = 11, S_PILLAR_SWAY_Z = 12, S_PILLAR_WOBBLE = 13, S_STALACTITE = 14,
            S_STALACTITE_AMP = 15, S_CEILING = 16, S_MASSIF_ON = 17,
            S_MASSIF_X = 18, S_MASSIF_Z = 19, S_MASSIF_R = 20, S_MASSIF_WOBBLE = 21, S_SHAFT_ON = 22,
            S_SHAFT_X = 23, S_SHAFT_Z = 24, S_SHAFT_DIR = 25, S_SHAFT_LEN = 26, S_SHAFT_TOP = 27,
            S_TUNNEL_WAVE = 28, S_TUNNEL_AMP = 29, S_PASS_ON = 30, S_PASS_DIR = 31, SALTS = 32;

    private final long[] salted = new long[SALTS];

    public TunnelLayer(long seed) {
        for (int i = 0; i < salted.length; i++) {
            salted[i] = mix(seed ^ (i * 0x9E3779B97F4A7C15L));
        }
    }

    /**
     * Per column data of the great cavern, filled by {@link #column}. {@code floor} is the first
     * air block, {@code ceiling} the last one before the 3D ceiling noise is applied.
     */
    public static final class Column {
        public boolean open;
        public int floor;
        public int ceiling;
        double wobble;
        int pillars;
        final double[] pillarX = new double[9];
        final double[] pillarZ = new double[9];
        final double[] pillarR = new double[9];
        final long[] pillarKey = new long[9];
    }

    /** Fills {@code out} for the column and returns whether the great cavern is open there. */
    public boolean column(int blockX, int blockZ, Column out) {
        final double px = blockX + 0.5;
        final double pz = blockZ + 0.5;
        out.pillars = 0;

        final double hall = hallHeight(px, pz);
        if (hall < EDGE_AIR) {
            out.open = false;
            out.floor = 0;
            out.ceiling = -1;
            return false;
        }

        final int floor = floorY(px, pz);
        int ceiling = Math.min(floor + (int) Math.round(hall) - 1, MAX_CEILING_Y);
        final int air = ceiling - floor + 1;

        // stalactites: narrow bumps hanging down, only in the taller parts
        final double room = Math.min(STALACTITE_MAX, (air - 8) * 0.35);
        if (room > 0.0) {
            final double s = perlin(px / STALACTITE_SCALE, 0.37, pz / STALACTITE_SCALE, S_STALACTITE);
            final double cluster = perlin(px / 40.0, 0.61, pz / 40.0, S_STALACTITE_AMP);
            final double drop = room * clamp01((s - 0.12) / 0.35) * clamp01(0.5 + 2.0 * cluster);
            ceiling -= (int) Math.round(drop);
        }

        out.open = true;
        out.floor = floor;
        out.ceiling = ceiling;
        // the 3D wobble must never take the air below 3 blocks
        out.wobble = Math.max(0.0, Math.min(CEILING_WOBBLE_MAX, (ceiling - floor - 3) * 0.3));

        // pillars of the 3x3 grid cells around (their reach is less than one cell)
        final int cx = (int) Math.floor(px / PILLAR_CELL);
        final int cz = (int) Math.floor(pz / PILLAR_CELL);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                final int gx = cx + dx;
                final int gz = cz + dz;
                if (unit(hash(gx, gz, S_PILLAR_ON)) >= PILLAR_CHANCE) {
                    continue;
                }
                final double sx = (gx + PILLAR_JITTER_MIN + PILLAR_JITTER_SPAN * unit(hash(gx, gz, S_PILLAR_X))) * PILLAR_CELL;
                final double sz = (gz + PILLAR_JITTER_MIN + PILLAR_JITTER_SPAN * unit(hash(gx, gz, S_PILLAR_Z))) * PILLAR_CELL;
                final double centreHall = hallHeight(sx, sz);
                if (centreHall < PILLAR_MIN_HALL) {
                    continue;
                }
                final double rnd = unit(hash(gx, gz, S_PILLAR_R));
                // thinner pillars in lower halls
                final double size = clamp01((centreHall - PILLAR_MIN_HALL) / 14.0);
                final int k = out.pillars++;
                out.pillarX[k] = sx;
                out.pillarZ[k] = sz;
                out.pillarR[k] = PILLAR_RADIUS_MIN + PILLAR_RADIUS_VAR * rnd * (0.45 + 0.55 * size);
                out.pillarKey[k] = hash(gx, gz, S_PILLAR_SWAY_X);
            }
        }
        return true;
    }

    /** Whether the block is air in the great cavern. {@code col} must be filled for (x, z). */
    public boolean cavernAir(int blockX, int y, int blockZ, Column col) {
        if (!col.open || y < col.floor) {
            return false;
        }
        final double px = blockX + 0.5;
        final double py = y + 0.5;
        final double pz = blockZ + 0.5;
        int top = col.ceiling;
        if (col.wobble > 0.0) {
            top += (int) Math.round(col.wobble * clampSigned(1.6 * perlin(px / 11.0, py / 7.0, pz / 11.0, S_CEILING)));
        }
        if (y > Math.min(top, MAX_AIR_Y)) {
            return false;
        }
        return !inPillar(px, py, pz, col);
    }

    private boolean inPillar(double px, double py, double pz, Column col) {
        if (col.pillars == 0) {
            return false;
        }
        final double span = Math.max(1.0, col.ceiling - col.floor);
        final double t = (py - col.floor) / span;
        final double e = Math.pow(Math.abs(2.0 * t - 1.0), 4.0); // 1 at floor and ceiling, ~0 in the middle
        for (int k = 0; k < col.pillars; k++) {
            final long key = col.pillarKey[k];
            final double sx = col.pillarX[k] + PILLAR_SWAY * (noise1(py / 13.0, key) - 0.5) * 2.0;
            final double sz = col.pillarZ[k] + PILLAR_SWAY * (noise1(py / 13.0, key ^ S_PILLAR_SWAY_Z) - 0.5) * 2.0;
            final double r0 = col.pillarR[k];
            final double r = r0 * (1.0 + PILLAR_FLARE * e) + PILLAR_FLARE_ADD * e;
            final double reach = r + 0.7;
            final double d2 = (px - sx) * (px - sx) + (pz - sz) * (pz - sz);
            if (d2 > reach * reach) {
                continue;
            }
            final double wobble = 0.7 * perlin(px / 4.0, py / 6.0, pz / 4.0, S_PILLAR_WOBBLE);
            if (Math.sqrt(d2) < r + wobble) {
                return true;
            }
        }
        return false;
    }

    /** Whether the block is air in the great cavern, connectors not included. {@code col} must be filled for (x, z). */
    public boolean isAir(int blockX, int y, int blockZ, Column col) {
        return y >= MIN_AIR_Y && y <= MAX_AIR_Y && cavernAir(blockX, y, blockZ, col);
    }

    // ---- connector tunnels ----------------------------------------------------------------

    /** Index into a {@link #connectorMask}, y from {@link #MIN_AIR_Y} to {@link #CONNECTOR_MAX_Y}. */
    public static int maskIndex(int dx, int y, int dz) {
        return ((y - MIN_AIR_Y) * 16 + dz) * 16 + dx;
    }

    /**
     * Air of the connector tunnels inside one chunk. Each tunnel is a chain of ellipsoids along a
     * winding path; only the tunnels starting in the grid cells around the chunk can reach it.
     */
    public boolean[] connectorMask(int chunkX, int chunkZ) {
        final boolean[] mask = new boolean[(CONNECTOR_MAX_Y - MIN_AIR_Y + 1) * 256];
        final int x0 = chunkX << 4, z0 = chunkZ << 4;
        final Column col = new Column();

        // up-shafts: from the cavern floor through the roof into the deepslate layer
        final int scx = (int) Math.floor((x0 + 8) / SHAFT_CELL);
        final int scz = (int) Math.floor((z0 + 8) / SHAFT_CELL);
        for (int gz = scz - 1; gz <= scz + 1; gz++) {
            for (int gx = scx - 1; gx <= scx + 1; gx++) {
                if (unit(hash(gx, gz, S_SHAFT_ON)) >= SHAFT_CHANCE) {
                    continue;
                }
                final double sx = (gx + 0.15 + 0.7 * unit(hash(gx, gz, S_SHAFT_X))) * SHAFT_CELL;
                final double sz = (gz + 0.15 + 0.7 * unit(hash(gx, gz, S_SHAFT_Z))) * SHAFT_CELL;
                if (!column((int) Math.floor(sx), (int) Math.floor(sz), col)) {
                    continue; // must start in the open cavern
                }
                final double angle = 2.0 * Math.PI * unit(hash(gx, gz, S_SHAFT_DIR));
                final double len = SHAFT_LENGTH_MIN + SHAFT_LENGTH_VAR * unit(hash(gx, gz, S_SHAFT_LEN));
                final double top = SHAFT_TOP_MIN + SHAFT_TOP_VAR * unit(hash(gx, gz, S_SHAFT_TOP));
                final double ex = sx + Math.cos(angle) * len, ez = sz + Math.sin(angle) * len;
                tunnel(mask, x0, z0, sx, col.floor + 1.8, sz, ex, top, ez,
                        hash(gx, gz, S_TUNNEL_WAVE), 5.0 + 7.0 * unit(hash(gx, gz, S_TUNNEL_AMP)));
                ellipsoid(mask, x0, z0, ex, top, ez, SHAFT_CHAMBER);
            }
        }

        // passes through the bigger massifs, at floor height on both sides
        final int mcx = (int) Math.floor((x0 + 8) / MASSIF_CELL);
        final int mcz = (int) Math.floor((z0 + 8) / MASSIF_CELL);
        for (int gz = mcz - 1; gz <= mcz + 1; gz++) {
            for (int gx = mcx - 1; gx <= mcx + 1; gx++) {
                if (!massifExists(gx, gz) || unit(hash(gx, gz, S_PASS_ON)) >= PASS_CHANCE) {
                    continue;
                }
                final double r = massifRadiusFitted(gx, gz);
                if (r < PASS_MIN_MASSIF) {
                    continue;
                }
                final double angle = 2.0 * Math.PI * unit(hash(gx, gz, S_PASS_DIR));
                final double half = r * (1.0 + MASSIF_WOBBLE) + MASSIF_EDGE;
                final double mx = massifX(gx, gz), mz = massifZ(gx, gz);
                final double ax = mx - Math.cos(angle) * half, az = mz - Math.sin(angle) * half;
                final double bx = mx + Math.cos(angle) * half, bz = mz + Math.sin(angle) * half;
                tunnel(mask, x0, z0, ax, floorY(ax, az) + 1.8, az, bx, floorY(bx, bz) + 1.8, bz,
                        hash(gx, gz, S_TUNNEL_WAVE), 3.0);
            }
        }
        return mask;
    }

    /** Winding tunnel from a to b: whole sine waves to the side, so both ends stay where they are. */
    private void tunnel(boolean[] mask, int x0, int z0, double ax, double ay, double az, double bx, double by, double bz,
            long key, double amp) {
        final double dx = bx - ax, dz = bz - az;
        final double len = Math.sqrt(dx * dx + dz * dz);
        // cheap reject: the tunnel can not come near the chunk
        final double reach = amp + TUNNEL_RADIUS + TUNNEL_RADIUS_VAR + 1.0;
        if (Math.max(ax, bx) + reach < x0 || Math.min(ax, bx) - reach > x0 + 15
                || Math.max(az, bz) + reach < z0 || Math.min(az, bz) - reach > z0 + 15) {
            return;
        }
        final double px = -dz / len, pz = dx / len; // sideways
        final int waves = 1 + (int) (2.0 * unit(key));
        final double phase = 2.0 * Math.PI * unit(mix(key));
        final int steps = (int) Math.ceil(Math.hypot(len, by - ay) / 0.6) + 1;
        for (int i = 0; i <= steps; i++) {
            final double t = i / (double) steps;
            final double side = amp * Math.sin(Math.PI * waves * t);
            final double r = TUNNEL_RADIUS + TUNNEL_RADIUS_VAR * Math.sin(7.0 * t + phase);
            ellipsoid(mask, x0, z0, ax + dx * t + px * side, ay + (by - ay) * t, az + dz * t + pz * side, r);
        }
    }

    private static void ellipsoid(boolean[] mask, int x0, int z0, double cx, double cy, double cz, double r) {
        final double ry = r * TUNNEL_FLATTEN;
        final int minX = Math.max(x0, (int) Math.floor(cx - r)), maxX = Math.min(x0 + 15, (int) Math.floor(cx + r));
        final int minZ = Math.max(z0, (int) Math.floor(cz - r)), maxZ = Math.min(z0 + 15, (int) Math.floor(cz + r));
        final int minY = Math.max(MIN_AIR_Y, (int) Math.floor(cy - ry)), maxY = Math.min(CONNECTOR_MAX_Y, (int) Math.floor(cy + ry));
        for (int y = minY; y <= maxY; y++) {
            final double ny = (y + 0.5 - cy) / ry;
            for (int z = minZ; z <= maxZ; z++) {
                final double nz = (z + 0.5 - cz) / r;
                for (int x = minX; x <= maxX; x++) {
                    final double nx = (x + 0.5 - cx) / r;
                    if (nx * nx + ny * ny + nz * nz <= 1.0) {
                        mask[maskIndex(x - x0, y, z - z0)] = true;
                    }
                }
            }
        }
    }

    /** Air height of the cavern at a position before ceiling details, below EDGE_AIR = solid rock. */
    private double hallHeight(double px, double pz) {
        final double t = 0.5 + 0.5 * clampSigned(2.2 * perlin(px / HEIGHT_SCALE, 0.23, pz / HEIGHT_SCALE, S_HEIGHT));
        // a plateau of tall halls, now and then dropping to the thin crawl stretches
        final double height = HEIGHT_MIN + HEIGHT_VAR * smoothstep(0.12, 0.62, t);

        final double region = 0.5 + 0.5 * clampSigned(2.0 * perlin(px / ROCK_REGION_SCALE, 0.71, pz / ROCK_REGION_SCALE, S_ROCK_REGION));
        final double threshold = ROCK_THRESHOLD_OPEN + (ROCK_THRESHOLD_DENSE - ROCK_THRESHOLD_OPEN) * smoothstep(0.3, 0.8, region);
        final double rock = 0.5 + 0.5 * clampSigned(1.7 * (0.8 * perlin(px / ROCK_SCALE, 0.13, pz / ROCK_SCALE, S_ROCK)
                + 0.35 * perlin(px / ROCK_DETAIL_SCALE, 0.89, pz / ROCK_DETAIL_SCALE, S_ROCK_DETAIL)));
        if (rock >= threshold) {
            return 0.0;
        }
        final double massif = massifDistance(px, pz);
        if (massif <= 0.0) {
            return 0.0;
        }
        // dome shaped towards the rock: the ceiling comes down, the floor stays walkable
        final double u = Math.min(clamp01((threshold - rock) / ROCK_EDGE), clamp01(massif / MASSIF_EDGE));
        final double dome = Math.sqrt(1.0 - (1.0 - u) * (1.0 - u));
        return EDGE_AIR + (height - EDGE_AIR) * dome;
    }

    /**
     * Distance in blocks to the outline of the nearest massif, <= 0 inside one. Only the 3x3
     * cells around can reach the position (diameter + jitter stays below one cell).
     */
    private double massifDistance(double px, double pz) {
        final int cx = (int) Math.floor(px / MASSIF_CELL);
        final int cz = (int) Math.floor(pz / MASSIF_CELL);
        final double reach = (MASSIF_RADIUS_MIN + MASSIF_RADIUS_VAR) * (1.0 + MASSIF_WOBBLE) + MASSIF_EDGE;
        double best = MASSIF_EDGE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                final int gx = cx + dx;
                final int gz = cz + dz;
                if (!massifExists(gx, gz)) {
                    continue;
                }
                final double d = Math.hypot(px - massifX(gx, gz), pz - massifZ(gx, gz));
                if (d > reach) {
                    continue;
                }
                final double r = massifRadiusFitted(gx, gz);
                if (r < MASSIF_RADIUS_DROP) {
                    continue;
                }
                final double w = 1.0 + MASSIF_WOBBLE * clampSigned(2.0 * perlin(px / 24.0, 0.29, pz / 24.0, S_MASSIF_WOBBLE));
                best = Math.min(best, d - r * w);
            }
        }
        return best;
    }

    private boolean massifExists(int gx, int gz) {
        return unit(hash(gx, gz, S_MASSIF_ON)) < MASSIF_CHANCE;
    }

    /**
     * Radius shrunk so that each massif keeps at most half of the gap to every neighbour minus the
     * corridor. Kept massifs never touch, so they can not enclose any part of the cavern.
     */
    private double massifRadiusFitted(int gx, int gz) {
        final double x = massifX(gx, gz), z = massifZ(gx, gz);
        double r = massifRadius(gx, gz);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx | dz) == 0 || !massifExists(gx + dx, gz + dz)) {
                    continue;
                }
                final double d = Math.hypot(x - massifX(gx + dx, gz + dz), z - massifZ(gx + dx, gz + dz));
                r = Math.min(r, (d - MASSIF_CORRIDOR) / (2.0 * (1.0 + MASSIF_WOBBLE)));
            }
        }
        return r;
    }

    private double massifX(int gx, int gz) {
        return (gx + MASSIF_JITTER_MIN + MASSIF_JITTER_SPAN * unit(hash(gx, gz, S_MASSIF_X))) * MASSIF_CELL;
    }

    private double massifZ(int gx, int gz) {
        return (gz + MASSIF_JITTER_MIN + MASSIF_JITTER_SPAN * unit(hash(gx, gz, S_MASSIF_Z))) * MASSIF_CELL;
    }

    private double massifRadius(int gx, int gz) {
        return MASSIF_RADIUS_MIN + MASSIF_RADIUS_VAR * unit(hash(gx, gz, S_MASSIF_R));
    }

    /** Floor height (first air block) at a position. Smooth, slope always below 1. */
    public int floorY(double px, double pz) {
        final double big = perlin(px / FLOOR_SCALE, 0.41, pz / FLOOR_SCALE, S_FLOOR);
        final double detail = perlin(px / FLOOR_DETAIL_SCALE, 0.53, pz / FLOOR_DETAIL_SCALE, S_FLOOR_DETAIL);
        final long y = Math.round(FLOOR_BASE + FLOOR_AMP * clampSigned(1.8 * big) + FLOOR_DETAIL_AMP * detail * 2.0);
        return (int) Math.max(MIN_FLOOR_Y, Math.min(MAX_FLOOR_Y, y));
    }

    // ---- noise helpers --------------------------------------------------------------------

    /** Gradient (Perlin) noise, roughly in [-1, 1], mostly in [-0.5, 0.5]. */
    private double perlin(double x, double y, double z, int salt) {
        final int ix = (int) Math.floor(x);
        final int iy = (int) Math.floor(y);
        final int iz = (int) Math.floor(z);
        final double fx = x - ix, fy = y - iy, fz = z - iz;
        final double u = fade(fx), v = fade(fy), w = fade(fz);
        final long base = salted[salt];
        final double x00 = lerp(u, grad(base, ix, iy, iz, fx, fy, fz), grad(base, ix + 1, iy, iz, fx - 1, fy, fz));
        final double x10 = lerp(u, grad(base, ix, iy + 1, iz, fx, fy - 1, fz), grad(base, ix + 1, iy + 1, iz, fx - 1, fy - 1, fz));
        final double x01 = lerp(u, grad(base, ix, iy, iz + 1, fx, fy, fz - 1), grad(base, ix + 1, iy, iz + 1, fx - 1, fy, fz - 1));
        final double x11 = lerp(u, grad(base, ix, iy + 1, iz + 1, fx, fy - 1, fz - 1), grad(base, ix + 1, iy + 1, iz + 1, fx - 1, fy - 1, fz - 1));
        return lerp(w, lerp(v, x00, x10), lerp(v, x01, x11));
    }

    private static double grad(long base, int x, int y, int z, double dx, double dy, double dz) {
        final long h = mix(base + x * 0xD6E8FEB86659FD93L + y * 0xA0761D6478BD642FL + z * 0xCA5A826395121157L);
        switch ((int) (h >>> 60)) { // the 12 cube edge directions, 4 repeated
            case 0: case 12: return dx + dy;
            case 1: case 13: return -dx + dy;
            case 2: return dx - dy;
            case 3: return -dx - dy;
            case 4: return dx + dz;
            case 5: return -dx + dz;
            case 6: return dx - dz;
            case 7: return -dx - dz;
            case 8: case 14: return dy + dz;
            case 9: case 15: return -dy + dz;
            case 10: return dy - dz;
            default: return -dy - dz;
        }
    }

    /** Smooth 1D value noise in [0, 1]. */
    private static double noise1(double t, long key) {
        final int i = (int) Math.floor(t);
        final double f = fade(t - i);
        final double a = unit(mix(key + i * 0xD6E8FEB86659FD93L));
        final double b = unit(mix(key + (i + 1) * 0xD6E8FEB86659FD93L));
        return a + (b - a) * f;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    private static double clampSigned(double v) {
        return v < -1.0 ? -1.0 : (v > 1.0 ? 1.0 : v);
    }

    private static double smoothstep(double a, double b, double v) {
        final double t = clamp01((v - a) / (b - a));
        return t * t * (3.0 - 2.0 * t);
    }

    private long hash(int x, int z, int salt) {
        long h = salted[salt];
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
