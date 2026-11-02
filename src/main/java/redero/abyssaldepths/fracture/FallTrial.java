package redero.abyssaldepths.fracture;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * The Fall: back into the body, straight down. A deep shaft walled with the land they stood in (in black and white),
 * floors of black across it one under another, each with openings rimmed in white; they fall slowly (the trial
 * holds them up a little) and steer through the openings. Landing on black costs a control (and the black gives way
 * under them). The shaft shifts as it goes down, the openings grow fewer and smaller, and now and then one is marked
 * and closes. Memories float in the shaft to be caught on the way. At the bottom their body lies in light: reaching
 * it wins; three hits fail it.
 */
final class FallTrial extends Trial {
    static final int LAYERS = 19, GAP = 12, HALF = 4, HITS = 3;
    /** The shaft takes nearly all the height of the trial world: its bottom here. */
    static final int BOTTOM_Y = 6;

    /** A floor: its height, the shaft's middle there, its openings (x, z of their corner, size), whether one closes. */
    private static final class Floor {
        final int y;
        final BlockPos middle;
        final List<int[]> holes = new ArrayList<>();
        boolean closes, closing;
        int closeAt = -1;
        int[] closed;

        Floor(int y, BlockPos middle) {
            this.y = y;
            this.middle = middle;
        }
    }

    private final List<Floor> floors = new ArrayList<>();
    private final List<ItemEntity> memories = new ArrayList<>();
    private List<ItemStack> things = List.of();
    private FracturedBody body;
    private BlockPos top, bottom;
    private int hits, invulnerable, breakAt = -1, guideUntil;
    private BlockPos breakUnder;

    FallTrial(ServerPlayer player, ServerLevel level, BlockPos origin, int scars) {
        super(player, level, origin, scars);
    }

    @Override
    public Type type() {
        return Type.FALL;
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
        final BlockPos.MutableBlockPos real = new BlockPos.MutableBlockPos();
        int sx = 0, sz = 0;
        final int topY = BOTTOM_Y + LAYERS * GAP;
        top = new BlockPos(origin.getX(), topY, origin.getZ());
        for (int layer = 0; layer <= LAYERS; layer++) {
            final int y = topY - layer * GAP;
            if (layer > 0) { // the shaft shifts a little at every floor
                sx = Mth.clamp(sx + random.nextInt(3) - 1, -3, 3);
                sz = Mth.clamp(sz + random.nextInt(3) - 1, -3, 3);
            }
            final BlockPos middle = new BlockPos(origin.getX() + sx, y, origin.getZ() + sz);
            final Floor floor = new Floor(y, middle);
            // the walls from here down to the next floor: the land round where they stood, a column of it at each place
            for (int dy = 0; dy < GAP && layer < LAYERS; dy++) {
                for (int k = -HALF - 1; k <= HALF + 1; k++) {
                    for (int[] w : new int[][] {{k, -HALF - 1}, {k, HALF + 1}, {-HALF - 1, k}, {HALF + 1, k}}) {
                        final int rx = home.getX() + w[0] * 3, rz = home.getZ() + w[1] * 3 + layer * 5;
                        final BlockState state = TrialLand.top(from, rx, rz, real);
                        level.setBlock(middle.offset(w[0], -dy, w[1]), TrialLand.tone(state, from, real, (dy + layer) % 3 == 0 ? 1 : 0), 2);
                    }
                }
            }
            if (layer == 0) { // where they start: a piece of their land, gone once the trial begins
                TrialLand.piece(level, from, home.getX(), home.getZ(), middle, HALF, null);
            } else if (layer < LAYERS) { // a black floor, with openings rimmed in white
                for (int dz = -HALF; dz <= HALF; dz++) {
                    for (int dx = -HALF; dx <= HALF; dx++) {
                        level.setBlock(middle.offset(dx, 0, dz), Trial.black(), 2);
                    }
                }
                final int size = layer < 8 ? 3 : 2, count = layer < 6 ? 3 : layer < 13 ? 2 : 1 + random.nextInt(2);
                for (int h = 0; h < count; h++) {
                    final int hx = -HALF + random.nextInt(2 * HALF + 2 - size), hz = -HALF + random.nextInt(2 * HALF + 2 - size);
                    floor.holes.add(new int[] {hx, hz, size});
                    for (int dz = -1; dz <= size; dz++) {
                        for (int dx = -1; dx <= size; dx++) {
                            final BlockPos at = middle.offset(hx + dx, 0, hz + dz);
                            if (Math.abs(at.getX() - middle.getX()) > HALF || Math.abs(at.getZ() - middle.getZ()) > HALF) {
                                continue;
                            }
                            final boolean inside = dx >= 0 && dz >= 0 && dx < size && dz < size;
                            final BlockState now = level.getBlockState(at);
                            if (inside) {
                                level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
                            } else if (!now.isAir()) {
                                level.setBlock(at, Blocks.WHITE_CONCRETE.defaultBlockState(), 2); // the rim, white: the way through
                            }
                        }
                    }
                }
                floor.closes = count > 1 && random.nextInt(100) < 45;
                // shards of black floating in the gap above (from the third floor on): steer round them
                if (layer > 2 && random.nextInt(100) < 55) {
                    for (int k = 0, n = 1 + random.nextInt(3); k < n; k++) {
                        final int kx = random.nextInt(2 * HALF - 1) - HALF + 1, kz = random.nextInt(2 * HALF - 1) - HALF + 1, ky = 4 + random.nextInt(5);
                        final boolean along = random.nextBoolean();
                        for (int l = 0; l < 2; l++) {
                            final BlockPos at = middle.offset(kx + (along ? l : 0), ky, kz + (along ? 0 : l));
                            if (Math.abs(at.getX() - middle.getX()) <= HALF && Math.abs(at.getZ() - middle.getZ()) <= HALF) {
                                level.setBlock(at, Trial.black(), 2);
                            }
                        }
                    }
                }
            } else { // the bottom: a floor of light, their body lying in it
                bottom = middle;
                for (int dz = -HALF; dz <= HALF; dz++) {
                    for (int dx = -HALF; dx <= HALF; dx++) {
                        level.setBlock(middle.offset(dx, 0, dz), (Math.abs(dx) + Math.abs(dz) < 3 ? Blocks.SMOOTH_QUARTZ : Blocks.WHITE_CONCRETE).defaultBlockState(), 2);
                    }
                }
                level.setBlock(middle.offset(-HALF, 1, -HALF), Blocks.LIGHT.defaultBlockState(), 2);
                body = FracturedBody.mirror(player, level, Vec3.atBottomCenterOf(middle.above()), 0.0F);
            }
            floors.add(floor);
        }
        final List<ItemStack> remembered = remembered(player, things, 3);
        // only a couple of memories in the shaft, and not always (deep down, worth steering for)
        final int[] at = {6 + random.nextInt(4), 12 + random.nextInt(4)};
        for (int m = 0; m < at.length; m++) {
            if (random.nextInt(100) >= 60) {
                continue;
            }
            final Floor f = floors.get(at[m]);
            memories.add(memory(remembered.get(m), Vec3.atBottomCenterOf(f.middle.offset(random.nextInt(2 * HALF - 1) - HALF + 1, GAP / 2, random.nextInt(2 * HALF - 1) - HALF + 1))));
        }
    }

    @Override
    Vec3 spawn() {
        return Vec3.atBottomCenterOf(top.above());
    }

    @Override
    float spawnYaw() {
        return 0.0F;
    }

    @Override
    BoundingBox bounds() {
        return new BoundingBox(origin.getX() - HALF - 6, BOTTOM_Y - 4, origin.getZ() - HALF - 6, origin.getX() + HALF + 6, BOTTOM_Y + LAYERS * GAP + 8,
                origin.getZ() + HALF + 6);
    }

    @Override
    void clear() {
        player.removeEffect(MobEffects.SLOW_FALLING);
        super.clear();
    }

    private void slow() {
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false, false));
    }

    @Override
    protected void orient(int t) {
        if (t == 1) {
            slow();
            say("fall");
        }
        hud(1.0F, 0.0F, 0.0F);
    }

    private void struck(Vec3 from) {
        if (invulnerable > 0 || FractureTrials.unharmed) {
            return;
        }
        if (wardOff()) {
            invulnerable = 30;
            return;
        }
        invulnerable = 30;
        hits++;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new redero.abyssaldepths.network.EffectPayload("corruption/" + player.getId() + "/" + Math.min(95, hits * 33), 0));
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 1.0F, 0.5F);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new redero.abyssaldepths.network.EffectPayload("trial_hit", 0));
        if (hits < HITS) {
            say(hits == 1 ? "hit1" : "hit2");
        }
    }

    @Override
    protected Result run(int t) {
        if (invulnerable > 0) {
            invulnerable--;
        }
        if (t % 100 == 0) {
            slow();
        }
        if (t == 1) { // the ground they stood on gives way
            for (int dz = -HALF; dz <= HALF; dz++) {
                for (int dx = -HALF; dx <= HALF; dx++) {
                    for (int dy = 0; dy < 3; dy++) {
                        level.setBlock(top.offset(dx, -dy, dz), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            level.playSound(null, top, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.5F, 0.4F);
        }
        // landed on black (with any part of their feet): a control lost, and the black gives way under them a moment later
        if (player.onGround() && breakAt < 0) {
            final net.minecraft.world.phys.AABB box = player.getBoundingBox();
            final int y = Mth.floor(box.minY - 0.05);
            outer:
            for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1.0E-4); x++) {
                for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1.0E-4); z++) {
                    if (level.getBlockState(new BlockPos(x, y, z)).is(redero.abyssaldepths.AbyssalDepths.FRACTURE_BLACK.get())) {
                        struck(player.position().add(0, -1, 0));
                        breakAt = t + 8;
                        breakUnder = new BlockPos(Mth.floor(player.getX()), y, Mth.floor(player.getZ()));
                        break outer;
                    }
                }
            }
        }
        if (t == breakAt) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    final BlockPos at = breakUnder.offset(dx, 0, dz);
                    if (level.getBlockState(at).is(redero.abyssaldepths.AbyssalDepths.FRACTURE_BLACK.get())) {
                        level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            level.sendParticles(ParticleTypes.SQUID_INK, breakUnder.getX() + 0.5, breakUnder.getY() + 1.0, breakUnder.getZ() + 0.5, 20, 0.6, 0.2, 0.6, 0.01);
            breakAt = -1;
        }
        // an opening marked as they come near, then closed
        for (Floor floor : floors) {
            if (floor.closes && !floor.closing && player.getY() - floor.y < 16 && player.getY() - floor.y > 5) {
                floor.closing = true;
                floor.closed = floor.holes.get(level.random.nextInt(floor.holes.size()));
                final List<int[]> cells = new ArrayList<>();
                for (int dz = 0; dz < floor.closed[2]; dz++) {
                    for (int dx = 0; dx < floor.closed[2]; dx++) {
                        cells.add(new int[] {floor.middle.getX() + floor.closed[0] + dx, floor.middle.getZ() + floor.closed[1] + dz});
                    }
                }
                warn(floor.y + 0.98, 1, cells, 26);
                floor.closeAt = t + 26;
                level.playSound(null, floor.middle, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.2F, 0.5F);
            }
            if (floor.closeAt == t) {
                for (int dz = 0; dz < floor.closed[2]; dz++) {
                    for (int dx = 0; dx < floor.closed[2]; dx++) {
                        level.setBlock(floor.middle.offset(floor.closed[0] + dx, 0, floor.closed[1] + dz), Trial.black(), 2);
                    }
                }
            }
        }
        for (ItemEntity memory : memories) {
            if (!memory.isRemoved() && memory.position().distanceTo(player.position().add(0, 0.8, 0)) < 1.6) {
                level.sendParticles(ParticleTypes.END_ROD, memory.getX(), memory.getY(), memory.getZ(), 25, 0.3, 0.3, 0.3, 0.08);
                level.playSound(null, memory.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 0.8F);
                memory.discard();
                hits = Math.max(0, hits - 1);
                ward = true; // the next landing on black comes to nothing
                guideUntil = t + 140; // and the way through the next floors shows
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new redero.abyssaldepths.network.EffectPayload("corruption/" + player.getId() + "/" + hits * 33, 0));
                say("remember");
            }
        }
        if (t < guideUntil && t % 4 == 0) { // remembered: the openings of the floors below them light up
            int shown = 0;
            for (Floor floor : floors) {
                if (floor.y >= player.getY() - 0.5 || floor.holes.isEmpty() || shown >= 3) {
                    continue;
                }
                shown++;
                for (int[] hole : floor.holes) {
                    if (hole == floor.closed && floor.closeAt >= 0 && t >= floor.closeAt) {
                        continue;
                    }
                    level.sendParticles(player, ParticleTypes.END_ROD, true, floor.middle.getX() + hole[0] + hole[2] / 2.0, floor.y + 0.6,
                            floor.middle.getZ() + hole[1] + hole[2] / 2.0, 3, hole[2] * 0.3, 0.1, hole[2] * 0.3, 0.0);
                }
            }
        }
        if (t % 6 == 0 && bottom != null) { // the light at the bottom
            level.sendParticles(ParticleTypes.END_ROD, bottom.getX() + 0.5, bottom.getY() + 1.2, bottom.getZ() + 0.5, 3, 1.5, 0.3, 1.5, 0.01);
        }
        final float depth = Mth.clamp((float) ((top.getY() - player.getY()) / (LAYERS * GAP)), 0.0F, 1.0F);
        hud(1.0F - hits / (float) HITS, depth, 0.0F);
        if (player.position().distanceTo(body.position()) < 1.8) {
            say("pass");
            return Result.PASSED;
        }
        if (hits >= HITS || t > 20 * 300 || player.getY() < BOTTOM_Y - 3) {
            return Result.FAILED;
        }
        return Result.RUNNING;
    }
}
