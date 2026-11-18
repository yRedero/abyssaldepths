package redero.abyssaldepths.worldgen;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import redero.abyssaldepths.AbyssalDepths;

/**
 * The Shadeborn camps and the outcasts' huts as real structures, so the game knows them: {@code /locate structure
 * abyssaldepths:shadeborn_camp} (or {@code outcast_hut}) finds them, and other mods that look for structures see them.
 * Where one may stand is decided as before, from the layer ({@link ShadebornCamps#camp} / {@link ShadebornCamps#hut});
 * the structure sets in {@code data/abyssaldepths/worldgen/structure_set} space them out. It is laid after the layer is
 * carved (the step in its file), each chunk its own part, by {@link Piece}.
 */
public final class ShadebornStructure extends Structure {
    public static final DeferredRegister<StructureType<?>> TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, AbyssalDepths.MODID);
    public static final DeferredRegister<StructurePieceType> PIECES = DeferredRegister.create(Registries.STRUCTURE_PIECE, AbyssalDepths.MODID);

    public static final MapCodec<ShadebornStructure> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(settingsCodec(i),
            Codec.STRING.fieldOf("kind").forGetter(s -> s.kind)).apply(i, ShadebornStructure::new));
    public static final DeferredHolder<StructureType<?>, StructureType<ShadebornStructure>> TYPE = TYPES.register("shadeborn", () -> () -> CODEC);
    public static final DeferredHolder<StructurePieceType, StructurePieceType> PIECE = PIECES.register("shadeborn",
            () -> (StructurePieceType.ContextlessType) Piece::new);

    /** "camp" or "hut". */
    private final String kind;

    public ShadebornStructure(StructureSettings settings, String kind) {
        super(settings);
        this.kind = kind;
    }

    /** The layer's context (the world's own, kept by the layer feature). */
    private static TunnelFeature.Context context() {
        final net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : TunnelFeature.Context.of(server.overworld());
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        final TunnelFeature.Context c = context();
        if (c == null) {
            return Optional.empty();
        }
        final ChunkPos chunk = ctx.chunkPos();
        final ShadebornCamps.Spot s = kind.equals("hut") ? ShadebornCamps.hut(c.layer, c.biomes, c.formations, chunk.x, chunk.z)
                : ShadebornCamps.camp(c.layer, c.biomes, c.formations, chunk.x, chunk.z);
        if (s == null) {
            return Optional.empty();
        }
        return Optional.of(new GenerationStub(s.hearth(), builder -> builder.addPiece(new Piece(s, kind.equals("hut")))));
    }

    @Override
    public StructureType<?> type() {
        return TYPE.get();
    }

    /** The one piece of a camp or hut: what it covers, and the building of its part in each chunk. */
    public static final class Piece extends StructurePiece {
        public final ShadebornCamps.Spot spot;
        public final boolean hut;

        Piece(ShadebornCamps.Spot spot, boolean hut) {
            super(PIECE.get(), 0, box(spot, hut));
            this.spot = spot;
            this.hut = hut;
        }

        Piece(CompoundTag tag) {
            super(PIECE.get(), tag);
            this.spot = new ShadebornCamps.Spot(tag.getInt("x"), tag.getInt("z"), tag.getInt("floor"), tag.getInt("biome"), tag.getBoolean("hostile"),
                    tag.getLong("id"));
            this.hut = tag.getBoolean("hut");
        }

        private static BoundingBox box(ShadebornCamps.Spot s, boolean hut) {
            final int r = hut ? 7 : 15;
            return new BoundingBox(s.x() - r, s.floor() - 4, s.z() - r, s.x() + r, s.floor() + 14, s.z() + r);
        }

        @Override
        protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
            tag.putInt("x", spot.x());
            tag.putInt("z", spot.z());
            tag.putInt("floor", spot.floor());
            tag.putInt("biome", spot.biome());
            tag.putBoolean("hostile", spot.hostile());
            tag.putLong("id", spot.id());
            tag.putBoolean("hut", hut);
        }

        @Override
        public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random, BoundingBox chunkBox,
                ChunkPos chunk, BlockPos pivot) {
            final TunnelFeature.Context c = TunnelFeature.Context.of(level);
            final ShadebornCamps build = new ShadebornCamps(level, c.layer, chunk.getMinBlockX(), chunk.getMinBlockZ());
            if (hut) {
                build.hut(spot);
            } else {
                build.camp(spot);
            }
        }
    }

    /** The camp or hut whose ground a place is (its piece), from the structures of the loaded chunks round it; or null. */
    public static Piece at(ServerLevel level, BlockPos at, int radius) {
        final int r = (radius >> 4) + 1;
        Piece best = null;
        double near = radius;
        for (int cx = (at.getX() >> 4) - r; cx <= (at.getX() >> 4) + r; cx++) {
            for (int cz = (at.getZ() >> 4) - r; cz <= (at.getZ() >> 4) + r; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                for (var start : level.getChunk(cx, cz).getAllStarts().values()) {
                    if (start.isValid() && !start.getPieces().isEmpty() && start.getPieces().get(0) instanceof Piece p) {
                        final double d = Math.hypot(p.spot.x() - at.getX(), p.spot.z() - at.getZ());
                        if (d < near) {
                            near = d;
                            best = p;
                        }
                    }
                }
            }
        }
        return best;
    }
}
