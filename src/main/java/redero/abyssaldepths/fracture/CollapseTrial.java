package redero.abyssaldepths.fracture;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * The Collapse: a long chain of platforms over the void, gaps of two and three blocks, some only a single block,
 * rising and falling, a stable one at its far end (ringed in grey, posts on its corners). Dark grey platforms break
 * away a moment after they are stepped on, light grey ones after a little longer, the few white ones hold; the
 * warm ones phase out and back in on a rhythm (turning to glass just before they go). Now and then a broken one is
 * answered by a new one beside the way. The platform the player starts on crumbles from behind once the trial
 * begins. Failed by falling into the void, or by not being on the far side within {@link #SECONDS} (less for the
 * scarred).
 */
final class CollapseTrial extends Trial {
    static final int SECONDS = 80, PLATFORMS = 24;
    private static final int FRAGILE_TICKS = 7, TIMED_TICKS = 26, START_ROW_TICKS = 14;
    /** The phasing platforms: their rhythm, how long of it they are there, the warning before they go. */
    private static final int PHASE_TICKS = 60, PHASE_SOLID = 36, PHASE_WARN = 12;

    /** A platform: its middle, its half width, its kind (0 holds, 1 breaks after a while, 2 at once, 3 phases), when stepped on. */
    private static final class Platform {
        final BlockPos middle;
        final int half, kind;
        long stepped = -1;
        boolean gone;
        /** Phasing: where in its rhythm it starts; how it is shown now (0 there, 1 about to go, 2 gone). */
        int offset, shown;

        Platform(BlockPos middle, int half, int kind) {
            this.middle = middle;
            this.half = half;
            this.kind = kind;
        }

        boolean under(ServerPlayer player) {
            return Math.abs(player.getX() - (middle.getX() + 0.5)) <= half + 0.8 && Math.abs(player.getZ() - (middle.getZ() + 0.5)) <= half + 0.8
                    && Math.abs(player.getY() - (middle.getY() + 1)) < 0.6;
        }
    }

    private final List<Platform> platforms = new ArrayList<>();
    private Platform start, goal;
    private int startRows;
    private int maxX = 6, maxZ = 6;

    CollapseTrial(ServerPlayer player, ServerLevel level, BlockPos origin, int scars) {
        super(player, level, origin, scars);
    }

    @Override
    public Type type() {
        return Type.COLLAPSE;
    }

    private int limit() {
        return (SECONDS - 5 * scars) * 20;
    }

    private static BlockState block(int kind) {
        return switch (kind) {
            case 2 -> Blocks.GRAY_CONCRETE.defaultBlockState();
            case 1 -> Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
            case 3 -> Blocks.CALCITE.defaultBlockState();
            default -> Blocks.WHITE_CONCRETE.defaultBlockState();
        };
    }

    private void place(Platform platform) {
        for (int dz = -platform.half; dz <= platform.half; dz++) {
            for (int dx = -platform.half; dx <= platform.half; dx++) {
                level.setBlock(platform.middle.offset(dx, 0, dz), block(platform.kind), 2);
            }
        }
        platforms.add(platform);
        maxX = Math.max(maxX, Math.abs(platform.middle.getX() - origin.getX()) + platform.half + 2);
        maxZ = Math.max(maxZ, platform.middle.getZ() - origin.getZ() + platform.half + 2);
    }

    @Override
    void build() {
        final net.minecraft.util.RandomSource random = level.random;
        start = new Platform(origin, 2, 0);
        place(start);
        final int[] kinds = new int[PLATFORMS];
        for (int i = 0; i < PLATFORMS; i++) {
            final int roll = random.nextInt(100);
            kinds[i] = i < 2 ? 1 : roll < 10 ? 0 : roll < 42 ? 2 : roll < 62 ? 3 : 1; // few that hold
        }
        for (int i = 1; i < PLATFORMS; i++) {
            if (kinds[i] == 3) { // a phasing one is waited for: the one before holds (never two phasing in a row)
                kinds[i - 1] = 0;
            }
        }
        if (kinds[PLATFORMS - 1] == 3) {
            kinds[PLATFORMS - 1] = 0; // the last waits on nothing: no need to phase
        }
        BlockPos at = origin;
        int half = 2;
        for (int i = 0; i < PLATFORMS; i++) {
            final int kind = kinds[i];
            final int next = kind != 3 && i > 3 && random.nextInt(100) < 22 ? 0 : 1; // now and then a single block
            final int rise = at.getY() - origin.getY();
            final int dy = i > 1 && random.nextInt(3) == 0 ? (rise >= 5 ? -1 : rise <= -4 ? 1 : random.nextBoolean() ? 1 : -1) : 0;
            // the gap between edges: two or three blocks (two, going up or onto a single block)
            final int gap = dy > 0 || next == 0 ? 2 : 2 + random.nextInt(2);
            final int dz = half + gap + next + 1;
            final int dx = Math.max(-7, Math.min(7, at.getX() - origin.getX() + random.nextInt(7) - 3)) - (at.getX() - origin.getX());
            at = at.offset(dx, dy, dz);
            final Platform platform = new Platform(at, next, kind);
            platform.offset = random.nextInt(PHASE_TICKS);
            place(platform);
            half = next;
        }
        goal = new Platform(at.offset(0, 0, half + 6), 3, 0);
        place(goal);
        for (int dz = -3; dz <= 3; dz++) { // ringed in black, lights on its corners
            for (int dx = -3; dx <= 3; dx++) {
                if (Math.abs(dx) == 3 || Math.abs(dz) == 3) {
                    level.setBlock(goal.middle.offset(dx, 0, dz), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
                }
            }
        }
        for (int corner = 0; corner < 4; corner++) {
            final int cx = corner % 2 == 0 ? -3 : 3, cz = corner < 2 ? -3 : 3;
            for (int h = 1; h <= 3; h++) { // a post, grey at its foot to white at its head
                level.setBlock(goal.middle.offset(cx, h, cz), (h == 1 ? Blocks.LIGHT_GRAY_CONCRETE : h == 2 ? Blocks.SMOOTH_QUARTZ : Blocks.WHITE_CONCRETE).defaultBlockState(), 2);
            }
        }
    }

    @Override
    Vec3 spawn() {
        return Vec3.atBottomCenterOf(origin.above());
    }

    @Override
    float spawnYaw() {
        return 0.0F; // towards the far side (south)
    }

    @Override
    BoundingBox bounds() {
        return new BoundingBox(origin.getX() - maxX - 4, origin.getY() - 6, origin.getZ() - 4, origin.getX() + maxX + 4, origin.getY() + 12,
                origin.getZ() + maxZ + 4);
    }

    /** Breaks a platform away: its blocks fall into the void, with a crack and dust. */
    private void collapse(Platform platform) {
        platform.gone = true;
        for (int dz = -platform.half; dz <= platform.half; dz++) {
            for (int dx = -platform.half; dx <= platform.half; dx++) {
                final BlockPos pos = platform.middle.offset(dx, 0, dz);
                final BlockState state = level.getBlockState(pos);
                if (!state.isAir()) {
                    FallingBlockEntity.fall(level, pos, state);
                }
            }
        }
        level.playSound(null, platform.middle, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.0F, 0.5F);
        // now and then a new platform answers beside the way ahead
        if (platform != start && level.random.nextInt(2) == 0) {
            final BlockPos beside = platform.middle.offset(level.random.nextBoolean() ? 3 : -3, 0, 3);
            if (platforms.stream().noneMatch(p -> !p.gone && p.middle.distManhattan(beside) < 4)) {
                place(new Platform(beside, 1, 1));
                level.sendParticles(ParticleTypes.END_ROD, beside.getX() + 0.5, beside.getY() + 1.0, beside.getZ() + 0.5, 20, 1.0, 0.3, 1.0, 0.02);
            }
        }
    }

    @Override
    protected Result run(int t) {
        final long now = level.getGameTime();
        // the start crumbles from behind, a row at a time
        if (!start.gone && t % START_ROW_TICKS == 0 && startRows < 5) {
            final int dz = -2 + startRows++;
            for (int dx = -2; dx <= 2; dx++) {
                final BlockPos pos = origin.offset(dx, 0, dz);
                final BlockState state = level.getBlockState(pos);
                if (!state.isAir()) {
                    FallingBlockEntity.fall(level, pos, state);
                }
            }
            if (startRows == 5) {
                start.gone = true;
            }
        }
        for (Platform platform : new ArrayList<>(platforms)) {
            if (platform.gone || platform == start || platform == goal) {
                continue;
            }
            if (platform.kind == 3) { // phasing: there, then glass a moment, then gone, then back
                final int at = (t + platform.offset) % PHASE_TICKS;
                final int shown = at < PHASE_SOLID - PHASE_WARN ? 0 : at < PHASE_SOLID ? 1 : 2;
                if (shown != platform.shown) {
                    platform.shown = shown;
                    final BlockState state = shown == 0 ? block(3) : shown == 1 ? Blocks.LIGHT_GRAY_STAINED_GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    for (int dz = -platform.half; dz <= platform.half; dz++) {
                        for (int dx = -platform.half; dx <= platform.half; dx++) {
                            level.setBlock(platform.middle.offset(dx, 0, dz), state, 2);
                        }
                    }
                    if (shown == 2) {
                        level.playSound(null, platform.middle, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 0.5F, 0.5F);
                    }
                }
                continue;
            }
            if (platform.stepped < 0 && platform.under(player) && player.onGround()) {
                platform.stepped = now;
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, block(platform.kind)), platform.middle.getX() + 0.5, platform.middle.getY() + 1.0,
                        platform.middle.getZ() + 0.5, 12, 0.8, 0.1, 0.8, 0.0);
                level.playSound(null, platform.middle, SoundEvents.AMETHYST_BLOCK_STEP, SoundSource.HOSTILE, 1.0F, 0.6F);
            }
            final int after = platform.kind == 2 ? FRAGILE_TICKS : platform.kind == 1 ? TIMED_TICKS : -1;
            if (platform.stepped >= 0 && after >= 0) {
                if (now - platform.stepped == after - 6) { // about to go: it shudders
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, block(platform.kind)), platform.middle.getX() + 0.5, platform.middle.getY() + 0.5,
                            platform.middle.getZ() + 0.5, 20, 0.8, 0.2, 0.8, 0.0);
                }
                if (now - platform.stepped >= after) {
                    collapse(platform);
                }
            }
        }
        hud(1.0F - t / (float) limit(), 0.0F, 0.0F);
        if (goal.under(player) && player.onGround()) {
            return Result.PASSED;
        }
        if (player.getY() < origin.getY() - 12 || t > limit()) {
            return Result.FAILED;
        }
        return Result.RUNNING;
    }
}
