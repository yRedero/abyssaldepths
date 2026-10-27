package redero.abyssaldepths.worldgen;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.block.ThermalVentBlock;
import redero.abyssaldepths.worldgen.LayerFormations.Biomes;
import redero.abyssaldepths.worldgen.LayerFormations.Mat;

/**
 * Builds the {@link TunnelLayer} in the chunk the feature is placed for. It runs once per chunk
 * (empty placement list) in the raw_generation step, before ores and other decoration, and only
 * touches the 16x16 columns of its chunk, in this order:
 * <ol>
 * <li>Carve the cavern and the connector tunnels (which reach up into the deepslate layer). Lava
 * next to the new air (vanilla lava tunnels and lava aquifers the up-shafts cut through) turns into
 * magma so it does not pour into the cavern; that may touch blocks of the neighbouring chunks,
 * which the region allows.</li>
 * <li>Lay the biome surfaces over the floor ({@link LayerSurface}).</li>
 * <li>Place this chunk's share of the formations ({@link LayerFormations}).</li>
 * <li>Restyle the walls and ceilings ({@link LayerWalls}).</li>
 * </ol>
 */
public class TunnelFeature extends Feature<NoneFeatureConfiguration> {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState MAGMA = Blocks.MAGMA_BLOCK.defaultBlockState();

    public TunnelFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        final long[] marks = WorldgenProfile.ON ? new long[WorldgenProfile.PHASES.length + 1] : null;
        mark(marks, 0);
        final WorldGenLevel level = context.level();
        final ChunkPos chunk = new ChunkPos(context.origin());
        final int x0 = chunk.getMinBlockX(), z0 = chunk.getMinBlockZ();
        final Context world = Context.of(level);
        final Biomes biomes = world.biomes;
        final TunnelLayer layer = world.layer;
        // the chunk's columns and one ring around it (the walls look across the chunk border)
        final TunnelLayer.Column[] ring = new TunnelLayer.Column[18 * 18];
        for (int dz = -1; dz <= 16; dz++) {
            for (int dx = -1; dx <= 16; dx++) {
                final TunnelLayer.Column c = ring[(dz + 1) * 18 + dx + 1] = new TunnelLayer.Column();
                layer.column(x0 + dx, z0 + dz, c);
            }
        }
        mark(marks, 1);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
        final boolean[] connectors = layer.connectorMask(chunk.x, chunk.z);
        mark(marks, 2);

        boolean placed = false;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                final int x = x0 + dx, z = z0 + dz;
                final TunnelLayer.Column column = ring[(dz + 1) * 18 + dx + 1];
                for (int y = TunnelLayer.MIN_AIR_Y; y <= TunnelLayer.CONNECTOR_MAX_Y; y++) {
                    if (!layer.isAir(x, y, z, column) && !connectors[TunnelLayer.maskIndex(dx, y, dz)]) {
                        continue;
                    }
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, AIR, 2);
                        placed = true;
                    }
                    for (Direction dir : Direction.values()) {
                        side.setWithOffset(pos, dir);
                        if (level.getFluidState(side).is(FluidTags.LAVA)) {
                            level.setBlock(side, MAGMA, 2);
                        }
                    }
                }
            }
        }

        mark(marks, 3);
        final List<LayerFormations.Formation> near = world.formations.near(x0 - 1, z0 - 1, x0 + 16, z0 + 16);
        mark(marks, 4);
        final LayerSurface surface = new LayerSurface(level, layer, near, x0, z0, biomes);
        final LayerFormations.Sink sink = new WorldSink(level);
        final LayerWalls walls = new LayerWalls(level, layer, surface, x0, z0, ring);
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                surface.apply(x0 + dx, z0 + dz, ring[(dz + 1) * 18 + dx + 1]);
            }
        }
        mark(marks, 5);
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                final TunnelLayer.Column c = ring[(dz + 1) * 18 + dx + 1];
                if (!c.open) {
                    continue;
                }
                // formations stand on the plateaus of the shale flats
                final int floor = c.floor + surface.plateauAt(x0 + dx, z0 + dz, c.ceiling - c.floor + 1);
                for (LayerFormations.Formation f : near) {
                    f.column(x0 + dx, z0 + dz, floor, c.ceiling, sink);
                }
            }
        }
        mark(marks, 6);
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                walls.apply(x0 + dx, z0 + dz);
            }
        }
        if (marks != null) {
            mark(marks, 7);
            WorldgenProfile.add(marks, near.size());
        }
        return placed;
    }

    private static void mark(long[] marks, int index) {
        if (marks != null) {
            marks[index] = System.nanoTime();
        }
    }

    /**
     * Everything about the layer that is the same for every chunk of a world, built once per world and
     * shared by all generation threads. The expensive answers (the biome of a spot needs the full climate
     * noise; the share of dunes around a spot 49 of those; the formations of a grid cell the cavern shape)
     * are kept in thread-safe caches instead of being worked out again for every neighbouring chunk.
     */
    private static final class Context {
        private static final int MAX_ENTRIES = 1_000_000;
        private static volatile Context current;

        final long seed;
        final BiomeSource source;
        final Biomes biomes;
        final TunnelLayer layer;
        final LayerFormations formations;
        private final ConcurrentHashMap<Long, Integer> biomeCache = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Double> dunesCache = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Boolean> pillarCache = new ConcurrentHashMap<>();

        private Context(WorldGenLevel level, BiomeSource source) {
            this.seed = level.getSeed();
            this.source = source;
            final Biomes raw = biomes(level);
            this.biomes = (x, z) -> cached(biomeCache, BlockPos.asLong(x >> 2, 0, z >> 2), k -> raw.at(x, z));
            // the shale flats are an open plain: no pillars there; the dunes keep about a third of them as
            // lone supports of their big hall (decided at the pillar centre)
            this.layer = new TunnelLayer(seed, (px, pz) -> {
                final long key = BlockPos.asLong((int) Math.floor(px), 0, (int) Math.floor(pz));
                return cached(pillarCache, key, k -> {
                    final int biome = biomes.at((int) Math.floor(px), (int) Math.floor(pz));
                    if (biome == Biomes.DUNES) {
                        final long h = key * 0x9E3779B97F4A7C15L;
                        return Long.remainderUnsigned(h ^ (h >>> 29) ^ seed, 100) < 35;
                    }
                    return biome != Biomes.FLATS;
                });
            }, DuneOpenness.of(biomes, dunesCache));
            this.formations = new LayerFormations(layer, biomes);
        }

        static Context of(WorldGenLevel level) {
            final BiomeSource source = level.getLevel().getChunkSource().getGenerator().getBiomeSource();
            Context c = current;
            if (c == null || c.seed != level.getSeed() || c.source != source) {
                synchronized (Context.class) {
                    c = current;
                    if (c == null || c.seed != level.getSeed() || c.source != source) {
                        current = c = new Context(level, source); // another world: start over
                    }
                }
            }
            return c;
        }

        private static <V> V cached(ConcurrentHashMap<Long, V> cache, long key, java.util.function.LongFunction<V> compute) {
            final V hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
            if (cache.size() > MAX_ENTRIES) {
                cache.clear(); // rarely: keeps memory bounded on long journeys
            }
            final V value = compute.apply(key);
            cache.putIfAbsent(key, value);
            return value;
        }
    }

    /**
     * Layer biome of a column, straight from the biome source: works for any position, also outside
     * the chunks this feature may access.
     */
    private static Biomes biomes(WorldGenLevel level) {
        final ServerLevel server = level.getLevel();
        final BiomeSource source = server.getChunkSource().getGenerator().getBiomeSource();
        final Climate.Sampler sampler = server.getChunkSource().randomState().sampler();
        final int qy = QuartPos.fromBlock(-100);
        return (x, z) -> {
            final Holder<Biome> biome = source.getNoiseBiome(QuartPos.fromBlock(x), qy, QuartPos.fromBlock(z), sampler);
            return biome.is(AbyssalDepths.SHALE_FLATS) ? Biomes.FLATS
                    : biome.is(AbyssalDepths.SILT_DUNES) ? Biomes.DUNES
                    : biome.is(AbyssalDepths.THERMAL_REACHES) ? Biomes.THERMAL : Biomes.DEPTHS;
        };
    }

    /** Formation materials into the world. */
    private static final class WorldSink implements LayerFormations.Sink {
        private final WorldGenLevel level;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        WorldSink(WorldGenLevel level) {
            this.level = level;
        }

        private BlockState get(int x, int y, int z) {
            return level.getBlockState(pos.set(x, y, z));
        }

        @Override
        public boolean air(int x, int y, int z) {
            return get(x, y, z).isAir();
        }

        @Override
        public boolean open(int x, int y, int z) {
            final BlockState s = get(x, y, z);
            return s.isAir() || s.is(AbyssalDepths.FINE_SILT.get());
        }

        @Override
        public boolean solid(int x, int y, int z) {
            final BlockState s = get(x, y, z);
            return !s.isAir() && s.getFluidState().isEmpty() && !s.is(Blocks.BEDROCK);
        }

        @Override
        public void set(int x, int y, int z, Mat mat) {
            level.setBlock(pos.set(x, y, z), state(mat), 2);
        }

        private static BlockState state(Mat mat) {
            return switch (mat) {
                case AIR -> AIR;
                case WATER -> Blocks.WATER.defaultBlockState();
                case FRACTURED_SHALE -> AbyssalDepths.FRACTURED_SHALE.get().defaultBlockState();
                case SHALE_RUBBLE -> AbyssalDepths.SHALE_RUBBLE.get().defaultBlockState();
                case SILTSTONE -> AbyssalDepths.SILTSTONE.get().defaultBlockState();
                case FOSSIL -> AbyssalDepths.FOSSILIZED_SILTSTONE.get().defaultBlockState();
                case THERMAL_ROCK -> AbyssalDepths.THERMAL_ROCK.get().defaultBlockState();
                case OXIDE -> AbyssalDepths.OXIDE_DEPOSIT.get().defaultBlockState();
                case SINTER -> AbyssalDepths.SINTER.get().defaultBlockState();
                case VENT -> AbyssalDepths.THERMAL_VENT.get().defaultBlockState();
                case ACTIVE_VENT -> AbyssalDepths.THERMAL_VENT.get().defaultBlockState().setValue(ThermalVentBlock.ACTIVE, true);
                case SULFUR -> AbyssalDepths.SULFUR_DEPOSIT.get().defaultBlockState();
                case MAT -> AbyssalDepths.THERMOPHILE_MAT.get().defaultBlockState();
                case SMOULDERING -> AbyssalDepths.SMOULDERING_ROCK.get().defaultBlockState();
                case ORE_IRON -> AbyssalDepths.THALAN_IRON_ORE.get().defaultBlockState();
                case ORE_COPPER -> AbyssalDepths.THALAN_COPPER_ORE.get().defaultBlockState();
                case ORE_GOLD -> AbyssalDepths.THALAN_GOLD_ORE.get().defaultBlockState();
                case ORE_REDSTONE -> AbyssalDepths.THALAN_REDSTONE_ORE.get().defaultBlockState();
            };
        }
    }
}
