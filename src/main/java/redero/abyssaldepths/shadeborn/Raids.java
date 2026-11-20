package redero.abyssaldepths.shadeborn;

import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.worldgen.ShadebornStructure;

/**
 * Raids, and how a camp meets a threat (DESIGN.md 5.2.1: raiders prey on camps):
 * <ul>
 * <li>A band of raiders that has a camp within reach goes for it: it creeps up ({@link #CREEP} blocks off it
 * gathers), then falls on the camp, each raider going for one of its people, until one of them gets to the store and
 * takes what it can carry; then the band makes off with it. A band breaks off and flees as soon as a raider is badly
 * hurt or half of it has fallen (they prey on the weak, they do not die for a barrel). What they carried falls where a
 * raider falls.</li>
 * <li>The camp: the alarm goes up when one of them is struck or a raider comes into sight; everyone near hears it.
 * The watchers go for the raiders (and for whoever struck one of theirs, unless trusted); the rest flee from the
 * raiders and from whoever struck them; the camp keeps on guard for a while.</li>
 * </ul>
 */
public final class Raids {
    /** How near the band creeps before falling on the camp; how far a raid may be (blocks). */
    static final int CREEP = 22, REACH = 96;
    /** How long a raid may last before the band gives up (ticks). */
    static final int GIVE_UP = 2400;

    private Raids() {
    }

    /** The camp a band could raid from a place: the nearest within reach, or null. */
    static ShadebornStructure.Piece campNear(ServerLevel level, BlockPos at) {
        final ShadebornStructure.Piece p = ShadebornStructure.at(level, at, REACH);
        return p != null && !p.hut ? p : null;
    }

    /** A band sets out for a camp: each of them knows where its hearth and store are. */
    static void setOut(List<Shadeborn> band, ShadebornStructure.Piece camp) {
        for (Shadeborn s : band) {
            s.raid = new Shadeborn.Raid(camp.spot.id(), camp.spot.hearth(), camp.spot.store(), band.size(), s.level().getGameTime());
        }
    }

    /**
     * The alarm: one of them struck (or a raider sighted). Everyone of the same camp (or band) within 24 blocks hears it:
     * the fighters turn on the one who did it (not on a trusted player: they only watch), the rest take fright of it.
     */
    static void alarm(Shadeborn caller, LivingEntity source) {
        if (!(caller.level() instanceof ServerLevel level) || source == null) {
            return;
        }
        final long now = level.getGameTime();
        if (now - caller.alarmAt < 40) {
            return;
        }
        level.playSound(null, caller.blockPosition(), SoundEvents.FOX_SCREECH, SoundSource.NEUTRAL, 1.4F, 0.55F); // the call
        for (Shadeborn o : level.getEntitiesOfClass(Shadeborn.class, caller.getBoundingBox().inflate(24),
                o -> o.isAlive() && (caller.camp() != 0 ? o.camp() == caller.camp() : o.camp() == 0 && o.role() == caller.role()))) {
            o.alarmAt = now;
            o.alarmedBy = source;
            o.alarmPos = source.position();
            final boolean trusted = source instanceof Player p && o.stage(p) >= Camps.WELCOME;
            if (o.role().fights() && !trusted && o.getTarget() == null && !(source instanceof Player p && (p.isCreative() || p.isSpectator()))) {
                o.setTarget(source);
            }
        }
        if (caller.camp() != 0) {
            final Camps.Camp c = Camps.of(level).camp(caller.camp());
            c.raidedUntil = Math.max(c.raidedUntil, source instanceof Shadeborn r && r.role() == Role.RAIDER ? now + 2400 : c.raidedUntil);
        }
    }

    /**
     * A raider's raid: creeping up, falling on the camp, taking from the store, making off. The goal only keeps its feet
     * for the raid (nothing else walks it off) while it has no one to fight; what it does is decided in {@link #think}, every
     * half second, fighting or not.
     */
    static final class Raid extends Goal {
        private final Shadeborn s;

        Raid(Shadeborn s) {
            this.s = s;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return s.role() == Role.RAIDER && s.raid != null && (s.getTarget() == null || s.raid.phase == Shadeborn.Raid.FLEE);
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

    }

    private static void moveTo(Shadeborn s, Vec3 to, double speed) {
        s.getNavigation().moveTo(to.x, to.y, to.z, speed);
    }

    /** Takes what one can carry from the store: a few stacks, part of each. */
    private static void steal(Shadeborn s, ServerLevel level, Shadeborn.Raid r) {
            final BarrelBlockEntity barrel = store(level, r.store);
        if (barrel == null) {
                s.loot.add(ItemStack.EMPTY); // (nothing there: they make off all the same)
                return;
            }
            barrel.unpackLootTable(null); // (what a store holds is only made the first time someone opens it)
            int taken = 0;
            for (int i = 0; i < barrel.getContainerSize() && taken < 3; i++) {
                final ItemStack st = barrel.getItem(i);
                if (!st.isEmpty()) {
                    final ItemStack part = st.split(Math.max(1, st.getCount() / 2));
                    markStolen(part, r.camp);
                    s.loot.add(part);
                    taken++;
                }
            }
            barrel.setChanged();
            if (taken == 0) {
                s.loot.add(ItemStack.EMPTY);
            }
            level.playSound(null, r.store, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 1.0F, 0.9F);
            redero.abyssaldepths.AbyssalDepths.LOGGER.debug("raid on {}: took {}", r.camp, s.loot);
    }

    /** How long a raid is fought at least before the band may give it up (ticks), unless broken. */
    static final int HOLD = 600;

    /**
     * A raider thinks (from its own tick, every half second). Stalking: watching the camp from cover at its edge for a
     * while. Creeping up, then the attack: those set to it (by turns) make for the store and, once they have something,
     * carry it back to where the band watched from; the rest fall on the camp's people (a chase given up after ten
     * seconds or when the one chased has fled the camp, another taken). The band holds on for half a minute at least, and
     * makes off once every thief has something, or when broken: a raider badly hurt, half of them fallen, too long at it.
     * One stuck (not getting anywhere) tries elsewhere.
     */
    static void think(Shadeborn s) {
        final Shadeborn.Raid r = s.raid;
        if (r == null || !(s.level() instanceof ServerLevel level)) {
            return;
        }
        final long now = level.getGameTime();
        final Vec3 hearth = Vec3.atBottomCenterOf(r.hearth), store = Vec3.atBottomCenterOf(r.store);
        final double far = s.position().distanceTo(hearth);
        final List<Shadeborn> band = level.getEntitiesOfClass(Shadeborn.class, s.getBoundingBox().inflate(64),
                o -> o.isAlive() && o.raid != null && o.raid.camp == r.camp);
        final boolean stealer = band.size() < 2 || (s.getId() + r.started) % 2 == 0;
        // the band's nerve
        final boolean broken = s.getHealth() < s.getMaxHealth() * 0.35F || band.size() * 2 <= r.bandSize && r.bandSize > 1 || now - r.started > GIVE_UP + 1200;
        final boolean done = r.phase == Shadeborn.Raid.ATTACK && now - r.attackSince > HOLD
                && band.stream().filter(o -> band.size() < 2 || (o.getId() + o.raid.started) % 2 == 0).allMatch(o -> !o.loot.isEmpty());
        if (r.phase != Shadeborn.Raid.FLEE && (broken || done)) {
            for (Shadeborn o : band) {
                o.raid.phase = Shadeborn.Raid.FLEE;
                o.setTarget(null);
            }
            redero.abyssaldepths.AbyssalDepths.LOGGER.debug("raid on {}: the band makes off ({} left of {}, {})", r.camp, band.size(), r.bandSize,
                    broken ? "broken" : "with its loot");
        }
        unstick(s, now);
        switch (r.phase) {
            case Shadeborn.Raid.STALK -> { // watching the camp from cover at its edge
                final Vec3 at = Vec3.atBottomCenterOf(r.rally != null ? r.rally : r.hearth);
                if (s.position().distanceTo(at) > 3) {
                    moveTo(s, at, 0.7);
                } else {
                    s.getNavigation().stop();
                    s.getLookControl().setLookAt(hearth.x, hearth.y + 1, hearth.z);
                }
                if (now >= r.stalkUntil) {
                    for (Shadeborn o : band) {
                        o.raid.phase = Shadeborn.Raid.CREEP;
                    }
                }
            }
            case Shadeborn.Raid.CREEP -> { // up to the camp's edge, quietly, together
                if (far > CREEP) {
                    if (s.getTarget() == null) {
                        moveTo(s, hearth, 0.75);
                    }
                } else {
                    for (Shadeborn o : band) {
                        o.raid.phase = Shadeborn.Raid.ATTACK;
                        o.raid.attackSince = now;
                    }
                    redero.abyssaldepths.AbyssalDepths.LOGGER.debug("raid on {}: the band falls on the camp", r.camp);
                    level.playSound(null, s.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.0F, 1.6F); // the war cry
                }
            }
            case Shadeborn.Raid.ATTACK -> {
                final net.minecraft.world.entity.LivingEntity t = s.getTarget();
                if (t != null && (now - r.chaseSince > 200 || t.position().distanceTo(hearth) > CREEP || !t.isAlive())) {
                    s.setTarget(null); // the chase given up
                    r.nextPick = now + 60;
                }
                final boolean struck = s.getLastHurtByMob() != null && s.tickCount - s.getLastHurtByMobTimestamp() < 60;
                if (stealer && !struck) { // for the store, then off with it to where they watched from
                    s.setTarget(null);
                    if (s.loot.isEmpty()) {
                        if (s.position().distanceTo(store) > 2.5) {
                            moveTo(s, store, 1.15);
                        } else {
                            steal(s, level, r);
                        }
                    } else {
                        moveTo(s, Vec3.atBottomCenterOf(r.rally != null ? r.rally : r.hearth), 1.1);
                    }
                } else if (s.getTarget() == null && now >= r.nextPick) { // for one of the camp (who is still in it)
                    final List<net.minecraft.world.entity.LivingEntity> foes = new java.util.ArrayList<>(level.getEntitiesOfClass(Shadeborn.class,
                            s.getBoundingBox().inflate(16), o -> o.isAlive() && o.camp() == r.camp && o.position().distanceTo(hearth) < CREEP));
                    level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, s.getBoundingBox().inflate(16),
                            p -> !p.isCreative() && !p.isSpectator() && p.position().distanceTo(hearth) < CREEP).forEach(foes::add);
                    if (!foes.isEmpty()) {
                        foes.sort((a, b) -> Double.compare(a.distanceToSqr(s), b.distanceToSqr(s)));
                        s.setTarget(foes.get(0));
                        r.chaseSince = now;
                    } else if (s.position().distanceTo(hearth) > 6) {
                        moveTo(s, hearth, 0.9); // no one in sight: into the camp's middle
                    }
                }
            }
            default -> { // making off: away from the camp, then gone
                s.setTarget(null);
                final Vec3 away = s.position().subtract(hearth).multiply(1, 0, 1);
                moveTo(s, s.position().add((away.lengthSqr() < 1 ? new Vec3(1, 0, 0) : away.normalize()).scale(16)), 1.25);
                if (far > 70 && level.getNearestPlayer(s, 32) == null) {
                    s.discard();
                } else if (far > 50) { // away: the raid is over; the band goes on with its life (keeping off that camp for a while)
                    s.avoid = r.hearth;
                    s.raid = null;
                    s.wild = "roaming";
                    s.wildGoal = null;
                    s.raidAgain = now + 12000;
                }
            }
        }
        if (now % 100 < 10) {
            redero.abyssaldepths.AbyssalDepths.LOGGER.debug("raid on {}: raider {} phase {} hp {} target {} store {}", r.camp, s.getId(), r.phase,
                    (int) s.getHealth(), s.getTarget() == null ? "-" : s.getTarget().getClass().getSimpleName(), (int) s.position().distanceTo(store));
        }
    }

    /** One that has not got anywhere in three seconds while it means to: off a little way to the side, to try again from there. */
    private static void unstick(Shadeborn s, long now) {
        if (s.getNavigation().isDone() || s.getTarget() != null) {
            s.stuckAt = null;
            return;
        }
        if (s.stuckAt == null || s.position().distanceTo(s.stuckAt) > 1.0) {
            s.stuckAt = s.position();
            s.stuckSince = now;
        } else if (now - s.stuckSince > 60) {
            final double a = s.getRandom().nextDouble() * Math.PI * 2;
            s.getNavigation().stop();
            s.getNavigation().moveTo(s.getX() + Math.cos(a) * 5, s.getY(), s.getZ() + Math.sin(a) * 5, 1.0);
            s.getJumpControl().jump();
            s.stuckSince = now;
        }
    }

    // ------------------------------------------------------------------ a band's life between raids

    /** How far a band roams in one go; how near a camp must be to stalk it; how near a player for an ambush. */
    static final int ROAM = 60, STALK_REACH = 80, AMBUSH = 30;

    /**
     * A raider band between raids, led by its first (the lowest id near): roaming the layer together (creeping when
     * anyone is near), resting by turns once tired (one keeping watch), stalking a camp within reach (watching it from
     * the edge of sight for a minute, then the raid: needs two at least), or lying in wait, crouched, for a player who
     * comes their way, springing when they come close (or giving up after a minute).
     */
    static void wild(Shadeborn s) {
        if (!(s.level() instanceof ServerLevel level)) {
            return;
        }
        final long now = level.getGameTime();
        final List<Shadeborn> band = level.getEntitiesOfClass(Shadeborn.class, s.getBoundingBox().inflate(24),
                o -> o.isAlive() && o.role() == Role.RAIDER && o.camp() == 0 && o.raid == null);
        band.sort(java.util.Comparator.comparingInt(Shadeborn::getId));
        final Shadeborn leader = band.isEmpty() ? s : band.get(0);
        unstick(s, now);
        if (band.size() <= 1) { // cut off from its band: back to the others (any raider within 64)
            final List<Shadeborn> others = level.getEntitiesOfClass(Shadeborn.class, s.getBoundingBox().inflate(64),
                    o -> o != s && o.isAlive() && o.role() == Role.RAIDER && o.camp() == 0);
            if (!others.isEmpty()) {
                others.sort(java.util.Comparator.comparingDouble(o -> o.distanceToSqr(s)));
                s.wild = "roaming";
                s.getNavigation().moveTo(others.get(0), 1.0);
                return;
            }
        }
        if (s.avoid != null && now >= s.raidAgain) {
            s.avoid = null;
        }
        if (leader != s) { // the rest keep with the leader and do as it does
            s.wild = leader.wild;
            s.sitting("resting".equals(s.wild) && !s.onWatch);
            if ("roaming".equals(s.wild) && s.distanceTo(leader) > 4) {
                s.getNavigation().moveTo(leader, 0.85);
            } else if ("lying_in_wait".equals(s.wild) || "resting".equals(s.wild)) {
                s.getNavigation().stop();
            }
            if ("lying_in_wait".equals(leader.wild) && leader.getTarget() != null) {
                s.setTarget(leader.getTarget()); // sprung
            }
            return;
        }
        // the leader decides
        final net.minecraft.world.entity.player.Player player = level.getNearestPlayer(s.getX(), s.getY(), s.getZ(), AMBUSH,
                e -> e instanceof net.minecraft.world.entity.player.Player p && !p.isCreative() && !p.isSpectator());
        final boolean hated = player != null && Camps.of(level).kinAnywhere(player.getUUID()); // one who sides with the camps
        switch (s.wild) {
            case "lying_in_wait" -> {
                s.getNavigation().stop();
                if (player != null) {
                    s.getLookControl().setLookAt(player, 20, 20);
                }
                if (player != null && player.distanceTo(s) < 9) { // sprung
                    for (Shadeborn o : band) {
                        o.setTarget(player);
                    }
                    s.wild = "roaming";
                    level.playSound(null, s.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.0F, 1.7F);
                } else if (player == null || now - s.wildSince > 1200) {
                    s.wild = "roaming"; // they did not come: on
                }
                return;
            }
            case "watching" -> { // a stranger passing: they stop and watch it, then go on
                s.getNavigation().stop();
                if (player != null) {
                    s.getLookControl().setLookAt(player, 20, 20);
                }
                if (player == null || now - s.wildSince > 240) {
                    s.wild = "roaming";
                    s.wildSince = now;
                }
                return;
            }
            case "resting" -> {
                s.sitting(!s.onWatch);
                // one keeps watch: the least tired
                band.forEach(o -> o.onWatch = false);
                band.stream().min(java.util.Comparator.comparingDouble(o -> o.fatigue)).ifPresent(o -> o.onWatch = true);
                if (band.stream().allMatch(o -> o.fatigue < 0.2F || o.onWatch)) {
                    s.wild = "roaming";
                    band.forEach(o -> {
                        o.onWatch = false;
                        o.sitting(false);
                    });
                }
                return;
            }
            default -> {
            }
        }
        // roaming: first what calls them aside
        if (hated && band.size() >= 2 && now - s.wildSince > 400 && level.random.nextInt(4) == 0 && player.distanceTo(s) > 14) {
            s.wild = "lying_in_wait"; // one who sides with the camps coming their way: down, still, waiting
            s.wildSince = now;
            return;
        }
        if (player != null && !hated && player.distanceTo(s) < 20 && now - s.wildSince > 600) {
            s.wild = "watching"; // a stranger: only watched
            s.wildSince = now;
            return;
        }
        if (band.size() >= 2 && now >= s.raidAgain && level.random.nextInt(6) == 0) {
            final redero.abyssaldepths.worldgen.ShadebornStructure.Piece camp = campNear(level, s.blockPosition());
            if (camp != null && camp.spot.hearth().closerThan(s.blockPosition(), STALK_REACH)) {
                stalk(level, band, camp, now);
                return;
            }
        }
        if (band.stream().mapToDouble(o -> o.fatigue).average().orElse(0) > 0.7) {
            s.wild = "resting";
            s.wildSince = now;
            band.forEach(o -> o.getNavigation().stop());
            return;
        }
        final boolean nearRaided = s.avoid != null && s.blockPosition().closerThan(s.avoid, 40);
        if (s.wildGoal == null || s.position().distanceTo(Vec3.atBottomCenterOf(s.wildGoal)) < 4 || now - s.wildSince > 2400
                || nearRaided && s.wildGoal.closerThan(s.avoid, 50)) {
            s.wildGoal = null;
            for (int k = 0; k < 16 && s.wildGoal == null; k++) {
                final double a = level.random.nextDouble() * Math.PI * 2, d = ROAM * (0.5 + level.random.nextDouble() * 0.5);
                final BlockPos g = floorNear(level, BlockPos.containing(s.getX() + Math.cos(a) * d, s.getY(), s.getZ() + Math.sin(a) * d));
                if (g != null && (s.avoid == null || !g.closerThan(s.avoid, 50))) {
                    s.wildGoal = g; // (not back towards the camp they raided)
                }
            }
            s.wildSince = now;
        }
        if (s.wildGoal != null) {
            s.getNavigation().moveTo(s.wildGoal.getX() + 0.5, s.wildGoal.getY(), s.wildGoal.getZ() + 0.5, player != null ? 0.6 : 0.85);
        }
    }

    /** The band sets out to stalk a camp: it will watch it for a minute from the edge of sight, then raid it. */
    private static void stalk(ServerLevel level, List<Shadeborn> band, redero.abyssaldepths.worldgen.ShadebornStructure.Piece camp, long now) {
        final Vec3 hearth = Vec3.atBottomCenterOf(camp.spot.hearth());
        Vec3 dir = band.get(0).position().subtract(hearth).multiply(1, 0, 1);
        dir = dir.lengthSqr() < 1 ? new Vec3(1, 0, 0) : dir.normalize();
        final BlockPos rally = floorNear(level, BlockPos.containing(hearth.add(dir.scale(28))));
        setOut(band, camp);
        for (Shadeborn o : band) {
            o.raid.phase = Shadeborn.Raid.STALK;
            o.raid.stalkUntil = now + 1200;
            o.raid.rally = rally;
        }
        redero.abyssaldepths.AbyssalDepths.LOGGER.debug("raiders stalk the camp at {} {} from {}", camp.spot.x(), camp.spot.z(), rally);
    }

    /** Open floor near a place (feet room over something solid), or null. */
    static BlockPos floorNear(ServerLevel level, BlockPos at) {
        final BlockPos.MutableBlockPos p = at.mutable().move(0, 10, 0);
        for (int k = 0; k < 30; k++) {
            if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir() && level.getBlockState(p.below()).isSolid()
                    && !level.getBlockState(p.below()).is(redero.abyssaldepths.AbyssalDepths.QUICKSILT.get())) {
                return p.immutable();
            }
            p.move(0, -1, 0);
        }
        return null;
    }

    /** A raider band's feet, between raids (the leader's choices, see {@link #wild}). */
    static final class Band extends Goal {
        private final Shadeborn s;

        Band(Shadeborn s) {
            this.s = s;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return s.role() == Role.RAIDER && s.camp() == 0 && s.raid == null && s.getTarget() == null;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void stop() {
            s.sitting(false);
        }
    }

    /** A camp's store: the barrel built on the ground at (or a little off) where it was laid out; or null. */
    public static BarrelBlockEntity store(ServerLevel level, BlockPos laid) {
        for (BlockPos p : BlockPos.betweenClosed(laid.offset(-2, -4, -2), laid.offset(2, 4, 2))) {
            if (level.getBlockEntity(p) instanceof BarrelBlockEntity b) {
                return b;
            }
        }
        return null;
    }

    private static final String STOLEN = "abyssaldepths_stolen_from";

    /** Marks what a raider took with the camp it came from (and says so on it). */
    static void markStolen(ItemStack st, long camp) {
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, st, t -> t.putLong(STOLEN, camp));
        st.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
                net.minecraft.network.chat.Component.translatable("item.abyssaldepths.stolen").withStyle(net.minecraft.ChatFormatting.GRAY))));
    }

    /** The camp a thing was stolen from, or 0. */
    static long stolenFrom(ItemStack st) {
        final net.minecraft.world.item.component.CustomData data = st.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return data != null && data.contains(STOLEN) ? data.copyTag().getLong(STOLEN) : 0;
    }
}
