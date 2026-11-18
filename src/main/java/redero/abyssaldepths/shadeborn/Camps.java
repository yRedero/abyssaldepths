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

        Camp(long id) {
            this.id = id;
        }

        public int trust(UUID player) {
            return trust.getOrDefault(player, 0);
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
            list.add(ct);
        }
        tag.put("camps", list);
        return tag;
    }
}
