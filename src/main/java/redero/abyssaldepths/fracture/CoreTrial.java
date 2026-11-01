package redero.abyssaldepths.fracture;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.AbyssalDepths;

/**
 * The Core: a round arena walled in black, a corrupted core ({@link FractureCore}) floating over its middle. In
 * turn it sends out rings running over the floor (jump them), a beam sweeping round low (jump it) and a barrage
 * of strikes on marked cells round the player (step out of them); after each, its shell opens for a while and
 * blows crack it. Each attack is foretold for {@link #WARN_TICKS} (what comes, and where: the sweep's start shown on
 * the floor, always across the arena from the player). Each attack that hits takes a share of the player's stability ({@link #HIT}, more for the
 * scarred); passed when the core breaks, failed when stability is gone. No time limit: its patterns are there
 * to be learned.
 */
final class CoreTrial extends Trial {
    static final int RADIUS = 11;
    static final float HIT = 0.25F;
    /** Ticks of each part: the shockwave, the sweep, the barrage, the shell open. */
    static final int SHOCKWAVE_TICKS = 70, SWEEP_TICKS = 80, BARRAGE_TICKS = 70, OPEN_TICKS = 60, WARN_TICKS = 32;
    /** How fast a ring runs out, and where they start. */
    static final float RING_SPEED = 0.3F, RING_FROM = 1.5F;

    private FractureCore core;
    private float stability = 1.0F;
    private int invulnerable, cycle, part, partStart, coming;
    private float comingFrom;
    private final List<int[]> pendingCells = new ArrayList<>();
    private int strikeAt = -1;

    CoreTrial(ServerPlayer player, ServerLevel level, BlockPos origin, int scars) {
        super(player, level, origin, scars);
    }

    @Override
    public Type type() {
        return Type.CORE;
    }

    private float hitCost() {
        return HIT + 0.05F * scars;
    }

    @Override
    void build() {
        for (int dz = -RADIUS - 1; dz <= RADIUS + 1; dz++) {
            for (int dx = -RADIUS - 1; dx <= RADIUS + 1; dx++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                if (d > RADIUS + 1.2) {
                    continue;
                }
                final boolean wall = d > RADIUS + 0.2;
                // warm white under the core, cold white round it, greying out towards the black wall
                final net.minecraft.world.level.block.Block floor = d > RADIUS - 0.8 ? Blocks.GRAY_CONCRETE : d > RADIUS - 1.8 ? Blocks.LIGHT_GRAY_CONCRETE
                        : d < 3.5 ? Blocks.SMOOTH_QUARTZ : Blocks.WHITE_CONCRETE;
                level.setBlock(origin.offset(dx, 0, dz), wall ? Trial.black() : floor.defaultBlockState(), 2);
                if (wall) {
                    for (int h = 1; h <= 3; h++) {
                        level.setBlock(origin.offset(dx, h, dz), Trial.black(), 2);
                    }
                }
            }
        }
        core = new FractureCore(AbyssalDepths.FRACTURE_CORE.get(), level);
        core.moveTo(origin.getX() + 0.5, origin.getY() + 3.5, origin.getZ() + 0.5);
        level.addFreshEntity(core);
    }

    @Override
    Vec3 spawn() {
        return Vec3.atBottomCenterOf(origin.offset(0, 1, -8));
    }

    @Override
    float spawnYaw() {
        return 0.0F; // facing the core
    }

    @Override
    BoundingBox bounds() {
        return new BoundingBox(origin.getX() - RADIUS - 3, origin.getY() - 2, origin.getZ() - RADIUS - 3, origin.getX() + RADIUS + 3, origin.getY() + 10,
                origin.getZ() + RADIUS + 3);
    }

    private double floorTop() {
        return origin.getY() + 1.0;
    }

    /** The player's distance from the middle, and their angle round it. */
    private double distance() {
        return Math.sqrt(Math.pow(player.getX() - (origin.getX() + 0.5), 2) + Math.pow(player.getZ() - (origin.getZ() + 0.5), 2));
    }

    private double angle() {
        return Math.atan2(player.getZ() - (origin.getZ() + 0.5), player.getX() - (origin.getX() + 0.5));
    }

    private void struck() {
        if (invulnerable > 0) {
            return;
        }
        invulnerable = 20;
        stability = Math.max(0.0F, stability - hitCost());
        hit(core.position());
    }

    /** Starts a part of the cycle: the three attacks in turn (each foretold first), the shell opening after each. */
    private void begin(int next, int t) {
        part = next;
        partStart = t;
        core.set(next, next == FractureCore.SWEEP ? comingFrom : 0.0F, 1.0F + 0.15F * cycle, coming);
        if (next == FractureCore.OPEN) {
            level.playSound(null, core.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.0F, 1.8F);
        }
    }

    /** Foretells an attack: what it is (the core and the floor show it), from where (the sweep starts across from the player). */
    private void foretell(int attack, int t) {
        coming = attack;
        comingFrom = (float) (angle() + Math.PI + (level.random.nextFloat() - 0.5F) * 1.2F);
        part = FractureCore.WARN;
        partStart = t;
        core.set(FractureCore.WARN, comingFrom, 1.0F + 0.15F * cycle, attack);
        switch (attack) {
            case FractureCore.SHOCKWAVE -> level.playSound(null, core.blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 0.8F, 0.5F);
            case FractureCore.SWEEP -> level.playSound(null, core.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.0F, 0.6F);
            default -> level.playSound(null, core.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.2F, 0.5F);
        }
    }

    @Override
    protected void orient(int t) {
        if (t == 1) {
            core.set(FractureCore.IDLE, 0.0F, 1.0F, FractureCore.IDLE);
            core.setLeft(FractureCore.STRENGTH);
        }
        hud(stability, 1.0F, 0.0F);
    }

    @Override
    protected Result run(int t) {
        if (invulnerable > 0) {
            invulnerable--;
        }
        if (t == 1) {
            foretell(FractureCore.SHOCKWAVE, t);
        }
        final int in = t - partStart;
        final float pace = 1.0F + 0.15F * cycle; // a little quicker every round
        switch (part) {
            case FractureCore.SHOCKWAVE -> {
                // three rings out from under the core, a moment apart: on the ground where one passes, it hits
                for (int k = 0; k < 3; k++) {
                    final float r = RING_FROM + (in - k * 18) * RING_SPEED * pace;
                    if (in >= k * 18 && Math.abs(distance() - r) < 0.6 && player.getY() - floorTop() < 0.35) {
                        struck();
                    }
                }
                if (in >= SHOCKWAVE_TICKS / pace) {
                    begin(FractureCore.OPEN, t);
                }
            }
            case FractureCore.SWEEP -> {
                // a low beam turning once round: where it passes, under the jump, it hits
                final double beam = core.sweepFrom() + in * Mth.TWO_PI / (SWEEP_TICKS / pace);
                final double gap = Math.abs(Mth.wrapDegrees(Math.toDegrees(angle() - beam)));
                if (distance() > 1.5 && gap < Math.toDegrees(0.7 / Math.max(1.0, distance())) + 4 && player.getY() - floorTop() < 0.9) {
                    struck();
                }
                if (in >= SWEEP_TICKS / pace) {
                    begin(FractureCore.OPEN, t);
                }
            }
            case FractureCore.BARRAGE -> {
                // three volleys of cells marked round where the player is, struck a moment later
                if (in % 22 == 0 && in < 66) {
                    pendingCells.clear();
                    final int px = Mth.floor(player.getX()), pz = Mth.floor(player.getZ());
                    pendingCells.add(new int[] {px, pz});
                    for (int k = 0; k < 4; k++) {
                        pendingCells.add(new int[] {px + (level.random.nextInt(5) - 2) * 3, pz + (level.random.nextInt(5) - 2) * 3});
                    }
                    warn(floorTop(), 3, pendingCells, 16);
                    strikeAt = t + 16;
                }
                if (t == strikeAt) {
                    burst(pendingCells, 3, floorTop());
                    if (inCells(pendingCells, 3, floorTop(), 1.6)) {
                        struck();
                    }
                }
                if (in >= BARRAGE_TICKS) {
                    begin(FractureCore.OPEN, t);
                }
            }
            case FractureCore.OPEN -> {
                if (in >= OPEN_TICKS) {
                    final int next = cycle % 3 == 0 ? FractureCore.SWEEP : cycle % 3 == 1 ? FractureCore.BARRAGE : FractureCore.SHOCKWAVE;
                    cycle++;
                    foretell(next, t);
                }
            }
            case FractureCore.WARN -> {
                if (in >= WARN_TICKS) {
                    begin(coming, t);
                }
            }
            default -> {
            }
        }
        hud(stability, core.left() / (float) FractureCore.STRENGTH, part == FractureCore.OPEN ? 1.0F : 0.0F);
        if (core.left() <= 0) {
            level.playSound(null, core.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 2.0F, 0.4F);
            return Result.PASSED;
        }
        return stability <= 0.0F ? Result.FAILED : Result.RUNNING;
    }
}
