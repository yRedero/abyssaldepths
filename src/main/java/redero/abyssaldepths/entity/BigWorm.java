package redero.abyssaldepths.entity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The thalan worm: an enormous worm, about a hundred blocks long, burrowing along the ground of the layer. Its head
 * goes where it wants, its body follows the very way the head went (kept here, tick by tick, on both sides), and so
 * it can be struck anywhere along it ({@link BigWormPart}s). It never falls: it keeps to the top of the ground (up out
 * of rock it is in, down along the bottom under water) and turns away from drops it cannot follow; it turns no tighter
 * than {@link #MIN_RADIUS}, so its body bends rather than breaks. Where it goes through the ground, the ground bursts
 * and rumbles. It wanders; spotting a player it rears its maw open a moment, then comes for them, and bites when its
 * mouth reaches them (the bite lands as the jaws snap shut). Its animations are the model's own ({@link #ANIMATIONS}).
 * A boss bar shows to those it hunts and those near.
 * <p>
 * Its position is the middle of its head; its own box is the head.
 */
public class BigWorm extends PathfinderMob {
    /** The model's animations, by number (the client plays the one the server set, from the tick it was set). */
    public static final String[] ANIMATIONS = {"", "keepMouthOpen", "shutMouth", "bite1", "bite2", "shortBite"};
    public static final int NONE = 0, GAPE = 1, SHUT = 2, BITE1 = 3, BITE2 = 4, SHORT_BITE = 5;
    private static final EntityDataAccessor<Integer> ANIM = SynchedEntityData.defineId(BigWorm.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ANIM_START = SynchedEntityData.defineId(BigWorm.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> BODY = SynchedEntityData.defineId(BigWorm.class, EntityDataSerializers.FLOAT);

    /** Blocks from the middle of the head to the mouth's front, and to the back of the head (where the body joins). */
    public static final double MOUTH = 3.6, JOINT = 3.5, AXIS = 3.0;
    /** How long its body is (blocks), and its tightest turn (radius, blocks). */
    public static final double LENGTH = 100.0, MIN_RADIUS = 9.0;
    /** Each worm's length is drawn between these (blocks). */
    public static final float SHORTEST = 85.0F, LONGEST = 125.0F;
    /** The last stretch of its body narrowing into the tail (blocks), down to this share of its full width. */
    public static final double TAIL = 0.36, TAIL_END = 0.3; // (the share of its length)
    /** The highest it goes (its position): it keeps to the thalan layer, and outside it bores down into it. */
    public static final double TOP = redero.abyssaldepths.worldgen.TunnelLayer.LAYER_TOP_Y - 2;
    private static final double SIGHT = 96.0, REACH = 3.2, CRAWL = 0.22, CHASE = 0.42;
    /** How deep it goes along: sunk this far into the floor, as if burrowing along it. */
    public static final double SINK = 2.0;
    private static final float DAMAGE = 72.0F;
    private static final int BITE_TICKS = 10, BITE_COOLDOWN = 34, GAPE_TICKS = 40, PARTS = 32;
    private boolean sized;

    private Player target;
    private int biteAt = -1, nextBite, gapeUntil = -1;
    private float wanderYaw, yawSpeed, steer;
    private double ySpeed;
    private boolean firstBite = true;
    private int biteLength = 10;
    /** The way its head went: the middle of its head (on its axis), tick by tick, newest first. Smooth, whatever it turns. */
    private final Deque<Vec3> way = new ArrayDeque<>();
    private final BigWormPart[] parts = new BigWormPart[PARTS];
    private final ServerBossEvent bar = new ServerBossEvent(net.minecraft.network.chat.Component.translatable("entity.abyssaldepths.big_worm"),
            BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10);

    public BigWorm(EntityType<? extends BigWorm> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
        setPersistenceRequired();
        xpReward = 100;
        for (int i = 0; i < PARTS; i++) {
            final float k = (float) thickness((i + 0.5) / PARTS);
            parts[i] = new BigWormPart(this, 5.5F * k, 6.0F * k);
        }
        setId(ENTITY_COUNTER.getAndAdd(PARTS + 1) + 1); // its parts' ids follow its own (as the dragon's do)
    }

    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 400.0).add(Attributes.ARMOR, 10.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0).add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ANIM, NONE);
        builder.define(ANIM_START, 0);
        builder.define(BODY, (float) LENGTH);
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        for (int i = 0; i < parts.length; i++) {
            parts[i].setId(id + i + 1);
        }
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public net.neoforged.neoforge.entity.PartEntity<?>[] getParts() {
        return parts;
    }

    /** The animation playing (a name of the model's, or "" for none) and the tick it began. */
    public String animation() {
        return ANIMATIONS[Mth.clamp(entityData.get(ANIM), 0, ANIMATIONS.length - 1)];
    }

    public int animationStart() {
        return entityData.get(ANIM_START);
    }

    private void play(int animation) {
        entityData.set(ANIM, animation);
        entityData.set(ANIM_START, tickCount);
    }

    /** Where its mouth is (the front of the head). */
    public Vec3 mouth() {
        return position().add(Vec3.directionFromRotation(getXRot(), getYRot()).scale(MOUTH)).add(0, AXIS, 0);
    }

    /** The point on its axis at the middle of its head, the worm at a place: the way is made of these. */
    public static Vec3 centre(Vec3 at) {
        return at.add(0, AXIS, 0);
    }

    /**
     * The point {@code s} blocks back along the way from {@code start} (the middle of the head now), and the way
     * forward there (towards the head); where the way remembered runs out, straight on back.
     */
    public Vec3[] along(Vec3 start, Vec3 back, double s) {
        Vec3 prev = start, dir = back;
        double left = s;
        final Iterator<Vec3> it = way.iterator();
        if (it.hasNext()) {
            it.next(); // (the newest is where the head is now)
        }
        while (it.hasNext()) {
            final Vec3 next = it.next();
            final double d = next.distanceTo(prev);
            if (d > 1.0E-4) {
                dir = next.subtract(prev).scale(1.0 / d);
                if (d >= left) {
                    return new Vec3[] {prev.add(dir.scale(left)), dir.scale(-1)};
                }
            }
            left -= d;
            prev = next;
        }
        return new Vec3[] {prev.add(dir.scale(left)), dir.scale(-1)}; // past the end: on along its last stretch
    }

    /** How thick its body is (share of full) a distance back from where it joins the head: it narrows into the tail. */
    public static double thickness(double share) {
        final double f = Mth.clamp((share - (1.0 - TAIL)) / TAIL, 0.0, 1.0);
        return 1.0 - (1.0 - TAIL_END) * f * f * (3 - 2 * f);
    }

    /** How long its body is (blocks): each worm's own. */
    public float length() {
        return entityData.get(BODY);
    }

    /** How far a point is from the nearest part of it (head or body). */
    public double nearest(Vec3 to) {
        double near = position().distanceTo(to);
        for (BigWormPart part : parts) {
            near = Math.min(near, part.position().distanceTo(to));
        }
        return near;
    }

    @Override
    public void travel(Vec3 input) {
        // it moves itself (aiStep): no walking, falling or swimming
    }

    @Override
    public void tick() {
        super.tick();
        if (!sized && !level().isClientSide) { // a new worm: its own length
            sized = true;
            entityData.set(BODY, SHORTEST + random.nextFloat() * (LONGEST - SHORTEST));
        }
        // the way, kept on both sides (at first laid out straight behind it, so its whole body lies still behind it
        // and only ever follows where the head went); the parts along it
        if (way.isEmpty()) {
            final Vec3 dir = Vec3.directionFromRotation(getXRot(), getYRot());
            for (double d = length() + JOINT + 20; d > 0; d -= 0.5) {
                way.addFirst(centre(position()).subtract(dir.scale(d)));
            }
        }
        way.addFirst(centre(position()));
        double kept = 0;
        Vec3 last = null;
        for (Iterator<Vec3> it = way.iterator(); it.hasNext(); ) {
            final Vec3 p = it.next();
            if (last != null) {
                kept += p.distanceTo(last);
            }
            last = p;
            if (kept > length() + JOINT + 20) {
                it.remove();
            }
        }
        final Vec3 start = centre(position()), back = Vec3.directionFromRotation(getXRot(), getYRot()).scale(-1);
        for (int i = 0; i < PARTS; i++) {
            final Vec3 p = along(start, back, JOINT + length() * (i + 0.5) / PARTS)[0];
            final double low = p.y - parts[i].getBbHeight() / 2;
            parts[i].setPos(p.x, low, p.z);
            parts[i].xo = parts[i].xOld = p.x;
            parts[i].yo = parts[i].yOld = low;
            parts[i].zo = parts[i].zOld = p.z;
        }
        // its body is solid: what it moves into is shoved aside
        if (!level().isClientSide && tickCount % 2 == 0) {
            for (BigWormPart part : parts) {
                for (net.minecraft.world.entity.Entity e : level().getEntities(this, part.getBoundingBox().inflate(0.2),
                        e -> e instanceof net.minecraft.world.entity.LivingEntity && !(e instanceof BigWorm) && !e.isSpectator())) {
                    final Vec3 away = new Vec3(e.getX() - part.getX(), 0, e.getZ() - part.getZ());
                    final Vec3 push = (away.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : away.normalize()).scale(0.35);
                    e.setDeltaMovement(push.x, Math.max(e.getDeltaMovement().y, 0.25), push.z);
                    e.hurtMarked = true;
                }
            }
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide) {
            return;
        }
        // whom it is after: the nearest player it can sense, not too far
        if (target != null && (!target.isAlive() || target.isCreative() || target.isSpectator() || target.distanceTo(this) > SIGHT * 1.3)) {
            target = null;
            play(SHUT);
        }
        if (target == null) {
            final Player near = level().getNearestPlayer(this, SIGHT);
            if (near != null && !near.isCreative() && !near.isSpectator()) {
                target = near;
                gapeUntil = tickCount + GAPE_TICKS; // it rears its maw open at them
                play(GAPE);
                level().playSound(null, blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3.0F, 0.4F);
            }
        }
        if (gapeUntil >= 0 && tickCount >= gapeUntil) {
            gapeUntil = -1;
            play(SHUT);
        }
        if (target instanceof ServerPlayer hunted && tickCount % 5 == 0) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(hunted, new redero.abyssaldepths.network.EffectPayload(
                    String.format(java.util.Locale.ROOT, "worm_chase/%.1f", nearest(hunted.position())), 0));
        }
        crawl();
        bite();
        burrow();
        bar.setProgress(getHealth() / getMaxHealth());
        if (tickCount % 20 == 0 && level() instanceof ServerLevel server) { // the bar for those near
            for (ServerPlayer p : server.players()) {
                final double near = nearest(p.position());
                if (near < 96 && !bar.getPlayers().contains(p)) {
                    bar.addPlayer(p);
                } else if (near > 112) {
                    bar.removePlayer(p);
                }
            }
        }
    }

    /**
     * It crawls on: it looks a few blocks ahead along headings round the one it wants and takes the nearest that keeps
     * to the ground (no wall too high, no drop), boring straight through only where none does; it turns with weight
     * (its turning speeds up and eases off, never tighter than its tightest turn) and sways a little, so it never runs
     * dead straight; it never stops or turns on the spot (its body would coil).
     */
    private void crawl() {
        final double speed;
        float want;
        if (target != null && target.getY() < TOP + 12) { // (a player above the layer it does not follow)
            final Vec3 to = target.position().subtract(position());
            want = (float) (Mth.atan2(-to.x, to.z) * Mth.RAD_TO_DEG);
            speed = gapeUntil >= 0 ? CRAWL * 0.5 : CHASE;
        } else {
            if (tickCount % 120 == 0) {
                wanderYaw = getYRot() + (random.nextFloat() - 0.5F) * 140.0F;
            }
            want = wanderYaw;
            speed = CRAWL;
        }
        want += (target != null ? 7.0F : 14.0F) * Mth.sin(tickCount * 0.035F); // a slow sway
        if (tickCount % 5 == 0) {
            steer = getY() > TOP ? want : way(want);
        }
        // turning with weight: it eases into a turn and out of it
        final float most = (float) Math.toDegrees(speed / MIN_RADIUS);
        final float wanted = Mth.clamp(Mth.wrapDegrees(steer - getYRot()) * 0.06F, -most, most);
        yawSpeed += Mth.clamp(wanted - yawSpeed, -most * 0.12F, most * 0.12F);
        final float yaw = getYRot() + yawSpeed;
        final Vec3 step = Vec3.directionFromRotation(0, yaw).scale(speed);
        final double x = getX() + step.x, z = getZ() + step.z;
        // the height of the ground under and ahead of it, evened out (steps and single blocks are not followed one by
        // one: it rises and sinks over them in a long, smooth swell, as a body that size would)
        final Vec3 dir = Vec3.directionFromRotation(0, yaw);
        double sum = 0;
        int n = 0;
        for (double a = -2; a <= 8; a += 2) {
            for (double side = -2; side <= 2; side += 2) {
                final double gx = x + dir.x * a - dir.z * side, gz = z + dir.z * a + dir.x * side;
                final int g = ground(gx, getY() + SINK, gz);
                if (g != Integer.MIN_VALUE && Math.abs(g + 1 - SINK - getY()) <= 5) {
                    sum += g + 1 - SINK;
                    n++;
                }
            }
        }
        double toY = n >= 5 ? sum / n : getY() - 12; // no ground near its height: down till it finds some
        if (target != null && target.getY() < TOP + 12 && Math.hypot(target.getX() - getX(), target.getZ() - getZ()) < 48
                && Math.abs(target.getY() - SINK - getY()) > 6) {
            toY = target.getY() - SINK; // the one it hunts far above or below: through the rock after them
        }
        toY = Math.max(toY, level().getMinBuildHeight() + 3);
        if (getY() > TOP) {
            toY = getY() - 12; // outside the layer: down into it, boring
        } else {
            toY = Math.min(toY, TOP); // it never leaves it
        }
        setYRot(yaw);
        setYHeadRot(yaw);
        yBodyRot = yaw;
        // rising and sinking with weight too, never steeper than its body bends
        final double wantY = Mth.clamp((toY - getY()) * 0.08, -speed * 0.5, speed * 0.5);
        ySpeed += Mth.clamp(wantY - ySpeed, -speed * 0.03, speed * 0.03);
        setXRot((float) -Math.toDegrees(Math.atan2(ySpeed, speed)));
        setPos(x, getY() + ySpeed, z);
    }

    /** The heading nearest to the one wanted that keeps to the ground for the next blocks; the one wanted if none does. */
    private float way(float want) {
        for (float off : new float[] {0, 20, -20, 40, -40, 65, -65, 95, -95}) {
            if (passable(want + off)) {
                return want + off;
            }
        }
        return want; // hemmed in: it bores on
    }

    /** Whether the ground goes on along a heading for the next twelve blocks: no step up or down it could not follow. */
    private boolean passable(float yaw) {
        final Vec3 dir = Vec3.directionFromRotation(0, yaw);
        double y = getY() + SINK;
        for (int d = 3; d <= 12; d += 3) {
            final int g = ground(getX() + dir.x * d, y, getZ() + dir.z * d);
            if (g == Integer.MIN_VALUE || Math.abs(g + 1 - y) > 2.5 || g + 1 - SINK > TOP) {
                return false;
            }
            y = g + 1;
        }
        return true;
    }

    /**
     * The top of the ground at a column near a height: up out of it if that height is in it, else down to it (under
     * water along the bottom); MIN_VALUE where there is none within reach (a drop, the void).
     */
    private int ground(double x, double y, double z) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, Math.floor(y), z);
        if (solid(pos)) {
            for (int k = 0; k < 10; k++) {
                pos.move(0, 1, 0);
                if (!solid(pos)) {
                    return pos.getY() - 1;
                }
            }
            return Integer.MIN_VALUE;
        }
        for (int k = 0; k < 16 && pos.getY() > level().getMinBuildHeight(); k++) {
            pos.move(0, -1, 0);
            if (solid(pos)) {
                return pos.getY();
            }
        }
        return Integer.MIN_VALUE;
    }

    private boolean solid(BlockPos pos) {
        return level().getBlockState(pos).isCollisionShapeFullBlock(level(), pos);
    }

    /** Biting: when its mouth reaches them, the jaws open, and the bite lands as they snap shut. */
    private void bite() {
        if (target != null && gapeUntil < 0 && tickCount >= nextBite && biteAt < 0 && mouth().distanceTo(target.position().add(0, 1, 0)) < REACH + 2.0) {
            biteAt = tickCount;
            nextBite = tickCount + BITE_COOLDOWN;
            final int kind = firstBite || random.nextInt(5) == 0 ? BITE1 : random.nextInt(3) == 0 ? SHORT_BITE : BITE2; // wide first, then mostly plain, now and then a quick snap
            firstBite = false;
            biteLength = kind == SHORT_BITE ? 5 : 10;
            play(kind);
            level().playSound(null, blockPosition(), SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 2.0F, 0.5F);
        }
        if (biteAt >= 0 && tickCount >= biteAt + biteLength) {
            biteAt = -1;
            if (target != null && mouth().distanceTo(target.position().add(0, 1, 0)) < REACH + 1.0) {
                target.hurt(damageSources().mobAttack(this), DAMAGE);
                final Vec3 push = Vec3.directionFromRotation(0, getYRot());
                target.push(push.x * 0.8, 0.4, push.z * 0.8);
                target.hurtMarked = true;
            }
            level().playSound(null, blockPosition(), SoundEvents.RAVAGER_ATTACK, SoundSource.HOSTILE, 2.0F, 0.5F);
        }
    }

    /**
     * Where it bores through a wall (rock above the floor it runs along): the rock bursts round its head, now and then a
     * deep rumble. (Its nearness is heard otherwise: stone crackling along its body, on the clients.)
     */
    private void burrow() {
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        final Vec3 front = position().add(Vec3.directionFromRotation(getXRot(), getYRot()).scale(MOUTH));
        BlockState through = null;
        for (int dy = (int) Math.ceil(SINK) + 1; dy <= 5 && through == null; dy++) {
            final BlockPos at = BlockPos.containing(front.x, getY() + dy, front.z);
            final BlockState st = level().getBlockState(at);
            if (st.isCollisionShapeFullBlock(level(), at)) {
                through = st;
            }
        }
        if (through != null) {
            if (tickCount % 3 == 0) {
                server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, through), front.x, getY() + AXIS, front.z, 10, 1.8, 1.4, 1.8, 0.15);
            }
            if (tickCount % 50 == 0) {
                level().playSound(null, BlockPos.containing(front), SoundEvents.WARDEN_DIG, SoundSource.HOSTILE, 1.6F, 0.5F);
            }
        }
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        bar.removePlayer(player);
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        bar.removeAllPlayers();
    }

    /** Not saved with the land: {@link WormManager} keeps every worm (it lives on while its land is unloaded). */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    /** It becomes real again where the manager kept it: there, turned so, as hurt as it was. */
    void restore(double x, double y, double z, float yaw, float health, float length) {
        moveTo(x, y, z, yaw, 0.0F);
        entityData.set(BODY, length);
        sized = true;
        wanderYaw = steer = yaw;
        setHealth(Math.min(getMaxHealth(), health));
    }

    /** Fire and lava do nothing to it. */
    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }
}
