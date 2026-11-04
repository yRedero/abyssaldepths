package redero.abyssaldepths.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * The buried skeleton: a rare set piece of the silt dunes. Something enormous died here long ago and lies on its back,
 * sunk in the silt: its spine runs under the dunes, rising out of them only along its middle; its ribs arch up out of
 * the silt in pairs like the frame of a hall, curving in towards each other, some broken short; its skull lies half
 * buried at one end, its jaw fallen away. The bones carry on under the silt, so digging finds them. At most one in a
 * large grid cell, only in the dunes and under a tall ceiling. Everything is decided from the cell, so each chunk lays
 * its own part.
 */
final class BuriedSkeleton {
    static final int CELL = 520, REACH = 48;
    private static final double CHANCE = 0.5;

    /**
     * A skeleton: the middle of its spine (x, z), the floor there, the way it lies (unit vector from tail to head), its
     * length and how high its ribs rise.
     */
    record Spot(int x, int z, int floor, double dirX, double dirZ, double length, double rise) {
    }

    private final WorldGenLevel level;
    private final TunnelLayer layer;
    private final Biomes biomes;
    private final int x0, z0;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    BuriedSkeleton(WorldGenLevel level, TunnelLayer layer, Biomes biomes, int x0, int z0) {
        this.level = level;
        this.layer = layer;
        this.biomes = biomes;
        this.x0 = x0;
        this.z0 = z0;
    }

    static Spot spot(TunnelLayer layer, Biomes biomes, int gx, int gz) {
        if (layer.random(gx, gz, 8301) >= CHANCE) {
            return null;
        }
        final int x = gx * CELL + 70 + (int) (layer.random(gx, gz, 8302) * (CELL - 140));
        final int z = gz * CELL + 70 + (int) (layer.random(gx, gz, 8303) * (CELL - 140));
        final TunnelLayer.Column c = new TunnelLayer.Column();
        if (biomes.at(x, z) != Biomes.DUNES || !layer.column(x, z, c) || c.ceiling - c.floor < 18) {
            return null; // not in the dunes, or no tall hall there
        }
        final double a = layer.random(gx, gz, 8304) * Math.PI * 2;
        final double rise = Math.min(12.0, c.ceiling - c.floor - 6.0) * (0.75 + layer.random(gx, gz, 8306) * 0.25);
        return new Spot(x, z, c.floor, Math.cos(a), Math.sin(a), 44.0 + layer.random(gx, gz, 8305) * 18.0, rise);
    }

    void apply() {
        for (int gx = Math.floorDiv(x0 - REACH, CELL); gx <= Math.floorDiv(x0 + 15 + REACH, CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - REACH, CELL); gz <= Math.floorDiv(z0 + 15 + REACH, CELL); gz++) {
                final Spot s = spot(layer, biomes, gx, gz);
                if (s == null || s.x() + REACH < x0 || s.x() - REACH > x0 + 15 || s.z() + REACH < z0 || s.z() - REACH > z0 + 15) {
                    continue;
                }
                scour(s);
                spine(s);
                ribs(s, gx, gz);
                skull(s);
            }
        }
    }

    /** Where a point along the body (u from the middle towards the head, v to its left, y up) lies in the world. */
    private double wx(Spot s, double u, double v) {
        return s.x() + 0.5 + u * s.dirX() - v * s.dirZ();
    }

    private double wz(Spot s, double u, double v) {
        return s.z() + 0.5 + u * s.dirZ() + v * s.dirX();
    }

    /** The spine's height at u: deep under the silt at both ends, just above the ground along the middle. */
    private double spineY(Spot s, double u) {
        final double t = u / (s.length() / 2); // -1 at the tail, 1 at the head
        return s.floor() - 4.0 + 5.0 * Math.cos(t * Math.PI / 2);
    }

    /** A bone: only into air and silt (it lies in the silt, not in the rock), turned along the way it runs. */
    private void bone(double x, double y, double z, Direction.Axis axis) {
        final int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        if (bx < x0 || bx > x0 + 15 || bz < z0 || bz > z0 + 15) {
            return;
        }
        final BlockState here = level.getBlockState(pos.set(bx, by, bz));
        if (here.isAir() || here.is(AbyssalDepths.FINE_SILT.get()) || here.is(AbyssalDepths.PACKED_SILT.get()) || here.is(AbyssalDepths.QUICKSILT.get())
                || here.is(Blocks.BONE_BLOCK)) {
            level.setBlock(pos, Blocks.BONE_BLOCK.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis), 2);
        }
    }

    /** The axis closest to a direction in the world (dx, dy, dz). */
    private static Direction.Axis axis(double dx, double dy, double dz) {
        final double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        return ay >= ax && ay >= az ? Direction.Axis.Y : ax >= az ? Direction.Axis.X : Direction.Axis.Z;
    }

    /**
     * The hollow the wind scoured round the bones: the dunes lie low along the body (the silt no higher than a block
     * over the floor), rising back to their own height over a few blocks round it, so the bones stand clear.
     */
    private void scour(Spot s) {
        final double half = s.length() / 2, tail = -half - 6, head = half + 14, clear = 4.5 + s.rise() * 0.45 + 4.0;
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                final double dx = x + 0.5 - s.x() - 0.5, dz = z + 0.5 - s.z() - 0.5;
                final double u = dx * s.dirX() + dz * s.dirZ(), v = -dx * s.dirZ() + dz * s.dirX();
                final double along = u < tail ? tail - u : u > head ? u - head : 0.0;
                final double d = Math.hypot(along, v) / clear; // 0 on the body, 1 at the hollow's edge
                if (d >= 1.6) {
                    continue;
                }
                final double cap = s.floor() + 1 + (d < 1.0 ? 0.0 : (d - 1.0) / 0.6 * 6.0) + 1.5 * layer.noise3(x / 5.0, 0.4, z / 5.0, 17);
                for (int y = s.floor() + 20; y > cap; y--) {
                    if (level.getBlockState(pos.set(x, y, z)).is(AbyssalDepths.FINE_SILT.get())) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /** The spine: a thick row of vertebrae from tail to head, a short spur on each, tapering into the tail. */
    private void spine(Spot s) {
        final Direction.Axis along = axis(s.dirX(), 0, s.dirZ());
        final double half = s.length() / 2;
        for (double u = -half - 6; u <= half; u += 0.5) {
            final double y = spineY(s, u);
            final double thick = u < -half ? 0.6 : 1.4; // the tail thins out
            for (double v = -thick; v <= thick; v += 0.5) {
                for (double dy = -thick; dy <= thick; dy += 0.5) {
                    if (v * v + dy * dy <= thick * thick) {
                        bone(wx(s, u, v), y + dy, wz(s, u, v), along);
                    }
                }
            }
            // on its back: the spurs of the vertebrae point down into the silt, every third block
            if (Math.floorMod((int) Math.floor(u), 3) == 0 && u > -half) {
                for (int k = 2; k <= 3; k++) {
                    bone(wx(s, u, 0), y - k, wz(s, u, 0), Direction.Axis.Y);
                }
            }
        }
    }

    /**
     * The ribs: pairs along the chest, rising out of the silt from both sides of the spine, out and up, then curving in
     * towards each other without meeting; tallest in the middle of the chest, swept a little back; a few broken short.
     */
    private void ribs(Spot s, int gx, int gz) {
        final double half = s.length() / 2;
        final double from = -half * 0.2, to = half * 0.6;
        int k = 0;
        for (double u0 = from; u0 <= to; u0 += 3.5, k++) {
            final double t = (u0 - from) / (to - from); // 0 at the back of the chest, 1 at the front
            final double size = 0.55 + 0.45 * Math.sin(Math.PI * (0.15 + 0.8 * t));
            final double width = (4.5 + s.rise() * 0.45) * size, height = s.rise() * size;
            final double base = spineY(s, u0);
            for (int side = -1; side <= 1; side += 2) {
                final double roll = layer.random(gx * 31 + k, gz * 17 + side, 8310);
                if (roll < 0.08) {
                    continue; // lost
                }
                final double end = roll < 0.3 ? 0.45 + roll : 1.0; // broken short, or whole
                // a half ellipse from the spine: out to its width and up to its height, then in again over the top
                final double tipAngle = Math.PI * 0.42;
                for (double a = -Math.PI / 2; a <= -Math.PI / 2 + (Math.PI / 2 + tipAngle) * end; a += 0.03) {
                    final double v = side * (1.2 + width * Math.cos(a));
                    final double y = base + height + height * Math.sin(a);
                    final double u = u0 - 0.18 * (y - base); // swept back towards the tail
                    // the way it runs here, for the bone's grain
                    final double dv = -side * width * Math.sin(a), dy = height * Math.cos(a);
                    final Direction.Axis grain = axis(-dv * s.dirZ(), dy, dv * s.dirX());
                    bone(wx(s, u, v), y, wz(s, u, v), grain);
                    if (y - base < height * 0.8) { // thicker low down, where they leave the spine
                        bone(wx(s, u - 0.7, v), y, wz(s, u - 0.7, v), grain);
                    }
                }
            }
        }
    }

    /**
     * The skull, half buried at the head end: a long hollow head lying on its side (its shell, two sockets, nostrils
     * at the snout) with the lower jaw fallen away beside it.
     */
    private void skull(Spot s) {
        final double half = s.length() / 2;
        final double cu = half + 6.5, cy = s.floor() + 2.5;
        final double lu = 7.5, lv = 4.5, ly = 4.0; // half length, width, height
        final Direction.Axis along = axis(s.dirX(), 0, s.dirZ());
        for (double u = -lu; u <= lu; u += 0.5) {
            for (double v = -lv; v <= lv; v += 0.5) {
                for (double dy = -ly; dy <= ly; dy += 0.5) {
                    final double taper = 1.0 - 0.45 * Math.max(0, u / lu); // narrower towards the snout
                    final double e = (u / lu) * (u / lu) + (v / (lv * taper)) * (v / (lv * taper)) + (dy / (ly * taper)) * (dy / (ly * taper));
                    if (e > 1.0) {
                        continue;
                    }
                    if (e < 0.62) { // inside it is hollow: the silt that blew in is gone, so the sockets show dark
                        final int ix = (int) Math.floor(wx(s, cu + u, v)), iy = (int) Math.floor(cy + dy), iz = (int) Math.floor(wz(s, cu + u, v));
                        if (dy > -ly * 0.35 && ix >= x0 && ix <= x0 + 15 && iz >= z0 && iz <= z0 + 15
                                && level.getBlockState(pos.set(ix, iy, iz)).is(AbyssalDepths.FINE_SILT.get())) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                        continue;
                    }
                    if (dy < -ly * 0.35) {
                        continue; // its underside is open: the jaw is gone
                    }
                    final boolean socket = Math.abs(u + lu * 0.2) < 1.8 && Math.abs(v) > lv * 0.35 && dy > 0.3 && dy < 2.6; // looking out to both sides
                    final boolean nostril = u > lu * 0.7 && Math.abs(v) < 1.0 && dy > 0;
                    if (socket || nostril) {
                        continue;
                    }
                    bone(wx(s, cu + u, v), cy + dy, wz(s, cu + u, v), along);
                }
            }
        }
        // the lower jaw: a long open V lying beside the skull, sunk in the silt
        final double jawV = lv + 3.5;
        for (double u = -lu; u <= lu * 0.9; u += 0.5) {
            final double spread = 2.8 * (1.0 - (u + lu) / (lu * 1.9));
            for (int side = -1; side <= 1; side += 2) {
                bone(wx(s, cu + u, jawV + side * spread), s.floor() - 0.5, wz(s, cu + u, jawV + side * spread), along);
                bone(wx(s, cu + u, jawV + side * spread), s.floor() + 0.5, wz(s, cu + u, jawV + side * spread), along);
            }
        }
    }

    /** The nearest skeleton to a place (the middle of its spine and the floor there), or null. */
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
