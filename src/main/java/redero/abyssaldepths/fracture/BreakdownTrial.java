package redero.abyssaldepths.fracture;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * The Breakdown: a square floor of 7 x 7 cells (three blocks each) walled in black. Cells are marked by a
 * flickering black and white pattern, and a moment later the corruption strikes them. The patterns grow from a
 * few cells to rows, rings and crosses, and at last everything but a few safe cells; they come quicker as it
 * goes. Passed by lasting {@link #SECONDS}; failed on the {@link #HITS}th strike that hits.
 */
final class BreakdownTrial extends Trial {
    static final int SECONDS = 45, HITS = 3, CELL = 3, REACH = 3;

    private int hits, next = 10, invulnerable;
    private List<int[]> pending;
    private int strikeAt = -1;

    BreakdownTrial(ServerPlayer player, ServerLevel level, BlockPos origin, int scars) {
        super(player, level, origin, scars);
    }

    @Override
    public Type type() {
        return Type.BREAKDOWN;
    }

    @Override
    void build() {
        final int r = REACH * CELL + 1; // the floor: x and z within 10 of the middle
        for (int dz = -r - 1; dz <= r + 1; dz++) {
            for (int dx = -r - 1; dx <= r + 1; dx++) {
                final boolean wall = Math.abs(dx) == r + 1 || Math.abs(dz) == r + 1;
                // the cells by turns cold white and warm white (no lines between: they read by their tone), greyer
                // towards the black walls so the floor eases into them
                final int ci = Math.floorDiv(dx + 1, CELL), cj = Math.floorDiv(dz + 1, CELL);
                final int fromWall = r + 1 - Math.max(Math.abs(dx), Math.abs(dz));
                final net.minecraft.world.level.block.Block floor = fromWall == 1 ? Blocks.GRAY_CONCRETE : fromWall == 2 ? Blocks.LIGHT_GRAY_CONCRETE
                        : Math.floorMod(ci + cj, 2) == 0 ? Blocks.WHITE_CONCRETE : Blocks.SMOOTH_QUARTZ;
                level.setBlock(origin.offset(dx, 0, dz), wall ? Trial.black() : floor.defaultBlockState(), 2);
                if (wall) {
                    for (int h = 1; h <= 4; h++) {
                        level.setBlock(origin.offset(dx, h, dz), Trial.black(), 2);
                    }
                }
            }
        }
    }

    @Override
    Vec3 spawn() {
        return Vec3.atBottomCenterOf(origin.above());
    }

    @Override
    float spawnYaw() {
        return 0.0F;
    }

    @Override
    BoundingBox bounds() {
        final int r = REACH * CELL + 3;
        return new BoundingBox(origin.getX() - r, origin.getY() - 2, origin.getZ() - r, origin.getX() + r, origin.getY() + 8, origin.getZ() + r);
    }

    private int[] cell(int ci, int cj) {
        return new int[] {origin.getX() + ci * CELL, origin.getZ() + cj * CELL};
    }

    /** The next pattern, harder the longer it has gone (t in ticks since the start). */
    private List<int[]> pattern(int t) {
        final RandomSource random = level.random;
        final List<int[]> cells = new ArrayList<>();
        final float stage = t / (SECONDS * 20.0F); // 0 .. 1
        final int kind = stage < 0.2F ? 0 : random.nextInt(stage < 0.6F ? 4 : 5);
        switch (kind) {
            case 0 -> { // cells at random, more and more
                final int count = 8 + (int) (stage * 22);
                for (int k = 0; k < count; k++) {
                    cells.add(cell(random.nextInt(7) - 3, random.nextInt(7) - 3));
                }
            }
            case 1 -> { // every other row (or column)
                final boolean rows = random.nextBoolean();
                final int parity = random.nextInt(2);
                for (int a = -3; a <= 3; a++) {
                    if (Math.floorMod(a, 2) == parity) {
                        for (int b = -3; b <= 3; b++) {
                            cells.add(rows ? cell(b, a) : cell(a, b));
                        }
                    }
                }
            }
            case 2 -> { // a ring, at a distance from the middle
                final int ring = random.nextInt(3) + 1;
                for (int a = -3; a <= 3; a++) {
                    for (int b = -3; b <= 3; b++) {
                        if (Math.max(Math.abs(a), Math.abs(b)) == ring || Math.max(Math.abs(a), Math.abs(b)) == ring - 1 && stage > 0.5F) {
                            cells.add(cell(a, b));
                        }
                    }
                }
            }
            case 3 -> { // a cross, or an X
                final boolean x = random.nextBoolean();
                for (int a = -3; a <= 3; a++) {
                    cells.add(x ? cell(a, a) : cell(a, 0));
                    cells.add(x ? cell(a, -a) : cell(0, a));
                }
            }
            default -> { // everything but a few safe cells
                final List<int[]> safe = new ArrayList<>();
                for (int k = 0; k < 3; k++) {
                    safe.add(new int[] {random.nextInt(7) - 3, random.nextInt(7) - 3});
                }
                for (int a = -3; a <= 3; a++) {
                    for (int b = -3; b <= 3; b++) {
                        final int fa = a, fb = b;
                        if (safe.stream().noneMatch(s -> s[0] == fa && s[1] == fb)) {
                            cells.add(cell(a, b));
                        }
                    }
                }
            }
        }
        return cells;
    }

    private int warnTicks(int t) {
        return Math.max(14, 24 - t / 90);
    }

    /** Looking round: the four corner cells are marked and struck, far from the player, to show what comes. */
    @Override
    protected void orient(int t) {
        if (t == 20) {
            pending = List.of(cell(-3, -3), cell(3, -3), cell(-3, 3), cell(3, 3));
            warn(origin.getY() + 1, CELL, pending, 24);
            strikeAt = t + 24;
        } else if (t == strikeAt && pending != null) {
            burst(pending, CELL, origin.getY() + 1);
            pending = null;
        }
        hud(1.0F, HITS, HITS);
    }

    @Override
    protected Result run(int t) {
        if (invulnerable > 0) {
            invulnerable--;
        }
        if (pending == null && t >= next) {
            pending = pattern(t);
            final int warn = warnTicks(t);
            warn(origin.getY() + 1, CELL, pending, warn);
            strikeAt = t + warn;
        }
        if (pending != null && t == strikeAt) {
            burst(pending, CELL, origin.getY() + 1);
            if (invulnerable == 0 && inCells(pending, CELL, origin.getY() + 1, 1.6)) {
                hits++;
                invulnerable = 20;
                hit(Vec3.atCenterOf(origin));
            }
            pending = null;
            next = t + Math.max(8, 22 - t / 60); // quicker as it goes
        }
        hud(1.0F - t / (SECONDS * 20.0F), HITS - hits, HITS);
        if (hits >= HITS) {
            return Result.FAILED;
        }
        return t >= SECONDS * 20 ? Result.PASSED : Result.RUNNING;
    }
}
