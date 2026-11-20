package redero.abyssaldepths.shadeborn;

import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.worldgen.ShadebornCamps;
import redero.abyssaldepths.worldgen.ShadebornStructure;

/**
 * How the Shadeborn live together (DESIGN.md 5.2.1):
 * <ul>
 * <li>{@link Formation}: they keep together. When something they fear is near (a stranger, a creature, a worm), the young
 * draw into the middle, the others close round them on the far side, the fighters (watchers, raiders) step out between
 * them and it. A band out in the wild (raiders) keeps to its own middle the same way.</li>
 * <li>{@link Routine}: the camp's rhythm, by tiredness (they know no day or night): each works at its role until worn
 * out, then rests in its tent; the watch is handed on (see {@link Expeditions}).</li>
 * </ul>
 */
final class CampLife {
    private CampLife() {
    }

    /** What they fear near them now (within 16 blocks), or null. */
    static LivingEntity threat(Shadeborn s) {
        LivingEntity worst = null;
        double near = 16;
        for (LivingEntity e : s.level().getEntitiesOfClass(LivingEntity.class, s.getBoundingBox().inflate(16),
                e -> e != s && e.isAlive() && !e.isSpectator())) {
            final boolean feared = e instanceof Monster || e instanceof redero.abyssaldepths.entity.BigWorm
                    || e instanceof Player p && !p.isCreative() && (s.stage(p) == Camps.STRANGER || s.distrusted(p))
                    || e instanceof Shadeborn o && o.role() == Role.RAIDER && s.role() != Role.RAIDER;
            if (feared && s.distanceTo(e) < near) {
                near = s.distanceTo(e);
                worst = e;
            }
        }
        return worst;
    }

    /** The middle of a group: the camp's hearth, or the band's own middle. */
    static Vec3 middle(Shadeborn s) {
        if (s.home() != null && s.camp() != 0) {
            return Vec3.atBottomCenterOf(s.home());
        }
        final List<Shadeborn> band = s.level().getEntitiesOfClass(Shadeborn.class, s.getBoundingBox().inflate(16), o -> o.camp() == 0 && o.role() == s.role());
        Vec3 sum = Vec3.ZERO;
        for (Shadeborn o : band) {
            sum = sum.add(o.position());
        }
        return band.isEmpty() ? s.position() : sum.scale(1.0 / band.size());
    }

    /** Keeping together against a threat: the young inside, the rest round them, the fighters outside towards it. */
    static final class Formation extends Goal {
        private final Shadeborn s;
        private LivingEntity threat;
        private int recheck;
        private ShadebornCamps.Spot camp;

        /** The tent this young one hides in (its camp's, by its id), or null. */
        private Vec3 tent() {
            if (s.camp() == 0 || s.home() == null || !(s.level() instanceof ServerLevel level)) {
                return null;
            }
            if (camp == null) {
                final ShadebornStructure.Piece p = ShadebornStructure.at(level, s.home(), 24);
                if (p == null) {
                    return null;
                }
                camp = p.spot;
            }
            final List<BlockPos> tents = ShadebornCamps.tents(camp);
            if (tents.isEmpty()) {
                return null;
            }
            final BlockPos t = tents.get(Math.floorMod(s.getId(), tents.size()));
            return new Vec3(t.getX() + 0.5, s.home().getY(), t.getZ() + 0.5);
        }

        Formation(Shadeborn s) {
            this.s = s;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (Shelter.shelters(s)) {
                return false; // (those who do not fight take shelter instead: see Shelter)
            }
            if (s.role() == Role.WANDERER || s.role() == Role.OUTCAST || s.temper() == Shadeborn.HOSTILE && s.camp() != 0 && s.getTarget() != null) {
                return false;
            }
            threat = threat(s);
            return threat != null && s.raid == null && s.role() != Role.RAIDER
                    || s.young() && s.camp() != 0 && s.shaken(); // the young stay hidden till the alarm has passed
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void tick() {
            if (--recheck > 0) {
                return;
            }
            recheck = 10;
            final Vec3 mid = middle(s);
            final Vec3 away = threat == null ? new Vec3(1, 0, 0) : mid.subtract(threat.position()).multiply(1, 0, 1).normalize();
            final double angle = (s.getId() % 7) * 0.9;
            Vec3 spot;
            final Vec3 tent = s.young() ? tent() : null;
            if (tent != null && (s.shaken() || threat instanceof Shadeborn r && r.role() == Role.RAIDER)) { // a raid, an alarm: into a tent
                spot = tent;
                if (s.position().distanceTo(tent) < 1.5) {
                    s.getNavigation().stop();
                    s.sitting(true);
                    return;
                }
            } else if (s.young()) {
                spot = mid; // the young in the middle
            } else if (s.role().fights()) {
                spot = mid.subtract(away.scale(5)).add(Math.cos(angle) * 1.5, 0, Math.sin(angle) * 1.5); // between them and it
            } else {
                spot = mid.add(away.scale(2.0)).add(Math.cos(angle) * 2.5, 0, Math.sin(angle) * 2.5); // round the young, the far side
            }
            if (s.position().distanceTo(spot) > (s.young() ? 1.5 : 2.0)) {
                s.getNavigation().moveTo(spot.x, spot.y, spot.z, s.young() ? 1.3 : 1.15);
            }
            s.sitting(false);
        }

        @Override
        public void stop() {
            s.sitting(false);
        }
    }

    /**
     * The camp's rhythm (they know no day or night: it is always dark down here): by tiredness. Each works at its role
     * until worn out, then rests in its tent until rested. The one on watch (the Elder sees to who: {@link Expeditions})
     * keeps it, at the post and walking the camp's edge by turns; a watcher off watch stands by or rests. The
     * lamp-keeper tends the fire, the trader keeps by the store, the Elder oversees from the hearth; the rest gather at the
     * hearth between expeditions.
     */
    static final class Routine extends Goal {
        private final Shadeborn s;
        private ShadebornCamps.Spot spot;
        private Vec3 target;
        private boolean sitThere;
        private int recheck;

        Routine(Shadeborn s) {
            this.s = s;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return s.camp() != 0 && s.home() != null && s.getTarget() == null && s.posture() != Shadeborn.COWER && s.posture() != Shadeborn.THREAT
                    && s.level() instanceof ServerLevel && !s.following() && !s.shaken() // (shaken: not back at work yet)
                    && Shelter.danger(s) == null && s.trip == null; // (in danger, or out on an expedition: not here)
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void stop() {
            s.sitting(false);
        }

        @Override
        public void tick() {
            if (--recheck > 0) {
                if (target != null && s.position().distanceTo(target) > 1.6) {
                    s.getNavigation().moveTo(target.x, target.y, target.z, 0.7);
                } else if (target != null && sitThere) {
                    s.sitting(true);
                    s.getNavigation().stop();
                }
                return;
            }
            recheck = 40;
            final ServerLevel level = (ServerLevel) s.level();
            if (spot == null) {
                final ShadebornStructure.Piece p = ShadebornStructure.at(level, s.home(), 24);
                if (p == null) {
                    return;
                }
                spot = p.spot;
            }
            final Vec3 hearth = Vec3.atBottomCenterOf(s.home());
            final double angle = (s.getId() % 8) * Math.PI / 4;
            sitThere = false;
            target = null;
            s.sitting(false);
            // worn out: to its tent (not while on watch: the one on watch waits to be relieved); rested: up again
            if (s.resting && s.fatigue < 0.15F) {
                s.resting = false;
            } else if (!s.resting && !s.onWatch && (s.fatigue > 0.7F || s.role() == Role.WATCHER && s.fatigue > 0.45F)) {
                s.resting = true;
            }
            if (s.resting) {
                final List<BlockPos> tents = ShadebornCamps.tents(spot);
                if (!tents.isEmpty()) {
                    final BlockPos t = tents.get(Math.floorMod(s.getId(), tents.size()));
                    target = new Vec3(t.getX() + 0.5, s.home().getY(), t.getZ() + 0.5);
                    sitThere = true;
                }
                return;
            }
            if (s.onWatch) { // the watch: at the post, and walking the camp's edge, by turns
                s.task = "on_watch";
                if ((level.getGameTime() / 800 + s.getId()) % 2 == 0) {
                    final BlockPos post = ShadebornCamps.post(spot);
                    target = new Vec3(post.getX() + 1.5, s.home().getY(), post.getZ() + 1.5);
                } else {
                    final double a = (level.getGameTime() / 100.0 + s.getId()) * Math.PI / 4;
                    target = hearth.add(Math.cos(a) * 12, 0, Math.sin(a) * 12);
                    recheck = 20;
                }
                return;
            }
            switch (s.role()) { // the work of each
                case WATCHER -> { // off watch: standing by near the hearth
                    s.task = "standing_by";
                    target = hearth.add(Math.cos(angle) * 4, 0, Math.sin(angle) * 4);
                }
                case LAMP_KEEPER -> {
                    s.task = "tending_fire";
                    target = hearth.add(1.6, 0, 0);
                    final BlockState fire = level.getBlockState(s.home());
                    if (fire.getBlock() instanceof CampfireBlock && !fire.getValue(CampfireBlock.LIT)) { // the fire went out: lit again
                        level.setBlockAndUpdate(s.home(), fire.setValue(CampfireBlock.LIT, true));
                        level.sendParticles(ParticleTypes.FLAME, s.home().getX() + 0.5, s.home().getY() + 0.5, s.home().getZ() + 0.5, 8, 0.2, 0.2, 0.2, 0.02);
                    }
                }
                case TRADER -> {
                    s.task = "at_store";
                    target = Vec3.atBottomCenterOf(spot.store()).add(-1, 0, 1).with(net.minecraft.core.Direction.Axis.Y, s.home().getY());
                }
                case KEEPER -> {
                    s.task = "overseeing";
                    target = hearth.add(-2, 0, 1);
                }
                default -> { // between expeditions and chores: gathered at the hearth
                    s.task = "at_hearth";
                    target = hearth.add(Math.cos(angle) * 3, 0, Math.sin(angle) * 3);
                    sitThere = true;
                }
            }
        }
    }

}
