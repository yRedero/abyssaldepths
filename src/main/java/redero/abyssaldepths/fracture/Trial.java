package redero.abyssaldepths.fracture;

import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.network.EffectPayload;

/**
 * A trial of Fracturing ({@link FractureTrials}): an arena built for one player in the trial dimension, its
 * own way to be passed and failed. It starts with {@link #ORIENT_TICKS} to look around (the arena shows what
 * is wanted by what it does), then runs until it is passed or failed. All in black and white.
 */
public abstract class Trial {
    /** Ticks to look around before the trial starts in earnest (from when the player's screen has cleared). */
    public static final int ORIENT_TICKS = 40;
    /** Ticks it waits at most for the player's screen to clear. */
    static final int READY_TICKS = 300;
    /** Whether the player's screen has cleared (the looking round starts then); ticks waited for it. */
    boolean ready;
    private int waited;

    public enum Result { RUNNING, PASSED, FAILED }

    /** The trials, with how often each comes (the simpler, the more often). */
    public enum Type {
        /** The first three only by command now; Fracturing brings the Reach. */
        COLLAPSE(0), BREAKDOWN(0), CORE(0), REACH(1), DUEL(1), HUSH(1), FALL(1);

        final int weight;

        Type(int weight) {
            this.weight = weight;
        }

        Trial create(ServerPlayer player, ServerLevel level, BlockPos origin, int scars) {
            return switch (this) {
                case COLLAPSE -> new CollapseTrial(player, level, origin, scars);
                case BREAKDOWN -> new BreakdownTrial(player, level, origin, scars);
                case CORE -> new CoreTrial(player, level, origin, scars);
                case REACH -> new ReachTrial(player, level, origin, scars, false);
                case DUEL -> new ReachTrial(player, level, origin, scars, true);
                case HUSH -> new HushTrial(player, level, origin, scars);
                case FALL -> new FallTrial(player, level, origin, scars);
            };
        }

        static Type pick(net.minecraft.util.RandomSource random) {
            int total = 0;
            for (Type type : values()) {
                total += type.weight;
            }
            int roll = random.nextInt(total);
            for (Type type : values()) {
                roll -= type.weight;
                if (roll < 0) {
                    return type;
                }
            }
            return COLLAPSE;
        }
    }

    protected final ServerPlayer player;
    protected final ServerLevel level;
    protected final BlockPos origin;
    /** The player's scars: the corruption has an easier time with the scarred. */
    protected final int scars;
    protected int age;

    protected Trial(ServerPlayer player, ServerLevel level, BlockPos origin, int scars) {
        this.player = player;
        this.level = level;
        this.origin = origin;
        this.scars = scars;
    }

    public abstract Type type();

    /** What the player carried (a few things, copies), before it was kept away: some trials make use of it. */
    void remember(List<net.minecraft.world.item.ItemStack> things) {
    }

    /** Builds the arena round the origin. */
    abstract void build();

    /** Where the player arrives, and which way they face. */
    abstract Vec3 spawn();

    abstract float spawnYaw();

    /** The box the arena takes (cleared after). */
    abstract BoundingBox bounds();

    /** A tick of the trial: looking round first, then in earnest. */
    final Result tick() {
        if (!ready && ++waited < READY_TICKS) {
            return Result.RUNNING; // the player does not see the arena yet
        }
        age++;
        if (age <= ORIENT_TICKS) {
            orient(age);
            return Result.RUNNING;
        }
        if (ward && age % 5 == 0) { // the ward: pale motes circling them
            final double a = age * 0.4;
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, player.getX() + Math.cos(a) * 0.7, player.getY() + 1.0, player.getZ() + Math.sin(a) * 0.7,
                    1, 0, 0.1, 0, 0);
        }
        return run(age - ORIENT_TICKS);
    }

    /** While the player looks round: the arena shows what it wants. */
    protected void orient(int t) {
    }

    /** A tick in earnest (t from 1). */
    protected abstract Result run(int t);

    /** Empties the arena (and what was in it). */
    void clear() {
        final BoundingBox box = bounds();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    if (!level.getBlockState(pos.set(x, y, z)).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2 | 16);
                    }
                }
            }
        }
        level.getEntities((net.minecraft.world.entity.Entity) null, net.minecraft.world.phys.AABB.of(box).inflate(4),
                e -> !(e instanceof net.minecraft.world.entity.player.Player)).forEach(e -> e.discard());
    }

    /** The walls' block: pure black, no detail. */
    static net.minecraft.world.level.block.state.BlockState black() {
        return redero.abyssaldepths.AbyssalDepths.FRACTURE_BLACK.get().defaultBlockState();
    }

    /** Held by a memory: the next hit comes to nothing (pale motes circle the player meanwhile). */
    protected boolean ward;

    /** Whether a hit is warded off (the ward spent: a burst of light, a word). */
    protected boolean wardOff() {
        if (!ward) {
            return false;
        }
        ward = false;
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.4, 0.6, 0.4, 0.12);
        level.playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE, net.minecraft.sounds.SoundSource.PLAYERS, 1.2F, 1.2F);
        say("ward");
        return true;
    }

    /** The corruption speaks to the player (a line on their screen). */
    protected void say(String key) {
        PacketDistributor.sendToPlayer(player, new EffectPayload("trial_voice/" + key, 0));
    }

    /** A thing the player carried, floating where they can reach it (glowing, so it shows in the dark): a memory. */
    protected net.minecraft.world.entity.item.ItemEntity memory(net.minecraft.world.item.ItemStack stack, Vec3 at) {
        final net.minecraft.world.entity.item.ItemEntity memory = new net.minecraft.world.entity.item.ItemEntity(level, at.x, at.y, at.z, stack);
        memory.setNoGravity(true);
        memory.setNeverPickUp();
        memory.setUnlimitedLifetime();
        memory.setDeltaMovement(Vec3.ZERO);
        memory.setGlowingTag(true);
        level.addFreshEntity(memory);
        return memory;
    }

    /** Things to remember: what they carried (copies), their own head where there is too little. */
    protected static List<net.minecraft.world.item.ItemStack> remembered(ServerPlayer player, List<net.minecraft.world.item.ItemStack> things, int count) {
        final List<net.minecraft.world.item.ItemStack> list = new java.util.ArrayList<>(things);
        while (list.size() < count) {
            final net.minecraft.world.item.ItemStack head = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD);
            head.set(net.minecraft.core.component.DataComponents.PROFILE, new net.minecraft.world.item.component.ResolvableProfile(player.getGameProfile()));
            list.add(head);
        }
        return list;
    }

    /** Tells the player's screen how the trial stands (three numbers, the trial's own; a few times a second). */
    protected void hud(float a, float b, float c) {
        if (age % 4 == 0) {
            PacketDistributor.sendToPlayer(player, new EffectPayload(String.format(Locale.ROOT, "trial_hud/%s/%.3f/%.3f/%.3f", type().name(), a, b, c), 0));
        }
    }

    /**
     * Marks cells of the floor for a strike, on the player's screen: squares of {@code size} blocks, their
     * middles given (x, z), at height y; the strike falls after {@code warn} ticks.
     */
    protected void warn(double y, int size, List<int[]> cells, int warn) {
        final StringBuilder list = new StringBuilder();
        for (int[] cell : cells) {
            list.append(cell[0]).append(',').append(cell[1]).append(';');
        }
        PacketDistributor.sendToPlayer(player, new EffectPayload(String.format(Locale.ROOT, "trial_strike/%.2f/%d/%d/%s", y, size, warn, list), 0));
    }

    /** Whether the player stands in one of these cells (square, {@code size} wide, middles x, z) low enough to be struck. */
    protected boolean inCells(List<int[]> cells, int size, double floorTop, double reach) {
        if (player.getY() - floorTop > reach) {
            return false;
        }
        for (int[] cell : cells) {
            if (Math.abs(player.getX() - (cell[0] + 0.5)) <= size / 2.0 + 0.3 && Math.abs(player.getZ() - (cell[1] + 0.5)) <= size / 2.0 + 0.3) {
                return true;
            }
        }
        return false;
    }

    /** A strike landing: shards of white and black bursting up from the cells, a crack of sound. */
    protected void burst(List<int[]> cells, int size, double y) {
        final net.minecraft.core.particles.DustParticleOptions white = new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(1, 1, 1), 1.4F);
        final net.minecraft.core.particles.DustParticleOptions black = new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(0, 0, 0), 1.4F);
        for (int[] cell : cells) {
            level.sendParticles(player, white, true, cell[0] + 0.5, y + 0.6, cell[1] + 0.5, 6, size * 0.3, 0.5, size * 0.3, 0.0);
            level.sendParticles(player, black, true, cell[0] + 0.5, y + 0.6, cell[1] + 0.5, 4, size * 0.3, 0.5, size * 0.3, 0.0);
        }
        level.playSound(null, cells.isEmpty() ? player.blockPosition() : BlockPos.containing(cells.get(0)[0], y, cells.get(0)[1]),
                net.minecraft.sounds.SoundEvents.GLASS_BREAK, net.minecraft.sounds.SoundSource.HOSTILE, 0.8F, 0.6F);
    }

    /** A hit on the player: thrown up and back a little, a hard sound, the screen told. */
    protected void hit(Vec3 from) {
        hit(from, 0.6);
    }

    /** A hit, throwing them so far (over the void, gently: a hit should not be a fall too). */
    protected void hit(Vec3 from, double push) {
        final Vec3 away = player.position().subtract(from);
        final Vec3 flat = new Vec3(away.x, 0, away.z);
        final Vec3 dir = flat.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : flat.normalize();
        player.setDeltaMovement(dir.x * push, 0.3 + push * 0.25, dir.z * push);
        player.hurtMarked = true;
        level.playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_BREAK, net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 0.5F);
        PacketDistributor.sendToPlayer(player, new EffectPayload("trial_hit", 0));
    }
}
