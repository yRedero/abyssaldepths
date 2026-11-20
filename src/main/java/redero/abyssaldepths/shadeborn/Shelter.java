package redero.abyssaldepths.shadeborn;

import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.entity.BigWorm;
import redero.abyssaldepths.worldgen.ShadebornCamps;
import redero.abyssaldepths.worldgen.ShadebornStructure;

/**
 * How the Shadeborn who do not fight meet danger (all but watchers, raiders and the hostile):
 * <ul>
 * <li><b>Judged, not jumped at:</b> what they fear and how much ({@link #danger}). Raiders, creatures, the worm, the
 * cause of an alarm, a player they hold a grudge against, one who just struck them, runs at them, or comes close with a
 * weapon: they <em>flee</em>. A stranger they are wary of who merely comes near: they <em>keep away</em>, backing off
 * slowly to some five blocks while watching them, without running.</li>
 * <li><b>Fleeing to a shelter, chosen:</b> the camp's tent nearest to them that is far from the danger and not past it;
 * without a camp, the farthest point they can reach on the side away from it. They run straight there (the camp's
 * bounds lifted meanwhile), hold to that choice unless the danger cuts it off, and once inside crouch there watching
 * the danger, until it has gone and they have calmed down.</li>
 * </ul>
 * Meanwhile nothing walks them home or back to their work (see {@link CampLife.Routine}, the going home and following).
 */
public final class Shelter extends Goal {
    /** How near a stranger may come before they back off; how far a flight goes without a camp. */
    static final double KEEP = 5.5, FLIGHT = 15;

    /** What they fear now: the cause, and whether they flee (else keep away). */
    public record Danger(LivingEntity source, boolean flee) {
    }

    private final Shadeborn s;
    private Vec3 shelter;
    private boolean inTent;
    private long chosenAt;
    private int recheck;

    public Shelter(Shadeborn s) {
        this.s = s;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Whether they would take shelter at all (those who fight never do). */
    static boolean shelters(Shadeborn s) {
        return !s.role().fights() && s.temper() != Shadeborn.HOSTILE && s.role() != Role.RAIDER;
    }

    /** What this one fears now (judged every half second), or null. */
    public static Danger danger(Shadeborn s) {
        if (!shelters(s) || !(s.level() instanceof ServerLevel level)) {
            return null;
        }
        final long now = level.getGameTime();
        if (now - s.dangerAt < 10) {
            return s.danger;
        }
        s.dangerAt = now;
        s.danger = judge(s, level, now);
        return s.danger;
    }

    private static Danger judge(Shadeborn s, ServerLevel level, long now) {
        Danger best = null;
        double near = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, s.getBoundingBox().inflate(24), e -> e != s && e.isAlive() && !e.isSpectator())) {
            final double d = s.distanceTo(e);
            Boolean flee = null;
            if (e instanceof Shadeborn o) {
                // raiders: fled from within 20 blocks; once fleeing, till 28 (no running back and forth at the edge)
                flee = o.role() == Role.RAIDER && (d < 20 || s.sheltering && d < 28) ? Boolean.TRUE : null;
            } else if (e instanceof BigWorm) {
                flee = Boolean.TRUE;
            } else if (e instanceof Monster) {
                flee = d < 12 ? Boolean.TRUE : null;
            } else if (e instanceof Player p && !p.isCreative()) {
                flee = fearOf(s, p, d);
            }
            if (flee == null && e == s.alarmedBy && s.shaken() && d < 24) {
                flee = Boolean.TRUE; // what the alarm was about, while still shaken
            }
            if (flee != null && (best == null || flee && !best.flee() || flee == best.flee() && d < near)) {
                best = new Danger(e, flee);
                near = d;
            }
        }
        return best;
    }

    /** How a player is feared: fled from (TRUE), kept away from (FALSE), or not at all (null). */
    private static Boolean fearOf(Shadeborn s, Player p, double d) {
        final boolean struck = s.getLastHurtByMob() == p && s.tickCount - s.getLastHurtByMobTimestamp() < 200;
        final Vec3 at = s.position().subtract(p.position());
        final boolean charging = p.isSprinting() && d < 10 && p.getDeltaMovement().multiply(1, 0, 1).dot(at.multiply(1, 0, 1).normalize()) > 0.1;
        final boolean armed = d < 6 && armed(p.getMainHandItem()) && s.stage(p) < Camps.WELCOME;
        if (d < 18 && (s.begrudges(p) || struck) || charging || armed) {
            return Boolean.TRUE;
        }
        return d < KEEP + 2 && s.wary(p) ? Boolean.FALSE : null;
    }

    private static boolean armed(ItemStack st) {
        return st.getItem() instanceof SwordItem || st.getItem() instanceof AxeItem || st.getItem() instanceof BowItem
                || st.getItem() instanceof CrossbowItem || st.getItem() instanceof TridentItem || st.getItem() instanceof MaceItem;
    }

    @Override
    public boolean canUse() {
        return danger(s) != null;
    }

    @Override
    public boolean canContinueToUse() {
        // in its shelter it stays while still shaken, even once the danger is out of sight
        return danger(s) != null || shelter != null && s.shaken() && Math.hypot(s.getX() - shelter.x, s.getZ() - shelter.z) < 2;
    }

    @Override
    public void start() {
        shelter = null;
        recheck = 0;
        s.sheltering = true;
    }

    @Override
    public void stop() {
        shelter = null;
        s.sheltering = false;
        s.getNavigation().stop();
        if (s.home() != null) {
            s.restrictTo(s.home(), 20); // back within the camp's bounds
        }
    }

    @Override
    public void tick() {
        final Danger d = danger(s);
        if (d != null) {
            s.getLookControl().setLookAt(d.source(), 30, 30); // eyes on it, always
        }
        if (--recheck > 0) {
            return;
        }
        recheck = 5;
        if (d == null) { // the danger gone: crouching where it is till calm
            s.getNavigation().stop();
            return;
        }
        final Vec3 threat = d.source().position();
        if (!d.flee()) { // keeping away: a few slow steps back, no running
            shelter = null;
            if (s.position().distanceTo(threat) < KEEP) {
                final Vec3 away = s.position().subtract(threat).multiply(1, 0, 1);
                final Vec3 to = s.position().add((away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize()).scale(3));
                s.getNavigation().moveTo(to.x, to.y, to.z, 0.75);
            } else {
                s.getNavigation().stop();
            }
            return;
        }
        s.clearRestriction(); // fleeing: the camp's bounds do not hold it
        final long now = s.level().getGameTime();
        final boolean cutOff = shelter != null && threat.distanceTo(shelter) < 4;
        if (shelter == null || cutOff || now - chosenAt > 60 && Math.hypot(s.getX() - shelter.x, s.getZ() - shelter.z) > 2.2 && s.getNavigation().isDone()) {
            shelter = choose(threat);
            inTent = shelter != null && tents().contains(shelter);
            chosenAt = now;
            redero.abyssaldepths.AbyssalDepths.LOGGER.debug("shelter: {} {} from {} ({} away) -> {} ({} from it)", s.role().id, s.getId(),
                    d.source().getType().getDescriptionId(), (int) s.position().distanceTo(threat), shelter == null ? "nowhere" : shelter.toString(),
                    shelter == null ? -1 : (int) shelter.distanceTo(threat));
        }
        if (shelter == null || !inTent && s.position().distanceTo(threat) > 18) {
            s.getNavigation().stop(); // far enough, with no tent to go to: standing, watching it (not running on and on)
            return;
        }
        final double flat = Math.hypot(s.getX() - shelter.x, s.getZ() - shelter.z);
        if (s.tickCount % 100 < 5) {
            redero.abyssaldepths.AbyssalDepths.LOGGER.debug("shelter: {} {} {} from its shelter", s.role().id, s.getId(), (int) flat);
        }
        if (flat > 1.3) {
            s.getNavigation().moveTo(shelter.x, shelter.y, shelter.z, 1.35);
        } else {
            s.getNavigation().stop(); // in: crouching, watching
        }
    }

    /**
     * Where to take shelter from a danger: the camp's tent nearest to it and far from the danger, not past it; else the
     * farthest reachable point away from it.
     */
    private Vec3 choose(Vec3 threat) {
        final Vec3 self = s.position();
        Vec3 best = null;
        double score = Double.MAX_VALUE;
        for (Vec3 tent : tents()) {
            final double dSelf = self.distanceTo(tent), dThreat = threat.distanceTo(tent);
            final Vec3 toTent = tent.subtract(self).multiply(1, 0, 1), toThreat = threat.subtract(self).multiply(1, 0, 1);
            final boolean past = dSelf > 3 && toTent.lengthSqr() > 0 && toThreat.lengthSqr() > 0 && toTent.normalize().dot(toThreat.normalize()) > 0.5;
            if (dThreat < 5 || past || dThreat < dSelf * 0.6) {
                continue; // too near the danger, or the way leads past it
            }
            final double sc = dSelf - 0.7 * dThreat;
            if (sc < score) {
                score = sc;
                best = tent;
            }
        }
        if (best != null) {
            return best;
        }
        // no tent to go to: the farthest point it can reach on the side away from the danger
        double far = -1;
        for (int i = 0; i < 8; i++) {
            final Vec3 p = LandRandomPos.getPosAway(s, (int) FLIGHT, 7, threat);
            if (p == null) {
                continue;
            }
            final Path path = s.getNavigation().createPath(BlockPos.containing(p), 0);
            final double dt = p.distanceTo(threat);
            if (path != null && path.canReach() && dt > far) {
                far = dt;
                best = p;
            }
        }
        return best;
    }

    private ShadebornCamps.Spot camp;

    /** The camp's tents (where one can take shelter), or none. */
    private List<Vec3> tents() {
        if (s.camp() == 0 || s.home() == null || !(s.level() instanceof ServerLevel level)) {
            return List.of();
        }
        if (camp == null) {
            final ShadebornStructure.Piece p = ShadebornStructure.at(level, s.home(), 24);
            if (p == null) {
                return List.of();
            }
            camp = p.spot;
        }
        return ShadebornCamps.tents(camp).stream().map(t -> floor(level, t.getX(), s.home().getY(), t.getZ())).toList();
    }

    /** The floor at a column near a height (the tents stand on their own ground, not always the hearth's). */
    private static Vec3 floor(ServerLevel level, int x, int y, int z) {
        final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 4; dy++) {
            for (int sign : new int[] {1, -1}) {
                p.set(x, y + dy * sign, z);
                if (level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                        && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty()) {
                    return new Vec3(x + 0.5, p.getY(), z + 0.5);
                }
            }
        }
        return new Vec3(x + 0.5, y, z + 0.5);
    }
}
