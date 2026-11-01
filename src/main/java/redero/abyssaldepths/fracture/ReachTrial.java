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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Reaching one's body: the trial of Fracturing. The land the player stood in, copied in black and white and broken
 * into floating pieces, leads back to where they stood; there their own body stands, frozen. The pieces are not all
 * to be trusted: some crumble once stepped on, some fade out and back on a rhythm (glass just before they go), some
 * are a single block. Their corrupted double steps out of the body and comes for them: it hunts them, marks strikes
 * round them or on the way ahead, now and then stands in the way; at the two gates (a black wall across the way) it
 * stands guard, and the wall only falls once it has been struck back twice. A blow staggers it; it cannot be beaten.
 * At the body (a wide piece, the copy of where they stood) the corruption's heart rises out of it: foretold, it sends
 * out rings (jump them) or strikes (step out of them), then opens; one blow per opening cracks it. Broken, the body
 * can be touched, and the player is back in it. Failed by a fall or by the {@link #HITS}th hit; the corruption
 * speaks to them as it goes, and the more control they lose, the more it shows (cracks over their screen; with one
 * left, their body slipping). Off the way, on small pieces of their own, float things they carried (or their own
 * head): reaching one, they remember, and win back a lost control. Two thirds of the way along, the double fights
 * them on a wide piece walled round: it dashes along a marked line, then stands open a moment; struck
 * {@link #DUEL_BLOWS} times while open, it falters and is gone till the end. Where it means to appear ahead is
 * marked first, and it appears on the far side, never on top of them.
 */
final class ReachTrial extends Trial {
    static final int ISLANDS = 22, FIGHT_ISLANDS = 4, HITS = 3, DUEL_BLOWS = 4, FIGHT_BLOWS = 4, SECOND_BLOWS = 4;
    /** The fight instead of the way: a short way to a wide piece, the body at its far side, the double to be broken there. */
    private final boolean fight;
    /** How far apart (in the real world) the places the pieces are copied from lie. */
    private static final int SAMPLE_STEP = 4;
    /** The heart: blows it takes; its parts. */
    private static final int HEART = 2, WARN = 20, SHOCKWAVE = 50, BARRAGE = 56, OPEN = 40;
    private static final int CRUMBLE_TICKS = 26, PHASE_TICKS = 80, PHASE_SOLID = 56, PHASE_WARN = 20, GATE_BLOWS = 2;
    /** The tones the copy is made of, dark to light. */
    private static final Block[] TONES = {Blocks.POLISHED_DEEPSLATE, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.CALCITE, Blocks.WHITE_CONCRETE,
            Blocks.SMOOTH_QUARTZ};

    /** A piece of the way: holds, crumbles once stepped on, phases, or is a gate. */
    private enum Kind { HOLD, CRUMBLE, PHASE, GATE, DUEL }

    private static final class Island {
        final BlockPos middle;
        final int half, offset;
        final Kind kind;
        long stepped = -1;
        boolean gone, open;
        int shown;
        /** Its blocks as copied (to bring a phasing one back). */
        final List<BlockPos> blocks = new ArrayList<>();
        final List<BlockState> states = new ArrayList<>();

        Island(BlockPos middle, int half, Kind kind, int offset) {
            this.middle = middle;
            this.half = half;
            this.kind = kind;
            this.offset = offset;
        }

        boolean under(ServerPlayer player) {
            return Math.abs(player.getX() - (middle.getX() + 0.5)) <= half + 0.8 && Math.abs(player.getZ() - (middle.getZ() + 0.5)) <= half + 0.8
                    && Math.abs(player.getY() - (middle.getY() + 1)) < 0.6;
        }
    }

    private final List<Island> islands = new ArrayList<>();
    private Island end;
    private FracturedBody body;
    private FractureDouble twin;
    private FractureCore heart;
    private int hits, invulnerable, stage, heartPart, partStart, heartNext = FractureCore.SHOCKWAVE, lastLeft = HEART;
    private int blockUntil, staggerUntil, nextEvent = 100, strikeAt = -1, maxX = 10, gateBlows;
    private Island guarding;
    private double strikeFloor;
    private final List<int[]> strikeCells = new ArrayList<>();
    /** Things they carried, to remember; where they float. */
    private List<net.minecraft.world.item.ItemStack> things = List.of();
    private final List<net.minecraft.world.entity.item.ItemEntity> memories = new ArrayList<>();
    /** Where the double means to appear ahead, and when. */
    private Island appearing;
    private int appearAt = -1, graceUntil;
    /** The duel: its piece, whether it is on (and done), its part and since when, the dash, blows landed. */
    private Island duel;
    private boolean dueling, duelDone;
    private int duelPart, duelStart, duelBlows, pattern, windup;
    private boolean secondDash;
    /** The fight's second stage (the piece crumbling at its rim, the double blinking and splitting); its decoys and their dashes. */
    private boolean secondStage;
    private final List<FractureDouble> decoys = new ArrayList<>();
    private final List<Vec3> decoyDirs = new ArrayList<>();
    private Vec3 blinkTo;
    private int crumbleAt = -1, pauseUntil = -1;
    /** Where the player was last tick, and how they are moving (per tick, as seen from here). */
    private Vec3 lastPos, moving = Vec3.ZERO;
    private Vec3 dashDir = Vec3.ZERO;
    private final List<BlockPos> barriers = new ArrayList<>();

    ReachTrial(ServerPlayer player, ServerLevel level, BlockPos origin, int scars, boolean fight) {
        super(player, level, origin, scars);
        this.fight = fight;
    }

    @Override
    public Type type() {
        return fight ? Type.DUEL : Type.REACH;
    }

    /** Where a part of the way is (for the dev tools): "gate", "duel", "memory", "body". */
    Vec3 place(String what) {
        return switch (what) {
            case "gate" -> islands.stream().filter(i -> i.kind == Kind.GATE).findFirst().map(i -> Vec3.atBottomCenterOf(i.middle.offset(0, 1, -1))).orElse(null);
            case "duel" -> duel == null ? null : Vec3.atBottomCenterOf(duel.middle.offset(0, 1, fight ? -3 : -duel.half + 1));
            case "memory" -> memories.isEmpty() ? null : memories.get(0).position().add(0, -0.6, -2.5);
            default -> body.position().add(0, 0, what.equals("touch") ? -0.8 : -3.5);
        };
    }

    @Override
    void remember(List<net.minecraft.world.item.ItemStack> things) {
        this.things = things;
    }

    /** A block's tone in the copy (by its colour's brightness), steps darker. */
    private static BlockState tone(BlockState real, ServerLevel from, BlockPos at, int darker) {
        final int rgb = real.getMapColor(from, at).col;
        final float lum = (0.3F * (rgb >> 16 & 255) + 0.59F * (rgb >> 8 & 255) + 0.11F * (rgb & 255)) / 255.0F;
        final int k = lum < 0.2F ? 0 : lum < 0.33F ? 1 : lum < 0.46F ? 2 : lum < 0.58F ? 3 : lum < 0.72F ? 4 : 5;
        return TONES[Math.max(0, k - darker)].defaultBlockState();
    }

    private void set(Island island, BlockPos at, BlockState state) {
        level.setBlock(at, state, 2);
        island.blocks.add(at);
        island.states.add(state);
    }

    /** Copies a piece of the real land (round real x, z) onto an island: its top as the floor, two layers under it, the rim a step darker. */
    private void copy(ServerLevel from, int realX, int realZ, Island island) {
        final BlockPos.MutableBlockPos real = new BlockPos.MutableBlockPos();
        for (int dz = -island.half; dz <= island.half; dz++) {
            for (int dx = -island.half; dx <= island.half; dx++) {
                final int x = realX + dx, z = realZ + dz;
                final int top = from.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                real.set(x, top, z);
                BlockState state = from.getBlockState(real);
                if (state.isAir()) {
                    state = Blocks.STONE.defaultBlockState(); // nothing there (the void, an unloaded edge): stone's grey
                }
                final boolean rim = Math.abs(dx) == island.half || Math.abs(dz) == island.half;
                final BlockPos at = island.middle.offset(dx, 0, dz);
                if (island.kind == Kind.PHASE) { // a phasing piece is glass, so it reads at a glance: not to stand on long
                    set(island, at, (rim ? Blocks.LIGHT_GRAY_STAINED_GLASS : Blocks.WHITE_STAINED_GLASS).defaultBlockState());
                    continue;
                }
                set(island, at, tone(state, from, real, rim ? 1 : 0));
                set(island, at.below(), tone(state, from, real, 1));
                set(island, at.below(2), tone(state, from, real, 2));
                if (rim && island.kind != Kind.PHASE && island.half > 1) { // what stood on the edge (a trunk, a wall), a block high, like a ruin
                    final BlockState above = from.getBlockState(real.set(x, top + 1, z));
                    if (above.isSolidRender(from, real) && (dx + dz) % 2 == 0) {
                        set(island, at.above(), tone(above, from, real, 0));
                    }
                }
            }
        }
    }

    /**
     * A gate: a black wall across the far edge of its island, four high, and past it on every side (round it and
     * over the void, above and below) a barrier no one sees: there is no way past but through the wall.
     */
    private void wall(Island island, boolean close) {
        final int z = island.middle.getZ() + island.half;
        final BlockState barrier = close ? Blocks.BARRIER.defaultBlockState() : Blocks.AIR.defaultBlockState(), black = close ? Trial.black() : Blocks.AIR.defaultBlockState();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = origin.getX() - maxX - 6; x <= origin.getX() + maxX + 6; x++) {
            for (int y = origin.getY() - 8; y <= origin.getY() + 16; y++) { // (within the bounds: cleared after)
                pos.set(x, y, z);
                final boolean onIsland = Math.abs(x - island.middle.getX()) <= island.half && y > island.middle.getY() && y <= island.middle.getY() + 4;
                if (onIsland) { // black, framed in light grey so it shows against the dark
                    final boolean frame = Math.abs(x - island.middle.getX()) == island.half || y == island.middle.getY() + 4;
                    level.setBlock(pos, frame && close ? Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState() : black, 2);
                } else if (level.getBlockState(pos).isAir() || !close && level.getBlockState(pos).is(Blocks.BARRIER)) {
                    level.setBlock(pos, barrier, 2);
                }
            }
        }
    }

    /** The duel's piece walled round (unseen) while it lasts. */
    private void ring(boolean close) {
        if (close) {
            final int r = duel.half + 1;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    for (int dy = 1; dy <= 4; dy++) {
                        final BlockPos pos = duel.middle.offset(dx, dy, dz);
                        if (level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), 2);
                            barriers.add(pos);
                        }
                    }
                }
            }
        } else {
            for (BlockPos pos : barriers) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
            barriers.clear();
        }
    }

    @Override
    void build() {
        final ServerLevel from = player.serverLevel();
        final BlockPos home = player.blockPosition();
        // the way back runs from out in front of where they looked to where they stood
        final float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        final float fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
        final net.minecraft.util.RandomSource random = level.random;
        final int count = fight ? FIGHT_ISLANDS : ISLANDS;
        final int gate1 = fight ? -1 : ISLANDS / 3, duelAt = fight ? count - 1 : ISLANDS * 2 / 3, gate2 = fight ? -1 : ISLANDS - 4;
        int lateral = 0, rise = 0, z = 0, half = 2, swing = random.nextBoolean() ? 1 : -1;
        Kind before = Kind.HOLD;
        for (int i = 0; i < count; i++) {
            final boolean last = i == count - 1, gate = i == gate1 || i == gate2, dueling = i == duelAt;
            Kind kind = i < 2 || last && !fight ? Kind.HOLD : gate ? Kind.GATE : dueling ? Kind.DUEL
                    : random.nextInt(100) < 28 ? Kind.CRUMBLE : random.nextInt(100) < 25 ? Kind.PHASE : Kind.HOLD;
            if (kind == Kind.PHASE && before != Kind.HOLD) {
                kind = Kind.CRUMBLE; // a phasing piece is waited for on one that holds
            }
            final int next = last ? (fight ? 8 : 7) : gate ? 3 : dueling ? 5 : i == 0 ? 2 : kind == Kind.PHASE ? 1 : random.nextInt(5) == 0 ? 0 : random.nextInt(3) == 0 ? 1 : 2;
            if (i > 0) {
                final int gap = 2 + (rise > 0 || next == 0 ? 0 : random.nextInt(2));
                z += half + gap + next + 1;
                if (random.nextInt(4) == 0) {
                    swing = -swing; // the way bends the other way
                }
                lateral = Mth.clamp(lateral + swing * random.nextInt(3), -8, 8);
                if (!last && i > 1 && random.nextInt(3) == 0) {
                    rise = Mth.clamp(rise + (random.nextBoolean() ? 1 : -1), -4, 5);
                }
            }
            final Island island = new Island(origin.offset(last ? 0 : lateral, rise, z), next, kind, random.nextInt(PHASE_TICKS));
            final int back = count - 1 - i; // how far from where they stood its land lies
            copy(from, home.getX() + Math.round(fx * back * SAMPLE_STEP), home.getZ() + Math.round(fz * back * SAMPLE_STEP), island);
            islands.add(island);
            if (dueling) {
                duel = island;
            }
            maxX = Math.max(maxX, Math.abs(island.middle.getX() - origin.getX()) + island.half + 4);
            half = next;
            before = kind;
        }
        end = islands.get(count - 1);
        // things they carried, floating on small pieces off the way (their own head if they carried nothing)
        final List<net.minecraft.world.item.ItemStack> remembered = new ArrayList<>(things);
        while (remembered.size() < 3) {
            final net.minecraft.world.item.ItemStack head = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD);
            head.set(net.minecraft.core.component.DataComponents.PROFILE, new net.minecraft.world.item.component.ResolvableProfile(player.getGameProfile()));
            remembered.add(head);
        }
        final int[] beside = fight ? new int[0] : new int[] {4, 10, ISLANDS - 6};
        for (int m = 0; m < beside.length; m++) {
            final Island by = islands.get(beside[m]);
            if (by.kind != Kind.HOLD && by.kind != Kind.CRUMBLE) {
                continue;
            }
            final int side = random.nextBoolean() ? 1 : -1;
            final Island off = new Island(by.middle.offset(side * (by.half + 2 + 2), random.nextInt(2), 0), 1, Kind.HOLD, 0);
            copy(from, home.getX() + Math.round(fx * (ISLANDS - beside[m]) * SAMPLE_STEP) + side * 6, home.getZ() + Math.round(fz * (ISLANDS - beside[m]) * SAMPLE_STEP), off);
            maxX = Math.max(maxX, Math.abs(off.middle.getX() - origin.getX()) + 6);
            final net.minecraft.world.entity.item.ItemEntity memory = new net.minecraft.world.entity.item.ItemEntity(level, off.middle.getX() + 0.5, off.middle.getY() + 1.6,
                    off.middle.getZ() + 0.5, remembered.get(m));
            memory.setNoGravity(true);
            memory.setNeverPickUp();
            memory.setUnlimitedLifetime();
            memory.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(memory);
            memories.add(memory);
        }
        for (Island island : islands) {
            if (island.kind == Kind.GATE) {
                wall(island, true);
            }
        }
        // their own body, frozen, facing the way they come
        body = FracturedBody.mirror(player, level, Vec3.atBottomCenterOf(end.middle.offset(0, 1, fight ? end.half - 2 : 0)), 180.0F);
    }

    @Override
    Vec3 spawn() {
        return Vec3.atBottomCenterOf(islands.get(0).middle.above());
    }

    @Override
    float spawnYaw() {
        return 0.0F; // towards the body
    }

    @Override
    BoundingBox bounds() {
        return new BoundingBox(origin.getX() - maxX - 6, origin.getY() - 8, origin.getZ() - 8, origin.getX() + maxX + 6, origin.getY() + 16,
                end == null ? origin.getZ() + ISLANDS * 10 + 20 : end.middle.getZ() + end.half + 10);
    }

    private void voice(String key) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new redero.abyssaldepths.network.EffectPayload("trial_voice/" + key, 0));
    }

    private void struck(Vec3 from) {
        if (invulnerable > 0 || FractureTrials.unharmed) {
            return;
        }
        invulnerable = 30;
        hits++;
        hit(from, 0.2);
        control();
        if (hits < HITS) {
            voice(hits == 1 ? "hit1" : "hit2");
        }
    }

    /** Their screen told how much control is gone: cracks over it, and with one left their body slips. */
    private void control() {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new redero.abyssaldepths.network.EffectPayload("corruption/" + player.getId() + "/" + Math.min(95, Math.max(0, hits) * 33), 0));
    }

    /** The island the player is nearest, by index. */
    private int islandOf() {
        int best = 0;
        double near = Double.MAX_VALUE;
        for (int i = 0; i < islands.size(); i++) {
            final double d = islands.get(i).middle.distToCenterSqr(player.position());
            if (d < near) {
                near = d;
                best = i;
            }
        }
        return best;
    }

    @Override
    protected void orient(int t) {
        if (t == 1) { // the double steps out of the body and turns to them
            twin = FractureDouble.of(player, level, body.position().add(0, 0, -1.2));
            level.playSound(null, end.middle, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 0.6F, 0.5F);
            voice("start");
        }
        if (twin != null) {
            twin.face(player.position());
        }
        hud(1.0F, -1.0F, 0.0F);
    }

    @Override
    protected Result run(int t) {
        if (invulnerable > 0) {
            invulnerable--;
        }
        if (lastPos != null) {
            moving = moving.scale(0.5).add(player.position().subtract(lastPos).scale(0.5)); // smoothed a little
        }
        lastPos = player.position();
        final long now = level.getGameTime();
        pieces(t, now);
        for (net.minecraft.world.entity.item.ItemEntity memory : memories) { // remembered: a lost control won back
            if (!memory.isRemoved() && memory.position().distanceTo(player.position().add(0, 0.8, 0)) < 1.4) {
                level.sendParticles(ParticleTypes.END_ROD, memory.getX(), memory.getY(), memory.getZ(), 25, 0.3, 0.3, 0.3, 0.08);
                level.playSound(null, memory.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 0.8F);
                memory.discard();
                hits = Math.max(0, hits - 1);
                control();
                voice("remember");
            }
        }
        switch (stage) {
            case 0 -> hunt(t);
            case 1 -> heart(t);
            default -> {
                if (player.position().distanceTo(body.position()) < 1.6) { // touched: back in it
                    voice("pass");
                    return Result.PASSED;
                }
            }
        }
        if (stage == 0 && end.under(player) && (!fight || duelDone) && player.position().distanceTo(body.position()) < 7.0) { // at the body: the heart rises
            stage = 1;
            if (twin != null) {
                level.sendParticles(ParticleTypes.SQUID_INK, twin.getX(), twin.getY() + 1.0, twin.getZ(), 30, 0.3, 0.6, 0.3, 0.02);
                twin.discard();
                twin = null;
            }
            heart = new FractureCore(AbyssalDepths.FRACTURE_CORE.get(), level);
            heart.moveTo(body.getX(), end.middle.getY() + 1 + 2.5, body.getZ());
            heart.setLeft(HEART);
            level.addFreshEntity(heart);
            foretell(t, FractureCore.SHOCKWAVE);
            voice("heart");
        }
        final float second = heart != null ? heart.left() / (float) HEART
                : dueling ? 1.0F - duelBlows / (float) (!fight ? DUEL_BLOWS : secondStage ? SECOND_BLOWS : FIGHT_BLOWS) : -1.0F; // the heart's bar, or the double's in a duel
        hud(1.0F - hits / (float) HITS, second, stage);
        if (player.getY() < origin.getY() - 16 || hits >= HITS || t > 20 * 360) {
            return Result.FAILED;
        }
        return Result.RUNNING;
    }

    /** The pieces' ways: crumbling once stepped on, phasing on their rhythm. */
    private void pieces(int t, long now) {
        for (Island island : islands) {
            if (island.gone) {
                continue;
            }
            if (island.kind == Kind.PHASE) { // there, glass a moment, gone, back again
                final int at = (t + island.offset) % PHASE_TICKS;
                final int shown = at < PHASE_SOLID - PHASE_WARN ? 0 : at < PHASE_SOLID ? 1 : 2;
                if (shown == 1 && t % 4 == 0) { // about to go: it smokes and ticks
                    level.sendParticles(ParticleTypes.SMOKE, island.middle.getX() + 0.5, island.middle.getY() + 1.1, island.middle.getZ() + 0.5, 6,
                            island.half * 0.5 + 0.2, 0.05, island.half * 0.5 + 0.2, 0.0);
                    level.playSound(null, island.middle, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 0.4F, 0.5F + 0.4F * (at - (PHASE_SOLID - PHASE_WARN)) / PHASE_WARN);
                }
                if (shown != island.shown) {
                    island.shown = shown;
                    for (int k = 0; k < island.blocks.size(); k++) {
                        level.setBlock(island.blocks.get(k), shown == 0 ? island.states.get(k)
                                : shown == 1 ? Blocks.GRAY_STAINED_GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                    }
                    if (shown == 2) {
                        level.playSound(null, island.middle, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 0.5F, 0.5F);
                    }
                }
                continue;
            }
            if (island.kind != Kind.CRUMBLE) {
                continue;
            }
            if (island.stepped < 0 && island.under(player) && player.onGround()) {
                island.stepped = now;
                level.playSound(null, island.middle, SoundEvents.AMETHYST_BLOCK_STEP, SoundSource.HOSTILE, 1.0F, 0.6F);
            }
            if (island.stepped >= 0 && now - island.stepped == CRUMBLE_TICKS - 8) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(island.middle)), island.middle.getX() + 0.5,
                        island.middle.getY() + 1.0, island.middle.getZ() + 0.5, 25, island.half * 0.5 + 0.3, 0.2, island.half * 0.5 + 0.3, 0.0);
            }
            if (island.stepped >= 0 && now - island.stepped >= CRUMBLE_TICKS) {
                island.gone = true;
                for (BlockPos pos : island.blocks) {
                    final BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        FallingBlockEntity.fall(level, pos, state);
                    }
                }
                level.playSound(null, island.middle, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.0F, 0.5F);
            }
        }
    }

    /**
     * The double on the way. At a gate it stands guard before the wall until struck back twice; otherwise it hunts,
     * and every few seconds does one thing of three: strikes round the player, strikes on the way ahead, or is
     * suddenly ahead, standing in the way. A blow staggers it.
     */
    private void hunt(int t) {
        if (twin == null || twin.isRemoved()) {
            return;
        }
        if (duel != null && !duelDone && (dueling || duel.under(player))) {
            duel(t);
            return;
        }
        if (duelDone) {
            return; // it faltered: gone till the end
        }
        if (fight) { // it waits before the body, watching them come
            twin.face(player.position());
            return;
        }
        final boolean struckBack = twin.takeStagger() && t >= staggerUntil;
        if (struckBack) { // it reels back
            staggerUntil = t + 35;
            final Vec3 away = twin.position().subtract(player.position()).multiply(1, 0, 1);
            final Vec3 push = away.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : away.normalize();
            twin.setPos(twin.position().add(push.scale(guarding != null ? 1.0 : 2.5)));
            level.playSound(null, twin.blockPosition(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 1.0F, 0.6F);
        }
        twin.face(player.position());
        // a gate: the double guards it until struck back twice
        final Island on = islands.get(islandOf());
        if (guarding == null && on.kind == Kind.GATE && !on.open && on.under(player)) {
            guarding = on;
            gateBlows = 0;
            twin.setPos(Vec3.atBottomCenterOf(on.middle.offset(0, 1, on.half - 1)));
            level.sendParticles(ParticleTypes.SQUID_INK, twin.getX(), twin.getY() + 1.0, twin.getZ(), 20, 0.3, 0.6, 0.3, 0.02);
            voice("gate");
        }
        if (guarding != null) {
            if (struckBack && ++gateBlows >= GATE_BLOWS) { // the wall falls
                guarding.open = true;
                wall(guarding, false);
                level.sendParticles(ParticleTypes.SQUID_INK, guarding.middle.getX() + 0.5, guarding.middle.getY() + 2.5, guarding.middle.getZ() + guarding.half + 0.5,
                        60, guarding.half, 1.0, 0.2, 0.02);
                level.playSound(null, guarding.middle, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.5F, 0.4F);
                guarding = null;
                nextEvent = t + 60;
            } else if (t >= staggerUntil) { // guarding: stepping back to the wall, lunging at them when near
                final Vec3 post = Vec3.atBottomCenterOf(guarding.middle.offset(0, 1, guarding.half - 1));
                final Vec3 to = (player.position().distanceTo(post) < 3.5 ? player.position() : post).subtract(twin.position());
                if (to.length() > 0.5) {
                    twin.setPos(twin.position().add(to.normalize().scale(Math.min(0.16, to.length()))));
                }
            }
        } else if (t >= staggerUntil && t >= blockUntil) {
            final Vec3 to = player.position().subtract(twin.position());
            final double speed = (to.length() > 12 ? 0.35 : 0.12) + 0.01 * scars; // closing in fast from afar, then stalking
            if (to.length() > 0.8) {
                twin.setPos(twin.position().add(to.normalize().scale(Math.min(speed, to.length()))));
            }
        }
        if (appearAt >= 0) { // where it means to appear: ink rising there, then it is there (on the far side from them)
            level.sendParticles(ParticleTypes.SQUID_INK, appearing.middle.getX() + 0.5, appearing.middle.getY() + 1.5, appearing.middle.getZ() + 0.5, 3,
                    appearing.half * 0.4, 0.4, appearing.half * 0.4, 0.01);
            if (t >= appearAt) {
                Vec3 spot = Vec3.atBottomCenterOf(appearing.middle.above());
                for (int cx = -1; cx <= 1; cx += 2) {
                    for (int cz = -1; cz <= 1; cz += 2) {
                        final Vec3 corner = Vec3.atBottomCenterOf(appearing.middle.offset(cx * appearing.half, 1, cz * appearing.half));
                        if (corner.distanceTo(player.position()) > spot.distanceTo(player.position())) {
                            spot = corner;
                        }
                    }
                }
                level.sendParticles(ParticleTypes.SQUID_INK, twin.getX(), twin.getY() + 1.0, twin.getZ(), 20, 0.3, 0.6, 0.3, 0.02);
                twin.setPos(spot);
                blockUntil = t + 70;
                graceUntil = t + 15;
                appearAt = -1;
                voice("block");
            }
        }
        if (twin.position().distanceTo(player.position()) < 1.2 && t >= staggerUntil && t >= graceUntil && appearAt < 0) {
            struck(twin.position());
        }
        if (guarding == null && t >= nextEvent && strikeAt < 0) {
            event(t);
        }
        if (t == strikeAt) {
            burst(strikeCells, 3, strikeFloor);
            if (inCells(strikeCells, 3, strikeFloor, 1.6)) {
                struck(new Vec3(player.getX(), strikeFloor, player.getZ() - 1));
            }
            strikeAt = -1;
        }
    }

    /** One of the double's doings, by turns at random. */
    private void event(int t) {
        nextEvent = t + 90 - 8 * scars + level.random.nextInt(30);
        final int at = islandOf();
        switch (level.random.nextInt(3)) {
            case 0 -> { // strikes round the player
                strikeCells.clear();
                final int px = Mth.floor(player.getX()), pz = Mth.floor(player.getZ());
                strikeCells.add(new int[] {px, pz});
                for (int k = 0; k < 2; k++) {
                    strikeCells.add(new int[] {px + (level.random.nextInt(3) - 1) * 3, pz + (level.random.nextInt(3) - 1) * 3});
                }
                strike(t, Math.floor(player.getY() + 0.01), 28);
            }
            case 1 -> { // strikes on the way ahead: the next pieces, in turn
                strikeCells.clear();
                for (int k = 1; k <= 2 && at + k < islands.size() - 1; k++) {
                    final Island ahead = islands.get(at + k);
                    if (!ahead.gone) {
                        strikeCells.add(new int[] {ahead.middle.getX(), ahead.middle.getZ()});
                    }
                }
                if (!strikeCells.isEmpty()) {
                    strike(t, islands.get(Math.min(islands.size() - 1, at + 1)).middle.getY() + 1.0, 40);
                }
            }
            default -> { // ahead, standing in the way: the piece marked first, a second to see it
                final Island ahead = at + 2 < islands.size() - 1 ? islands.get(at + 2) : null;
                if (ahead != null && !ahead.gone && ahead.kind != Kind.GATE && ahead.kind != Kind.DUEL && ahead.kind != Kind.PHASE) {
                    appearing = ahead;
                    appearAt = t + 24;
                    strikeCells.clear();
                    strikeCells.add(new int[] {ahead.middle.getX(), ahead.middle.getZ()});
                    warn(ahead.middle.getY() + 1.0, ahead.half * 2 + 1, strikeCells, 24); // its whole top flickering: something comes
                    strikeCells.clear();
                }
            }
        }
    }

    /**
     * The duel. It walls the piece round, the double across from them; then by turns it closes in, crouches (the line
     * it will dash along marked), dashes, and stands open a moment (white motes round it): only then do blows land.
     */
    private void duel(int t) {
        if (!dueling) {
            dueling = true;
            ring(true);
            twin.setPos(Vec3.atBottomCenterOf(duel.middle.offset(0, 1, duel.half - 1)));
            duelPart = 0;
            duelStart = t;
            appearAt = -1;
            voice("duel");
            level.playSound(null, duel.middle, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 0.8F, 0.6F);
        }
        twin.face(player.position());
        if (t < pauseUntil) { // between the stages: it gathers itself (the rim crumbling meanwhile), doing nothing
            twin.takeStagger();
            if (t == crumbleAt) {
                crumble();
            }
            level.sendParticles(ParticleTypes.SQUID_INK, twin.getX(), twin.getY() + 1.0, twin.getZ(), 2, 0.3, 0.6, 0.3, 0.01);
            duelStart = t;
            return;
        }
        final int in = t - duelStart;
        final boolean blow = twin.takeStagger();
        final double floor = duel.middle.getY() + 1.0;
        switch (duelPart) {
            case 0 -> { // closing in (circling a little), then crouching to dash
                final Vec3 to = player.position().subtract(twin.position()).multiply(1, 0, 1);
                final Vec3 round = to.lengthSqr() < 1.0E-4 ? Vec3.ZERO : new Vec3(-to.z, 0, to.x).normalize().scale(0.08 * (duelBlows % 2 == 0 ? 1 : -1));
                if (to.length() > 3.5) {
                    twin.setPos(arena(twin.position().add(to.normalize().scale(0.14)).add(round)));
                } else if (to.length() < 2.4 && to.lengthSqr() > 1.0E-4) { // never on top of them: it steps back
                    twin.setPos(arena(twin.position().subtract(to.normalize().scale(0.18)).add(round)));
                } else {
                    twin.setPos(arena(twin.position().add(round)));
                }
                if (in >= 34 || to.length() <= 3.5 && in >= 10) {
                    // what it does: at first a straight dash; then also a dash and a second at once, and a wide one
                    // the second stage: blinking behind them, or splitting into three, besides the dashes
                    pattern = secondStage ? 1 + level.random.nextInt(4) : duelBlows == 0 ? 0 : level.random.nextInt(duelBlows >= 2 ? 3 : 2);
                    secondDash = false;
                    if (pattern == 3) {
                        blink(t, floor);
                    } else {
                        aim(t, floor, 22);
                        if (pattern == 4) {
                            split(t, floor);
                        }
                    }
                }
            }
            case 1 -> { // crouched, the line marked
                if (in >= windup) {
                    if (pattern == 3) { // the blink: it is there, its strike falling with it; then open
                        level.sendParticles(ParticleTypes.SQUID_INK, twin.getX(), twin.getY() + 1.0, twin.getZ(), 20, 0.3, 0.6, 0.3, 0.02);
                        twin.setPos(arena(blinkTo));
                        duelPart = 3;
                    } else {
                        duelPart = 2;
                    }
                    duelStart = t;
                }
            }
            case 2 -> { // the dash
                twin.setPos(arena(twin.position().add(dashDir.scale(1.1 + (fight ? 0.08 * duelBlows : 0.0))))); // quicker as it weakens
                for (int d = 0; d < decoys.size(); d++) { // its copies dash with it; they hit as hard
                    final FractureDouble decoy = decoys.get(d);
                    decoy.setPos(arena(decoy.position().add(decoyDirs.get(d).scale(1.1))));
                    if (decoy.position().distanceTo(player.position()) < 1.3) {
                        struck(decoy.position());
                    }
                }
                final double reach = pattern == 2 ? 2.0 : 1.3; // the wide one hits to its sides too
                if (twin.position().distanceTo(player.position()) < reach) { // it hits, and springs back (not through them)
                    struck(twin.position());
                    twin.setPos(arena(twin.position().subtract(dashDir.scale(2.5))));
                    duelPart = 3;
                    duelStart = t;
                }
                if (duelPart == 2 && in >= 7 && !decoys.isEmpty()) { // the copies spent: ink
                    for (FractureDouble decoy : decoys) {
                        level.sendParticles(ParticleTypes.SQUID_INK, decoy.getX(), decoy.getY() + 1.0, decoy.getZ(), 20, 0.3, 0.6, 0.3, 0.02);
                        decoy.discard();
                    }
                    decoys.clear();
                    decoyDirs.clear();
                }
                if (duelPart == 2 && in >= 7) {
                    if (pattern == 1 && !secondDash) { // at once again, aimed anew
                        secondDash = true;
                        aim(t, floor, 12);
                    } else {
                        duelPart = 3;
                        duelStart = t;
                    }
                }
            }
            default -> { // open: blows land
                level.sendParticles(ParticleTypes.END_ROD, twin.getX(), twin.getY() + 1.0, twin.getZ(), 2, 0.3, 0.5, 0.3, 0.01);
                if (blow) {
                    duelBlows++;
                    level.playSound(null, twin.blockPosition(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 1.3F, 0.5F + 0.15F * duelBlows);
                    duelPart = 0;
                    duelStart = t;
                }
                if (in >= (fight ? 36 - 2 * duelBlows : 36)) {
                    duelPart = 0;
                    duelStart = t;
                }
            }
        }
        if (duelPart != 1 && duelPart != 2 && !decoys.isEmpty()) { // the dash over (or cut short by a hit): the copies go
            for (FractureDouble decoy : decoys) {
                level.sendParticles(ParticleTypes.SQUID_INK, decoy.getX(), decoy.getY() + 1.0, decoy.getZ(), 20, 0.3, 0.6, 0.3, 0.02);
                decoy.discard();
            }
            decoys.clear();
            decoyDirs.clear();
        }
        if (blow && duelPart != 0) { // a blow while it is not open: it only reels (staying on its piece)
            twin.setPos(arena(twin.position().add(twin.position().subtract(player.position()).multiply(1, 0, 1).normalize().scale(0.8))));
        }
        if (fight && duelPart == 0 && in == 1 && duelBlows >= 2 && strikeAt < 0) { // in the fight, from the third blow on: strikes round them too
            strikeCells.clear();
            final int px = Mth.floor(player.getX()), pz = Mth.floor(player.getZ());
            strikeCells.add(new int[] {px, pz});
            strikeCells.add(new int[] {px + (level.random.nextInt(3) - 1) * 3, pz + (level.random.nextInt(3) - 1) * 3});
            strike(t, floor, 30);
        }
        if (t == strikeAt) {
            burst(strikeCells, 3, strikeFloor);
            if (inCells(strikeCells, 3, strikeFloor, 1.6)) {
                struck(new Vec3(player.getX(), strikeFloor, player.getZ() - 1));
            }
            strikeAt = -1;
        }
        if (fight && !secondStage && duelBlows >= FIGHT_BLOWS) { // not broken yet: it rises again, and the piece begins to crumble at its rim
            secondStage = true;
            duelBlows = 0;
            duelPart = 0;
            duelStart = t;
            strikeAt = -1;
            crumbleAt = t + 40; // the rim marked, then gone
            pauseUntil = t + 90;
            final List<int[]> rim = new ArrayList<>();
            for (int dz = -duel.half; dz <= duel.half; dz++) {
                for (int dx = -duel.half; dx <= duel.half; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) >= duel.half - 1) {
                        rim.add(new int[] {duel.middle.getX() + dx, duel.middle.getZ() + dz});
                    }
                }
            }
            warn(floor, 1, rim, 40);
            level.sendParticles(ParticleTypes.SQUID_INK, twin.getX(), twin.getY() + 1.0, twin.getZ(), 60, 0.5, 1.0, 0.5, 0.05);
            twin.setPos(arena(Vec3.atBottomCenterOf(duel.middle.above())));
            level.playSound(null, duel.middle, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 1.2F, 0.4F);
            level.playSound(null, duel.middle, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.5F, 0.4F);
            voice("rise");
        }
        if (duelBlows >= (!fight ? DUEL_BLOWS : secondStage ? SECOND_BLOWS : Integer.MAX_VALUE)) {
            falter();
        }
    }

    /** The duel piece's rim falls away. */
    private void crumble() {
        {
            for (BlockPos pos : duel.blocks) {
                if (Math.max(Math.abs(pos.getX() - duel.middle.getX()), Math.abs(pos.getZ() - duel.middle.getZ())) >= duel.half - 1) {
                    final BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        FallingBlockEntity.fall(level, pos, state);
                    }
                }
            }
            level.playSound(null, duel.middle, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.5F, 0.4F);
        }
    }

    /** It falters: gone till the end. */
    private void falter() {
        { // it falters: gone till the end
            duelDone = true;
            dueling = false;
            ring(false);
            level.sendParticles(ParticleTypes.SQUID_INK, twin.getX(), twin.getY() + 1.0, twin.getZ(), 50, 0.4, 0.8, 0.4, 0.04);
            level.playSound(null, twin.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.5F, 0.5F);
            twin.discard();
            twin = null;
            voice("falter");
        }
    }

    /** Keeps the double on the duel's piece (inside its unseen ring, where blows reach it). */
    private Vec3 arena(Vec3 at) {
        final double limit = duel.half - (secondStage ? 2.7 : 0.7); // (the rim gone in the second stage)
        final double cx = duel.middle.getX() + 0.5, cz = duel.middle.getZ() + 0.5;
        return new Vec3(Mth.clamp(at.x, cx - limit, cx + limit), duel.middle.getY() + 1.0, Mth.clamp(at.z, cz - limit, cz + limit));
    }

    /** The blink: a spot behind them marked (a strike), and there it will be when it falls. */
    private void blink(int t, double floor) {
        final Vec3 look = player.getLookAngle().multiply(1, 0, 1);
        final Vec3 back = look.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, -1) : look.normalize().scale(-2.5);
        blinkTo = arena(player.position().add(back));
        strikeCells.clear();
        strikeCells.add(new int[] {Mth.floor(blinkTo.x), Mth.floor(blinkTo.z)});
        strike(t, floor, 26);
        windup = 26;
        duelPart = 1;
        duelStart = t;
        level.sendParticles(ParticleTypes.SQUID_INK, blinkTo.x, blinkTo.y + 1.0, blinkTo.z, 15, 0.4, 0.6, 0.4, 0.01);
        level.playSound(null, twin.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.6F, 0.5F);
    }

    /** The split: two copies beside it, each with its own line marked at the player, dashing with it. */
    private void split(int t, double floor) {
        for (int side = -1; side <= 1; side += 2) {
            final Vec3 from = player.position().subtract(twin.position()).multiply(1, 0, 1);
            final Vec3 across = from.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : new Vec3(-from.z, 0, from.x).normalize();
            final Vec3 at = arena(twin.position().add(across.scale(side * 3.5)));
            final FractureDouble decoy = FractureDouble.of(player, level, at);
            decoy.face(player.position());
            final Vec3 to = player.position().subtract(at).multiply(1, 0, 1);
            final Vec3 dir = to.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, -1) : to.normalize();
            final List<int[]> line = new ArrayList<>();
            for (int k = 1; k <= 9; k++) {
                final Vec3 p = at.add(dir.scale(k));
                line.add(new int[] {Mth.floor(p.x), Mth.floor(p.z)});
            }
            warn(floor, 1, line, windup);
            decoys.add(decoy);
            decoyDirs.add(dir);
            level.sendParticles(ParticleTypes.SQUID_INK, at.x, at.y + 1.0, at.z, 15, 0.3, 0.6, 0.3, 0.02);
        }
    }

    /**
     * Aims a dash: where they will be (their way of moving carried on a little), not where they are; marks the line
     * (three wide for the wide dash) for so long.
     */
    private void aim(int t, double floor, int ticks) {
        final Vec3 lead = moving.multiply(1, 0, 1).scale(8.0 + level.random.nextInt(6));
        final Vec3 to = player.position().add(lead).subtract(twin.position()).multiply(1, 0, 1);
        dashDir = to.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, -1) : to.normalize();
        final List<int[]> line = new ArrayList<>();
        final Vec3 side = new Vec3(-dashDir.z, 0, dashDir.x);
        for (int k = 1; k <= 9; k++) {
            for (int w = pattern == 2 ? -1 : 0; w <= (pattern == 2 ? 1 : 0); w++) {
                final Vec3 p = twin.position().add(dashDir.scale(k)).add(side.scale(w));
                line.add(new int[] {Mth.floor(p.x), Mth.floor(p.z)});
            }
        }
        warn(floor, 1, line, ticks);
        windup = ticks;
        duelPart = 1;
        duelStart = t;
        level.playSound(null, twin.blockPosition(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 0.5F, pattern == 2 ? 1.0F : 1.4F);
    }

    private void strike(int t, double floor, int warn) {
        warn(floor, 3, strikeCells, warn);
        strikeAt = t + warn;
        strikeFloor = floor;
    }

    /** Foretells the heart's next attack. */
    private void foretell(int t, int attack) {
        heartPart = FractureCore.WARN;
        heartNext = attack;
        partStart = t;
        heart.set(FractureCore.WARN, 0.0F, 1.0F, attack);
        level.playSound(null, heart.blockPosition(), attack == FractureCore.SHOCKWAVE ? SoundEvents.RESPAWN_ANCHOR_CHARGE : SoundEvents.AMETHYST_BLOCK_RESONATE,
                SoundSource.HOSTILE, 0.9F, 0.5F);
    }

    /**
     * The heart: foretold, rings running out (jump them) or a barrage of strikes round the player (step out), then
     * its shell opens: one blow and it closes again. Round again, by turns, till it breaks.
     */
    private void heart(int t) {
        if (heart == null) {
            return;
        }
        final int in = t - partStart;
        final double floorTop = end.middle.getY() + 1.0;
        switch (heartPart) {
            case FractureCore.WARN -> {
                if (in >= WARN) {
                    heartPart = heartNext;
                    partStart = t;
                    heart.set(heartNext, 0.0F, 1.0F, FractureCore.IDLE);
                }
            }
            case FractureCore.SHOCKWAVE -> {
                final double d = Math.hypot(player.getX() - heart.getX(), player.getZ() - heart.getZ());
                for (int k = 0; k < 3; k++) {
                    final float r = CoreTrial.RING_FROM + (in - k * 18) * CoreTrial.RING_SPEED;
                    if (in >= k * 18 && Math.abs(d - r) < 0.6 && player.getY() - floorTop < 0.35) {
                        struck(heart.position());
                    }
                }
                if (in >= SHOCKWAVE) {
                    open(t);
                }
            }
            case FractureCore.BARRAGE -> {
                if (in % 30 == 0 && in < 66) {
                    strikeCells.clear();
                    final int px = Mth.floor(player.getX()), pz = Mth.floor(player.getZ());
                    strikeCells.add(new int[] {px, pz});
                    for (int k = 0; k < 3; k++) {
                        strikeCells.add(new int[] {px + (level.random.nextInt(5) - 2) * 3, pz + (level.random.nextInt(5) - 2) * 3});
                    }
                    strike(t, floorTop, 26);
                }
                if (t == strikeAt) {
                    burst(strikeCells, 3, strikeFloor);
                    if (inCells(strikeCells, 3, strikeFloor, 1.6)) {
                        struck(heart.position());
                    }
                    strikeAt = -1;
                }
                if (in >= BARRAGE) {
                    open(t);
                }
            }
            case FractureCore.OPEN -> {
                if (heart.left() < lastLeft || in >= OPEN) { // one blow, and it closes
                    lastLeft = heart.left();
                    if (heart.left() > 0) {
                        foretell(t, heartNext == FractureCore.SHOCKWAVE ? FractureCore.BARRAGE : FractureCore.SHOCKWAVE);
                    }
                }
            }
            default -> {
            }
        }
        if (heart.left() <= 0) { // broken: the body is theirs to take back
            level.playSound(null, heart.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 2.0F, 0.4F);
            level.sendParticles(ParticleTypes.END_ROD, heart.getX(), heart.getY(), heart.getZ(), 40, 0.6, 0.6, 0.6, 0.15);
            heart.discard();
            heart = null;
            stage = 2;
            voice("broken");
        }
    }

    private void open(int t) {
        heartPart = FractureCore.OPEN;
        partStart = t;
        lastLeft = heart.left();
        heart.set(FractureCore.OPEN, 0.0F, 1.0F, FractureCore.IDLE);
        level.playSound(null, heart.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.0F, 1.8F);
    }
}
