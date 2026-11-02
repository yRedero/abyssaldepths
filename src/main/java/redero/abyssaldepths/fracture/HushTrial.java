package redero.abyssaldepths.fracture;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * Hush: finding the body in the dark. The land they stood in lies round them as a wide field of pieces, too far
 * apart to jump between, some joined by stepping stones: a maze (with a few loops), in darkness (they see only a
 * few blocks). Their body waits at the piece furthest from where they start along the ways; its heartbeat sounds
 * from there, quicker the nearer they come. Memories lie at dead ends: one wins back a control, keeps them silent a
 * while and shows the way towards the heartbeat. Their double hunts by sound: running, jumping and landing make
 * noise (rings show it), walking a little, sneaking none; heard, it comes for them fast. It also sweeps its gaze round
 * as it searches (its outline glows, so they see where it looks): standing in front of it, not sneaking, they are
 * seen. Reaching the body wins; three hits or a fall fail it.
 */
final class HushTrial extends Trial {
    static final int GRID = 8, SPACING = 10, HITS = 3;
    private static final int SAMPLE_STEP = 5;
    /** How loud they are (0 .. 100), and over how much the double hears them. */
    private static final float HEARD = 35.0F;

    private final List<BlockPos> middles = new ArrayList<>();
    private final List<ItemEntity> memories = new ArrayList<>();
    private List<ItemStack> things = List.of();
    private FracturedBody body;
    private FractureDouble twin;
    private BlockPos start;
    private int hits, invulnerable, chaseUntil, nextWander, lastHeard = -1000, silentUntil, guideUntil;
    private float noise, sweep;
    private boolean wasOnGround = true;
    private Vec3 target;

    HushTrial(ServerPlayer player, ServerLevel level, BlockPos origin, int scars) {
        super(player, level, origin, scars);
    }

    @Override
    public Type type() {
        return Type.HUSH;
    }

    @Override
    void remember(List<ItemStack> things) {
        this.things = things;
    }

    @Override
    void build() {
        final ServerLevel from = player.serverLevel();
        final BlockPos home = player.blockPosition();
        final net.minecraft.util.RandomSource random = level.random;
        final int n = GRID * GRID;
        final int[] half = new int[n], height = new int[n];
        final BlockPos[] at = new BlockPos[n];
        final float p = random.nextFloat() * Mth.TWO_PI, q = random.nextFloat() * Mth.TWO_PI;
        for (int c = 0; c < n; c++) {
            final int i = c % GRID, j = c / GRID;
            half[c] = random.nextInt(3) == 0 ? 2 : 1;
            // heights rolling gently (neighbours never far apart: every stone a step up or down at most)
            height[c] = Mth.clamp(Math.round(1.4F * Mth.sin(i * 0.7F + p) + 1.0F * Mth.cos(j * 0.6F + q)), -2, 2);
            at[c] = origin.offset((i - GRID / 2) * SPACING, height[c], j * SPACING); // (on the grid: pieces not joined stay out of reach)
        }
        // the ways: a maze grown from a corner (each piece reached once), and a few loops besides
        final boolean[] seen = new boolean[n];
        final List<int[]> ways = new ArrayList<>();
        final java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        final int first = random.nextBoolean() ? 0 : GRID - 1;
        seen[first] = true;
        stack.push(first);
        while (!stack.isEmpty()) {
            final int c = stack.peek();
            final List<Integer> next = new ArrayList<>();
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                final int i = c % GRID + d[0], j = c / GRID + d[1];
                if (i >= 0 && j >= 0 && i < GRID && j < GRID && !seen[j * GRID + i]) {
                    next.add(j * GRID + i);
                }
            }
            if (next.isEmpty()) {
                stack.pop();
                continue;
            }
            final int to = next.get(random.nextInt(next.size()));
            seen[to] = true;
            ways.add(new int[] {c, to});
            stack.push(to);
        }
        for (int k = 0; k < n / 8; k++) {
            final int c = random.nextInt(n), i = c % GRID, j = c / GRID;
            if (i + 1 < GRID) {
                ways.add(new int[] {c, c + 1});
            } else if (j + 1 < GRID) {
                ways.add(new int[] {c, c + GRID});
            }
        }
        final List<List<Integer>> joined = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            joined.add(new ArrayList<>());
        }
        for (int[] w : ways) {
            joined.get(w[0]).add(w[1]);
            joined.get(w[1]).add(w[0]);
        }
        // the pieces, copied from the land round where they stood
        for (int c = 0; c < n; c++) {
            final int i = c % GRID, j = c / GRID;
            TrialLand.piece(level, from, home.getX() + (i - GRID / 2) * SAMPLE_STEP, home.getZ() + (j - GRID / 2) * SAMPLE_STEP, at[c], half[c], null);
            middles.add(at[c]);
        }
        // stepping stones over the gaps of the ways: two wide, never more than one block apart (easy steps in the dark)
        final BlockPos.MutableBlockPos real = new BlockPos.MutableBlockPos();
        for (int[] w : ways) {
            final BlockPos a = at[w[0]], b = at[w[1]];
            final boolean alongX = Math.abs(w[1] - w[0]) == 1;
            final int edgeA = half[w[0]], edgeB = half[w[1]];
            final double length = alongX ? Math.abs(b.getX() - a.getX()) : Math.abs(b.getZ() - a.getZ());
            final double gap = length - edgeA - edgeB - 1;
            final List<Integer> spots = new ArrayList<>();
            for (int d = edgeA + 2; d <= edgeA + gap - 1; d += 2) {
                spots.add(d);
            }
            if (spots.isEmpty() || spots.get(spots.size() - 1) != (int) (edgeA + gap - 1)) {
                spots.add((int) (edgeA + gap - 1)); // (the last one right before the far piece)
            }
            for (int d : spots) {
                final double f = d / length;
                final int sx = (int) Math.round(Mth.lerp(f, a.getX(), b.getX())), sz = (int) Math.round(Mth.lerp(f, a.getZ(), b.getZ()));
                final int sy = (int) Math.round(Mth.lerp(f, a.getY(), b.getY()));
                final BlockState state = TrialLand.top(from, home.getX() + sx - origin.getX(), home.getZ() + sz - origin.getZ(), real);
                for (int wide = 0; wide < 2; wide++) {
                    level.setBlock(new BlockPos(sx + (alongX ? 0 : wide), sy, sz + (alongX ? wide : 0)), TrialLand.tone(state, from, real, 1), 2);
                }
            }
        }
        // the body at the piece furthest along the ways from the start
        final int[] steps = new int[n];
        java.util.Arrays.fill(steps, -1);
        final java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        steps[first] = 0;
        queue.add(first);
        int far = first;
        while (!queue.isEmpty()) {
            final int c = queue.poll();
            if (steps[c] > steps[far]) {
                far = c;
            }
            for (int to : joined.get(c)) {
                if (steps[to] < 0) {
                    steps[to] = steps[c] + 1;
                    queue.add(to);
                }
            }
        }
        start = at[first];
        body = FracturedBody.mirror(player, level, Vec3.atBottomCenterOf(at[far].above()), 180.0F);
        // memories at dead ends (not the start, not the body), the furthest first
        final List<Integer> ends = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            if (joined.get(c).size() == 1 && c != first && c != far) {
                ends.add(c);
            }
        }
        ends.sort((x, y) -> Integer.compare(steps[y], steps[x]));
        final List<ItemStack> remembered = remembered(player, things, 3);
        for (int m = 0; m < Math.min(3, ends.size()); m++) {
            memories.add(memory(remembered.get(m), Vec3.atBottomCenterOf(at[ends.get(m * Math.max(1, ends.size() / 3))]).add(0, 1.6, 0)));
        }
    }

    @Override
    Vec3 spawn() {
        return Vec3.atBottomCenterOf(start.above());
    }

    @Override
    float spawnYaw() {
        return 0.0F;
    }

    @Override
    BoundingBox bounds() {
        return new BoundingBox(origin.getX() - GRID / 2 * SPACING - 8, origin.getY() - 8, origin.getZ() - 8, origin.getX() + GRID / 2 * SPACING + 8,
                origin.getY() + 12, origin.getZ() + GRID * SPACING + 8);
    }

    @Override
    void clear() {
        player.removeEffect(MobEffects.BLINDNESS);
        player.removeEffect(MobEffects.MOVEMENT_SPEED);
        super.clear();
    }

    private void dark() {
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 200, 0, false, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 0, false, false, false)); // quicker on their feet: walking (quiet) carries them
    }

    @Override
    protected void orient(int t) {
        if (t == 1) {
            dark();
            final BlockPos mid = middles.get(middles.size() / 2 + GRID / 2);
            twin = FractureDouble.of(player, level, Vec3.atBottomCenterOf(mid.above()));
            twin.setGlowingTag(true); // its outline shows in the dark: where it is, where it looks
            say("hush");
        }
        heartbeat(t);
        hud(1.0F, 0.0F, 0.0F);
    }

    /** The body's heartbeat, from where it waits: quicker the nearer they come. */
    private void heartbeat(int t) {
        final double d = player.position().distanceTo(body.position());
        final int every = d > 40 ? 40 : d > 20 ? 30 : d > 8 ? 22 : 14;
        if (t % every == 0) {
            level.playSound(null, body.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 3.0F, 0.9F);
        }
    }

    private void struck(Vec3 from) {
        if (invulnerable > 0 || FractureTrials.unharmed) {
            return;
        }
        invulnerable = 30;
        hits++;
        hit(from, 0.2);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new redero.abyssaldepths.network.EffectPayload("corruption/" + player.getId() + "/" + Math.min(95, hits * 33), 0));
        if (hits < HITS) {
            say(hits == 1 ? "hit1" : "hit2");
        }
    }

    /** A ring of noise round them, pale, as loud as it was. */
    private void ring(float loud) {
        final DustParticleOptions dust = new DustParticleOptions(new Vector3f(0.9F, 0.9F, 0.88F), 1.0F);
        final int count = 18;
        final double r = 0.8 + loud / 10.0;
        for (int k = 0; k < count; k++) {
            final double a = k * Mth.TWO_PI / count;
            level.sendParticles(dust, player.getX() + Math.cos(a) * r, player.getY() + 0.1, player.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    @Override
    protected Result run(int t) {
        if (invulnerable > 0) {
            invulnerable--;
        }
        if (t % 100 == 0) {
            dark();
        }
        heartbeat(t);
        // how loud they are
        final Vec3 v = player.getDeltaMovement();
        final double moving = Math.hypot(player.getX() - player.xo, player.getZ() - player.zo);
        float added = 0;
        if (player.isSprinting()) {
            added += 2.6F;
        } else if (moving > 0.04 && !player.isCrouching()) {
            added += 0.7F;
        }
        if (wasOnGround && !player.onGround() && v.y > 0.1) {
            added += 16; // a jump
        } else if (!wasOnGround && player.onGround()) {
            added += 7; // landing
        }
        wasOnGround = player.onGround();
        noise = t < silentUntil ? 0.0F : Mth.clamp(noise * 0.97F - 0.25F + added, 0.0F, 100.0F); // (remembered: silent a while)
        if (t >= silentUntil && (added >= 6 || player.isSprinting() && t % 8 == 0)) {
            ring(added >= 6 ? added : 4);
        }
        // memories
        for (ItemEntity memory : memories) {
            if (!memory.isRemoved() && memory.position().distanceTo(player.position().add(0, 0.8, 0)) < 1.4) {
                level.sendParticles(ParticleTypes.END_ROD, memory.getX(), memory.getY(), memory.getZ(), 25, 0.3, 0.3, 0.3, 0.08);
                level.playSound(null, memory.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 0.8F);
                memory.discard();
                hits = Math.max(0, hits - 1);
                silentUntil = t + 200; // silent for ten seconds
                guideUntil = t + 120; // and the way towards the heartbeat shows
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new redero.abyssaldepths.network.EffectPayload("corruption/" + player.getId() + "/" + hits * 33, 0));
                say("remember");
            }
        }
        if (t < guideUntil && t % 4 == 0) { // remembered: motes drifting off towards the body
            final Vec3 to = body.position().subtract(player.position()).normalize();
            for (int k = 1; k <= 4; k++) {
                level.sendParticles(player, ParticleTypes.END_ROD, true, player.getX() + to.x * k * 1.5, player.getY() + 1.2 + to.y * k, player.getZ() + to.z * k * 1.5,
                        1, 0.05, 0.05, 0.05, 0.0);
            }
        }
        hunt(t);
        hud(1.0F - hits / (float) HITS, noise / 100.0F, 0.0F);
        if (player.position().distanceTo(body.position()) < 1.6) {
            say("pass");
            return Result.PASSED;
        }
        if (player.getY() < origin.getY() - 14 || hits >= HITS || t > 20 * 240) {
            return Result.FAILED;
        }
        return Result.RUNNING;
    }

    /** The double: searching (wandering, its gaze sweeping), hearing them, seeing them in its gaze; coming for them. */
    private void hunt(int t) {
        if (twin == null || twin.isRemoved()) {
            return;
        }
        final Vec3 toPlayer = player.position().subtract(twin.position()).multiply(1, 0, 1);
        final boolean chasing = t < chaseUntil;
        // its gaze: seen standing in front of it, near, unless sneaking
        final float facing = chasing ? (float) Math.atan2(toPlayer.z, toPlayer.x) : sweep;
        final double off = Math.abs(Mth.wrapDegrees(Math.toDegrees(Math.atan2(toPlayer.z, toPlayer.x) - facing)));
        final boolean seen = !chasing && toPlayer.length() < 9.0 && off < 25.0 && !player.isCrouching();
        if (noise > HEARD || seen) {
            if (!chasing && t - lastHeard > 160) {
                say("heard");
                level.playSound(null, twin.blockPosition(), SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 0.7F, 0.7F);
            }
            lastHeard = t;
            target = player.position();
            chaseUntil = t + 70;
        }
        if (t < chaseUntil && target != null) { // coming for where it heard them (and after them while they stay loud)
            if (noise > 20) {
                target = player.position();
            }
            final Vec3 to = target.subtract(twin.position());
            if (to.length() > 0.3) {
                twin.setPos(twin.position().add(to.normalize().scale(Math.min(0.17 + 0.01 * scars, to.length()))));
            }
            twin.face(target);
        } else { // searching: wandering from piece to piece, its gaze sweeping round
            if (t >= nextWander || target == null) {
                final BlockPos piece = middles.get(1 + level.random.nextInt(middles.size() - 2));
                target = Vec3.atBottomCenterOf(piece.above());
                nextWander = t + 120 + level.random.nextInt(80);
            }
            final Vec3 to = target.subtract(twin.position());
            if (to.length() > 0.3) {
                twin.setPos(twin.position().add(to.normalize().scale(Math.min(0.06, to.length()))));
            }
            sweep += 0.045F;
            twin.face(twin.position().add(Math.cos(sweep) * 4, 0, Math.sin(sweep) * 4));
            if (t % 3 == 0) { // its gaze, in motes along the way it looks
                for (int k = 2; k <= 8; k += 2) {
                    level.sendParticles(ParticleTypes.END_ROD, twin.getX() + Math.cos(sweep) * k, twin.getY() + 1.5, twin.getZ() + Math.sin(sweep) * k, 1, 0.1, 0.1, 0.1, 0.0);
                }
            }
        }
        if (twin.position().distanceTo(player.position()) < 1.2) { // caught: a hit, and it loses them again
            struck(twin.position());
            final BlockPos away = middles.get(level.random.nextInt(middles.size()));
            level.sendParticles(ParticleTypes.SQUID_INK, twin.getX(), twin.getY() + 1.0, twin.getZ(), 20, 0.3, 0.6, 0.3, 0.02);
            twin.setPos(Vec3.atBottomCenterOf(away.above()));
            chaseUntil = 0;
            target = null;
            noise = 0;
            say("lost");
        }
    }
}
