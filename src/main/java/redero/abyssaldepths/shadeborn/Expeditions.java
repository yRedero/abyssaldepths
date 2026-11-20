package redero.abyssaldepths.shadeborn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.ShadebornCamps;
import redero.abyssaldepths.worldgen.TunnelFeature;

/**
 * A camp's life without day or night (they do not know the sky): the Elder oversees it ({@link #oversee}, from the
 * camp's tick).
 * <ul>
 * <li><b>The watch</b> goes by tiredness: the least tired watcher stands it; when it tires, a rested one relieves it
 * (with none, the Elder itself stands in) and it goes to rest.</li>
 * <li><b>Progress</b> (0–100, kept per camp): rises while the camp is left in peace and has food and light in its store,
 * and with the player's help; falls with raids, deaths, the worm near, an empty store, harm done by the player.</li>
 * <li><b>Expeditions</b>, as progress allows (from {@link #GATHER_AT}: gathering food; from {@link #SCOUT_AT}: scouting
 * the region; from {@link #LONG_AT}: longer ones, three strong, with an escort): the Elder sends rested ones out with a
 * provision from the store. They go together to a place out in the dark, search it, and come back: with food for the
 * store, or knowing a place (the Pathfinder then sells a map to it). Out there they meet what is out there; they may
 * come back hurt, or not at all. A player trusted as Kin may go with them (the leader beckons): staying near them on
 * the search earns a share and trust.</li>
 * </ul>
 */
public final class Expeditions {
    static final float GATHER_AT = 10, SCOUT_AT = 30, LONG_AT = 60;
    /** How often the Elder thinks of sending one (ticks); how long one may take at most. */
    static final int THINK = 2400, MAX_TICKS = 6000;

    private Expeditions() {
    }

    /** An expedition in hand (each member carries it): what for, where to, how far along, who leads, since when. */
    public static final class Trip {
        static final int OUT = 0, SEARCH = 1, BACK = 2;
        final boolean scout;
        final BlockPos target;
        final UUID leader;
        final long started;
        int phase;
        long searchUntil;
        final java.util.Set<UUID> joined = new java.util.HashSet<>();

        Trip(boolean scout, BlockPos target, UUID leader, long started) {
            this.scout = scout;
            this.target = target;
            this.leader = leader;
            this.started = started;
        }

        /** The same trip (each member carries its own copy once saved and loaded): the same leader, set out at the same time. */
        @Override
        public boolean equals(Object o) {
            return o instanceof Trip t && t.leader.equals(leader) && t.started == started;
        }

        @Override
        public int hashCode() {
            return leader.hashCode() * 31 + Long.hashCode(started);
        }

        CompoundTag save() {
            final CompoundTag tag = new CompoundTag();
            tag.putBoolean("scout", scout);
            tag.putLong("target", target.asLong());
            tag.putUUID("leader", leader);
            tag.putLong("started", started);
            tag.putInt("phase", phase);
            tag.putLong("search", searchUntil);
            return tag;
        }

        static Trip load(CompoundTag tag) {
            final Trip t = new Trip(tag.getBoolean("scout"), BlockPos.of(tag.getLong("target")), tag.getUUID("leader"), tag.getLong("started"));
            t.phase = tag.getInt("phase");
            t.searchUntil = tag.getLong("search");
            return t;
        }
    }

    // ------------------------------------------------------------------ the Elder's oversight

    /** The Elder (or, with none, the camp itself) oversees its camp: the watch, progress, expeditions. Every 5 seconds. */
    static void oversee(ServerLevel level, ShadebornCamps.Spot s, Camps.Camp c, long now) {
        final List<Shadeborn> members = Shadeborn.of(level, s.id(), s.hearth(), 48);
        if (members.isEmpty()) {
            return;
        }
        watch(members);
        progress(level, s, c, members, now);
        if (now - c.lastExpedition >= THINK && members.stream().noneMatch(m -> m.trip != null)) {
            send(level, s, c, members, now, null, false);
        }
    }

    /** The watch: the least tired watcher stands it; a tired one is relieved by a rested one (or the Elder). */
    private static void watch(List<Shadeborn> members) {
        final List<Shadeborn> watchers = members.stream().filter(m -> m.role() == Role.WATCHER && m.trip == null).toList();
        final Shadeborn elder = members.stream().filter(m -> m.role() == Role.KEEPER).findFirst().orElse(null);
        Shadeborn on = members.stream().filter(m -> m.onWatch).findFirst().orElse(null);
        if (on != null && on.fatigue > 0.85F) { // tired: relieved by a rested one
            final Shadeborn relief = watchers.stream().filter(w -> w != on && w.fatigue < 0.35F).min(Comparator.comparingDouble(w -> w.fatigue))
                    .orElse(elder != null && elder != on && elder.fatigue < 0.5F ? elder : null);
            if (relief != null) {
                on.onWatch = false;
                relief.onWatch = true;
                relief.gesture("nod"); // the watch handed over
            }
        }
        if (members.stream().noneMatch(m -> m.onWatch)) {
            watchers.stream().filter(w -> w.fatigue < 0.7F).min(Comparator.comparingDouble(w -> w.fatigue)).ifPresent(w -> w.onWatch = true);
        }
    }

    /** The camp's progress: peace and a stocked store raise it slowly; trouble and an empty store lower it. */
    private static void progress(ServerLevel level, ShadebornCamps.Spot s, Camps.Camp c, List<Shadeborn> members, long now) {
        final boolean trouble = c.raidedUntil > now || members.stream().anyMatch(m -> now - m.alarmAt < 600)
                || !level.getEntities(AbyssalDepths.BIG_WORM.get(), w -> w.isAlive() && w.position().distanceTo(Vec3.atCenterOf(s.hearth())) < 80).isEmpty();
        final int[] stock = stock(level, s);
        float by = trouble ? -0.05F : 0.04F; // peace (about +0.5 a minute) or trouble
        by += stock[0] == 0 ? -0.03F : 0.03F * Math.min(1, stock[0] / 24F); // food
        by += 0.02F * Math.min(1, stock[1] / 16F); // light
        Camps.of(level).progress(c.id, by);
    }

    /** What the store holds: food (items), light (torches, lanterns, candles). */
    static int[] stock(ServerLevel level, ShadebornCamps.Spot s) {
        final BarrelBlockEntity b = Raids.store(level, s.store());
        int food = 0, light = 0;
        if (b != null) {
            for (int i = 0; i < b.getContainerSize(); i++) {
                final ItemStack st = b.getItem(i);
                if (st.has(DataComponents.FOOD)) {
                    food += st.getCount();
                } else if (st.is(Items.TORCH) || st.is(Items.LANTERN) || st.is(Items.CANDLE) || st.is(Items.SOUL_LANTERN)) {
                    light += st.getCount();
                }
            }
        }
        return new int[] {food, light};
    }

    /**
     * Sends an expedition if the camp can (progress, rested people, a provision in the store, nothing dangerous about):
     * gathering when food runs low, else scouting once progress allows. With a player asking (trusted as Kin), it is
     * theirs to join. Returns what happened, for the asker.
     */
    static String send(ServerLevel level, ShadebornCamps.Spot s, Camps.Camp c, List<Shadeborn> members, long now, ServerPlayer asker, boolean force) {
        c.lastExpedition = now;
        if (c.progress < GATHER_AT && !force) {
            return "the camp is not ready for that yet";
        }
        if (members.stream().anyMatch(m -> m.trip != null)) {
            return "an expedition is already out";
        }
        if (members.stream().anyMatch(m -> Shelter.danger(m) != null || m.shaken())) {
            return "not with danger about";
        }
        final int[] stock = stock(level, s);
        final boolean scout = (stock[0] >= 8 || force && asker == null) && (c.progress >= SCOUT_AT || force);
        final int size = c.progress >= LONG_AT || force ? 3 : 2;
        final List<Shadeborn> go = new ArrayList<>();
        // who goes: the one who leads it (a pathfinder to scout, a forager to gather, else any rested one who does not keep the camp)
        members.stream().filter(m -> m.fatigue < 0.4F && !m.onWatch && spared(m.role()))
                .sorted(Comparator.comparingInt((Shadeborn m) -> m.role() == (scout ? Role.PATHFINDER : Role.FORAGER) ? 0
                        : m.role() == Role.FORAGER || m.role() == Role.PATHFINDER || m.role() == Role.DELVER ? 1 : 2))
                .limit(size - 1).forEach(go::add);
        if (go.isEmpty()) {
            return "no one rested to send";
        }
        members.stream().filter(m -> m.role() == Role.WATCHER && !m.onWatch && m.fatigue < 0.4F && m.trip == null).findFirst()
                .filter(w -> c.progress >= LONG_AT || force).ifPresent(go::add); // an escort, when the camp can spare one
        if (!force && !take(level, s)) {
            return "nothing in the store to take along";
        }
        final BlockPos target = target(level, s, scout ? 60 : 40, scout ? 90 : 60);
        if (target == null) {
            return "no way out found";
        }
        final Trip trip = new Trip(scout, target, go.get(0).getUUID(), now);
        for (Shadeborn m : go) {
            m.trip = trip;
            m.onWatch = false;
            m.sitting(false);
        }
        members.stream().filter(m -> m.role() == Role.KEEPER).findFirst().ifPresent(e -> {
            e.gesture("point"); // the Elder sends them, pointing the way
            e.getLookControl().setLookAt(target.getX(), target.getY(), target.getZ());
        });
        // a Kin player near is beckoned along
        for (Player p : level.getEntitiesOfClass(Player.class, go.get(0).getBoundingBox().inflate(16), p -> !p.isSpectator())) {
            if (go.get(0).stage(p) >= Camps.KIN) {
                go.get(0).gesture("beckon");
                break;
            }
        }
        AbyssalDepths.LOGGER.debug("expedition from {}: {} with {} to {}", c.id, scout ? "scouting" : "gathering",
                go.stream().map(m -> m.role().id).toList(), target);
        return (scout ? "a scouting party" : "a gathering party") + " of " + go.size() + " sets out";
    }

    /** Whether one of this role can be spared for an expedition (those who keep the camp stay: the Elder, the fire, the store, the mending, the young; watchers go only as escort). */
    private static boolean spared(Role r) {
        return switch (r) {
            case KEEPER, WATCHER, LAMP_KEEPER, TRADER, MENDER, YOUNG, RAIDER, WANDERER, OUTCAST -> false;
            default -> true;
        };
    }

    /** Takes a food item from the store (a provision, a newcomer's share). */
    static boolean takeFood(ServerLevel level, ShadebornCamps.Spot s) {
        return take(level, s);
    }

    /** Takes a provision (a food item) from the store for the way. */
    private static boolean take(ServerLevel level, ShadebornCamps.Spot s) {
        final BarrelBlockEntity b = Raids.store(level, s.store());
        if (b != null) {
            for (int i = 0; i < b.getContainerSize(); i++) {
                if (b.getItem(i).has(DataComponents.FOOD)) {
                    b.getItem(i).shrink(1);
                    b.setChanged();
                    return true;
                }
            }
        }
        return false;
    }

    /** A place out in the dark, so far from the hearth, on open floor they can walk to. */
    private static BlockPos target(ServerLevel level, ShadebornCamps.Spot s, int min, int max) {
        for (int k = 0; k < 16; k++) {
            final double a = level.random.nextDouble() * Math.PI * 2, d = min + level.random.nextDouble() * (max - min);
            final BlockPos at = floor(level, BlockPos.containing(s.x() + Math.cos(a) * d, s.floor(), s.z() + Math.sin(a) * d));
            if (at != null) {
                return at;
            }
        }
        return null;
    }

    private static BlockPos floor(ServerLevel level, BlockPos at) {
        final BlockPos.MutableBlockPos p = at.mutable().move(0, 10, 0);
        for (int k = 0; k < 30; k++) {
            if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir() && level.getBlockState(p.below()).isSolid()) {
                return p.immutable();
            }
            p.move(0, -1, 0);
        }
        return null;
    }

    // ------------------------------------------------------------------ on the way

    /** A member on an expedition: out together (following its leader), searching the place, back home. */
    static final class Go extends Goal {
        private final Shadeborn s;
        private int recheck;

        Go(Shadeborn s) {
            this.s = s;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return s.trip != null && s.home() != null && s.getTarget() == null;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            s.clearRestriction(); // out beyond the camp
            s.sitting(false);
        }

        @Override
        public void stop() {
            if (s.trip == null && s.home() != null) {
                s.restrictTo(s.home(), 20);
            }
        }

        @Override
        public void tick() {
            if (--recheck > 0) {
                return;
            }
            recheck = 10;
            final Trip t = s.trip;
            final ServerLevel level = (ServerLevel) s.level();
            final long now = level.getGameTime();
            final boolean leading = s.getUUID().equals(t.leader) || !(level.getEntity(t.leader) instanceof Shadeborn leader) || !leader.isAlive();
            if (now - t.started > MAX_TICKS && t.phase != Trip.BACK) {
                t.phase = Trip.BACK; // too long out: home
            }
            // the Kin with them share in it: near the leader while it searches
            if (leading && t.phase == Trip.SEARCH) {
                for (Player p : level.getEntitiesOfClass(Player.class, s.getBoundingBox().inflate(12), p -> !p.isSpectator())) {
                    if (s.stage(p) >= Camps.KIN) {
                        t.joined.add(p.getUUID());
                    }
                }
            }
            if (!leading) { // keeping with the leader
                final Shadeborn leader = (Shadeborn) level.getEntity(t.leader);
                if (s.distanceTo(leader) > 3.5) {
                    s.getNavigation().moveTo(leader, t.phase == Trip.BACK ? 1.0 : 0.9);
                }
                return;
            }
            final Vec3 home = Vec3.atBottomCenterOf(s.home());
            switch (t.phase) {
                case Trip.OUT -> {
                    if (s.position().distanceTo(Vec3.atBottomCenterOf(t.target)) < 4) {
                        t.phase = Trip.SEARCH;
                        t.searchUntil = now + 400;
                    } else {
                        s.getNavigation().moveTo(t.target.getX() + 0.5, t.target.getY(), t.target.getZ() + 0.5, 0.85);
                    }
                }
                case Trip.SEARCH -> { // looking about the place
                    if (now >= t.searchUntil) {
                        risk(level, t);
                        t.phase = Trip.BACK;
                    } else if (s.getNavigation().isDone()) {
                        final double a = s.getRandom().nextDouble() * Math.PI * 2;
                        s.getNavigation().moveTo(t.target.getX() + Math.cos(a) * 7, t.target.getY(), t.target.getZ() + Math.sin(a) * 7, 0.7);
                    }
                }
                default -> {
                    if (s.position().distanceTo(home) < 6) {
                        home(level, t);
                    } else {
                        s.getNavigation().moveTo(home.x, home.y, home.z, 0.95);
                    }
                }
            }
        }

        /**
         * What the dark held for them: with a trusted player along, now and then raiders come at them (to be fought off
         * together); alone, now and then one comes back hurt, or (rarely) not at all.
         */
        private void risk(ServerLevel level, Trip t) {
            final List<Shadeborn> party = party(level, t);
            final float chance = t.scout ? 0.25F : 0.15F;
            if (level.random.nextFloat() >= chance || party.isEmpty()) {
                return;
            }
            final boolean watched = !t.joined.isEmpty() || level.getNearestPlayer(s, 48) != null;
            if (watched) { // raiders, for real
                final BlockPos at = floor(level, s.blockPosition().offset(10, 0, 0));
                if (at != null) {
                    for (int i = 0; i < 2; i++) {
                        final Shadeborn r = AbyssalDepths.SHADEBORN.get().create(level);
                        if (r != null) {
                            r.moveTo(at.getX() + 0.5 + i, at.getY(), at.getZ() + 0.5, 0, 0);
                            r.setup(Role.RAIDER, Shadeborn.HOSTILE, 0, null);
                            level.addFreshEntity(r);
                        }
                    }
                    AbyssalDepths.LOGGER.debug("expedition: raiders fall on it");
                }
            } else { // out of sight: hurt, or lost
                final Shadeborn victim = party.get(level.random.nextInt(party.size()));
                if (level.random.nextFloat() < 0.15F) {
                    AbyssalDepths.LOGGER.debug("expedition: {} lost", victim.role().id);
                    victim.kill();
                } else {
                    victim.hurt(level.damageSources().generic(), 8);
                    AbyssalDepths.LOGGER.debug("expedition: {} hurt", victim.role().id);
                }
            }
        }

        /** Back: what they found, into the store or the camp's knowledge; shares for the Kin who went along. */
        private void home(ServerLevel level, Trip t) {
            final List<Shadeborn> party = party(level, t);
            final long camp = s.camp();
            final Camps.Camp c = Camps.of(level).camp(camp);
            final ShadebornCamps.Spot spot = spot(level);
            String found = null;
            if (t.scout) {
                found = scout(level, c, t.target);
                Camps.of(level).progress(camp, 4);
            } else if (spot != null) {
                final BarrelBlockEntity b = Raids.store(level, spot.store());
                final int amount = 2 + (int) (c.progress / 25) + party.size();
                if (b != null) {
                    put(b, new ItemStack(level.random.nextBoolean() ? Items.COOKED_MUTTON : Items.GLOW_BERRIES, amount));
                }
                Camps.of(level).progress(camp, 2);
            }
            for (UUID id : t.joined) {
                if (level.getPlayerByUUID(id) instanceof ServerPlayer p) {
                    final ItemStack share = found != null ? map(level, c, found) : new ItemStack(Items.COOKED_MUTTON, 3);
                    if (!share.isEmpty() && !p.addItem(share)) {
                        p.drop(share, false);
                    }
                    Camps.of(level).change(camp, id, 5);
                    s.gesture("bow");
                }
            }
            for (Shadeborn m : party) {
                m.trip = null;
                m.restrictTo(m.home(), 20);
                m.fatigue = Math.min(1, m.fatigue + 0.3F);
            }
            level.playSound(null, s.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.0F, 0.8F);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, s.getX(), s.getEyeY() + 0.4, s.getZ(), 6, 0.4, 0.3, 0.4, 0);
            AbyssalDepths.LOGGER.debug("expedition back to {}: {} of them, {}", camp, party.size(), found != null ? "found " + found : "gathered");
        }

        private List<Shadeborn> party(ServerLevel level, Trip t) {
            return level.getEntitiesOfClass(Shadeborn.class, s.getBoundingBox().inflate(32), m -> m.isAlive() && t.equals(m.trip));
        }

        private ShadebornCamps.Spot spot(ServerLevel level) {
            final var p = redero.abyssaldepths.worldgen.ShadebornStructure.at(level, s.home(), 24);
            return p == null ? null : p.spot;
        }
    }

    private static void put(BarrelBlockEntity b, ItemStack st) {
        for (int i = 0; i < b.getContainerSize() && !st.isEmpty(); i++) {
            if (b.getItem(i).isEmpty()) {
                b.setItem(i, st.copy());
                st.setCount(0);
            }
        }
        b.setChanged();
    }

    /** The places a scouting party can come to know, by name (as the Pathfinder's maps). */
    private static final String[] PLACES = {"grotto", "frozenfall", "tidelake", "skeleton", "lavafall", "ventlung", "canyon"};

    private static BiFunction<ServerLevel, BlockPos, BlockPos> finder(String name) {
        return switch (name) {
            case "grotto" -> TunnelFeature::nearestGrotto;
            case "frozenfall" -> TunnelFeature::nearestFrozenFall;
            case "tidelake" -> TunnelFeature::nearestTideLake;
            case "skeleton" -> TunnelFeature::nearestSkeleton;
            case "lavafall" -> TunnelFeature::nearestLavaFall;
            case "ventlung" -> TunnelFeature::nearestVentLung;
            default -> TunnelFeature::nearestCanyon;
        };
    }

    /** A scouting party comes to know the nearest place it did not know yet (from where it searched); its name, or null. */
    private static String scout(ServerLevel level, Camps.Camp c, BlockPos from) {
        String best = null;
        BlockPos bestAt = null;
        for (String name : PLACES) {
            if (c.scouted.containsKey(name)) {
                continue;
            }
            final BlockPos at = finder(name).apply(level, from);
            if (at != null && (bestAt == null || at.distSqr(from) < bestAt.distSqr(from))) {
                best = name;
                bestAt = at;
            }
        }
        if (best != null) {
            c.scouted.put(best, bestAt);
            Camps.of(level).setDirty();
        }
        return best;
    }

    /** A map to a place the camp knows. */
    static ItemStack map(ServerLevel level, Camps.Camp c, String name) {
        final BlockPos at = c.scouted.get(name);
        if (at == null) {
            return ItemStack.EMPTY;
        }
        final ItemStack map = MapItem.create(level, at.getX(), at.getZ(), (byte) 2, true, true);
        MapItemSavedData.addTargetDecoration(map, at, "+", MapDecorationTypes.RED_X);
        map.set(DataComponents.ITEM_NAME, Component.translatable("item.abyssaldepths.shadeborn_map." + name));
        return map;
    }
}
