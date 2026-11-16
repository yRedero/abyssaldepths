package redero.abyssaldepths.fracture;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * The threshold of a trial: where the player wakes, set apart from the trial itself. A pale walkway leads to a doorway
 * of white; beside it, on pieces of their own, echoes of the player act out the trial's rules over and over, each
 * {@link Lesson} one rule: stepping on a piece that crumbles, waiting for one that phases, striking the double back
 * from a gate, stepping out of a marked strike, a memory winning back control, the double coming for noise, an
 * opening in a black floor. Nothing here counts: the trial begins when they step through the doorway (they are taken
 * to its start), and a fall from the walkway only sets them back on it. All in the trial's black and white.
 */
final class Threshold {
    /** How far the stations stand from the walkway's middle, and from one another along it. */
    private static final int SIDE = 10, STEP = 11, WIDTH = 2;

    private final Trial trial;
    private final ServerLevel level;
    private final ServerPlayer player;
    private final BlockPos start; // the walkway's near end, on its floor
    private final int length;
    private final List<Lesson> lessons;
    private final List<BlockPos> placed = new ArrayList<>();
    private int t;
    boolean crossed;

    private Threshold(Trial trial, BlockPos start, List<Lesson> lessons) {
        this.trial = trial;
        this.level = trial.level;
        this.player = trial.player;
        this.start = start;
        this.lessons = lessons;
        this.length = 6 + (lessons.size() + 1) / 2 * STEP + 4;
    }

    /** The threshold of a trial, out beyond its arena (behind where it starts), or null if the trial has nothing to show. */
    static Threshold of(Trial trial, List<Lesson> lessons) {
        if (lessons.isEmpty()) {
            return null;
        }
        final BoundingBox box = trial.bounds();
        final int len = 6 + (lessons.size() + 1) / 2 * STEP + 4;
        // far out of sight of the trial (it is not to be seen from here)
        final BlockPos start = new BlockPos((box.minX() + box.maxX()) / 2, trial.origin.getY(), box.minZ() - 640 - len);
        final Threshold threshold = new Threshold(trial, start, lessons);
        threshold.build();
        return threshold;
    }

    /** Where the player wakes: the walkway's near end, looking along it. */
    Vec3 arrival() {
        return Vec3.atBottomCenterOf(start.offset(0, 1, 1));
    }

    float arrivalYaw() {
        return 0.0F; // along +z, towards the doorway
    }

    /** The box it takes (cleared with the arena). */
    BoundingBox bounds() {
        return new BoundingBox(start.getX() - SIDE - 8, start.getY() - 14, start.getZ() - 4, start.getX() + SIDE + 8, start.getY() + 16,
                start.getZ() + length + 6);
    }

    private void set(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 2);
        placed.add(pos.immutable());
    }

    private void build() {
        final BlockState floor = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), rim = Blocks.WHITE_CONCRETE.defaultBlockState();
        for (int z = 0; z <= length; z++) {
            for (int x = -WIDTH; x <= WIDTH; x++) {
                set(start.offset(x, 0, z), Math.abs(x) == WIDTH ? rim : floor);
            }
        }
        // the doorway at the far end: a white frame, open, light drifting in it
        for (int y = 1; y <= 4; y++) {
            set(start.offset(-WIDTH, y, length), rim);
            set(start.offset(WIDTH, y, length), rim);
        }
        for (int x = -WIDTH; x <= WIDTH; x++) {
            set(start.offset(x, 5, length), Blocks.SMOOTH_QUARTZ.defaultBlockState());
        }
        for (int i = 0; i < lessons.size(); i++) {
            final int side = i % 2 == 0 ? -1 : 1;
            final BlockPos at = start.offset(side * SIDE, 0, 6 + i / 2 * STEP + (side > 0 ? STEP / 2 : 0));
            lessons.get(i).place(this, at, side);
        }
    }

    /** A tick on the threshold: the echoes act; through the doorway, the trial begins; fallen, back on the walkway. */
    void tick() {
        t++;
        for (Lesson lesson : lessons) {
            lesson.tick(t % lesson.period(), t);
        }
        if (t % 6 == 0) {
            level.sendParticles(player, ParticleTypes.END_ROD, true, start.getX() + 0.5, start.getY() + 2.5, start.getZ() + length + 0.5, 3, 1.0, 1.2, 0.1, 0.0);
        }
        final Vec3 p = player.position();
        if (p.y < start.getY() - 10) { // fell: back where they woke (it does not count here)
            final Vec3 back = arrival();
            player.teleportTo(level, back.x, back.y, back.z, arrivalYaw(), 10.0F);
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
            level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 0.8F, 0.6F);
            return;
        }
        if (p.z >= start.getZ() + length - 0.2 && Math.abs(p.x - (start.getX() + 0.5)) < WIDTH && p.y < start.getY() + 5) {
            cross();
        }
    }

    /** Through at once (the command). */
    void crossNow() {
        cross();
    }

    /** Through the doorway: the echoes go, and they are at the trial's start. */
    private void cross() {
        crossed = true;
        for (Lesson lesson : lessons) {
            lesson.remove();
        }
        trial.clear(bounds()); // the threshold is gone once it is behind them
        final Vec3 spawn = trial.spawn();
        player.teleportTo(level, spawn.x, spawn.y, spawn.z, trial.spawnYaw(), 10.0F);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        level.playSound(null, BlockPos.containing(spawn), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.2F, 0.5F);
    }

    // ---- what the lessons use ------------------------------------------------------------------------------------

    TrialEcho echo(Vec3 at, Vec3 facing) {
        final TrialEcho echo = TrialEcho.of(player, level, at, 0.0F);
        echo.faceTowards(facing);
        return echo;
    }

    /** An echo armed (for the lessons where it strikes): a sword in its hand. */
    TrialEcho armed(Vec3 at, Vec3 facing) {
        final TrialEcho echo = echo(at, facing);
        echo.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        return echo;
    }

    /** The echo strikes the double: a lunge and a swing, the double flashing and thrown back a little, sparks where it lands. */
    void strike(TrialEcho echo, FractureDouble twin, double back) {
        echo.swing(InteractionHand.MAIN_HAND);
        final Vec3 dir = twin.position().subtract(echo.position()).multiply(1, 0, 1).normalize();
        echo.setPos(echo.position().add(dir.scale(0.35)));
        twin.setPos(twin.position().add(dir.scale(back)));
        level.broadcastDamageEvent(twin, level.damageSources().generic()); // it flashes as it is struck
        level.sendParticles(ParticleTypes.CRIT, twin.getX(), twin.getY() + 1.2, twin.getZ(), 12, 0.25, 0.3, 0.25, 0.25);
        level.sendParticles(WHITE, twin.getX(), twin.getY() + 1.1, twin.getZ(), 10, 0.3, 0.4, 0.3, 0.0);
    }

    /** The echo is struck (as the player would be): it flashes red, reels back, shards of black and white burst, the hit's sound. */
    void hurt(TrialEcho echo, Vec3 from) {
        level.broadcastDamageEvent(echo, level.damageSources().generic());
        final Vec3 away = echo.position().subtract(from).multiply(1, 0, 1);
        if (away.lengthSqr() > 1.0E-4) {
            echo.setPos(echo.position().add(away.normalize().scale(0.6)).add(0, 0.25, 0));
        }
        level.sendParticles(WHITE, echo.getX(), echo.getY() + 1.0, echo.getZ(), 14, 0.3, 0.5, 0.3, 0.0);
        level.sendParticles(BLACK, echo.getX(), echo.getY() + 1.0, echo.getZ(), 14, 0.3, 0.5, 0.3, 0.0);
        level.playSound(null, echo.blockPosition(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 1.0F, 0.5F);
    }

    FractureDouble twin(Vec3 at, Vec3 facing) {
        final FractureDouble twin = FractureDouble.of(player, level, at);
        twin.face(facing);
        return twin;
    }

    ItemEntity memory(Vec3 at) {
        return trial.memory(Trial.remembered(player, trial.carried, 1).get(0).copy(), at);
    }

    /** Pieces of the threshold (placed and cleared with it). */
    void block(BlockPos pos, BlockState state) {
        set(pos, state);
    }

    /** A block changing as the lesson goes (no record: the box is cleared anyway). */
    void change(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 2);
    }

    ServerLevel level() {
        return level;
    }

    Trial trial() {
        return trial;
    }

    /** Where between two places a walk of {@code from .. to} ticks is at tick {@code t} (eased at both ends). */
    static Vec3 along(Vec3 a, Vec3 b, int t, int from, int to) {
        final float k = Mth.clamp((t - from) / (float) Math.max(1, to - from), 0.0F, 1.0F);
        final float e = k * k * (3 - 2 * k);
        return a.add(b.subtract(a).scale(e));
    }

    static final DustParticleOptions WHITE = new DustParticleOptions(new Vector3f(0.92F, 0.92F, 0.9F), 1.0F);
    static final DustParticleOptions BLACK = new DustParticleOptions(new Vector3f(0.0F, 0.0F, 0.0F), 1.2F);

    // ---- the lessons ---------------------------------------------------------------------------------------------

    /** One rule acted out at one station, looping every {@link #period} ticks. */
    abstract static class Lesson {
        Threshold th;
        BlockPos at; // the station's middle, on its floor
        int side;

        void place(Threshold th, BlockPos at, int side) {
            this.th = th;
            this.at = at;
            this.side = side;
            build();
        }

        abstract int period();

        abstract void build();

        /** {@code t}: the tick within the loop; {@code total}: ticks since the threshold began. */
        abstract void tick(int t, int total);

        abstract void remove();

        Vec3 p(double dx, double dy, double dz) {
            return new Vec3(at.getX() + 0.5 + dx, at.getY() + 1 + dy, at.getZ() + 0.5 + dz);
        }

        BlockPos b(int dx, int dy, int dz) {
            return at.offset(dx, dy, dz);
        }

        void pad(int x0, int z0, int x1, int z1, BlockState state) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    th.block(b(x, 0, z), state);
                }
            }
        }

        void sound(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
            th.level().playSound(null, at, sound, SoundSource.HOSTILE, volume, pitch);
        }

        static void gone(net.minecraft.world.entity.Entity e) {
            if (e != null && !e.isRemoved()) {
                e.discard();
            }
        }
    }

    static BlockState white() {
        return Blocks.WHITE_CONCRETE.defaultBlockState();
    }

    static BlockState grey() {
        return Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
    }

    /** A piece that crumbles: the echo crosses it, it cracks under them; once they are off it, it gives way behind them. */
    static final class Crumble extends Lesson {
        private TrialEcho echo;
        private boolean down;

        int period() {
            return 120;
        }

        void build() {
            pad(-1, -5, 1, -3, white()); // one that holds, before
            pad(-1, 4, 1, 6, white()); // one that holds, after
            crumbleBack();
        }

        private void crumbleBack() {
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    th.change(b(x, 0, z), grey());
                }
            }
            down = false;
        }

        void tick(int t, int total) {
            if (echo == null || echo.isRemoved()) {
                echo = th.echo(p(0, 0, -4), p(0, 0, 4));
            }
            if (t == 0) {
                if (down) {
                    crumbleBack();
                }
                echo.setPos(p(0, 0, -4));
            } else if (t <= 24) { // onto it
                echo.setPos(along(p(0, 0, -4), p(0, 0, 0), t, 6, 24).add(0, t > 10 && t < 18 ? 0.4 : 0, 0));
            } else if (t == 25) { // it cracks under them
                sound(SoundEvents.AMETHYST_BLOCK_STEP, 1.0F, 0.6F);
                th.level().sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, grey()), at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5,
                        12, 0.8, 0.1, 0.8, 0.0);
            } else if (t > 40 && t <= 60) { // and on, off it
                echo.setPos(along(p(0, 0, 0), p(0, 0, 5), t, 40, 60).add(0, t > 46 && t < 54 ? 0.4 : 0, 0));
            } else if (t == 62) {
                th.level().sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, grey()), at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5,
                        25, 0.8, 0.2, 0.8, 0.0);
            } else if (t == 72) { // behind them, it gives way
                for (int z = -1; z <= 1; z++) {
                    for (int x = -1; x <= 1; x++) {
                        th.change(b(x, 0, z), Blocks.AIR.defaultBlockState());
                    }
                }
                down = true;
                sound(SoundEvents.GLASS_BREAK, 1.0F, 0.5F);
            } else if (t == 100) {
                echo.setPos(p(0, -14, 0)); // (out of sight while it starts again)
            }
        }

        void remove() {
            gone(echo);
        }
    }

    /** A piece that phases out and back on its rhythm (glass just before it goes): the echo waits for it, then goes. */
    static final class Phase extends Lesson {
        private TrialEcho echo;

        int period() {
            return 120;
        }

        void build() {
            pad(-1, -4, 1, -2, white());
            pad(-1, 4, 1, 6, white());
        }

        private void piece(BlockState state) {
            for (int z = 0; z <= 2; z++) {
                for (int x = -1; x <= 1; x++) {
                    th.change(b(x, 0, z), state);
                }
            }
        }

        void tick(int t, int total) {
            if (echo == null || echo.isRemoved()) {
                echo = th.echo(p(0, 0, -3), p(0, 0, 3));
            }
            // the piece: solid 0 .. 60, glass 60 .. 80 (smoking, ticking), gone 80 .. 120
            if (t == 0) {
                piece(grey());
                echo.setPos(p(0, 0, -3));
            } else if (t == 60) {
                piece(Blocks.GRAY_STAINED_GLASS.defaultBlockState());
            } else if (t == 80) {
                piece(Blocks.AIR.defaultBlockState());
                sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.5F, 0.5F);
            }
            if (t > 60 && t < 80 && t % 4 == 0) {
                th.level().sendParticles(ParticleTypes.SMOKE, at.getX() + 0.5, at.getY() + 1.1, at.getZ() + 1.5, 6, 0.7, 0.05, 0.7, 0.0);
                sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.4F, 0.5F + 0.4F * (t - 60) / 20.0F);
            }
            // the echo: waits while it is gone (from the last loop), crosses right after it is back, before the glass
            if (t > 4 && t <= 44) {
                echo.setPos(along(p(0, 0, -3), p(0, 0, 5), t, 4, 44).add(0, (t > 10 && t < 16) || (t > 32 && t < 38) ? 0.4 : 0, 0));
            } else if (t == 100) {
                echo.setPos(p(0, 0, -3)); // back to wait again
            }
        }

        void remove() {
            gone(echo);
        }
    }

    /** A gate: a black wall, the double guarding it; struck back twice, the wall falls and the way is open. */
    static final class Gate extends Lesson {
        private TrialEcho echo;
        private FractureDouble twin;

        int period() {
            return 130;
        }

        void build() {
            pad(-3, -4, 3, 4, white());
            wall(true);
        }

        private void wall(boolean up) {
            for (int y = 1; y <= 4; y++) {
                for (int x = -3; x <= 3; x++) {
                    final boolean frame = Math.abs(x) == 3 || y == 4;
                    th.change(b(x, y, 2), up ? (frame ? white() : Trial.black()) : Blocks.AIR.defaultBlockState());
                }
            }
        }

        void tick(int t, int total) {
            if (echo == null || echo.isRemoved()) {
                echo = th.armed(p(0, 0, -3), p(0, 0, 3));
            }
            if (t == 0) {
                wall(true);
                echo.setPos(p(0, 0, -3));
                echo.faceTowards(p(0, 0, 3));
                gone(twin);
                twin = th.twin(p(0, 0, 0.8), p(0, 0, -3));
            }
            if (twin == null || twin.isRemoved()) {
                return;
            }
            if (t <= 20) {
                echo.setPos(along(p(0, 0, -3), p(0, 0, -0.6), t, 4, 20));
            } else if (t == 30 || t == 52) { // a blow: it reels back against its wall
                th.strike(echo, twin, 0.3);
                sound(SoundEvents.SHIELD_BLOCK, 0.8F, 0.6F);
            } else if (t == 34 || t == 56) {
                echo.setPos(p(0, 0, -0.6)); // back from the lunge
            } else if (t == 60) { // struck back twice: the wall falls, the double is gone
                wall(false);
                th.level().sendParticles(WHITE, at.getX() + 0.5, at.getY() + 2.5, at.getZ() + 2.5, 30, 1.2, 1.0, 0.2, 0.0);
                th.level().sendParticles(BLACK, at.getX() + 0.5, at.getY() + 2.5, at.getZ() + 2.5, 30, 1.2, 1.0, 0.2, 0.0);
                th.level().sendParticles(ParticleTypes.SQUID_INK, twin.getX(), twin.getY() + 1.0, twin.getZ(), 20, 0.3, 0.6, 0.3, 0.02);
                sound(SoundEvents.GLASS_BREAK, 1.0F, 0.5F);
                gone(twin);
            }
            if (t > 64 && t <= 100) {
                echo.setPos(along(p(0, 0, -0.6), p(0, 0, 3.5), t, 64, 100));
            }
        }

        void remove() {
            gone(echo);
            gone(twin);
        }
    }

    /** A strike marked on the floor round them: staying in the marks, the echo is struck; the next time it steps out of them. */
    static final class Strike extends Lesson {
        private TrialEcho echo;

        int period() {
            return 160;
        }

        void build() {
            pad(-3, -3, 3, 3, white());
        }

        private List<int[]> cells() {
            final List<int[]> cells = new ArrayList<>();
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    cells.add(new int[] {at.getX() + dx, at.getZ() + dz - 1});
                }
            }
            return cells;
        }

        void tick(int t, int total) {
            if (echo == null || echo.isRemoved()) {
                echo = th.echo(p(0, 0, -1), p(0, 0, 3));
            }
            final int k = t % 80;
            final boolean stays = t < 80; // first it stays (and is struck), then it steps out
            if (k == 0) {
                echo.setPos(p(0, 0, -1));
            } else if (k == 10) {
                th.trial().warn(at.getY() + 1, 1, cells(), 30);
            } else if (!stays && k > 14 && k <= 30) {
                echo.setPos(along(p(0, 0, -1), p(0, 0, 2.2), k, 14, 30));
            } else if (k == 40) {
                th.trial().burst(cells(), 1, at.getY() + 1);
                if (stays) {
                    th.hurt(echo, echo.position().add(0, -1, 0.01));
                }
            } else if (!stays && k > 60 && k <= 76) {
                echo.setPos(along(p(0, 0, 2.2), p(0, 0, -1), k, 60, 76));
            }
        }

        void remove() {
            gone(echo);
        }
    }

    /** A memory: something they carried, floating on its own piece; reached, a lost control is won back (light). */
    static final class Memory extends Lesson {
        private TrialEcho echo;
        private ItemEntity memory;

        int period() {
            return 110;
        }

        void build() {
            pad(-1, -4, 1, -2, white());
            pad(-1, 2, 1, 4, white());
        }

        void tick(int t, int total) {
            if (echo == null || echo.isRemoved()) {
                echo = th.echo(p(0, 0, -3), p(0, 0, 3));
            }
            if (t == 0) {
                gone(memory);
                memory = th.memory(p(0, 0.7, 3));
                echo.setPos(p(0, 0, -3));
            }
            if (t <= 34) {
                echo.setPos(along(p(0, 0, -3), p(0, 0, 2.6), t, 6, 34).add(0, t > 14 && t < 22 ? 0.4 : 0, 0));
            } else if (t == 36) { // remembered: light bursts from it, green round the echo, whole again for a while
                if (memory != null) {
                    th.level().sendParticles(ParticleTypes.END_ROD, memory.getX(), memory.getY(), memory.getZ(), 25, 0.3, 0.3, 0.3, 0.08);
                    gone(memory);
                }
                th.level().sendParticles(ParticleTypes.HAPPY_VILLAGER, echo.getX(), echo.getY() + 1.0, echo.getZ(), 16, 0.5, 0.7, 0.5, 0.0);
                sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, 0.8F);
            } else if (t > 36 && t < 90 && t % 4 == 0) {
                final double a = t * 0.4;
                th.level().sendParticles(ParticleTypes.END_ROD, echo.getX() + Math.cos(a) * 0.7, echo.getY() + 1.0, echo.getZ() + Math.sin(a) * 0.7, 1, 0, 0.1, 0, 0);
            }
        }

        void remove() {
            gone(echo);
            gone(memory);
        }
    }

    /**
     * The duel: the double dashes along a marked line. Staying on the line, the echo is run through; the next time it
     * steps aside, and while the double stands open (white motes) its blow lands.
     */
    static final class Duel extends Lesson {
        private TrialEcho echo;
        private FractureDouble twin;

        int period() {
            return 220;
        }

        void build() {
            pad(-3, -4, 3, 4, white());
        }

        private List<int[]> line() {
            final List<int[]> cells = new ArrayList<>();
            for (int dz = -3; dz <= 3; dz++) {
                cells.add(new int[] {at.getX(), at.getZ() + dz});
            }
            return cells;
        }

        void tick(int t, int total) {
            if (echo == null || echo.isRemoved()) {
                echo = th.armed(p(0, 0, -2), p(0, 0, 3));
            }
            if (twin == null || twin.isRemoved()) {
                twin = th.twin(p(0, 0, 3), p(0, 0, -3));
            }
            if (t < 90) { // staying on its line: run through
                if (t == 0) {
                    echo.setPos(p(0, 0, -2));
                    twin.setPos(p(0, 0, 3));
                } else if (t == 8) {
                    th.trial().warn(at.getY() + 1, 1, line(), 22);
                } else if (t > 30 && t <= 36) {
                    twin.setPos(along(p(0, 0, 3), p(0, 0, -3), t, 30, 36));
                    if (t == 31) {
                        sound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.5F);
                    } else if (t == 34) {
                        th.hurt(echo, twin.position());
                    }
                } else if (t > 60 && t <= 80) {
                    twin.setPos(along(p(0, 0, -3), p(0, 0, 3), t, 60, 80));
                    echo.setPos(along(echo.position(), p(0, 0, -2), t, 60, 80));
                }
                twin.face(echo.position());
                return;
            }
            final int k = t - 90; // the next time: out of its way, then the blow while it stands open
            if (k == 0) {
                echo.setPos(p(0, 0, -2));
                twin.setPos(p(0, 0, 3));
            } else if (k == 8) {
                th.trial().warn(at.getY() + 1, 1, line(), 22);
            } else if (k > 10 && k <= 22) {
                echo.setPos(along(p(0, 0, -2), p(1.8, 0, -2), k, 10, 22));
            } else if (k > 30 && k <= 36) {
                twin.setPos(along(p(0, 0, 3), p(0, 0, -3), k, 30, 36));
                if (k == 31) {
                    sound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.5F);
                }
            } else if (k > 38 && k < 80) { // open: white motes round it
                th.level().sendParticles(ParticleTypes.END_ROD, twin.getX(), twin.getY() + 1.0, twin.getZ(), 2, 0.3, 0.5, 0.3, 0.01);
                if (k > 40 && k <= 50) {
                    echo.setPos(along(p(1.8, 0, -2), p(0.9, 0, -2.6), k, 40, 50));
                }
                echo.faceTowards(twin.position());
                if (k == 54) {
                    th.strike(echo, twin, 0.2);
                    sound(SoundEvents.AMETHYST_BLOCK_BREAK, 1.3F, 0.65F);
                } else if (k == 58) {
                    echo.setPos(p(0.9, 0, -2.6));
                }
            } else if (k > 100 && k <= 120) {
                twin.setPos(along(p(0, 0, -3), p(0, 0, 3), k, 100, 120));
                echo.setPos(along(p(0.9, 0, -2.6), p(0, 0, -2), k, 100, 120));
            }
            twin.face(echo.position());
        }

        void remove() {
            gone(echo);
            gone(twin);
        }
    }

    /** The double hunting: let it reach them and they are struck; strike it as it closes in and it reels back. */
    static final class Hunt extends Lesson {
        private TrialEcho echo;
        private FractureDouble twin;

        int period() {
            return 200;
        }

        void build() {
            pad(-2, -4, 2, 4, white());
        }

        void tick(int t, int total) {
            if (echo == null || echo.isRemoved()) {
                echo = th.armed(p(0, 0, -3), p(0, 0, 3));
            }
            if (twin == null || twin.isRemoved()) {
                twin = th.twin(p(0, 0, 3.5), p(0, 0, -3));
            }
            final int k = t % 100;
            final boolean fights = t >= 100; // first it lets it come, then strikes it back
            if (k == 0) {
                echo.setPos(p(0, 0, -3));
                twin.setPos(p(0, 0, 3.5));
            } else if (k > 10 && k <= 40) { // it stalks closer
                final Vec3 stop = fights ? p(0, 0, -1.3) : p(0, 0, -2.2);
                twin.setPos(along(p(0, 0, 3.5), stop, k, 10, 40));
            } else if (k == 42) {
                if (fights) {
                    th.strike(echo, twin, 1.6); // struck back: it reels away
                    sound(SoundEvents.AMETHYST_BLOCK_BREAK, 1.0F, 0.6F);
                } else {
                    th.hurt(echo, twin.position()); // it reached them
                }
            } else if (k == 46 && fights) {
                echo.setPos(p(0, 0, -3));
            } else if (k > 70 && k <= 90) {
                twin.setPos(along(twin.position(), p(0, 0, 3.5), k, 70, 90));
                if (!fights) {
                    echo.setPos(along(echo.position(), p(0, 0, -3), k, 70, 90));
                }
            }
            twin.face(echo.position());
            echo.faceTowards(twin.position());
        }

        void remove() {
            gone(echo);
            gone(twin);
        }
    }

    /** Noise: running makes rings of noise and the double comes for it; sneaking past, it hears nothing. */
    static final class Noise extends Lesson {
        private TrialEcho echo;
        private FractureDouble twin;

        int period() {
            return 200;
        }

        void build() {
            pad(-2, -4, 2, 4, white());
        }

        private void ring(Vec3 c, double r) {
            for (int k = 0; k < 18; k++) {
                final double a = k * Mth.TWO_PI / 18;
                th.level().sendParticles(WHITE, c.x + Math.cos(a) * r, c.y + 0.1, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
            }
        }

        void tick(int t, int total) {
            if (echo == null || echo.isRemoved()) {
                echo = th.echo(p(-1, 0, -3.5), p(0, 0, 3));
            }
            if (twin == null || twin.isRemoved()) {
                twin = th.twin(p(0, 0, 3.5), p(0, 0, 8));
                twin.setGlowingTag(true);
            }
            if (t == 0) {
                echo.crouch(false);
                echo.setPos(p(-1, 0, -3.5));
                twin.setPos(p(0, 0, 3.5));
                twin.face(p(0, 0, 8)); // looking away
            }
            if (t < 100) { // running: noise rings, it turns and comes
                if (t > 4 && t <= 30) {
                    echo.setPos(along(p(-1, 0, -3.5), p(1, 0, 0), t, 4, 30).add(0, (t / 5) % 2 == 0 ? 0.25 : 0, 0));
                    if (t % 5 == 0) {
                        ring(echo.position(), 1.4);
                        sound(SoundEvents.STONE_STEP, 0.6F, 0.8F);
                    }
                }
                if (t >= 30 && t <= 46) {
                    twin.face(echo.position());
                    twin.setPos(along(p(0, 0, 3.5), echo.position().add(0, 0, 0.9), t, 32, 46));
                } else if (t == 48) { // caught: it is struck
                    th.hurt(echo, twin.position());
                    th.level().sendParticles(ParticleTypes.SQUID_INK, twin.getX(), twin.getY() + 1.0, twin.getZ(), 15, 0.3, 0.6, 0.3, 0.02);
                } else if (t == 70) {
                    twin.setPos(p(0, 0, 3.5));
                    twin.face(p(0, 0, 8));
                    echo.setPos(p(-1, 0, -3.5));
                }
            } else { // sneaking: no rings, it does not turn
                if (t == 100) {
                    echo.crouch(true);
                }
                if (t > 104 && t <= 180) {
                    echo.setPos(along(p(-1, 0, -3.5), p(-1.5, 0, 3.5), t, 104, 180));
                    if (t % 10 == 0) {
                        final double a = t * 0.05;
                        for (int k = 2; k <= 6; k += 2) { // its gaze, sweeping ahead of it, away from them
                            th.level().sendParticles(ParticleTypes.END_ROD, twin.getX() + Math.sin(a) * 0.5 * k, twin.getY() + 1.5, twin.getZ() + k, 1, 0.1, 0.1, 0.1, 0.0);
                        }
                    }
                }
            }
        }

        void remove() {
            gone(echo);
            gone(twin);
        }
    }

    /** Its gaze: standing in front of it the echo is seen and it comes; sneaking in front of it, it is not. */
    static final class Gaze extends Lesson {
        private TrialEcho echo;
        private FractureDouble twin;

        int period() {
            return 180;
        }

        void build() {
            pad(-2, -4, 2, 4, white());
        }

        void tick(int t, int total) {
            if (echo == null || echo.isRemoved()) {
                echo = th.echo(p(0, 0, -2), p(0, 0, 3));
            }
            if (twin == null || twin.isRemoved()) {
                twin = th.twin(p(0, 0, 3), p(0, 0, -3));
                twin.setGlowingTag(true);
            }
            final double sweep = Math.sin(t * 0.06) * 0.9; // its gaze swinging across them
            final Vec3 look = new Vec3(Math.sin(sweep), 0, -Math.cos(sweep));
            if (t == 0 || t == 90) {
                echo.crouch(t == 90);
                echo.setPos(p(0, 0, -2));
                twin.setPos(p(0, 0, 3));
            }
            final boolean seenPart = t < 90;
            if (seenPart && t > 30 && t <= 46) { // seen standing: it comes
                twin.face(echo.position());
                twin.setPos(along(p(0, 0, 3), p(0, 0, -1.1), t, 30, 46));
                if (t == 46) {
                    th.hurt(echo, twin.position()); // seen, and struck
                }
            } else if (!(seenPart && t > 46)) {
                twin.face(twin.position().add(look));
                if (t % 3 == 0) {
                    for (int k = 2; k <= 6; k += 2) {
                        th.level().sendParticles(ParticleTypes.END_ROD, twin.getX() + look.x * k, twin.getY() + 1.5, twin.getZ() + look.z * k, 1, 0.1, 0.1, 0.1, 0.0);
                    }
                }
            }
        }

        void remove() {
            gone(echo);
            gone(twin);
        }
    }

    /** The fall: through an opening rimmed in white the echo drops on; landing on the black, the black gives way. */
    static final class Openings extends Lesson {
        private TrialEcho through, onto;

        int period() {
            return 140;
        }

        void build() {
            opening(); // a black floor with an opening rimmed in white
            for (int z = -4; z <= 4; z++) { // a floor of light below, where it lands
                for (int x = -4; x <= 4; x++) {
                    th.block(b(x, -9, z), Blocks.SMOOTH_QUARTZ.defaultBlockState());
                }
            }
        }

        /** The black floor with a 2x2 opening on one side, rimmed in white; the black on the other side whole; a grey border round it all. */
        private void opening() {
            for (int z = -5; z <= 5; z++) {
                for (int x = -5; x <= 5; x++) {
                    final boolean border = Math.abs(x) == 5 || Math.abs(z) == 5;
                    final boolean hole = x >= -3 && x <= -2 && z >= -1 && z <= 0;
                    final boolean rim = x >= -4 && x <= -1 && z >= -2 && z <= 1 && !hole;
                    th.change(b(x, 0, z), border ? grey() : hole ? Blocks.AIR.defaultBlockState() : rim ? white() : Trial.black());
                }
            }
        }

        void tick(int t, int total) {
            if (through == null || through.isRemoved()) {
                through = th.echo(p(-2, 7, -0), p(0, 0, 3));
            }
            if (onto == null || onto.isRemoved()) {
                onto = th.echo(p(2.5, 7, 0.5), p(0, 0, 3));
            }
            if (t == 0) {
                opening();
                through.setPos(p(-2, 7, 0));
                onto.setPos(p(2.5, 7, 0.5));
            }
            // falling slowly (the trial holds them up a little): through the opening, on down to the light
            if (t > 4 && t <= 90) {
                through.setPos(p(-2, 7 - (t - 4) * 0.19, 0));
            }
            if (t > 4 && t <= 40) {
                onto.setPos(p(2.5, 7 - (t - 4) * 0.19, 0.5));
            } else if (t == 41) { // landed on black: a control lost (it is struck), the black gives way
                th.hurt(onto, onto.position().add(0, -1, 0));
                for (int z = -1; z <= 2; z++) {
                    for (int x = 1; x <= 4; x++) {
                        th.change(b(x, 0, z), Blocks.AIR.defaultBlockState());
                    }
                }
                th.level().sendParticles(BLACK, at.getX() + 3, at.getY() + 0.8, at.getZ() + 1, 30, 1.0, 0.2, 1.0, 0.0);
            } else if (t > 41 && t <= 90) {
                onto.setPos(p(2.5, 0.1 - (t - 41) * 0.19, 0.5));
            }
        }

        void remove() {
            gone(through);
            gone(onto);
        }
    }
}
