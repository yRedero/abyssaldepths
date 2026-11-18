package redero.abyssaldepths.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.shadeborn.Role;
import redero.abyssaldepths.shadeborn.Shadeborn;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * The Shadeborn camps and the outcasts' huts (DESIGN.md 5.2.1), laid as structures ({@link ShadebornStructure}: so
 * {@code /locate} finds them): small camps of 3 to 8 Shadeborn, built of the layer's stone, dusk stalk and duskweave,
 * beside a gloam pocket (life gathers round the gloam; in the dunes, where the silt keeps it buried, on open silt). Each
 * has a hearth in its middle, a store (barrels of supplies), tents round it and a watch post; the ground round the
 * hearth cleared (the Shadeborn cleared it), every part set on the ground of its own column. Who lives there goes by
 * the region: stormwatchers in the dunes, smiths in the reaches, delvers on the flats, seers and lamp-keepers in the
 * depths; a few camps are hostile bands. Outcasts live apart in huts of their own.
 */
public final class ShadebornCamps {
    private static final double HOSTILE = 0.12;

    /** Who lives in a camp of each region, in the order they come as it grows (a huddle: 3, a camp: 6, a hold: 8). */
    public static Role[] members(int biome) {
        return switch (biome) {
            case Biomes.DUNES -> new Role[] {Role.KEEPER, Role.STORMWATCHER, Role.TRADER, Role.WATCHER, Role.STORMWATCHER, Role.YOUNG, Role.PATHFINDER, Role.MENDER};
            case Biomes.THERMAL -> new Role[] {Role.KEEPER, Role.SMITH, Role.LAMP_KEEPER, Role.WATCHER, Role.SMITH, Role.YOUNG, Role.TRADER, Role.WEAVER};
            case Biomes.FLATS -> new Role[] {Role.KEEPER, Role.DELVER, Role.TRADER, Role.WATCHER, Role.DELVER, Role.YOUNG, Role.PATHFINDER, Role.FORAGER};
            default -> new Role[] {Role.KEEPER, Role.SEER, Role.LAMP_KEEPER, Role.WATCHER, Role.MOURNER, Role.YOUNG, Role.MENDER, Role.WEAVER};
        };
    }

    /** How many live in a camp grown so far (a huddle, a camp, a hold). */
    public static int count(int growth) {
        return growth >= 2 ? 8 : growth == 1 ? 6 : 3;
    }

    /** A camp (or hut): its hearth (x, z), the layer's floor there, its region, a hostile band or not, its id (never 0). */
    public record Spot(int x, int z, int floor, int biome, boolean hostile, long id) {
        public BlockPos hearth() {
            return new BlockPos(x, floor, z);
        }

        public BlockPos store() {
            return new BlockPos(x + 3, floor, z - 2);
        }
    }

    /** A camp's id from its hearth. */
    public static long id(int x, int z) {
        return BlockPos.asLong(x, 0, z) ^ 0x5A11_0000_0000_0001L;
    }

    /**
     * The camp of a chunk, if there is room for one in it: its hearth inside the chunk (so the game's /locate points at
     * it), where the floor lies level and open, no rock formation stands, and a gloam pocket lies near (life gathers
     * round the gloam); in the dunes, where the silt keeps the gloam buried, open silt is enough.
     */
    public static Spot camp(TunnelLayer layer, Biomes biomes, LayerFormations formations, int chunkX, int chunkZ) {
        final TunnelLayer.Column c = new TunnelLayer.Column();
        final boolean hostile = layer.random(chunkX, chunkZ, 9102) < HOSTILE;
        for (int k = 0; k < 9; k++) {
            final int x = chunkX * 16 + 3 + (k % 3) * 5, z = chunkZ * 16 + 3 + (k / 3) * 5;
            final int biome = biomes.at(x, z);
            if (!level(layer, x, z, c, 10) || !formations.near(x - 13, z - 13, x + 13, z + 13).isEmpty()) {
                continue;
            }
            if (biome == Biomes.DUNES || gloamNear(layer, biomes, x, z, 30)) {
                layer.column(x, z, c);
                return new Spot(x, z, c.floor, biome, hostile, id(x, z));
            }
        }
        return null;
    }

    /** Whether a gloam pocket lies within so many blocks of a place. */
    private static boolean gloamNear(TunnelLayer layer, Biomes biomes, int x, int z, int reach) {
        final int cell = GloamPockets.POCKET_CELL;
        for (int px = Math.floorDiv(x - reach, cell); px <= Math.floorDiv(x + reach, cell); px++) {
            for (int pz = Math.floorDiv(z - reach, cell); pz <= Math.floorDiv(z + reach, cell); pz++) {
                final int[] pocket = GloamPockets.pocket(layer, px, pz);
                if (pocket != null && biomes.at(pocket[0], pocket[1]) != Biomes.DUNES && Math.hypot(pocket[0] - x, pocket[1] - z) < reach) {
                    return true;
                }
            }
        }
        return false;
    }

    /** An outcast's hut in a chunk, if there is room: open level floor, no rock formation there. */
    public static Spot hut(TunnelLayer layer, Biomes biomes, LayerFormations formations, int chunkX, int chunkZ) {
        final TunnelLayer.Column c = new TunnelLayer.Column();
        for (int k = 0; k < 9; k++) {
            final int x = chunkX * 16 + 3 + (k % 3) * 5, z = chunkZ * 16 + 3 + (k / 3) * 5;
            if (level(layer, x, z, c, 6) && formations.near(x - 7, z - 7, x + 7, z + 7).isEmpty()) {
                layer.column(x, z, c);
                return new Spot(x, z, c.floor, biomes.at(x, z), layer.random(chunkX, chunkZ, 9205) < 0.4, id(x, z) ^ 0x77);
            }
        }
        return null;
    }

    /** The layer of the running world (for the camp's layout at run time). */
    private static TunnelLayer runningLayer() {
        final net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : TunnelFeature.Context.of(server.overworld()).layer;
    }

    /** Where a camp's tents stand (inside each, by its mat), as laid. */
    public static java.util.List<BlockPos> tents(Spot s) {
        final TunnelLayer layer = runningLayer();
        final java.util.List<BlockPos> out = new java.util.ArrayList<>();
        if (layer == null) {
            return out;
        }
        final int tents = 2 + (int) (layer.random(s.x(), s.z(), 9130) * 3);
        for (int k = 0; k < tents; k++) {
            final double a = (k + 0.3 * layer.random(s.x(), s.z(), 9131 + k)) * Math.PI * 2 / tents + layer.random(s.x(), s.z(), 9135) * Math.PI;
            final int tx = s.x() + (int) Math.round(Math.cos(a) * 7.5), tz = s.z() + (int) Math.round(Math.sin(a) * 7.5);
            out.add(new BlockPos(tx + (int) Math.round(Math.cos(a)) * 2, s.floor(), tz + (int) Math.round(Math.sin(a)) * 2));
        }
        return out;
    }

    /** Where a camp's watch post stands. */
    public static BlockPos post(Spot s) {
        final TunnelLayer layer = runningLayer();
        final double w = layer == null ? 0 : layer.random(s.x(), s.z(), 9140) * Math.PI * 2;
        return new BlockPos(s.x() + (int) Math.round(Math.cos(w) * 11), s.floor(), s.z() + (int) Math.round(Math.sin(w) * 11));
    }

    /** Whether the floor lies level and open round a place, room enough overhead for a camp. */
    private static boolean level(TunnelLayer layer, int x, int z, TunnelLayer.Column c, int reach) {
        if (!layer.column(x, z, c) || c.ceiling - c.floor < 9) {
            return false;
        }
        final int floor = c.floor;
        for (int k = 0; k < 12; k++) {
            final double a = k * Math.PI / 6;
            for (int r : new int[] {reach / 2, reach}) {
                if (!layer.column(x + (int) (Math.cos(a) * r), z + (int) (Math.sin(a) * r), c) || Math.abs(c.floor - floor) > 1 || c.ceiling - c.floor < 6) {
                    return false;
                }
            }
        }
        layer.column(x, z, c);
        return true;
    }

    // ------------------------------------------------------------------ building (each chunk its own part)

    private final WorldGenLevel level;
    private final TunnelLayer layer;
    private final int x0, z0;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    ShadebornCamps(WorldGenLevel level, TunnelLayer layer, int x0, int z0) {
        this.level = level;
        this.layer = layer;
        this.x0 = x0;
        this.z0 = z0;
    }

    private boolean inChunk(int x, int z) {
        return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
    }

    /**
     * The ground of a column: the layer's own floor there (the same answer in every chunk, whatever the neighbours'
     * state; the campsite is levelled to it), or the height given where the layer has no open floor.
     */
    private int ground(int x, int z, int near) {
        final TunnelLayer.Column c = new TunnelLayer.Column();
        return layer.column(x, z, c) && Math.abs(c.floor - near) <= 3 ? c.floor : near;
    }

    /** A block into air (or what grows on the floor): nothing of the rock is replaced. */
    private void put(int x, int y, int z, BlockState state) {
        if (!inChunk(x, z)) {
            return;
        }
        final BlockState here = level.getBlockState(pos.set(x, y, z));
        if (here.isAir() || here.canBeReplaced()) {
            level.setBlock(pos, state, 2);
        }
    }

    /** The campsite cleared: within a radius of the hearth, nothing stands over the ground (stray formations taken away). */
    private void clear(Spot s, int radius, int height) {
        final TunnelLayer.Column c = new TunnelLayer.Column();
        for (int x = Math.max(x0, s.x() - radius); x <= Math.min(x0 + 15, s.x() + radius); x++) {
            for (int z = Math.max(z0, s.z() - radius); z <= Math.min(z0 + 15, s.z() + radius); z++) {
                if (Math.hypot(x - s.x(), z - s.z()) > radius || !layer.column(x, z, c)) {
                    continue;
                }
                final int g = ground(x, z, s.floor());
                for (int y = g; y < Math.min(g + height, c.ceiling - 1); y++) { // nothing over the ground (dune silt dug out)
                    final BlockState here = level.getBlockState(pos.set(x, y, z));
                    if (!here.isAir() && here.getFluidState().isEmpty()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                final BlockState under = level.getBlockState(pos.set(x, g - 1, z));
                if (under.isAir() || under.canBeReplaced()) { // a hole in the ground: filled
                    level.setBlock(pos, level.getBlockState(pos.set(x, g - 2, z)).isAir() ? AbyssalDepths.THALAN.get().defaultBlockState()
                            : level.getBlockState(pos.set(x, g - 2, z)), 2);
                }
            }
        }
    }

    void camp(Spot s) {
        clear(s, 13, 7);
        final int hg = ground(s.x(), s.z(), s.floor());
        // the hearth: a ring of stones round a fire (the camp's light)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    put(s.x() + dx, ground(s.x() + dx, s.z() + dz, hg), s.z() + dz, AbyssalDepths.COBBLED_THALAN_SLAB.get().defaultBlockState());
                }
            }
        }
        put(s.x(), hg, s.z(), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, false));
        // the store: barrels of supplies (what they have of food and light)
        final BlockPos store = s.store();
        if (inChunk(store.getX(), store.getZ())) {
            final int sg = ground(store.getX(), store.getZ(), hg);
            put(store.getX(), sg, store.getZ(), Blocks.BARREL.defaultBlockState().setValue(net.minecraft.world.level.block.BarrelBlock.FACING, Direction.UP));
            if (level.getBlockEntity(pos.set(store.getX(), sg, store.getZ())) instanceof RandomizableContainerBlockEntity barrel) {
                barrel.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "chests/shadeborn_store")), s.id() * 0x9E3779B97F4A7C15L);
            }
        }
        if (inChunk(store.getX() + 1, store.getZ())) {
            put(store.getX() + 1, ground(store.getX() + 1, store.getZ(), hg), store.getZ(),
                    Blocks.BARREL.defaultBlockState().setValue(net.minecraft.world.level.block.BarrelBlock.FACING, Direction.UP));
        }
        // the tents round it, facing the hearth
        final int tents = 2 + (int) (layer.random(s.x(), s.z(), 9130) * 3);
        for (int k = 0; k < tents; k++) {
            final double a = (k + 0.3 * layer.random(s.x(), s.z(), 9131 + k)) * Math.PI * 2 / tents + layer.random(s.x(), s.z(), 9135) * Math.PI;
            final int tx = s.x() + (int) Math.round(Math.cos(a) * 7.5), tz = s.z() + (int) Math.round(Math.sin(a) * 7.5);
            tent(tx, ground(tx, tz, hg), tz, a);
        }
        // the watch post
        final double w = layer.random(s.x(), s.z(), 9140) * Math.PI * 2;
        final int wx = s.x() + (int) Math.round(Math.cos(w) * 11), wz = s.z() + (int) Math.round(Math.sin(w) * 11);
        post(wx, ground(wx, wz, hg), wz);
        if (inChunk(s.x() + 2, s.z() + 2)) {
            put(s.x() + 2, ground(s.x() + 2, s.z() + 2, hg), s.z() + 2, AbyssalDepths.DUSKWEAVE_MAT.get().defaultBlockState());
        }
        // who lives there (when the chunk with the hearth is made)
        if (inChunk(s.x(), s.z())) {
            final Role[] roles = members(s.biome());
            for (int i = 0; i < count(0); i++) {
                spawn(s, roles[i], i, hg);
            }
            if (layer.random(s.x(), s.z(), 9150) < 0.5) {
                spawn(s, Role.FORAGER, 3, hg);
            }
        }
    }

    /** A Shadeborn of the camp near the hearth, set on clear ground (room for its height). */
    private void spawn(Spot s, Role role, int i, int hg) {
        final Shadeborn sb = AbyssalDepths.SHADEBORN.get().create(level.getLevel());
        if (sb == null) {
            return;
        }
        final double a = i * 1.9 + layer.random(s.x(), s.z(), 9160 + i);
        int x = s.x() + (int) Math.round(Math.cos(a) * 3.5), z = s.z() + (int) Math.round(Math.sin(a) * 3.5);
        if (!inChunk(x, z)) {
            x = s.x() + (i % 3) - 1;
            z = s.z() + 2;
        }
        final int y = ground(x, z, hg);
        sb.moveTo(x + 0.5, y, z + 0.5, (float) (a * 57.3), 0);
        sb.setup(role, temper(s, role, i), s.id(), new BlockPos(s.x(), hg, s.z()));
        sb.finalizeSpawn(level, level.getCurrentDifficultyAt(s.hearth()), MobSpawnType.STRUCTURE, null);
        level.addFreshEntity(sb);
    }

    /** A temperament: the hostile bands' all hostile; the young fearful; the rest fearful, wary or neutral. */
    public static int temper(Spot s, Role role, int i) {
        if (s.hostile() && role != Role.YOUNG) {
            return Shadeborn.HOSTILE;
        }
        if (role == Role.YOUNG) {
            return Shadeborn.FEARFUL;
        }
        if (role == Role.KEEPER || role == Role.WATCHER) {
            return Shadeborn.NEUTRAL;
        }
        final long h = (s.id() * 31 + i) * 0x9E3779B97F4A7C15L;
        final int r = (int) Math.floorMod(h >>> 33, 100L);
        return r < 30 ? Shadeborn.FEARFUL : r < 65 ? Shadeborn.WARY : Shadeborn.NEUTRAL;
    }

    /** A tent of duskweave over dusk stalk poles: five wide, four deep, room to stand under its ridge, open towards the hearth. */
    private void tent(int cx, int f, int cz, double a) {
        final int ox = (int) Math.round(Math.cos(a)), oz = (int) Math.round(Math.sin(a)); // out, away from the hearth
        final int sx = -oz, sz = ox; // across
        final BlockState weave = AbyssalDepths.DUSKWEAVE.get().defaultBlockState();
        final BlockState pole = AbyssalDepths.DUSK_STALK.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        for (int d = 0; d < 4; d++) {
            final int x = cx + ox * d, z = cz + oz * d;
            for (int y = 0; y <= 1; y++) {
                put(x + sx * 2, f + y, z + sz * 2, d == 0 ? pole : weave);
                put(x - sx * 2, f + y, z - sz * 2, d == 0 ? pole : weave);
            }
            put(x + sx, f + 2, z + sz, weave);
            put(x - sx, f + 2, z - sz, weave);
            put(x, f + 3, z, weave);
            if (d == 3) { // its back
                for (int k = -1; k <= 1; k++) {
                    put(x + sx * k, f, z + sz * k, weave);
                    put(x + sx * k, f + 1, z + sz * k, weave);
                }
                put(x, f + 2, z, weave);
            }
        }
        put(cx + ox * 2, f, cz + oz * 2, AbyssalDepths.DUSKWEAVE_MAT.get().defaultBlockState());
    }

    /** The watch post: a dusk stalk post with a ladder up it, a platform of dusk planks, a lantern hung under it. */
    private void post(int x, int f, int z) {
        final BlockState pole = AbyssalDepths.DUSK_STALK.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        for (int y = 0; y < 5; y++) {
            put(x, f + y, z, pole);
            put(x, f + y, z + 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 1) {
                    put(x + dx, f + 5, z + dz, AbyssalDepths.DUSK_PLANKS.get().defaultBlockState());
                }
            }
        }
        put(x - 1, f + 4, z - 1, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        put(x + 1, f + 6, z - 1, AbyssalDepths.DUSK_FENCE.get().defaultBlockState());
        put(x - 1, f + 6, z + 1, AbyssalDepths.DUSK_FENCE.get().defaultBlockState());
    }

    /** A tent more, put up in a Keeper's camp (in the running world, round the hearth at an angle). */
    public static void raiseTent(net.minecraft.server.level.ServerLevel level, BlockPos hearth, double angle) {
        final TunnelLayer layer = runningLayer();
        if (layer == null) {
            return;
        }
        final int tx = hearth.getX() + (int) Math.round(Math.cos(angle) * 9.5), tz = hearth.getZ() + (int) Math.round(Math.sin(angle) * 9.5);
        for (int cx = (tx - 4) >> 4; cx <= (tx + 4) >> 4; cx++) {
            for (int cz = (tz - 4) >> 4; cz <= (tz + 4) >> 4; cz++) {
                final ShadebornCamps b = new ShadebornCamps(level, layer, cx << 4, cz << 4);
                b.tent(tx, b.ground(tx, tz, hearth.getY()), tz, angle);
            }
        }
    }

    /** An outcast's hut: a lean-to of duskweave, a cold fire, its one dweller. */
    void hut(Spot s) {
        clear(s, 6, 6);
        final int f = ground(s.x(), s.z(), s.floor());
        tent(s.x(), f, s.z(), layer.random(s.x(), s.z(), 9204) * Math.PI * 2);
        if (inChunk(s.x() + 2, s.z() - 2)) {
            put(s.x() + 2, ground(s.x() + 2, s.z() - 2, f), s.z() - 2, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        }
        if (inChunk(s.x(), s.z())) {
            final Shadeborn sb = AbyssalDepths.SHADEBORN.get().create(level.getLevel());
            if (sb != null) {
                sb.moveTo(s.x() + 0.5, f, s.z() + 0.5, 0, 0);
                sb.setup(Role.OUTCAST, s.hostile() ? Shadeborn.HOSTILE : Shadeborn.WARY, 0, new BlockPos(s.x(), f, s.z()));
                level.addFreshEntity(sb);
            }
        }
    }
}
