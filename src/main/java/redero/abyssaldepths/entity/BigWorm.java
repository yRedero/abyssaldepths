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
import net.minecraft.world.level.block.Blocks;
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
public class BigWorm extends PathfinderMob implements redero.abyssaldepths.cinema.CutsceneActor {
    /** The model's animations, by number (the client plays the one the server set, from the tick it was set). */
    public static final String[] ANIMATIONS = {"", "keepMouthOpen", "shutMouth", "bite1", "bite2", "shortBite"};
    public static final int NONE = 0, GAPE = 1, SHUT = 2, BITE1 = 3, BITE2 = 4, SHORT_BITE = 5;
    private static final EntityDataAccessor<Integer> ANIM = SynchedEntityData.defineId(BigWorm.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ANIM_START = SynchedEntityData.defineId(BigWorm.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> BODY = SynchedEntityData.defineId(BigWorm.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> SWEEP_DATA = SynchedEntityData.defineId(BigWorm.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SLAM_DATA = SynchedEntityData.defineId(BigWorm.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> SLAM_SHARE = SynchedEntityData.defineId(BigWorm.class, EntityDataSerializers.FLOAT);

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
    /**
     * How cunning it is in a hunt, 1 to 4 (each worm its own): 1 comes straight on; 2 aims where its prey is going;
     * 3 circles its prey, closing in, its body walling them in, and lunges; 4 circles the way they run, cutting off
     * their escape, closes the ring tight and lunges when they are cornered or look away.
     */
    private int smarts;
    public static final int SMARTEST = 4;
    private static final int APPROACH = 0, CIRCLE = 1, LUNGE = 2;
    private int phase = APPROACH, phaseUntil, circleSign = 1;
    private Vec3 seen, seenVel = Vec3.ZERO;
    /** How many lunges it made at its prey (each makes its ring tighter and its circling shorter). */
    private int lunges;
    /** Whether it is striking now (lunging, or its prey close): it turns tight, rears up at them, does not sway. */
    private boolean striking;
    /** How near its mouth came to its prey while the jaws were closing (the bite lands if near enough at any moment). */
    private double biteBest = Double.MAX_VALUE;
    /** Its tightest turn while striking (blocks). */
    private static final double STRIKE_RADIUS = 4.5;

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
        builder.define(SWEEP_DATA, -1);
        builder.define(SLAM_DATA, -1);
        builder.define(SLAM_SHARE, 0.5F);
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

    /** The animation playing (a name of the model's, or "" for none) and the game tick it began. */
    public String animation() {
        return ANIMATIONS[Mth.clamp(entityData.get(ANIM), 0, ANIMATIONS.length - 1)];
    }

    public int animationStart() {
        return entityData.get(ANIM_START);
    }

    private void play(int animation) {
        entityData.set(ANIM, animation);
        entityData.set(ANIM_START, (int) level().getGameTime());
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

    private Acting acting;

    @Override
    public void act(String animation, long fromTick) {
        acting = animation.isEmpty() ? null : new Acting(animation, fromTick);
    }

    @Override
    public Acting acted() {
        return acting;
    }

    /** Whom it hunts now, or null. */
    public Player hunting() {
        return target;
    }

    /** Whether a player is down in the thalan layer (it does not even notice those above it). */
    public static boolean inLayer(Player p) {
        return p.getY() < redero.abyssaldepths.worldgen.TunnelLayer.LAYER_TOP_Y;
    }

    /** How cunning it is (1 to 4). */
    public int smarts() {
        return smarts;
    }

    public void setSmarts(int smarts) {
        this.smarts = Mth.clamp(smarts, 1, SMARTEST);
    }

    /** A cunning drawn for a new worm: the plain more often than the cunning. */
    public static int drawSmarts(net.minecraft.util.RandomSource random) {
        final float r = random.nextFloat();
        return r < 0.35F ? 1 : r < 0.65F ? 2 : r < 0.87F ? 3 : 4;
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
            if (smarts == 0) {
                smarts = drawSmarts(random);
            }
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
        // its body is solid, like a boat: one can stand on it; what it moves into is lifted onto it, not shoved off
        if (!level().isClientSide && tickCount % 2 == 0) {
            for (BigWormPart part : parts) {
                final double top = part.getBoundingBox().maxY;
                for (net.minecraft.world.entity.Entity e : level().getEntities(this, part.getBoundingBox(),
                        e -> e instanceof net.minecraft.world.entity.LivingEntity && !(e instanceof BigWorm) && !e.isSpectator())) {
                    if (e.getY() < top - 0.4) { // in it (not standing on it)
                        e.setDeltaMovement(e.getDeltaMovement().x, Math.max(e.getDeltaMovement().y, 0.32), e.getDeltaMovement().z);
                        e.hurtMarked = true;
                    }
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
        if (target != null && (!target.isAlive() || target.isCreative() || target.isSpectator() || target.distanceTo(this) > SIGHT * 1.3
                || !inLayer(target))) {
            target = null;
            play(SHUT);
        }
        if (target == null && retreatUntil <= tickCount) {
            final Player near = level().getNearestPlayer(getX(), getY(), getZ(), SIGHT,
                    p -> p instanceof Player pl && !pl.isCreative() && !p.isSpectator() && inLayer(pl));
            if (near != null) {
                target = near;
                phase = APPROACH;
                lunges = 0;
                huntStart = tickCount;
                nextSkill = tickCount + 80;
                seen = null;
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
        skills();
        crawl();
        bite();
        constrict();
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
        double speed;
        float want;
        striking = false;
        if (skillSpeed >= 0) { // a skill steers it
            want = (float) skillWant;
            speed = skillSpeed;
            striking = skillTight;
        } else if (retreatUntil > tickCount) { // slinking off: away from where it was hurt
            want = (float) (Mth.atan2(-(getX() - retreatFrom.x), getZ() - retreatFrom.z) * Mth.RAD_TO_DEG);
            speed = CHASE;
        } else if (target != null) {
            final double[] hunt = hunt();
            want = (float) hunt[0];
            speed = (gapeUntil >= 0 ? CRAWL * 0.5 : hunt[1]) * (biteAt >= 0 ? 0.45 : 1.0); // (it checks itself as the jaws close)
            striking = phase == LUNGE || Math.hypot(target.getX() - getX(), target.getZ() - getZ()) < 14;
        } else {
            if (tickCount % 120 == 0) {
                wanderYaw = getYRot() + (random.nextFloat() - 0.5F) * 140.0F;
            }
            want = wanderYaw;
            speed = CRAWL;
        }
        if (enraged) {
            speed *= 1.2;
        }
        if (skillSpeed < 0) {
            want += (target != null ? (phase == APPROACH && !striking ? 7.0F : 0.0F) : 14.0F) * Mth.sin(tickCount * 0.035F); // a slow sway
        }
        if (striking || skillSpeed >= 0) {
            steer = want; // straight at them (or where the skill wants), whatever is in the way
        } else if (tickCount % 5 == 0) {
            steer = getY() > TOP ? want : way(want);
        }
        // turning with weight: it eases into a turn and out of it
        final float most = (float) Math.toDegrees(speed / (striking ? STRIKE_RADIUS : MIN_RADIUS));
        final float wanted = Mth.clamp(Mth.wrapDegrees(steer - getYRot()) * (striking ? 0.25F : 0.06F), -most, most);
        yawSpeed += Mth.clamp(wanted - yawSpeed, -most * (striking ? 0.4F : 0.12F), most * (striking ? 0.4F : 0.12F));
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
        if (target != null && Math.hypot(target.getX() - getX(), target.getZ() - getZ()) < 14 && Math.abs(target.getY() - SINK - getY()) > 6) {
            toY = target.getY() - SINK; // the one it hunts right above or below it, and no way round: through the rock after them
        }
        if (!Double.isNaN(skillY)) {
            toY = skillY;
        } else if (striking && target != null && Math.hypot(target.getX() - getX(), target.getZ() - getZ()) < 9) {
            toY = target.getY() - 1.5; // close: it rears up out of the ground at them (on its back too)
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
        final double climb = Math.max(speed * (striking ? 0.9 : 0.5), skillClimb), change = Math.max(speed * (striking ? 0.12 : 0.03), skillClimb * 0.15);
        final double wantY = Mth.clamp((toY - getY()) * (striking ? 0.2 : 0.08), -climb, climb);
        ySpeed += Mth.clamp(wantY - ySpeed, -change, change);
        setXRot((float) -Math.toDegrees(Math.atan2(ySpeed, Math.max(speed, 0.05))));
        setPos(x, getY() + ySpeed, z);
    }

    /**
     * The heading it takes for one wanted: the nearest that keeps to the ground for the next sixteen blocks; failing
     * that, the one that keeps to it longest (a turn along the cavern rather than into its wall); only hemmed in
     * all round does it bore on through the rock.
     */
    private float way(float want) {
        float best = want;
        double bestScore = -1;
        for (float off : new float[] {0, 15, -15, 30, -30, 50, -50, 75, -75, 100, -100, 130, -130}) {
            final int run = run(want + off);
            if (run >= 16) {
                return want + off;
            }
            final double score = run - Math.abs(off) * 0.02;
            if (score > bestScore) {
                bestScore = score;
                best = want + off;
            }
        }
        return bestScore >= 6 ? best : want; // hemmed in: it bores on
    }

    /** How far the ground goes on along a heading (up to sixteen blocks) with no step up or down it could not follow. */
    private int run(float yaw) {
        final Vec3 dir = Vec3.directionFromRotation(0, yaw);
        double y = getY() + SINK;
        for (int d = 2; d <= 16; d += 2) {
            final int g = ground(getX() + dir.x * d, y, getZ() + dir.z * d);
            if (g == Integer.MIN_VALUE || Math.abs(g + 1 - y) > 2.5 || g + 1 - SINK > TOP) {
                return d - 2;
            }
            y = g + 1;
        }
        return 16;
    }

    /**
     * Its hunt, by its cunning: the heading it wants and its speed. (Where its prey is going is reckoned from how they
     * moved the last ticks.)
     */
    private double[] hunt() {
        final Vec3 at = target.position();
        seenVel = seen == null ? Vec3.ZERO : seenVel.scale(0.8).add(at.subtract(seen).scale(0.2));
        seen = at;
        final Vec3 to = at.subtract(position());
        final double dist = Math.hypot(to.x, to.z);
        final double straight = Mth.atan2(-to.x, to.z) * Mth.RAD_TO_DEG;
        final int level = Math.max(1, smarts);
        if (level == 1) {
            return new double[] {straight, CHASE};
        }
        // where they will be by the time it gets there
        final double lead = Mth.clamp(dist / CHASE, 0, level >= 4 ? 50 : 30);
        final Vec3 ahead = at.add(seenVel.x * lead, 0, seenVel.z * lead).subtract(position());
        final double intercept = Mth.atan2(-ahead.x, ahead.z) * Mth.RAD_TO_DEG;
        if (level == 2) {
            return new double[] {intercept, CHASE};
        }
        final double chase = level >= 4 ? CHASE * 1.1 : CHASE;
        switch (phase) {
            case APPROACH -> {
                if (dist < 34) { // near: it starts to circle them
                    phase = CIRCLE;
                    phaseUntil = tickCount + circling(level);
                    circleSign = level >= 4 ? cutOff(to) : (random.nextBoolean() ? 1 : -1);
                }
                return new double[] {intercept, chase};
            }
            case CIRCLE -> {
                // the ring narrows as the time runs out (its body, following its head round, walls them in)
                final double left = Mth.clamp((phaseUntil - tickCount) / (double) circling(level), 0, 1);
                final double radius = Math.max(MIN_RADIUS + 0.5, Mth.lerp(left, level >= 4 ? 11.0 : 13.0, 19.0) - 1.5 * lunges);
                final double heading = straight + circleSign * Mth.clamp(90.0 - 6.0 * (dist - radius), 15.0, 165.0);
                final double outward = seenVel.x * to.x + seenVel.z * to.z; // >0: they move away from it
                if (level >= 4 && dist > radius + 6 && outward > 0.12 * dist) { // breaking out: cut them off where they run
                    return new double[] {intercept, chase * 1.15};
                }
                final Vec3 look = target.getLookAngle();
                final boolean away = look.x * -to.x + look.z * -to.z < -0.3 * dist; // they look away from it
                if (tickCount >= phaseUntil || dist < radius * 0.6 || (level >= 4 && (away || seenVel.horizontalDistance() < 0.02) && dist < radius + 3)) {
                    phase = LUNGE; // cornered, looking away, or time: it lunges
                    phaseUntil = tickCount + 80;
                    if (level >= 4 && skill == NO_SKILL && dist > 6 && dist < 18) { // the most cunning: first it draws them in
                        startSkill(INHALE);
                    }
                    level().playSound(null, blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2.5F, 0.55F);
                }
                return new double[] {heading, chase * 0.95};
            }
            default -> { // LUNGE
                if (tickCount >= phaseUntil) {
                    lunges++;
                    phase = dist < 16 && lunges % 3 != 0 ? LUNGE : CIRCLE; // still close: at them again at once (every third time it draws back round them)
                    phaseUntil = tickCount + (phase == LUNGE ? 70 : circling(level));
                    if (level >= 4) {
                        circleSign = cutOff(to);
                    }
                }
                return new double[] {intercept, chase * 1.3};
            }
        }
    }

    /**
     * The cunning (3 and 4) crush what their ring walls in: a prey with its body close round them on (nearly) every
     * side is squeezed, hurt and slowed, the harder the tighter the ring.
     */
    private void constrict() {
        if (target == null || smarts < 3 || tickCount % 10 != 0) {
            return;
        }
        final boolean[] sides = new boolean[8];
        double closest = Double.MAX_VALUE;
        for (BigWormPart part : parts) {
            final double dx = part.getX() - target.getX(), dz = part.getZ() - target.getZ(), d = Math.hypot(dx, dz);
            if (d < 11 && Math.abs(part.getY() + part.getBbHeight() / 2 - target.getY()) < 6) {
                sides[(int) ((Math.atan2(dz, dx) + Math.PI) / (Math.PI * 2) * 8) & 7] = true;
                closest = Math.min(closest, d);
            }
        }
        int walled = 0;
        for (boolean side : sides) {
            walled += side ? 1 : 0;
        }
        if (walled >= 6) {
            final float squeeze = (float) Mth.clamp(1.0 - (closest - 3.0) / 8.0, 0.3, 1.0);
            target.hurt(damageSources().mobAttack(this), 4.0F + 10.0F * squeeze);
            target.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 30, squeeze > 0.7F ? 2 : 1));
            level().playSound(null, target.blockPosition(), SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 1.5F, 0.4F);
            if (phase == CIRCLE) {
                phase = LUNGE; // walled in: now for the bite
                phaseUntil = tickCount + 70;
            }
        }
    }

    // ============================================================== its skills
    // Each comes with a warning first, so one who watches can get away. Which it has goes by its cunning (see
    // eligible). They steer it while they last (skillWant, skillSpeed, skillY, skillTight, skillClimb).

    private static final int NO_SKILL = 0, ERUPT = 1, CHARGE = 2, INHALE = 3, SPIT = 4, SWEEP = 5, SLAM = 6, CEILING = 7, CAVE_IN = 8,
            UNDERMINE = 9, AMBUSH = 10;
    private int skill = NO_SKILL, skillStart, skillStage, nextSkill = 100, huntStart;
    private Vec3 skillSpot = Vec3.ZERO;
    private float skillHeading;
    private boolean skillHit;
    private double skillWant, skillSpeed = -1, skillY = Double.NaN, skillClimb;
    private boolean skillTight;
    private int stunnedUntil = -1, slamPart;
    private boolean enraged, shed, ambushing;
    private int retreatUntil = -1, lastRetreat = -100000;
    private Vec3 retreatFrom = Vec3.ZERO;
    /** Rocks it threw or brought down (falling blocks), and where they were last: they burst where they land. */
    private final java.util.Map<net.minecraft.world.entity.item.FallingBlockEntity, Vec3> rocks = new java.util.HashMap<>();
    /** Pits it opened (blocks taken out, with when they come back). */
    private final java.util.List<Object[]> pits = new java.util.ArrayList<>();
    private final java.util.Map<java.util.UUID, Integer> tremored = new java.util.HashMap<>(), standing = new java.util.HashMap<>();

    /** Whether it keeps quiet now (lying in ambush, or slinking away to heal): nothing of it is heard. */
    public boolean silent() {
        return ambushing || retreatUntil > tickCount;
    }

    /** The tail sweep playing (game tick it began, times two, plus 1 for the one side), or -1. */
    public int sweep() {
        return entityData.get(SWEEP_DATA);
    }

    /** The body slam playing (game tick it began), or -1; and where along the body (share of its length). */
    public int slam() {
        return entityData.get(SLAM_DATA);
    }

    public float slamShare() {
        return entityData.get(SLAM_SHARE);
    }

    private int now() {
        return (int) level().getGameTime();
    }

    private void endSkill() {
        skill = NO_SKILL;
        skillSpeed = -1;
        skillY = Double.NaN;
        ambushing = false;
        nextSkill = tickCount + (int) ((70 + random.nextInt(70)) * (enraged ? 0.6 : 1.0));
    }

    private void startSkill(int which) {
        skill = which;
        skillStart = tickCount;
        skillStage = 0;
        skillHit = false;
        skillSpot = target.position();
        skillHeading = getYRot();
    }

    /** Its skills, each tick (server): the passive ones always, an active one chosen when it may. */
    private void skills() {
        rocks();
        pits();
        tremor();
        shrug();
        reactions();
        skillSpeed = -1;
        skillY = Double.NaN;
        skillClimb = 0;
        skillTight = false;
        if (target == null) {
            if (skill != NO_SKILL) {
                endSkill();
            }
            return;
        }
        if (skill == NO_SKILL && tickCount >= nextSkill && gapeUntil < 0 && biteAt < 0 && stunnedUntil < tickCount) {
            choose();
        }
        if (stunnedUntil > tickCount) { // dazed after running into the rock
            skillSpeed = 0.03;
            skillWant = getYRot();
            if (tickCount % 4 == 0 && level() instanceof ServerLevel server) {
                server.sendParticles(ParticleTypes.CRIT, mouth().x, mouth().y + 1, mouth().z, 6, 1.0, 0.6, 1.0, 0.1);
            }
            return;
        }
        final int s = tickCount - skillStart;
        switch (skill) {
            case ERUPT -> erupt(s);
            case CHARGE -> charge(s);
            case INHALE -> inhale(s);
            case SPIT -> spit(s);
            case SWEEP -> sweepTail(s);
            case SLAM -> slamBody(s);
            case CEILING -> ceiling(s);
            case CAVE_IN -> caveIn(s);
            case UNDERMINE -> undermine(s);
            case AMBUSH -> ambush(s);
            default -> {
            }
        }
    }

    /** The skills by name (for the test command). */
    public static final String[] SKILLS = {"", "erupt", "charge", "inhale", "spit", "sweep", "slam", "ceiling", "cavein", "undermine", "ambush"};

    /** Starts a skill now at a player (a test command): true if it could. */
    public boolean force(String name, Player at) {
        final int which = java.util.Arrays.asList(SKILLS).indexOf(name);
        if (which <= 0) {
            return false;
        }
        target = at;
        if (seen == null) {
            seen = at.position();
        }
        if (which == SLAM) {
            double near = Double.MAX_VALUE;
            for (int i = 3; i < PARTS * 3 / 4; i++) {
                final double d = parts[i].position().distanceTo(at.position());
                if (d < near) {
                    near = d;
                    slamPart = i;
                }
            }
        }
        if (which == CAVE_IN && seenVel.horizontalDistance() < 0.05) {
            seenVel = at.position().subtract(position()).multiply(1, 0, 1).normalize().scale(0.2);
        }
        startSkill(which);
        return true;
    }

    /** Picks a skill its cunning has and the moment allows: the tail and the body first if they are near it. */
    private void choose() {
        final int level = Math.max(1, smarts);
        final double dist = Math.hypot(target.getX() - getX(), target.getZ() - getZ());
        // near its tail: the tail sweeps; near its body (3+): the body slams
        for (int i = PARTS * 3 / 4; i < PARTS; i++) {
            if (parts[i].position().distanceTo(target.position()) < 8) {
                startSkill(SWEEP);
                return;
            }
        }
        if (level >= 3) {
            for (int i = 3; i < PARTS * 3 / 4; i++) {
                if (Math.hypot(parts[i].getX() - target.getX(), parts[i].getZ() - target.getZ()) < 5 && dist > 6) {
                    slamPart = i;
                    startSkill(SLAM);
                    return;
                }
            }
        }
        final java.util.List<Integer> can = new java.util.ArrayList<>();
        final boolean sees = hasLineOfSight(target);
        final Vec3 forward = Vec3.directionFromRotation(0, getYRot());
        final Vec3 to = target.position().subtract(position());
        final boolean ahead = to.horizontalDistance() > 0 && (forward.x * to.x + forward.z * to.z) / to.horizontalDistance() > 0.6;
        if (level >= 2 && dist > 8 && dist < 30 && target.onGround()) {
            can.add(ERUPT);
        }
        if (level >= 2 && dist > 12 && dist < 35 && sees) {
            can.add(CHARGE);
        }
        if (level >= 2 && dist > 10 && dist < 40 && sees) {
            can.add(SPIT);
        }
        if (level >= 3 && dist > 6 && dist < 18 && ahead && sees) {
            can.add(INHALE);
        }
        if (level >= 3 && dist < 30 && ceilingAbove(target.position(), 22) != null) {
            can.add(CEILING);
        }
        if (level >= 4 && dist < 25 && target.onGround()) {
            can.add(UNDERMINE);
        }
        if (level >= 4 && dist > 14 && dist < 40 && seenVel.horizontalDistance() > 0.12 && (seenVel.x * to.x + seenVel.z * to.z) > 0) {
            can.add(CAVE_IN);
        }
        if (level >= 4 && tickCount - huntStart > 600 && dist > 24) {
            can.add(AMBUSH);
        }
        if (!can.isEmpty()) {
            startSkill(can.get(random.nextInt(can.size())));
        } else {
            nextSkill = tickCount + 20;
        }
    }

    /** Eruption: it dives under the spot they stood on; the ground cracks and dust rises; then it bursts up through it. */
    private void erupt(int s) {
        final double h = Math.hypot(skillSpot.x - getX(), skillSpot.z - getZ());
        skillTight = true;
        if (skillStage == 0) { // under it
            skillWant = heading(skillSpot);
            skillSpeed = CHASE * 1.1;
            skillY = skillSpot.y - 10;
            skillClimb = 0.4;
            if (h < 3 && getY() < skillSpot.y - 6) {
                skillStage = 1;
                skillStart = tickCount;
            } else if (s > 120) {
                endSkill();
            }
        } else if (skillStage == 1) { // the warning: the ground cracks over it
            skillWant = heading(skillSpot) + 90;
            skillSpeed = 0.15;
            skillY = skillSpot.y - 10;
            warnGround(skillSpot, 2.5, s % 2 == 0);
            if (s >= 22) {
                skillStage = 2;
                skillStart = tickCount;
                play(BITE1);
            }
        } else { // up through it
            skillWant = heading(skillSpot);
            skillSpeed = 0.12;
            skillY = skillSpot.y + 6;
            skillClimb = 0.7;
            if (!skillHit && getY() + AXIS >= skillSpot.y) {
                skillHit = true;
                burst(skillSpot, 3.2, 40.0F, new Vec3(0, 1.1, 0));
                level().playSound(null, BlockPos.containing(skillSpot), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3.0F, 0.5F);
            }
            if (s > 34) {
                endSkill();
            }
        }
    }

    /** Charge: it rears up with its maw held open, roaring, then rushes straight on; into rock it stuns itself. */
    private void charge(int s) {
        if (skillStage == 0) { // the wind-up
            if (s == 0) {
                play(GAPE);
                level().playSound(null, blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3.0F, 0.35F);
            }
            if (s == 28) {
                level().playSound(null, blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3.0F, 0.6F);
            }
            skillTight = true;
            skillWant = heading(target.position());
            skillSpeed = 0.06;
            skillY = target.getY() - 1.5;
            skillClimb = 0.2;
            if (s >= 40) {
                skillStage = 1;
                skillStart = tickCount;
                final Vec3 lead = target.position().add(seenVel.scale(12));
                skillHeading = heading(lead);
            }
        } else { // the rush: no turning
            skillWant = skillHeading;
            skillSpeed = enraged ? 0.95 : 0.85;
            final Vec3 front = position().add(Vec3.directionFromRotation(0, getYRot()).scale(MOUTH + 1.5));
            if (!skillHit) {
                for (Player p : level().getEntitiesOfClass(Player.class, new net.minecraft.world.phys.AABB(mouth(), mouth()).inflate(3.5))) {
                    if (!p.isSpectator() && !p.isCreative()) {
                        skillHit = true;
                        p.hurt(damageSources().mobAttack(this), 50.0F);
                        final Vec3 push = Vec3.directionFromRotation(0, getYRot());
                        p.push(push.x * 2.0, 0.6, push.z * 2.0);
                        p.hurtMarked = true;
                        play(SHORT_BITE);
                    }
                }
            }
            boolean wall = false;
            for (int dy = 1; dy <= 4 && !wall; dy++) {
                wall = solid(BlockPos.containing(front.x, getY() + SINK + dy, front.z));
            }
            if (wall && s > 4) { // into the rock: dazed a while (a chance to hit back)
                stunnedUntil = tickCount + 50;
                play(SHUT);
                level().playSound(null, BlockPos.containing(front), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 3.0F, 0.5F);
                if (level() instanceof ServerLevel server) {
                    server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level().getBlockState(BlockPos.containing(front.x, getY() + SINK + 2, front.z))),
                            front.x, getY() + SINK + 2, front.z, 60, 1.5, 1.5, 1.5, 0.3);
                }
                endSkill();
            } else if (s > 45) {
                play(SHUT);
                endSkill();
            }
        }
    }

    /** Inhale: maw wide, it draws the air in, and what is before it slides towards it (cover, or crouching, holds). */
    private void inhale(int s) {
        if (s == 0) {
            play(GAPE);
        }
        skillTight = true;
        skillWant = heading(target.position());
        skillSpeed = 0.08;
        if (s % 10 == 0) {
            level().playSound(null, blockPosition(), SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 3.0F, 0.4F);
        }
        final Vec3 mouth = mouth(), forward = Vec3.directionFromRotation(getXRot(), getYRot());
        for (net.minecraft.world.entity.Entity e : level().getEntities(this, new net.minecraft.world.phys.AABB(mouth, mouth).inflate(20),
                e -> !(e instanceof BigWorm) && !(e instanceof BigWormPart) && !e.isSpectator() && !(e instanceof Player p && p.isCreative()))) {
            final Vec3 to = mouth.subtract(e.position());
            final double d = to.length();
            if (d < 1.5 || d > 20 || forward.dot(to.scale(-1 / d)) < 0.5) {
                continue;
            }
            if (level().clip(new net.minecraft.world.level.ClipContext(mouth, e.getEyePosition(), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, this)).getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                continue; // behind cover
            }
            final double pull = (e.isShiftKeyDown() ? 0.025 : 0.075) * (1.2 - d / 20);
            e.setDeltaMovement(e.getDeltaMovement().add(to.scale(pull / d)));
            e.hurtMarked = true;
        }
        if (level() instanceof ServerLevel server && s % 2 == 0) {
            for (int i = 0; i < 4; i++) {
                final Vec3 from = mouth.add(forward.scale(6 + random.nextDouble() * 12)).add(random.nextGaussian() * 3, random.nextGaussian() * 2, random.nextGaussian() * 3);
                final Vec3 v = mouth.subtract(from).scale(0.08);
                server.sendParticles(ParticleTypes.CLOUD, from.x, from.y, from.z, 0, v.x, v.y, v.z, 1.0);
            }
        }
        if (s >= 50) {
            play(SHUT);
            phase = LUNGE; // and now the bite
            phaseUntil = tickCount + 60;
            endSkill();
        }
    }

    /** Spit: it throws rock it swallowed burrowing, in arcs; where each will land is marked on the ground. */
    private void spit(int s) {
        skillTight = true;
        skillWant = heading(target.position());
        skillSpeed = 0.12;
        if (s == 0) {
            play(GAPE);
        }
        if (s == 10 || s == 18 || s == 26) {
            final Vec3 from = mouth().add(0, 1, 0);
            final Vec3 aim = target.position().add(seenVel.scale(14)).add(random.nextGaussian(), 0, random.nextGaussian());
            final double t = Mth.clamp(from.distanceTo(aim) / 0.9, 16, 40);
            final double g = 0.04;
            final Vec3 v = new Vec3((aim.x - from.x) / t, (aim.y - from.y + 0.5 * g * t * t) / t, (aim.z - from.z) / t);
            rock(from, v, redero.abyssaldepths.AbyssalDepths.COBBLED_THALAN.get().defaultBlockState(), false, aim);
            level().playSound(null, BlockPos.containing(from), SoundEvents.LLAMA_SPIT, SoundSource.HOSTILE, 3.0F, 0.3F);
        }
        if (s >= 34) {
            play(SHUT);
            endSkill();
        }
    }

    /** Tail sweep: the tail coils back, then lashes across a wide arc. */
    private void sweepTail(int s) {
        if (s == 0) {
            final BigWormPart tail = parts[PARTS - 4], before = parts[PARTS - 8];
            final Vec3 f = before.position().subtract(tail.position());
            final Vec3 lat = new Vec3(-f.z, 0, f.x);
            final boolean side = lat.dot(target.position().subtract(tail.position())) > 0;
            entityData.set(SWEEP_DATA, now() * 2 + (side ? 1 : 0));
            level().playSound(null, tail.blockPosition(), SoundEvents.RAVAGER_STEP, SoundSource.HOSTILE, 3.0F, 0.4F);
        }
        if (s == 14) {
            level().playSound(null, parts[PARTS - 4].blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 3.0F, 0.4F);
        }
        if (s == 17) {
            for (Player p : level().getEntitiesOfClass(Player.class, parts[PARTS - 4].getBoundingBox().inflate(9))) {
                double near = Double.MAX_VALUE;
                for (int i = PARTS * 3 / 4; i < PARTS; i++) {
                    near = Math.min(near, parts[i].position().distanceTo(p.position()));
                }
                if (near < 7 && !p.isSpectator() && !p.isCreative()) {
                    p.hurt(damageSources().mobAttack(this), 24.0F);
                    final Vec3 away = p.position().subtract(parts[PARTS - 4].position()).multiply(1, 0, 1).normalize();
                    p.push(away.x * 1.5, 0.5, away.z * 1.5);
                    p.hurtMarked = true;
                }
            }
        }
        if (s >= 40) {
            entityData.set(SWEEP_DATA, -1);
            endSkill();
        }
    }

    /** Body slam: a stretch of its body rises (its shadow and falling dust beneath), then crashes down. */
    private void slamBody(int s) {
        final BigWormPart part = parts[slamPart];
        final Vec3 under = new Vec3(part.getX(), part.getY() + 1, part.getZ());
        if (s == 0) {
            entityData.set(SLAM_SHARE, (slamPart + 0.5F) / PARTS);
            entityData.set(SLAM_DATA, now());
            level().playSound(null, part.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2.0F, 0.3F);
        }
        if (s < 26 && level() instanceof ServerLevel server && s % 2 == 0) {
            server.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.TUFF.defaultBlockState()), under.x, under.y + 7, under.z, 10,
                    2.5, 0.5, 2.5, 0);
            for (int i = 0; i < 12; i++) {
                final double a = i * Math.PI / 6;
                server.sendParticles(ParticleTypes.SMOKE, under.x + Math.cos(a) * 4, under.y + 0.1, under.z + Math.sin(a) * 4, 1, 0, 0, 0, 0);
            }
        }
        if (s == 28) {
            level().playSound(null, part.blockPosition(), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 3.0F, 0.45F);
            burst(under, 4.5, 30.0F, new Vec3(0, 0.4, 0));
            for (Player p : level().getEntitiesOfClass(Player.class, new net.minecraft.world.phys.AABB(under, under).inflate(4.5, 5, 4.5))) {
                p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 40, 1));
            }
        }
        if (s >= 36) {
            entityData.set(SLAM_DATA, -1);
            endSkill();
        }
    }

    /** Ceiling fall: it rams the rock; dust pours from the ceiling over them, then the ceiling comes down there. */
    private void ceiling(int s) {
        final Integer top = ceilingAbove(skillSpot, 22);
        if (top == null) {
            endSkill();
            return;
        }
        if (s == 0) {
            level().playSound(null, blockPosition(), SoundEvents.WARDEN_ATTACK_IMPACT, SoundSource.HOSTILE, 3.0F, 0.4F);
        }
        if (s < 30 && level() instanceof ServerLevel server) {
            final BlockState rock = level().getBlockState(BlockPos.containing(skillSpot.x, top, skillSpot.z));
            server.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, rock), skillSpot.x, top - 0.1, skillSpot.z, 14, 4.0, 0, 4.0, 0);
            if (s % 6 == 0) {
                level().playSound(null, BlockPos.containing(skillSpot.x, top, skillSpot.z), SoundEvents.DEEPSLATE_HIT, SoundSource.HOSTILE, 2.5F, 0.5F);
            }
        }
        if (s >= 30 && s <= 46 && s % 2 == 0) {
            for (int i = 0; i < 2; i++) {
                final double a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(random.nextDouble()) * 4.5;
                final double x = skillSpot.x + Math.cos(a) * r, z = skillSpot.z + Math.sin(a) * r;
                final Integer y = ceilingAbove(new Vec3(x, skillSpot.y, z), 22);
                if (y != null) {
                    rock(new Vec3(x, y - 0.6, z), Vec3.ZERO, level().getBlockState(BlockPos.containing(x, y, z)), false, null);
                }
            }
        }
        if (s > 50) {
            endSkill();
        }
    }

    /** Cave-in: ahead of one running from it, the ceiling comes down across the way (dust first), walling it off. */
    private void caveIn(int s) {
        if (s == 0) {
            final Vec3 run = seenVel.multiply(1, 0, 1).normalize();
            skillSpot = target.position().add(run.scale(9));
            skillHeading = (float) (Mth.atan2(-run.x, run.z) * Mth.RAD_TO_DEG);
            level().playSound(null, blockPosition(), SoundEvents.WARDEN_ATTACK_IMPACT, SoundSource.HOSTILE, 3.0F, 0.35F);
        }
        final Vec3 run = Vec3.directionFromRotation(0, skillHeading), across = new Vec3(-run.z, 0, run.x);
        if (s < 25 && level() instanceof ServerLevel server && s % 2 == 0) {
            for (int k = -3; k <= 3; k++) {
                final Vec3 at = skillSpot.add(across.scale(k));
                final Integer top = ceilingAbove(at, 20);
                if (top != null) {
                    server.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.COBBLED_DEEPSLATE.defaultBlockState()), at.x, top - 0.1, at.z,
                            3, 0.4, 0, 0.4, 0);
                }
            }
        }
        if (s == 25 || s == 31 || s == 37) {
            for (int k = -3; k <= 3; k++) {
                final Vec3 at = skillSpot.add(across.scale(k));
                final Integer top = ceilingAbove(at, 20);
                if (top != null) {
                    rock(new Vec3(Math.floor(at.x) + 0.5, top - 0.6, Math.floor(at.z) + 0.5), Vec3.ZERO,
                            redero.abyssaldepths.AbyssalDepths.COBBLED_THALAN.get().defaultBlockState(), true, null);
                }
            }
            level().playSound(null, BlockPos.containing(skillSpot), SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 3.0F, 0.4F);
        }
        if (s > 40) {
            endSkill();
        }
    }

    /** Undermine: the ground round them cracks, then gives way into a pit, its maw below; the ground comes back later. */
    private void undermine(int s) {
        skillTight = true;
        skillWant = heading(skillSpot);
        skillSpeed = CHASE;
        skillY = skillSpot.y - 7;
        skillClimb = 0.4;
        if (s < 25) {
            warnGround(skillSpot, 2.5, s % 2 == 0);
        }
        if (s == 25) {
            final java.util.List<Object[]> taken = new java.util.ArrayList<>();
            final BlockPos feet = BlockPos.containing(skillSpot);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx * dx + dz * dz > 5) {
                        continue;
                    }
                    for (int dy = -1; dy >= -4; dy--) {
                        final BlockPos at = feet.offset(dx, dy, dz);
                        final BlockState st = level().getBlockState(at);
                        if (st.isCollisionShapeFullBlock(level(), at) && !st.hasBlockEntity() && st.getDestroySpeed(level(), at) >= 0) {
                            taken.add(new Object[] {at.immutable(), st});
                            level().setBlock(at, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            }
            if (!taken.isEmpty()) {
                pits.add(new Object[] {tickCount + 140, taken});
            }
            level().playSound(null, feet, SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 3.0F, 0.4F);
            if (level() instanceof ServerLevel server) {
                server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBBLED_DEEPSLATE.defaultBlockState()), skillSpot.x, skillSpot.y, skillSpot.z, 80,
                        2, 1, 2, 0.2);
            }
            phase = LUNGE;
            phaseUntil = tickCount + 60;
        }
        if (s > 45) {
            endSkill();
        }
    }

    /** Ambush: it goes quiet (nothing of it is heard) and deep, gets ahead of them, and bursts up where they are. */
    private void ambush(int s) {
        ambushing = true;
        final Vec3 ahead = target.position().add(seenVel.scale(30));
        skillWant = heading(ahead);
        skillSpeed = CHASE * 1.2;
        skillY = target.getY() - 12;
        skillClimb = 0.4;
        if (Math.hypot(target.getX() - getX(), target.getZ() - getZ()) < 9 && getY() < target.getY() - 6) {
            ambushing = false;
            skill = ERUPT; // up, without the warning (the silence was it)
            skillStage = 2;
            skillStart = tickCount;
            skillSpot = target.position();
            skillHit = false;
            play(BITE1);
        } else if (s > 300) {
            endSkill();
        }
    }

    /** Passing right under one on the ground, it heaves it: thrown up, slowed, a little hurt. */
    private void tremor() {
        if (tickCount % 4 != 0) {
            return;
        }
        for (Player p : level().getEntitiesOfClass(Player.class, getBoundingBox().inflate(4, 8, 4))) {
            if (p.isSpectator() || p.isCreative() || !p.onGround() || p.getY() < getY() + 1 || p.getY() > getY() + 8
                    || Math.hypot(p.getX() - getX(), p.getZ() - getZ()) > 3.5 || tickCount < tremored.getOrDefault(p.getUUID(), 0)) {
                continue;
            }
            tremored.put(p.getUUID(), tickCount + 30);
            p.hurt(damageSources().mobAttack(this), 4.0F);
            p.push(0, 0.45, 0);
            p.hurtMarked = true;
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 20, 1));
            level().playSound(null, p.blockPosition(), SoundEvents.GRAVEL_BREAK, SoundSource.HOSTILE, 2.0F, 0.5F);
        }
    }

    /** One standing on its back too long is rolled off. */
    private void shrug() {
        if (tickCount % 5 != 0) {
            return;
        }
        for (Player p : level().getEntitiesOfClass(Player.class, getBoundingBox().inflate(length()))) {
            boolean on = false;
            Vec3 under = null;
            for (BigWormPart part : parts) {
                final net.minecraft.world.phys.AABB box = part.getBoundingBox();
                if (p.getX() > box.minX && p.getX() < box.maxX && p.getZ() > box.minZ && p.getZ() < box.maxZ && p.getY() > box.maxY - 0.4
                        && p.getY() < box.maxY + 0.8) {
                    on = true;
                    under = part.position();
                    break;
                }
            }
            final int count = on ? standing.getOrDefault(p.getUUID(), 0) + 5 : 0;
            standing.put(p.getUUID(), count);
            if (count > 40 && !p.isCreative() && !p.isSpectator()) {
                standing.put(p.getUUID(), 0);
                final Vec3 off = p.position().subtract(under).multiply(1, 0, 1);
                final Vec3 dir = off.lengthSqr() < 1.0E-3 ? new Vec3(1, 0, 0) : off.normalize();
                p.push(dir.x * 1.0, 0.7, dir.z * 1.0);
                p.hurtMarked = true;
                p.hurt(damageSources().mobAttack(this), 2.0F);
                level().playSound(null, p.blockPosition(), SoundEvents.RAVAGER_STUNNED, SoundSource.HOSTILE, 2.0F, 0.4F);
            }
        }
    }

    /** As it is hurt: at two thirds it sheds its hard skin (less armour); at half it rages; at a quarter it slinks off to heal. */
    private void reactions() {
        final float health = getHealth() / getMaxHealth();
        if (!shed && health < 0.66F) {
            shed = true;
            final var armor = getAttribute(Attributes.ARMOR);
            if (armor != null) {
                armor.setBaseValue(0.0);
            }
            level().playSound(null, blockPosition(), SoundEvents.ARMOR_EQUIP_NETHERITE.value(), SoundSource.HOSTILE, 3.0F, 0.3F);
            if (level() instanceof ServerLevel server) {
                for (BigWormPart part : parts) {
                    server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BLACKSTONE.defaultBlockState()), part.getX(), part.getY() + 3, part.getZ(),
                            12, 1.5, 1.5, 1.5, 0.1);
                }
            }
        }
        if (!enraged && health < 0.5F) {
            enraged = true;
            level().playSound(null, blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 4.0F, 0.3F);
        }
        if (enraged && tickCount % 4 == 0 && level() instanceof ServerLevel server) { // a red breath from its maw
            final Vec3 m = mouth();
            server.sendParticles(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(0.55F, 0.04F, 0.08F), 1.6F), m.x, m.y, m.z, 3,
                    0.8, 0.6, 0.8, 0);
        }
        if (health < 0.25F && retreatUntil < tickCount && tickCount - lastRetreat > 6000 && target != null) {
            retreatUntil = tickCount + 1200; // a minute away, healing, silent
            lastRetreat = tickCount;
            retreatFrom = target.position();
            target = null;
            endSkill();
            play(SHUT);
            level().playSound(null, blockPosition(), SoundEvents.RAVAGER_HURT, SoundSource.HOSTILE, 3.0F, 0.3F);
        }
        if (retreatUntil > tickCount && tickCount % 10 == 0) {
            heal(1.0F);
        }
    }

    /** The rocks in flight: where each lands it bursts, hurting those right there. */
    private void rocks() {
        if (rocks.isEmpty()) {
            return;
        }
        for (var it = rocks.entrySet().iterator(); it.hasNext(); ) {
            final var e = it.next();
            final var rock = e.getKey();
            if (rock.isRemoved() || rock.onGround()) {
                final Vec3 at = rock.isRemoved() ? e.getValue() : rock.position();
                for (Player p : level().getEntitiesOfClass(Player.class, new net.minecraft.world.phys.AABB(at, at).inflate(1.8, 2.5, 1.8))) {
                    if (!p.isSpectator() && !p.isCreative()) {
                        p.hurt(damageSources().mobAttack(this), 12.0F);
                    }
                }
                if (level() instanceof ServerLevel server) {
                    server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, rock.getBlockState()), at.x, at.y + 0.5, at.z, 30, 0.6, 0.4, 0.6, 0.15);
                }
                level().playSound(null, BlockPos.containing(at), SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 2.0F, 0.6F);
                it.remove();
            } else {
                e.setValue(rock.position());
            }
        }
    }

    /** The pits it opened fill in again (where nothing stands). */
    @SuppressWarnings("unchecked")
    private void pits() {
        for (var it = pits.iterator(); it.hasNext(); ) {
            final Object[] pit = it.next();
            if (tickCount >= (int) pit[0] || isRemoved()) {
                for (Object[] b : (java.util.List<Object[]>) pit[1]) {
                    final BlockPos at = (BlockPos) b[0];
                    if (level().getBlockState(at).isAir() && level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                            new net.minecraft.world.phys.AABB(at)).isEmpty()) {
                        level().setBlock(at, (BlockState) b[1], 3);
                    }
                }
                it.remove();
            }
        }
    }

    /** A rock (a falling block): thrown with a speed, or dropped; it leaves no block unless it should (a cave-in). */
    private void rock(Vec3 at, Vec3 velocity, BlockState state, boolean stays, Vec3 lands) {
        final var rock = EntityType.FALLING_BLOCK.create(level());
        if (rock == null) {
            return;
        }
        final net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.put("BlockState", net.minecraft.nbt.NbtUtils.writeBlockState(state));
        tag.putInt("Time", 1);
        tag.putBoolean("DropItem", false);
        tag.putBoolean("HurtEntities", true);
        tag.putFloat("FallHurtAmount", 2.0F);
        tag.putInt("FallHurtMax", 20);
        rock.load(tag);
        rock.setPos(at.x, at.y, at.z);
        rock.setDeltaMovement(velocity);
        if (!stays) {
            rock.disableDrop();
            rocks.put(rock, at);
        }
        level().addFreshEntity(rock);
        if (lands != null && level() instanceof ServerLevel server) { // where it will land, marked
            for (int i = 0; i < 10; i++) {
                final double a = i * Math.PI / 5;
                server.sendParticles(ParticleTypes.SMOKE, lands.x + Math.cos(a) * 1.5, lands.y + 0.1, lands.z + Math.sin(a) * 1.5, 2, 0, 0.05, 0, 0.0);
            }
        }
    }

    /** Hurts and throws those within a radius of a place (not itself). */
    private void burst(Vec3 at, double radius, float damage, Vec3 throwing) {
        for (Player p : level().getEntitiesOfClass(Player.class, new net.minecraft.world.phys.AABB(at, at).inflate(radius, 4, radius))) {
            if (!p.isSpectator() && !p.isCreative() && Math.hypot(p.getX() - at.x, p.getZ() - at.z) < radius) {
                p.hurt(damageSources().mobAttack(this), damage);
                p.push(throwing.x, throwing.y, throwing.z);
                p.hurtMarked = true;
            }
        }
        if (level() instanceof ServerLevel server) {
            final BlockState ground = level().getBlockState(BlockPos.containing(at).below());
            server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground.isAir() ? Blocks.TUFF.defaultBlockState() : ground), at.x, at.y + 0.5, at.z,
                    90, radius * 0.6, 0.8, radius * 0.6, 0.3);
        }
    }

    /** The ground cracking over a place (a warning): bits of it jumping, dust rising, grinding. */
    private void warnGround(Vec3 at, double radius, boolean sound) {
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        final BlockState ground = level().getBlockState(BlockPos.containing(at).below());
        server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground.isAir() ? Blocks.TUFF.defaultBlockState() : ground), at.x, at.y + 0.1, at.z, 14,
                radius * 0.5, 0.05, radius * 0.5, 0.15);
        server.sendParticles(ParticleTypes.ASH, at.x, at.y + 0.3, at.z, 10, radius * 0.5, 0.2, radius * 0.5, 0.02);
        server.sendParticles(ParticleTypes.POOF, at.x, at.y + 0.2, at.z, 2, radius * 0.4, 0.1, radius * 0.4, 0.01);
        if (sound) {
            level().playSound(null, BlockPos.containing(at), random.nextBoolean() ? SoundEvents.GRAVEL_BREAK : SoundEvents.DEEPSLATE_HIT, SoundSource.HOSTILE,
                    1.8F, 0.5F);
        }
    }

    /** The height of the rock roof above a place (within so many blocks), or null where there is none. */
    private Integer ceilingAbove(Vec3 at, int reach) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int up = 2; up <= reach; up++) {
            pos.set(at.x, at.y + up, at.z);
            if (solid(pos)) {
                return pos.getY();
            }
        }
        return null;
    }

    private float heading(Vec3 to) {
        return (float) (Mth.atan2(-(to.x - getX()), to.z - getZ()) * Mth.RAD_TO_DEG);
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return super.hurt(source, stunnedUntil > tickCount ? amount * 1.5F : amount); // dazed, it takes blows harder
    }

    /** How long it circles before it lunges: shorter with every lunge. */
    private int circling(int level) {
        return Math.max(60, (level >= 4 ? 260 : 200) - 50 * lunges);
    }

    /** Which way round to circle so its head keeps ahead of where they are going (cutting them off). */
    private int cutOff(Vec3 to) {
        // the side they move to, seen from it: its head goes round that way, before them
        final double cross = to.x * seenVel.z - to.z * seenVel.x;
        return Math.abs(cross) < 1.0E-3 ? (random.nextBoolean() ? 1 : -1) : (cross > 0 ? -1 : 1);
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
        final double reach = striking ? REACH + 3.0 : REACH + 2.0;
        if (target != null && gapeUntil < 0 && tickCount >= nextBite && biteAt < 0 && mouth().distanceTo(target.position().add(0, 1, 0)) < reach) {
            biteAt = tickCount;
            biteBest = Double.MAX_VALUE;
            nextBite = tickCount + BITE_COOLDOWN;
            final int kind = firstBite || random.nextInt(5) == 0 ? BITE1 : striking || random.nextInt(3) == 0 ? SHORT_BITE : BITE2; // wide first, then mostly plain, now and then a quick snap
            firstBite = false;
            biteLength = kind == SHORT_BITE ? 5 : 10;
            play(kind);
            level().playSound(null, blockPosition(), SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 2.0F, 0.5F);
        }
        if (biteAt >= 0 && target != null) {
            biteBest = Math.min(biteBest, mouth().distanceTo(target.position().add(0, 1, 0)));
        }
        if (biteAt >= 0 && tickCount >= biteAt + biteLength) {
            biteAt = -1;
            if (phase == LUNGE) {
                phaseUntil = Math.min(phaseUntil, tickCount + 10); // bitten: the lunge is spent
            }
            if (target != null && biteBest < REACH + 1.5) {
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
        pits.forEach(pit -> pit[0] = 0);
        if (!level().isClientSide) {
            pits();
        }
        super.remove(reason);
        bar.removeAllPlayers();
    }

    /** Not saved with the land: {@link WormManager} keeps every worm (it lives on while its land is unloaded). */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    /** It becomes real again where the manager kept it: there, turned so, as hurt as it was. */
    void restore(double x, double y, double z, float yaw, float health, float length, int smarts) {
        this.smarts = Mth.clamp(smarts, 1, SMARTEST);
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
