package redero.abyssaldepths.shadeborn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import redero.abyssaldepths.AbyssalDepths;

/**
 * What the world remembers of the Shadeborn camps: per camp (its id is its grid cell) where it is, its store, how far it
 * has grown, and how much each player is trusted there; whose camp it is once a player became its Keeper.
 * <p>
 * Trust is kept per camp and rises through five stages (DESIGN.md 5.2.1): Stranger, Tolerated, Welcome, Kin, Keeper.
 */
public final class Camps extends SavedData {
    private static final String NAME = AbyssalDepths.MODID + "_camps";
    /** Trust needed for each stage (Keeper also needs the Keeper's acceptance). */
    public static final int[] STAGE = {0, 10, 30, 60, 100};
    public static final int STRANGER = 0, TOLERATED = 1, WELCOME = 2, KIN = 3, KEEPER = 4;
    /** How a camp has grown: a huddle, a camp, a hold. */
    public static final int HUDDLE = 0, CAMP = 1, HOLD = 2;

    public static final class Camp {
        public final long id;
        public BlockPos center = BlockPos.ZERO, store = BlockPos.ZERO;
        public int growth;
        public UUID keeper;
        public long lastSupply;
        public final Map<UUID, Integer> trust = new HashMap<>();
        /** Who was given the camp's mark (Kin). */
        public final java.util.Set<UUID> marked = new java.util.HashSet<>();
        /** Tents a Keeper had put up (beyond those it was built with). */
        public int extraTents;
        /** Who harmed them, and till when they hold it against them (game time). */
        public final Map<UUID, Long> grudges = new HashMap<>();
        /** A raid on the camp: till when they keep on guard (game time). */
        public long raidedUntil;
        /** How far the camp has come (0–100): what it can do (expeditions...). See Expeditions. */
        public float progress;
        /** When the Elder last thought of sending an expedition (game time); when it last oversaw the camp. */
        public long lastExpedition, lastOversee;
        /** When the last newcomer came (game time): the next comes only after a while. */
        public long lastNewcomer;
        /** Places its scouts came to know, by name (as the Pathfinder's maps). */
        public final Map<String, BlockPos> scouted = new HashMap<>();

        Camp(long id) {
            this.id = id;
        }

        public int trust(UUID player) {
            return trust.getOrDefault(player, 0);
        }

        /** How much longer they hold a grudge against a player (ticks; 0 for none). */
        public long grudge(UUID player, long now) {
            return Math.max(0, grudges.getOrDefault(player, 0L) - now);
        }

        /** The stage a player stands at here. */
        public int stage(UUID player) {
            if (player.equals(keeper)) {
                return KEEPER;
            }
            final int t = trust(player);
            int stage = STRANGER;
            for (int s = TOLERATED; s <= KIN; s++) {
                if (t >= STAGE[s]) {
                    stage = s;
                }
            }
            return stage;
        }
    }

    private final Map<Long, Camp> camps = new HashMap<>();

    public static Camps of(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Camps::new, Camps::load, null), NAME);
    }

    public Camp camp(long id) {
        return camps.computeIfAbsent(id, Camp::new);
    }

    public java.util.Collection<Camp> all() {
        return camps.values();
    }

    /** Changes a player's trust at a camp (never below a floor of -100); true if they reached a new stage by it. */
    public boolean change(long id, UUID player, int by) {
        final Camp c = camp(id);
        final int before = c.stage(player);
        c.trust.put(player, Math.max(-100, Math.min(STAGE[KEEPER] + 50, c.trust(player) + by)));
        setDirty();
        return c.stage(player) > before;
    }

    /** A day of the world, in ticks. */
    public static final long DAY = 24000;

    /**
     * How long a blow is held against a player: the less they were trusted, the longer (a stranger is remembered for
     * days, Kin forgiven within half a day); a death twice as long. Blows add up, to ten days at most.
     */
    public static long grudgeFor(int trustBefore, boolean killed) {
        final long base = trustBefore < 0 ? 5 * DAY : trustBefore < STAGE[TOLERATED] ? 4 * DAY : trustBefore < STAGE[WELCOME] ? 2 * DAY
                : trustBefore < STAGE[KIN] ? DAY : DAY / 2;
        return killed ? base * 2 : base;
    }

    /** Whether a player is Kin (or more) of any camp: raiders hate those who side with the camps. */
    public boolean kinAnywhere(UUID player) {
        return camps.values().stream().anyMatch(c -> c.stage(player) >= KIN);
    }

    /** Moves a camp's progress (kept between 0 and 100). */
    public void progress(long id, float by) {
        final Camp c = camp(id);
        c.progress = Math.max(0, Math.min(100, c.progress + by));
        setDirty();
    }

    /** A camp remembers a blow from a player. */
    public void hold(long id, UUID player, long now, long ticks) {
        final Camp c = camp(id);
        c.grudges.put(player, Math.min(now + 10 * DAY, Math.max(now, c.grudges.getOrDefault(player, 0L)) + ticks));
        setDirty();
    }

    /** A camp lets go of some of a grudge (a gift of what they lack): half of what is left. */
    public void soften(long id, UUID player, long now) {
        final Camp c = camp(id);
        final long left = c.grudge(player, now);
        if (left > 0) {
            c.grudges.put(player, now + left / 2);
            setDirty();
        }
    }

    private static Camps load(CompoundTag tag, HolderLookup.Provider registries) {
        final Camps m = new Camps();
        for (Tag t : tag.getList("camps", Tag.TAG_COMPOUND)) {
            final CompoundTag ct = (CompoundTag) t;
            final Camp c = m.camp(ct.getLong("id"));
            c.center = NbtUtils.readBlockPos(ct, "center").orElse(BlockPos.ZERO);
            c.store = NbtUtils.readBlockPos(ct, "store").orElse(BlockPos.ZERO);
            c.growth = ct.getInt("growth");
            c.lastSupply = ct.getLong("supply");
            if (ct.hasUUID("keeper")) {
                c.keeper = ct.getUUID("keeper");
            }
            for (Tag e : ct.getList("marked", Tag.TAG_INT_ARRAY)) {
                c.marked.add(NbtUtils.loadUUID(e));
            }
            c.extraTents = ct.getInt("tents");
            for (Tag e : ct.getList("trust", Tag.TAG_COMPOUND)) {
                c.trust.put(((CompoundTag) e).getUUID("p"), ((CompoundTag) e).getInt("t"));
            }
            for (Tag e : ct.getList("grudges", Tag.TAG_COMPOUND)) {
                c.grudges.put(((CompoundTag) e).getUUID("p"), ((CompoundTag) e).getLong("until"));
            }
            c.raidedUntil = ct.getLong("raided");
            c.progress = ct.getFloat("progress");
            c.lastExpedition = ct.getLong("expedition");
            c.lastNewcomer = ct.getLong("newcomer");
            final CompoundTag known = ct.getCompound("scouted");
            for (String k : known.getAllKeys()) {
                c.scouted.put(k, BlockPos.of(known.getLong(k)));
            }
        }
        return m;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        final ListTag list = new ListTag();
        for (Camp c : camps.values()) {
            final CompoundTag ct = new CompoundTag();
            ct.putLong("id", c.id);
            ct.put("center", NbtUtils.writeBlockPos(c.center));
            ct.put("store", NbtUtils.writeBlockPos(c.store));
            ct.putInt("growth", c.growth);
            ct.putLong("supply", c.lastSupply);
            if (c.keeper != null) {
                ct.putUUID("keeper", c.keeper);
            }
            final ListTag trust = new ListTag();
            c.trust.forEach((p, t) -> {
                final CompoundTag e = new CompoundTag();
                e.putUUID("p", p);
                e.putInt("t", t);
                trust.add(e);
            });
            ct.put("trust", trust);
            final ListTag marked = new ListTag();
            c.marked.forEach(u -> marked.add(NbtUtils.createUUID(u)));
            ct.put("marked", marked);
            ct.putInt("tents", c.extraTents);
            final ListTag grudges = new ListTag();
            c.grudges.forEach((p, until) -> {
                final CompoundTag e = new CompoundTag();
                e.putUUID("p", p);
                e.putLong("until", until);
                grudges.add(e);
            });
            ct.put("grudges", grudges);
            ct.putLong("raided", c.raidedUntil);
            ct.putFloat("progress", c.progress);
            ct.putLong("expedition", c.lastExpedition);
            ct.putLong("newcomer", c.lastNewcomer);
            final CompoundTag known = new CompoundTag();
            c.scouted.forEach((k, at) -> known.putLong(k, at.asLong()));
            ct.put("scouted", known);
            list.add(ct);
        }
        tag.put("camps", list);
        return tag;
    }
}
