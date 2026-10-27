package redero.abyssaldepths.worldgen;

import java.util.ArrayList;
import java.util.List;

/**
 * Rock formations of the layer biomes, as pure geometry: no Minecraft dependencies, so they can be
 * previewed and tested offline. Each formation comes from a jittered grid cell (deterministic per
 * seed) and only exists if its centre lies in the open cavern of its biome. A chunk asks for the
 * formations near it and lets each one fill its own columns; formations crossing chunk borders are
 * therefore built piece by piece, every chunk placing only its share, after its own carving.
 * <ul>
 * <li><b>Shale flats:</b> spires (pointed towers with a fractured vein, rubble ring), shards (round
 * cones with a broad foot and a sharp point, in groups, rubble heaps), tilted round slabs of fractured
 * shale (shelter below the raised side), stone cairns, and small thalan-lined craters (some with a
 * hole into a cavity with ore in its walls).</li>
 * <li><b>Silt dunes:</b> tall slender arches, big outcrops, collapsed basins with dark rock, fossils
 * and ore at the bottom, sediment falls (a ledge with silt pouring over into a fan) and collapse holes
 * into cavities with fossils and ore.</li>
 * <li><b>Thermal reaches:</b> vent cones of vent blocks, broad at the foot (in clusters around an
 * oxide deposit with a sulfur core, or alone), fields of small vents in the ground, mineral chimneys,
 * sinter terraces with stepped basins around a pool, and collapsed vent
 * craters showing older layers, with a pool or a vent at the bottom (then glowing around it).</li>
 * </ul>
 */
public final class LayerFormations {
    /** Materials, mapped to blocks by the caller. */
    public enum Mat {
        AIR, WATER, THALAN, SHALE, FRACTURED_SHALE, SHALE_RUBBLE, SILTSTONE, PACKED_SILT, FINE_SILT, FOSSIL,
        THERMAL_ROCK, OXIDE, SINTER, VENT, ACTIVE_VENT, SULFUR, MAT, SMOULDERING, ORE_IRON, ORE_COPPER, ORE_GOLD, ORE_REDSTONE
    }

    /** Layer biome of a block column. */
    public interface Biomes {
        int DEPTHS = 0, FLATS = 1, DUNES = 2, THERMAL = 3;

        int at(int x, int z);
    }

    /** The world (or a test grid) the formations are placed into. */
    public interface Sink {
        boolean air(int x, int y, int z);

        /** Air or loose sediment (fine / packed silt): formations of rock may replace it. */
        boolean open(int x, int y, int z);

        /** Solid ground that may be dug out or turned into ore (no fluids, no other formations' vents). */
        boolean solid(int x, int y, int z);

        void set(int x, int y, int z, Mat mat);
    }

    /** Lowest y a cavity may reach, keeps rock above the bedrock. */
    private static final int CAVITY_FLOOR = -121;

    private final TunnelLayer layer;
    private final Biomes biomes;

    public LayerFormations(TunnelLayer layer, Biomes biomes) {
        this.layer = layer;
        this.biomes = biomes;
    }

    /** All formations that can reach into the box x0..x1, z0..z1. */
    public List<Formation> near(int x0, int z0, int x1, int z1) {
        final List<Formation> out = new ArrayList<>();
        for (Type type : Type.values()) {
            // centres further away than the type's largest reach can not touch the box
            final int margin = type.reach;
            final int gx0 = Math.floorDiv(x0 - margin, type.cell), gx1 = Math.floorDiv(x1 + margin, type.cell);
            final int gz0 = Math.floorDiv(z0 - margin, type.cell), gz1 = Math.floorDiv(z1 + margin, type.cell);
            for (int gz = gz0; gz <= gz1; gz++) {
                for (int gx = gx0; gx <= gx1; gx++) {
                    create(type, gx, gz, out);
                }
            }
        }
        out.removeIf(f -> f.maxX() < x0 || f.minX() > x1 || f.maxZ() < z0 || f.minZ() > z1);
        return out;
    }

    /** Extra dune height from sediment piling up against formations, in blocks. */
    public static double duneBoost(List<Formation> near, int x, int z) {
        double boost = 0.0;
        for (Formation f : near) {
            boost = Math.max(boost, f.duneBoost(x + 0.5, z + 0.5));
        }
        return boost;
    }

    /** Factor on the dune height, 0 inside collapsed basins. */
    public static double duneDamp(List<Formation> near, int x, int z) {
        double damp = 1.0;
        for (Formation f : near) {
            damp = Math.min(damp, f.duneDamp(x + 0.5, z + 0.5));
        }
        return damp;
    }

    // ---- instances ----------------------------------------------------------------

    private enum Type {
        // cell, chance, biome, largest reach from the centre (group spread included)
        BLADES(18, 0.7, Biomes.FLATS, 32), PLATE(30, 0.55, Biomes.FLATS, 16), CAIRN(40, 0.3, Biomes.FLATS, 7),
        SPIRE(36, 0.45, Biomes.FLATS, 10), SHALE_CRATER(30, 0.35, Biomes.FLATS, 12), ARCH(70, 0.4, Biomes.DUNES, 28), OUTCROP(64, 0.45, Biomes.DUNES, 26),
        BASIN(64, 0.35, Biomes.DUNES, 26), FALL(50, 0.4, Biomes.DUNES, 25), CAVITY(36, 0.35, Biomes.DUNES, 8),
        VENT_CLUSTER(64, 0.55, Biomes.THERMAL, 16), FUMAROLE(14, 0.22, Biomes.THERMAL, 5), CHIMNEY(34, 0.3, Biomes.THERMAL, 8),
        VENT_FIELD(48, 0.45, Biomes.THERMAL, 13),
        // terraces hold water at their own levels: little jitter, so two of them never touch
        TERRACE(60, 0.65, Biomes.THERMAL, 0.3, 0.4, 23), CRATER(52, 0.35, Biomes.THERMAL, 14);

        final int cell;
        final double chance;
        final int biome, reach;
        final double jitterMin, jitterSpan;

        Type(int cell, double chance, int biome, int reach) {
            this(cell, chance, biome, 0.15, 0.7, reach);
        }

        Type(int cell, double chance, int biome, double jitterMin, double jitterSpan, int reach) {
            this.cell = cell;
            this.chance = chance;
            this.biome = biome;
            this.jitterMin = jitterMin;
            this.jitterSpan = jitterSpan;
            this.reach = reach;
        }
    }

    /** Largest terrace: pool 4.1 + 4 steps of 3.0, times the wobble. Two never overlap: cell 60, jitter 0.4. */
    private static final double TERRACE_MAX = 17.5, CRATER_MAX = 9.0;

    /** Whether a crater could reach within {@code dist} of the point (they hold water too). */
    private boolean craterNear(double x, double z, double dist) {
        final int cell = Type.CRATER.cell;
        for (int gz = Math.floorDiv((int) (z - dist - CRATER_MAX), cell); gz <= Math.floorDiv((int) (z + dist + CRATER_MAX), cell); gz++) {
            for (int gx = Math.floorDiv((int) (x - dist - CRATER_MAX), cell); gx <= Math.floorDiv((int) (x + dist + CRATER_MAX), cell); gx++) {
                final Rand r = new Rand(gx, gz, Type.CRATER.ordinal());
                if (r.next() >= Type.CRATER.chance) {
                    continue;
                }
                final double cx = (gx + 0.15 + 0.7 * r.next()) * cell, cz = (gz + 0.15 + 0.7 * r.next()) * cell;
                if (Math.hypot(cx - x, cz - z) < dist + CRATER_MAX) {
                    return true;
                }
            }
        }
        return false;
    }

    private void create(Type type, int gx, int gz, List<Formation> out) {
        final Rand r = new Rand(gx, gz, type.ordinal());
        if (r.next() >= type.chance) {
            return;
        }
        final double cx = (gx + type.jitterMin + type.jitterSpan * r.next()) * type.cell;
        final double cz = (gz + type.jitterMin + type.jitterSpan * r.next()) * type.cell;
        final int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
        if (biomes.at(bx, bz) != type.biome) {
            return;
        }
        final TunnelLayer.Column col = new TunnelLayer.Column();
        if (!layer.column(bx, bz, col)) {
            return; // must stand in the open cavern
        }
        // formations of the shale flats stand on its plateaus
        final int raise = type.biome == Biomes.FLATS ? Math.max(0, Math.min(layer.plateau(bx, bz), col.ceiling - col.floor + 1 - 8)) : 0;
        final int base = col.floor + raise, room = col.ceiling - col.floor + 1 - raise;
        switch (type) {
            case BLADES -> {
                final int n = 1 + (int) (r.next() * 6);
                for (int i = 0; i < n; i++) {
                    final double a = r.next() * 2 * Math.PI, d = i == 0 ? 0 : 2 + r.next() * 6;
                    // many small shards, a few very large ones; tall ones are thick
                    final double size = Math.pow(r.next(), 1.6);
                    final double h = Math.min(4 + size * 24, room - 2);
                    if (h >= 3) {
                        final double lean = 0.12 * h;
                        out.add(new Blade(cx + Math.cos(a) * d, cz + Math.sin(a) * d, base, 0.8 + size * 3 + r.next() * 0.6, h,
                                (r.next() - 0.5) * lean, (r.next() - 0.5) * lean, r.seed()));
                    }
                }
            }
            case PLATE -> {
                final double rise = Math.min(3 + r.next() * 4, room - 4);
                if (rise >= 2) {
                    out.add(new Plate(cx, cz, base, r.next() * 2 * Math.PI, 10 + r.next() * 10, 5 + r.next() * 5,
                            2 + (int) (r.next() * 2), rise, r.seed()));
                }
            }
            case CAIRN -> {
                if (room > 8) {
                    out.add(new Cairn(cx, cz, base, 3 + (int) (r.next() * 4), 1.6 + r.next() * 0.8, r));
                }
            }
            case SPIRE -> {
                final double h = Math.min(10 + r.next() * 14, room - 2);
                if (h >= 8) {
                    out.add(new Spire(cx, cz, base, 1.5 + r.next() * 1.5, h, (r.next() - 0.5) * 2.0, (r.next() - 0.5) * 2.0, r.seed()));
                }
            }
            case SHALE_CRATER -> {
                final double radius = 3 + r.next() * 3;
                final int depth = 1 + (int) (r.next() * 3);
                final double kind = r.next(); // a third lead into a cavity, about a quarter show an ore seam
                out.add(new ShaleCrater(cx, cz, base, radius, depth, r.seed()));
                if (kind < 0.33 && base - depth - 8 >= CAVITY_FLOOR) {
                    out.add(new Cavity(cx, cz, base - depth, 1.2, base - depth - 5, 3.5, 2.3, false, r.seed()));
                }
            }
            case ARCH -> {
                // tall and slender: a narrow band of rock spanning 18 to 30 blocks, only its feet buried
                final double radius = 9 + r.next() * 6, rise = 1.0 + r.next() * 0.4, bury = radius * 0.15;
                final double top = radius * rise - bury;
                if (room > top + 4) {
                    out.add(new Arch(cx, cz, base, r.next() * Math.PI, radius, rise, 0.8 + r.next() * 0.4, 0.6 + r.next() * 0.8,
                            bury, r.seed()));
                }
            }
            case OUTCROP -> {
                final double h = Math.min(8 + r.next() * 10, room - 3);
                if (h >= 6) {
                    out.add(new Outcrop(cx, cz, base, 5 + r.next() * 5, h, r.next() * 6.28, r.seed()));
                }
            }
            case BASIN -> out.add(new Basin(cx, cz, base, 10 + r.next() * 10, 1 + (int) (r.next() * 3), r.seed()));
            case FALL -> {
                final double h = Math.min(4 + r.next() * 3, room - 4);
                if (h >= 3) {
                    out.add(new Fall(cx, cz, base, r.next() * 2 * Math.PI, 5 + r.next() * 3, 4 + r.next() * 3, h, r.seed()));
                }
            }
            case CAVITY -> {
                if (base - 8 >= CAVITY_FLOOR) {
                    out.add(new Cavity(cx, cz, base, 1.5 + r.next() * 1.5, base - 5, 3.5, 2.5, true, r.seed()));
                }
            }
            case VENT_CLUSTER -> {
                // a large oxide deposit (sulfur in the core of the big ones) with taller vents around it
                final double radius = 2.5 + r.next() * 2.5;
                out.add(new Deposit(cx, cz, base, radius, r.seed()));
                final int vents = 3 + (int) (r.next() * 4);
                for (int i = 0; i < vents; i++) {
                    final double a = r.next() * 2 * Math.PI, d = 2 + r.next() * (radius + 4);
                    final int vx = (int) Math.floor(cx + Math.cos(a) * d), vz = (int) Math.floor(cz + Math.sin(a) * d);
                    out.add(new Fumarole(vx, vz, base, room > 10 ? 2 + (int) (r.next() * 4) : 1, r.next() < 0.35, r.seed()));
                }
            }
            case FUMAROLE -> out.add(new Fumarole(bx, bz, base, room > 6 ? (int) (r.next() * 3) : 0, r.next() < 0.2, r.seed()));
            case VENT_FIELD -> out.add(new VentField(cx, cz, base, 6 + r.next() * 5, r.seed()));
            case CHIMNEY -> {
                final double h = 6 + r.next() * 12;
                out.add(new Chimney(cx, cz, base, 1.3 + r.next(), h, h >= room - 1, r.next() < 0.3, r.seed()));
            }
            case TERRACE -> {
                final int steps = 2 + (int) (r.next() * 3);
                if (room >= steps + 4 && !craterNear(cx, cz, TERRACE_MAX + 2)) {
                    final double[] widths = new double[steps];
                    for (int i = 0; i < steps; i++) {
                        widths[i] = 2.3 + r.next() * 0.7;
                    }
                    out.add(new Terrace(cx, cz, base, 2.6 + r.next() * 1.5, widths, r.seed()));
                }
            }
            case CRATER -> {
                final double radius = 5 + r.next() * 4;
                final int depth = 2 + (int) (r.next() * 3);
                boolean pool = r.next() < 0.6;
                // a pool only if the ground around stays higher than the water
                for (int i = 0; i < 8 && pool; i++) {
                    final double a = i * Math.PI / 4;
                    pool = layer.floorY(cx + Math.cos(a) * (radius + 1), cz + Math.sin(a) * (radius + 1)) > base - depth + 2;
                }
                out.add(new Crater(cx, cz, base, radius, depth, pool, r.next() < 0.4, r.seed()));
            }
        }
    }

    /** Deterministic random sequence of one grid cell. */
    private final class Rand {
        private final int gx, gz, salt;
        private int i;

        Rand(int gx, int gz, int salt) {
            this.gx = gx;
            this.gz = gz;
            this.salt = salt;
        }

        double next() {
            return layer.random(gx, gz, 1000 + salt * 100 + i++);
        }

        long seed() {
            return (long) (next() * Long.MAX_VALUE);
        }
    }

    /**
     * Vertical vein through a column-like formation: a thin line beside the centre that wanders a
     * little with height, so it runs through the whole formation instead of forming layers.
     */
    private static boolean vein(long seed, int x, int y, int z, double cx, double cz, double r0) {
        final double a = (seed >>> 16) % 628 / 100.0, phase = (seed >>> 24) % 628 / 100.0;
        final double vx = cx + Math.cos(a) * r0 * 0.45 + 0.5 * Math.sin(y / 6.0 + phase);
        final double vz = cz + Math.sin(a) * r0 * 0.45 + 0.5 * Math.cos(y / 7.0 + phase);
        return Math.hypot(x + 0.5 - vx, z + 0.5 - vz) < 0.7;
    }

    /** Patch value 0..1 for material choices: blobs of about {@code scale} blocks, never single specks. */
    private double blob(int x, int y, int z, double scale, int channel) {
        return layer.patch(x, y, z, scale, channel);
    }

    /** Ore of a cluster: the type changes only over several blocks, so a seam is one ore. */
    private Mat ore(long seed, int x, int y, int z) {
        final double r = layer.patch(x, y, z, 6.0, 28);
        return r < 0.4 ? Mat.ORE_IRON : r < 0.7 ? Mat.ORE_COPPER : r < 0.85 ? Mat.ORE_GOLD : Mat.ORE_REDSTONE;
    }

    // ---- formations ----------------------------------------------------------------

    /** One formation; {@link #column} fills its part of a cavern column (floor = first air block). */
    public abstract class Formation {
        final double cx, cz;
        final int base;
        final long seed;

        Formation(double cx, double cz, int base, long seed) {
            this.cx = cx;
            this.cz = cz;
            this.base = base;
            this.seed = seed;
        }

        abstract double reach();

        int minX() { return (int) Math.floor(cx - reach()); }
        int maxX() { return (int) Math.ceil(cx + reach()); }
        int minZ() { return (int) Math.floor(cz - reach()); }
        int maxZ() { return (int) Math.ceil(cz + reach()); }

        public abstract void column(int x, int z, int floor, int ceiling, Sink s);

        double duneBoost(double x, double z) { return 0.0; }

        double duneDamp(double x, double z) { return 1.0; }
    }

    /**
     * Shard: round cone of shale standing out of the ground, a broad flared foot narrowing to a sharp
     * point, leaning a little. The thick ones have one vein of fractured shale running up through
     * them; broken pieces in heaps at the foot.
     */
    final class Blade extends Formation {
        final double r0, h, leanX, leanZ;

        Blade(double cx, double cz, int base, double r0, double h, double leanX, double leanZ, long seed) {
            super(cx, cz, base, seed);
            this.r0 = r0;
            this.h = h;
            this.leanX = leanX;
            this.leanZ = leanZ;
        }

        double reach() { return r0 * 1.7 + Math.hypot(leanX, leanZ) + 3; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            boolean any = false;
            for (int y = floor; y < base + h && y < ceiling - 1; y++) {
                final double rel = Math.max(0.0, y + 0.5 - base), t = rel / h;
                final double ox = cx + leanX * t, oz = cz + leanZ * t;
                // narrows to a point, the foot flares out over the lowest blocks
                final double r = 0.3 + r0 * Math.pow(1.0 - t, 1.3) + 0.6 * r0 * Math.max(0.0, 1.0 - rel / 3.0);
                // every block stands on the one below: nothing hangs in the air in front of the shard
                if (Math.hypot(x + 0.5 - ox, z + 0.5 - oz) >= r || !s.open(x, y, z) || s.air(x, y - 1, z)) {
                    break;
                }
                s.set(x, y, z, r0 > 1.8 && vein(seed, x, y, z, ox, oz, r0) ? Mat.FRACTURED_SHALE : Mat.SHALE);
                any = true;
            }
            // broken pieces collect in heaps at the foot
            if (!any && Math.hypot(x + 0.5 - cx, z + 0.5 - cz) < 1.6 * r0 + 1.5 && s.air(x, floor, z) && !s.air(x, floor - 1, z)
                    && blob(x, floor, z, 2.5, 29) < 0.4) {
                s.set(x, floor, z, Mat.SHALE_RUBBLE);
            }
        }
    }

    /**
     * Round slab of fractured shale rising out of the ground: one side buried, the other raised,
     * shelter below it. One block only, so it stands apart from the shale around it.
     */
    final class Plate extends Formation {
        final double dx, dz, length, width, rise;
        final int thick;

        Plate(double cx, double cz, int base, double yaw, double length, double width, int thick, double rise, long seed) {
            super(cx, cz, base, seed);
            dx = Math.cos(yaw);
            dz = Math.sin(yaw);
            this.length = length;
            this.width = width;
            this.thick = thick;
            this.rise = rise;
        }

        double reach() { return Math.hypot(length / 2, width / 2) + 2; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            final double px = x + 0.5 - cx, pz = z + 0.5 - cz;
            final double a = px * dx + pz * dz, b = -px * dz + pz * dx;
            // a round outline (an ellipse), a little wobbly
            final double e = Math.hypot(a / (length / 2), b / (width / 2)) - 0.12 * layer.noise3(x / 4.0, 0.3, z / 4.0, 3);
            if (e >= 1.0) {
                return;
            }
            // the edge is rounded off too: thinner towards the rim
            final double top = rise * (a / length + 0.5), t = thick * Math.sqrt(1.0 - e * e) + 0.5;
            for (int y = floor; y < ceiling; y++) {
                final double rel = y + 0.5 - base;
                if (rel <= top && rel > top - t && s.open(x, y, z)) {
                    s.set(x, y, z, Mat.FRACTURED_SHALE);
                }
            }
        }
    }

    /** Pile of flat shale stones, each a bit smaller and shifted, the top one broken. */
    final class Cairn extends Formation {
        final double[] r, ox, oz;

        Cairn(double cx, double cz, int base, int levels, double r0, Rand rand) {
            super(cx, cz, base, rand.seed());
            r = new double[levels];
            ox = new double[levels];
            oz = new double[levels];
            double sx = 0, sz = 0;
            for (int k = 0; k < levels; k++) {
                r[k] = r0 * (1.0 - 0.16 * k);
                sx += (rand.next() - 0.5) * 0.9;
                sz += (rand.next() - 0.5) * 0.9;
                ox[k] = sx;
                oz[k] = sz;
            }
        }

        double reach() { return r[0] + 3; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            for (int k = 0; k < r.length; k++) {
                final double dx = Math.abs(x + 0.5 - cx - ox[k]), dz = Math.abs(z + 0.5 - cz - oz[k]);
                if (dx * dx * dx + dz * dz * dz >= r[k] * r[k] * r[k]) {
                    continue; // squarish flat stone
                }
                final int from = k == 0 ? floor : base + k, to = base + k;
                for (int y = from; y <= to && y < ceiling; y++) {
                    if (s.open(x, y, z)) {
                        s.set(x, y, z, k == r.length - 1 ? Mat.FRACTURED_SHALE : Mat.SHALE);
                    }
                }
            }
        }
    }

    /** Pointed tower of shale with one vein of fractured shale running up through it, a ring of rubble at its base. */
    final class Spire extends Formation {
        final double r0, h, leanX, leanZ;

        Spire(double cx, double cz, int base, double r0, double h, double leanX, double leanZ, long seed) {
            super(cx, cz, base, seed);
            this.r0 = r0;
            this.h = h;
            this.leanX = leanX;
            this.leanZ = leanZ;
        }

        double reach() { return r0 + Math.hypot(leanX, leanZ) + 3; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            boolean any = false;
            for (int y = floor; y < base + h && y < ceiling - 1; y++) {
                final double t = Math.max(0.0, y + 0.5 - base) / h;
                final double ox = cx + leanX * t, oz = cz + leanZ * t;
                final double d = Math.hypot(x + 0.5 - ox, z + 0.5 - oz);
                final double r = r0 * Math.pow(1.0 - t, 0.85) + 0.3; // tapers to a point
                if (d >= r || !s.open(x, y, z) || s.air(x, y - 1, z)) {
                    break; // standing on the block below, never hanging
                }
                s.set(x, y, z, vein(seed, x, y, z, ox, oz, r0) ? Mat.FRACTURED_SHALE : Mat.SHALE);
                any = true;
            }
            final double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
            if (!any && d < r0 + 1.8 && s.air(x, floor, z) && !s.air(x, floor - 1, z)) {
                s.set(x, floor, z, Mat.SHALE_RUBBLE); // the ring of fallen pieces
            }
        }
    }

    /**
     * Small bowl in the shale, the whole hollow (bottom, sides and the edge around it) dark thalan:
     * one block, where the shale was broken away.
     */
    final class ShaleCrater extends Formation {
        final double radius;
        final int depth;

        ShaleCrater(double cx, double cz, int base, double radius, int depth, long seed) {
            super(cx, cz, base, seed);
            this.radius = radius;
            this.depth = depth;
        }

        double reach() { return radius + 2; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            final double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
            if (d >= radius + 1.2) {
                return;
            }
            // dug out inside the radius; the edge around it only gets its sides lined
            final int dig = d >= radius ? 0 : Math.max(1, (int) Math.round(depth * (1.0 - (d / radius) * (d / radius))));
            for (int y = floor - dig; y < floor; y++) {
                if (s.solid(x, y, z)) {
                    s.set(x, y, z, Mat.AIR);
                }
            }
            // everything the hollow shows: the bottom and every block a deeper neighbour looks at
            for (int y = floor - depth - 2; y < floor - dig; y++) {
                if (s.solid(x, y, z)) {
                    s.set(x, y, z, Mat.THALAN);
                }
            }
        }
    }

    /**
     * Hole from the surface into a small cavity below: pits of the fracture fields (ore in the walls)
     * or collapse holes of the dunes (fossils and ore, the sand around the hole packed).
     */
    final class Cavity extends Formation {
        final double hole, cy, rx, ry;
        final boolean dunes;

        Cavity(double cx, double cz, int base, double hole, double cy, double rx, double ry, boolean dunes, long seed) {
            super(cx, cz, base, seed);
            this.hole = hole;
            this.cy = cy;
            this.rx = rx;
            this.ry = ry;
            this.dunes = dunes;
        }

        double reach() { return rx + 2; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            final double dx = x + 0.5 - cx, dz = z + 0.5 - cz, d = Math.hypot(dx, dz);
            if (d < hole) { // the hole, also through the sand above the floor
                for (int y = (int) Math.floor(cy); y < Math.min(floor + 22, ceiling); y++) {
                    if (y < floor ? s.solid(x, y, z) : (s.open(x, y, z) && !s.air(x, y, z))) {
                        s.set(x, y, z, Mat.AIR);
                    }
                }
            } else if (dunes && d < hole + 1.2) {
                for (int y = floor; y < Math.min(floor + 22, ceiling); y++) {
                    if (s.open(x, y, z) && !s.air(x, y, z)) {
                        s.set(x, y, z, Mat.PACKED_SILT); // the rim holds
                    }
                }
            }
            for (int y = (int) Math.floor(cy - ry - 1); y <= cy + ry + 1; y++) {
                final double dy = y + 0.5 - cy;
                final double e = (dx * dx + dz * dz) / (rx * rx) + dy * dy / (ry * ry);
                if (e < 1.0 && y < floor - 1) {
                    if (s.solid(x, y, z)) {
                        s.set(x, y, z, Mat.AIR);
                    }
                } else if (e < 1.7 && y < floor - 1 && s.solid(x, y, z)) {
                    // clusters: a fossil layer, ore seams, siltstone where the sediment hardened
                    if (dunes && blob(x, y, z, 3.0, 30) < 0.14) {
                        s.set(x, y, z, Mat.FOSSIL);
                    } else if (blob(x, y, z, 2.5, 31) < (dunes ? 0.16 : 0.2)) {
                        s.set(x, y, z, ore(seed, x, y, z));
                    } else if (dunes && blob(x, y, z, 5.0, 32) < 0.5) {
                        s.set(x, y, z, Mat.SILTSTONE);
                    }
                }
            }
        }
    }

    /**
     * Tall slender arch of siltstone: a narrow band of rock rising out of the dunes, spanning 18 to 30
     * blocks, thicker at the feet and thin over the top; sediment piled against its feet.
     */
    final class Arch extends Formation {
        final double ux, uz, radius, rise, tube, depth, bury;

        Arch(double cx, double cz, int base, double yaw, double radius, double rise, double tube, double depth, double bury, long seed) {
            super(cx, cz, base, seed);
            ux = Math.cos(yaw);
            uz = Math.sin(yaw);
            this.radius = radius;
            this.rise = rise;
            this.tube = tube;
            this.depth = depth;
            this.bury = bury;
        }

        double reach() { return radius + tube + 7; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            final double px = x + 0.5 - cx, pz = z + 0.5 - cz;
            final double along = px * ux + pz * uz, across = -px * uz + pz * ux;
            final double centreY = base - bury;
            for (int y = floor; y < ceiling - 1; y++) {
                final double t = (y + 0.5 - centreY) / rise; // the arch is taller than wide
                if (t < 0) {
                    continue;
                }
                final double q = Math.hypot(along, t), up = t / radius; // 0 at the feet, 1 at the top
                final double thick = tube * (1.0 + 0.9 * (1.0 - up)) * (0.9 + 0.2 * layer.noise3(x / 3.0, y / 3.0, z / 3.0, 4));
                final double wide = depth * (1.0 + 0.8 * (1.0 - up)) + 0.5;
                if (Math.abs(q - radius) < thick && Math.abs(across) < wide && s.open(x, y, z)) {
                    s.set(x, y, z, Mat.SILTSTONE);
                }
            }
        }

        @Override
        double duneBoost(double x, double z) {
            final double px = x - cx, pz = z - cz;
            final double along = px * ux + pz * uz, across = -px * uz + pz * ux;
            final double feet = Math.hypot(Math.abs(along) - radius, across);
            return Math.max(0.0, 3.5 * (1.0 - feet / 6.0));
        }
    }

    /**
     * Big outcrop of siltstone standing out of the dunes: steep, irregular sides, a broad rounded top,
     * all one rock. Sand drifts high against its foot.
     */
    final class Outcrop extends Formation {
        final double radius, h, phase;

        Outcrop(double cx, double cz, int base, double radius, double h, double phase, long seed) {
            super(cx, cz, base, seed);
            this.radius = radius;
            this.h = h;
            this.phase = phase;
        }

        double reach() { return radius * 1.4 + 7; }

        /** Distance from the centre relative to the lobed, uneven outline: below 1 is inside. */
        private double rel(double x, double z) {
            final double px = x - cx, pz = z - cz, a = Math.atan2(pz, px);
            final double outline = radius * (1.0 + 0.2 * Math.sin(3 * a + phase) + 0.1 * Math.sin(5 * a + 2 * phase)
                    + 0.12 * layer.noise3(x / 5.0, 0.5, z / 5.0, 12));
            return Math.hypot(px, pz) / outline;
        }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            final double d = rel(x + 0.5, z + 0.5);
            if (d >= 1.0) {
                return;
            }
            // steep sides, flattish top that sags and bulges a little: a height map, nothing overhangs
            final double top = base + h * Math.sqrt(1.0 - Math.pow(d, 4)) * (0.88 + 0.12 * layer.noise3(x / 4.0, 1.3, z / 4.0, 13));
            for (int y = floor; y < top && y < ceiling - 2; y++) {
                if (s.open(x, y, z)) {
                    s.set(x, y, z, Mat.SILTSTONE);
                }
            }
        }

        @Override
        double duneBoost(double x, double z) {
            final double d = (rel(x, z) - 1.0) * radius;
            return d < 0 ? 0.0 : Math.max(0.0, 5.0 * (1.0 - d / 7.0));
        }
    }

    /** Wide hollow where the sediment settled away: dark rock at the bottom, fossils and ore in the middle. */
    final class Basin extends Formation {
        final double radius;
        final int depth;

        Basin(double cx, double cz, int base, double radius, int depth, long seed) {
            super(cx, cz, base, seed);
            this.radius = radius;
            this.depth = depth;
        }

        double reach() { return radius * 1.15 + 1; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            final double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
            if (d >= radius || !s.air(x, floor, z)) {
                return; // outside, or sand lies here (digging below it would leave it hanging)
            }
            final int dig = (int) Math.round(depth * (1.0 - (d / radius) * (d / radius)));
            for (int y = floor - dig; y < floor; y++) {
                if (s.solid(x, y, z)) {
                    s.set(x, y, z, Mat.AIR);
                }
            }
            final int bottom = floor - dig - 1;
            if (!s.solid(x, bottom, z)) {
                return;
            }
            if (d < 3.0 && blob(x, bottom, z, 2.0, 33) < 0.35) {
                s.set(x, bottom, z, Mat.FOSSIL);
            } else if (d < 3.5 && blob(x, bottom, z, 2.0, 34) < 0.3) {
                s.set(x, bottom, z, ore(seed, x, bottom, z));
            } else {
                s.set(x, bottom, z, d < radius * 0.6 ? Mat.THALAN : Mat.SILTSTONE);
            }
        }

        @Override
        double duneDamp(double x, double z) {
            final double d = Math.hypot(x - cx, z - cz) / radius;
            return d < 0.75 ? 0.0 : Math.min(1.0, (d - 0.75) / 0.4);
        }
    }

    /** Rock ledge with sediment on top that has poured over its edge into a broad fan. */
    final class Fall extends Formation {
        final double ux, uz, length, halfWidth, h;

        Fall(double cx, double cz, int base, double yaw, double length, double halfWidth, double h, long seed) {
            super(cx, cz, base, seed);
            ux = Math.cos(yaw);
            uz = Math.sin(yaw);
            this.length = length;
            this.halfWidth = halfWidth;
            this.h = h;
        }

        double reach() { return Math.max(length, 1.8 * h) + halfWidth + 3; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            final double px = x + 0.5 - cx, pz = z + 0.5 - cz;
            final double front = px * ux + pz * uz, side = -px * uz + pz * ux;
            final double edge = halfWidth + 0.8 * layer.noise3(front / 3.0, 0.7, seed % 89, 5);
            if (front < 0 && front >= -length && Math.abs(side) < edge) { // the ledge
                final int top = (int) Math.floor(base + h);
                for (int y = floor; y <= top && y < ceiling; y++) {
                    if (s.open(x, y, z)) {
                        s.set(x, y, z, y == top ? Mat.FINE_SILT : Mat.SILTSTONE);
                    }
                }
                return;
            }
            final double reachFan = 1.8 * h;
            if (front >= 0 && front < reachFan) { // the fan of sediment below it
                final double w = side / (halfWidth + 2.0 + front * 0.4);
                final double fan = h * (1.0 - front / reachFan) * (1.0 - w * w);
                for (int y = floor; y < base + fan && y < ceiling; y++) {
                    if (s.air(x, y, z)) {
                        s.set(x, y, z, y >= base + fan - 2 ? Mat.FINE_SILT : Mat.PACKED_SILT);
                    }
                }
            }
        }
    }

    /**
     * Vent built from the ground up: a cone of vent blocks, very broad at the foot and narrowing to a
     * single vent on top (the only one that breathes), on a skirt of thermal rock. Height 0 = one vent
     * flush with the ground.
     */
    final class Fumarole extends Formation {
        final int vx, vz, height;
        final boolean active;
        final double foot;

        Fumarole(int vx, int vz, int base, int height, boolean active, long seed) {
            super(vx + 0.5, vz + 0.5, base, seed);
            this.vx = vx;
            this.vz = vz;
            this.height = height;
            this.active = active;
            foot = height <= 1 ? 0.5 + height : 1.0 + 0.4 * height; // radius at the ground
        }

        double reach() { return foot + 2; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            final int dx = x - vx, dz = z - vz;
            final double d = Math.hypot(dx, dz);
            if (dx == 0 && dz == 0) {
                for (int y = floor - 1; y < floor + height && y < ceiling - 1; y++) {
                    if (y == floor - 1 ? s.solid(x, y, z) : s.air(x, y, z)) {
                        s.set(x, y, z, y == floor + height - 1 && active ? Mat.ACTIVE_VENT : Mat.VENT);
                    }
                }
                return;
            }
            // the cone around the core: below the top block, thinner with every block up, a little uneven
            for (int k = 0; k < height - 1 && floor + k < ceiling - 1; k++) {
                final double r = 0.5 + (foot - 0.5) * Math.pow(1.0 - k / (double) height, 1.6) + 0.35 * (blob(x, floor + k, z, 2.0, 40) - 0.5);
                if (d >= r || !s.air(x, floor + k, z) || s.air(x, floor + k - 1, z)) {
                    break;
                }
                s.set(x, floor + k, z, Mat.VENT);
            }
            // the skirt of thermal rock on the ground around it
            if (d < foot + 1.5 && s.solid(x, floor - 1, z)) {
                s.set(x, floor - 1, z, Mat.THERMAL_ROCK);
            }
        }
    }

    /**
     * Field of small vents in the ground: thermal rock with a vent in it every few blocks (thinning out
     * towards the edge), a few of them breathing, some raised by a block.
     */
    final class VentField extends Formation {
        final double radius;

        VentField(double cx, double cz, int base, double radius, long seed) {
            super(cx, cz, base, seed);
            this.radius = radius;
        }

        double reach() { return radius * 1.25 + 1; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            final double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz) / (radius * (1.0 + 0.25 * layer.noise3(x / 5.0, 0.7, z / 5.0, 14)));
            if (d >= 1.0 || !s.solid(x, floor - 1, z)) {
                return;
            }
            // one spot per 3 x 3 cell, so the vents never touch
            final int gx = Math.floorDiv(x, 3), gz = Math.floorDiv(z, 3);
            final boolean vent = layer.random(gx, gz, 4100) < 0.85 - 0.6 * d
                    && x == gx * 3 + (int) (layer.random(gx, gz, 4101) * 3) && z == gz * 3 + (int) (layer.random(gx, gz, 4102) * 3);
            if (!vent) {
                s.set(x, floor - 1, z, Mat.THERMAL_ROCK);
                return;
            }
            final boolean active = layer.random(gx, gz, 4103) < 0.25;
            if (layer.random(gx, gz, 4104) < 0.3 && s.air(x, floor, z) && ceiling - floor > 3) {
                s.set(x, floor - 1, z, Mat.VENT);
                s.set(x, floor, z, active ? Mat.ACTIVE_VENT : Mat.VENT);
            } else {
                s.set(x, floor - 1, z, active ? Mat.ACTIVE_VENT : Mat.VENT);
            }
        }
    }

    /** Mineral deposit on the ground: an oxide blob, sulfur in the core of the big ones. */
    final class Deposit extends Formation {
        final double radius;

        Deposit(double cx, double cz, int base, double radius, long seed) {
            super(cx, cz, base, seed);
            this.radius = radius;
        }

        double reach() { return radius * 1.3 + 1; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            final double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz) / (1.0 + 0.25 * layer.noise3(x / 4.0, 0.5, z / 4.0, 9));
            if (d >= radius || !s.solid(x, floor - 1, z)) {
                return;
            }
            s.set(x, floor - 1, z, radius > 4.2 && d < 1.5 ? Mat.SULFUR : Mat.OXIDE);
        }
    }

    /**
     * Tall irregular column grown around a vent: thermal rock with one vein of oxide running up
     * through it, a column of vents in its core that breathes at the top (unless it reaches the roof).
     */
    final class Chimney extends Formation {
        final double r0, h;
        final boolean toCeiling, active;

        Chimney(double cx, double cz, int base, double r0, double h, boolean toCeiling, boolean active, long seed) {
            super(cx, cz, base, seed);
            this.r0 = r0;
            this.h = h;
            this.toCeiling = toCeiling;
            this.active = active;
        }

        double reach() { return r0 * 1.7 + 2; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            final double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
            // up to the real, uneven roof: the open check stops it at the rock
            final int top = toCeiling ? ceiling + 4 : (int) Math.floor(base + h);
            for (int y = floor; y <= top; y++) {
                final double t = Math.max(0.0, y + 0.5 - base) / h;
                double r = r0 * (1.0 - 0.35 * Math.min(1.0, t)) * (1.0 + 0.3 * layer.noise3(x / 3.0, y / 3.0, z / 3.0, 6));
                if (t < 0.1) {
                    r *= 1.3; // flared foot
                }
                if (d >= r || !s.open(x, y, z)) {
                    continue;
                }
                if (d < 0.75) { // the vent running up the core
                    s.set(x, y, z, !toCeiling && y == top && active ? Mat.ACTIVE_VENT : Mat.VENT);
                    continue;
                }
                s.set(x, y, z, vein(seed, x, y, z, cx, cz, r0) ? Mat.OXIDE : Mat.THERMAL_ROCK);
            }
        }
    }

    /**
     * Sinter terraces stepping down around a warm pool. Every step is a basin held by a rim; each
     * basin's water stands one block lower than the one inside it, so nothing can run out.
     */
    final class Terrace extends Formation {
        final double[] edges; // outer radius of the pool (index 0) and of every step
        final double wobble;

        Terrace(double cx, double cz, int base, double pool, double[] widths, long seed) {
            super(cx, cz, base, seed);
            edges = new double[widths.length + 1];
            edges[0] = pool;
            for (int i = 0; i < widths.length; i++) {
                edges[i + 1] = edges[i] + widths[i];
            }
            wobble = (seed % 628) / 100.0;
        }

        double reach() { return edges[edges.length - 1] * 1.1 + 4; }

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            final double px = x + 0.5 - cx, pz = z + 0.5 - cz;
            final double d = Math.hypot(px, pz) / (1.0 + 0.08 * Math.sin(3 * Math.atan2(pz, px) + wobble));
            final int steps = edges.length;
            final double outer = edges[steps - 1];
            if (d >= outer) {
                // thermophile mats on the ground just outside
                if (d < outer + 2.5 && s.air(x, floor, z) && blob(x, floor, z, 3.0, 36) < 0.5) {
                    s.set(x, floor, z, Mat.MAT);
                }
                return;
            }
            int k = 0;
            while (d >= edges[k]) {
                k++;
            }
            final int water = base + (steps - k);         // water level of this basin, the pool highest
            final boolean rim = d >= edges[k] - 1.6;
            final int solidTop = rim ? water : water - 1;
            for (int y = floor; y <= solidTop && y < ceiling; y++) {
                if (s.open(x, y, z)) {
                    s.set(x, y, z, Mat.SINTER);
                }
            }
            if (rim) {
                if (s.air(x, water + 1, z) && blob(x, water, z, 3.0, 37) < 0.3) {
                    s.set(x, water + 1, z, Mat.MAT);
                }
            } else if (s.air(x, water, z)) {
                s.set(x, water, z, Mat.WATER);
            }
        }
    }

    /** Bowl where an old vent collapsed: rings of older layers in its walls, a pool or a vent at the bottom. */
    final class Crater extends Formation {
        final double radius;
        final int depth;
        final boolean pool, active;

        Crater(double cx, double cz, int base, double radius, int depth, boolean pool, boolean active, long seed) {
            super(cx, cz, base, seed);
            this.radius = radius;
            this.depth = depth;
            this.pool = pool;
            this.active = active;
        }

        double reach() { return radius + 3; }

        private final Mat[] layers = {Mat.THALAN, Mat.THALAN, Mat.THERMAL_ROCK};

        @Override
        public void column(int x, int z, int floor, int ceiling, Sink s) {
            final double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
            if (d >= radius) {
                return;
            }
            final int dig = (int) Math.round(depth * (1.0 - (d / radius) * (d / radius)));
            for (int y = floor - dig; y < floor; y++) {
                if (s.solid(x, y, z)) {
                    s.set(x, y, z, Mat.AIR);
                }
            }
            // the walls show older layers, thin bands of the old deposits
            for (int y = floor - depth - 1; y < floor - dig; y++) {
                if (s.solid(x, y, z)) {
                    s.set(x, y, z, layers[Math.floorMod(y, layers.length)]);
                }
            }
            final int bottom = floor - dig;
            if (pool) {
                final int level = base - depth + 1;
                for (int y = bottom; y <= level; y++) {
                    if (s.air(x, y, z)) {
                        s.set(x, y, z, Mat.WATER);
                    }
                }
                if (bottom == level + 1 && s.air(x, bottom, z) && blob(x, bottom, z, 3.0, 38) < 0.5) {
                    s.set(x, bottom, z, Mat.MAT); // mats on the shore
                }
            } else if (d < 0.75 && s.solid(x, bottom - 1, z)) {
                s.set(x, bottom - 1, z, active ? Mat.ACTIVE_VENT : Mat.VENT);
            } else if (d < radius * 0.45 && s.solid(x, bottom - 1, z)) {
                s.set(x, bottom - 1, z, Mat.SMOULDERING); // the floor of a dry crater still glows around its vent
            }
        }
    }
}
