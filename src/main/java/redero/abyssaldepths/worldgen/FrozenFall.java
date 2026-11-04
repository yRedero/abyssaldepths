package redero.abyssaldepths.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * The frozen fall: a rare set piece of the thalan depths, the cold end of the layer. A fall of water froze where it
 * poured from a crack in a tall hall's ceiling: a column of dark ice from the ceiling to the floor, leaning a little
 * as if still falling, fluted where it ran, narrowing as it fell and spreading where it struck; gloam caught in its
 * core glows through the ice. Round its foot the floor is a frozen pool with splashes frozen as they rose; round its
 * source, icicles hang. At most one in a large grid cell, only in the depths and under a tall ceiling. Everything is
 * decided from the cell, so each chunk freezes its own part.
 */
final class FrozenFall {
    static final int CELL = 400, REACH = 14;
    private static final double CHANCE = 0.5;

    /** A fall: where it pours from (x, z), the hall's floor and ceiling there, how it leans (per block down), how wide. */
    record Spot(int x, int z, int floor, int ceiling, double leanX, double leanZ, double width) {
    }

    private final WorldGenLevel level;
    private final TunnelLayer layer;
    private final Biomes biomes;
    private final int x0, z0;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    FrozenFall(WorldGenLevel level, TunnelLayer layer, Biomes biomes, int x0, int z0) {
        this.level = level;
        this.layer = layer;
        this.biomes = biomes;
        this.x0 = x0;
        this.z0 = z0;
    }

    static Spot spot(TunnelLayer layer, Biomes biomes, int gx, int gz) {
        if (layer.random(gx, gz, 8201) >= CHANCE) {
            return null;
        }
        final int x = gx * CELL + 40 + (int) (layer.random(gx, gz, 8202) * (CELL - 80));
        final int z = gz * CELL + 40 + (int) (layer.random(gx, gz, 8203) * (CELL - 80));
        final TunnelLayer.Column c = new TunnelLayer.Column();
        if (biomes.at(x, z) != Biomes.DEPTHS || !layer.column(x, z, c) || c.ceiling - c.floor < 16) {
            return null; // not in the depths, or no tall hall there
        }
        final double a = layer.random(gx, gz, 8204) * Math.PI * 2, lean = (2.0 + layer.random(gx, gz, 8205) * 3.0) / (c.ceiling - c.floor);
        return new Spot(x, z, c.floor, c.ceiling, Math.cos(a) * lean, Math.sin(a) * lean, 1.8 + layer.random(gx, gz, 8206) * 0.8);
    }

    void apply() {
        for (int gx = Math.floorDiv(x0 - REACH, CELL); gx <= Math.floorDiv(x0 + 15 + REACH, CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - REACH, CELL); gz <= Math.floorDiv(z0 + 15 + REACH, CELL); gz++) {
                final Spot s = spot(layer, biomes, gx, gz);
                if (s == null || s.x() + REACH < x0 || s.x() - REACH > x0 + 15 || s.z() + REACH < z0 || s.z() - REACH > z0 + 15) {
                    continue;
                }
                fall(s);
                pool(s);
                icicles(s);
            }
        }
    }

    private BlockState ice() {
        return AbyssalDepths.DARK_ICE.get().defaultBlockState();
    }

    /**
     * The column: at each height a fluted ring round where the water was then (leaning off as it fell), wide at the
     * source, narrowing, spreading again where it struck; filling only air, so it runs from the real ceiling to the
     * real floor. Its core holds gloam.
     */
    private void fall(Spot s) {
        final int top = s.ceiling() + 6, bottom = s.floor() - 2;
        final double span = Math.max(1, s.ceiling() - s.floor());
        for (int y = bottom; y <= top; y++) {
            final double t = Math.max(0.0, Math.min(1.0, (s.ceiling() - y) / span)); // 0 at the source, 1 at the floor
            final double cx = s.x() + 0.5 + s.leanX() * (s.ceiling() - y), cz = s.z() + 0.5 + s.leanZ() * (s.ceiling() - y);
            final double radius = s.width() * (1.25 - 0.5 * Math.sin(t * Math.PI * 0.85)) + (t > 0.85 ? (t - 0.85) * 14.0 : 0.0);
            final int r = (int) Math.ceil(radius + 1);
            for (int x = Math.max(x0, (int) Math.floor(cx) - r); x <= Math.min(x0 + 15, (int) Math.floor(cx) + r); x++) {
                for (int z = Math.max(z0, (int) Math.floor(cz) - r); z <= Math.min(z0 + 15, (int) Math.floor(cz) + r); z++) {
                    final double dx = x + 0.5 - cx, dz = z + 0.5 - cz;
                    final double flute = 0.35 * Math.sin(Math.atan2(dz, dx) * 5 + y * 0.35); // where the water ran
                    final double d = Math.sqrt(dx * dx + dz * dz);
                    if (d > radius + flute) {
                        continue;
                    }
                    final BlockState here = level.getBlockState(pos.set(x, y, z));
                    if (!here.isAir() && here.getFluidState().isEmpty()) {
                        continue; // only the air of the hall freezes
                    }
                    final boolean core = d < Math.max(0.9, radius - 1.3) && layer.random(x, z, 8210 + y) < 0.55;
                    level.setBlock(pos, core ? AbyssalDepths.GLOAM_CRYSTAL.get().defaultBlockState() : ice(), 2);
                }
            }
        }
    }

    /** The frozen pool round its foot (the floor's top turned to ice, its edge ragged) and splashes frozen as they rose. */
    private void pool(Spot s) {
        final double cx = s.x() + 0.5 + s.leanX() * (s.ceiling() - s.floor()), cz = s.z() + 0.5 + s.leanZ() * (s.ceiling() - s.floor());
        final double radius = 7.0 + s.width() * 1.5;
        final int r = (int) Math.ceil(radius) + 1;
        for (int x = Math.max(x0, (int) cx - r); x <= Math.min(x0 + 15, (int) cx + r); x++) {
            for (int z = Math.max(z0, (int) cz - r); z <= Math.min(z0 + 15, (int) cz + r); z++) {
                final double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz) / (radius * (1.0 + 0.2 * layer.noise3(x / 4.0, 0.7, z / 4.0, 16)));
                if (d > 1.0) {
                    continue;
                }
                int y = s.floor() + 4; // down to the floor's top
                while (y > s.floor() - 6 && level.getBlockState(pos.set(x, y, z)).isAir()) {
                    y--;
                }
                final BlockState ground = level.getBlockState(pos.set(x, y, z));
                if (ground.isAir() || !ground.getFluidState().isEmpty() || ground.is(AbyssalDepths.DARK_ICE.get())) {
                    continue;
                }
                level.setBlock(pos, ice(), 2);
                // splashes: a ring of short spikes of ice out from where it struck
                if (d > 0.35 && d < 0.75 && layer.random(x, z, 8220) < 0.12) {
                    final int h = 1 + (int) (layer.random(x, z, 8221) * 3);
                    for (int k = 1; k <= h && level.getBlockState(pos.set(x, y + k, z)).isAir(); k++) {
                        level.setBlock(pos, ice(), 2);
                    }
                }
            }
        }
    }

    /** Icicles of dark ice round the source, hanging from the ceiling. */
    private void icicles(Spot s) {
        final int r = 7;
        for (int x = Math.max(x0, s.x() - r); x <= Math.min(x0 + 15, s.x() + r); x++) {
            for (int z = Math.max(z0, s.z() - r); z <= Math.min(z0 + 15, s.z() + r); z++) {
                final double d = Math.hypot(x - s.x(), z - s.z());
                if (d > r || layer.random(x, z, 8230) > 0.22 * (1.0 - d / r)) {
                    continue;
                }
                int y = s.ceiling() - 6; // up to the ceiling's underside
                while (y < s.ceiling() + 8 && level.getBlockState(pos.set(x, y, z)).isAir()) {
                    y++;
                }
                if (level.getBlockState(pos.set(x, y, z)).isAir()) {
                    continue;
                }
                final int length = 1 + (int) (layer.random(x, z, 8231) * 4 * (1.0 - d / r) + 1);
                for (int k = 1; k <= length && level.getBlockState(pos.set(x, y - k, z)).isAir(); k++) {
                    level.setBlock(pos, ice(), 2);
                }
            }
        }
    }

    /** The nearest frozen fall to a place (its source's x, z and the floor there), or null. */
    static BlockPos nearest(TunnelLayer layer, Biomes biomes, int x, int z, int cells) {
        BlockPos best = null;
        double near = Double.MAX_VALUE;
        final int cx = Math.floorDiv(x, CELL), cz = Math.floorDiv(z, CELL);
        for (int gx = cx - cells; gx <= cx + cells; gx++) {
            for (int gz = cz - cells; gz <= cz + cells; gz++) {
                final Spot s = spot(layer, biomes, gx, gz);
                if (s != null && Math.hypot(s.x() - x, s.z() - z) < near) {
                    near = Math.hypot(s.x() - x, s.z() - z);
                    best = new BlockPos(s.x(), s.floor(), s.z());
                }
            }
        }
        return best;
    }
}
