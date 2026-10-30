package redero.abyssaldepths.artifact;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;

/**
 * The island form of the inner self (a first draft, to be reshaped): floating in the white, a central
 * island of grass over stone with a meditation mat at its heart and a blossoming tree to one side, smaller
 * islands around it at other heights (some with a small tree, some with a mossy boulder), and stepping
 * stones from the central island to a few of them. Built the same for the same place every time.
 */
final class InnerIslands {
    /** How far from the middle the islands reach (the space cleared before building). */
    static final int REACH = 30;
    private static final int CENTRAL_RADIUS = 7;

    private InnerIslands() {
    }

    /**
     * Builds the islands around centre (the top of the central island is one below it: centre is where one
     * stands): the central island and {@code small} more around it (one per mastery milestone), every one
     * reachable over stepping stones.
     */
    static void build(ServerLevel level, BlockPos centre, int seed, int small) {
        final RandomSource random = RandomSource.create(seed * 341873128712L + 132897987541L);
        final BlockPos top = centre.below();
        island(level, top, CENTRAL_RADIUS, random);
        level.setBlock(centre, AbyssalDepths.MEDITATION_MAT.get().defaultBlockState(), 2);
        tree(level, top.offset(-4, 1, -3), 5, random);
        petals(level, top, CENTRAL_RADIUS - 1, random);
        pond(level, top.offset(3, 0, 2));
        for (int i = 0; i < small; i++) {
            final double angle = 2 * Math.PI * i / Math.max(4, small) + (random.nextDouble() - 0.5) * 0.4;
            final double distance = 15 + random.nextDouble() * 8;
            final int radius = 2 + random.nextInt(3);
            final BlockPos at = top.offset((int) Math.round(Math.cos(angle) * distance), random.nextInt(7) - 3, (int) Math.round(Math.sin(angle) * distance));
            island(level, at, radius, random);
            if (random.nextBoolean()) {
                tree(level, at.above(), 3, random);
            } else {
                level.setBlock(at.above(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
            }
            stones(level, top, at, angle, radius);
            if (i == 1) {
                waterfall(level, at, radius, angle);
            }
        }
    }

    /** An island: a ragged disc of grass, dirt under it, and stone hanging below, deepest in the middle. */
    private static void island(ServerLevel level, BlockPos top, int radius, RandomSource random) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final double[] edge = new double[16]; // a ragged outline: the radius changes around it
        for (int i = 0; i < edge.length; i++) {
            edge[i] = radius * (0.8 + 0.35 * random.nextDouble());
        }
        for (int dz = -radius - 2; dz <= radius + 2; dz++) {
            for (int dx = -radius - 2; dx <= radius + 2; dx++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                final double angle = (Math.atan2(dz, dx) + Math.PI) / (2 * Math.PI) * edge.length;
                final int a = (int) angle % edge.length, b = (a + 1) % edge.length;
                final double reach = Mth.lerp(angle - Math.floor(angle), edge[a], edge[b]);
                if (d > reach) {
                    continue;
                }
                final int depth = 2 + (int) Math.round((reach - d) * 1.4 + random.nextInt(2));
                for (int dy = 0; dy < depth; dy++) {
                    final BlockState state = dy == 0 ? Blocks.GRASS_BLOCK.defaultBlockState()
                            : dy < 3 ? Blocks.DIRT.defaultBlockState()
                            : random.nextInt(5) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.STONE.defaultBlockState();
                    level.setBlock(pos.set(top.getX() + dx, top.getY() - dy, top.getZ() + dz), state, 2);
                }
            }
        }
    }

    /** A small pool sunk into the grass, with a lily pad. */
    private static void pond(ServerLevel level, BlockPos at) {
        for (int dz = 0; dz < 2; dz++) {
            for (int dx = 0; dx < 3; dx++) {
                level.setBlock(at.offset(dx, 0, dz), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(at.offset(dx, -1, dz), Blocks.DIRT.defaultBlockState(), 2);
            }
        }
        level.setBlock(at.offset(1, 1, 0), Blocks.LILY_PAD.defaultBlockState(), 2);
    }

    /** Water welling up at an island's outer edge and falling into the clouds. */
    private static void waterfall(ServerLevel level, BlockPos top, int radius, double angle) {
        final BlockPos edge = top.offset((int) Math.round(Math.cos(angle) * (radius - 1)), 0, (int) Math.round(Math.sin(angle) * (radius - 1)));
        level.setBlock(edge, Blocks.WATER.defaultBlockState(), 3);
    }

    /** A small blossoming tree: a cherry trunk and a round crown of (lasting) cherry leaves. */
    private static void tree(ServerLevel level, BlockPos base, int height, RandomSource random) {
        for (int y = 0; y < height; y++) {
            level.setBlock(base.above(y), Blocks.CHERRY_LOG.defaultBlockState(), 2);
        }
        final BlockState leaves = Blocks.CHERRY_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        final int crown = height <= 3 ? 1 : 2;
        final BlockPos top = base.above(height - 1);
        for (int dy = -1; dy <= crown; dy++) {
            for (int dz = -crown - 1; dz <= crown + 1; dz++) {
                for (int dx = -crown - 1; dx <= crown + 1; dx++) {
                    final double d = Math.sqrt(dx * dx + dz * dz + dy * dy * 1.5);
                    final BlockPos pos = top.offset(dx, dy, dz);
                    if (d <= crown + 0.6 + random.nextDouble() * 0.6 && level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, leaves, 2);
                    }
                }
            }
        }
    }

    /** Blossom petals scattered on the grass (not on the mat or right next to it). */
    private static void petals(ServerLevel level, BlockPos top, int radius, RandomSource random) {
        for (int i = 0; i < 14; i++) {
            final int dx = random.nextInt(2 * radius + 1) - radius, dz = random.nextInt(2 * radius + 1) - radius;
            final BlockPos pos = top.offset(dx, 1, dz);
            if (Math.abs(dx) + Math.abs(dz) > 2 && level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).is(Blocks.GRASS_BLOCK)) {
                level.setBlock(pos, Blocks.PINK_PETALS.defaultBlockState(), 2);
            }
        }
    }

    /** Stepping stones from the central island's edge to a small island: a jump apart, never more than one up. */
    private static void stones(ServerLevel level, BlockPos from, BlockPos to, double angle, int radius) {
        final double startDistance = CENTRAL_RADIUS + 1.5, endDistance = Math.sqrt(from.distSqr(new BlockPos(to.getX(), from.getY(), to.getZ()))) - radius - 1;
        final int steps = Math.max(1, (int) ((endDistance - startDistance) / 3.0));
        final int rise = to.getY() - from.getY();
        // never steeper than a jump (the islands lie at most 3 apart in height, so this always reaches)
        for (int i = 0; i <= steps; i++) {
            final double t = (double) i / steps;
            final double distance = Mth.lerp(t, startDistance, endDistance);
            final int y = from.getY() + (int) Math.round(rise * t);
            final BlockPos pos = new BlockPos(from.getX() + (int) Math.round(Math.cos(angle) * distance), y, from.getZ() + (int) Math.round(Math.sin(angle) * distance));
            level.setBlock(pos, Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), 2);
        }
    }
}
