package redero.abyssaldepths.artifact.reprise;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.RepriseItem;

/**
 * Reprise's memory of the last {@link #WINDOW} ticks, kept only while some player carries a Reprise
 * (checked every second; when nobody does, everything is forgotten).
 * <ul>
 * <li><b>Blocks</b>: every change in a loaded chunk, with the state it replaced ({@link LevelLog}), from
 * a hook in {@code LevelChunk.setBlockState}; at most once per block per second.</li>
 * <li><b>Block entities</b> (chests, furnaces...): their contents are kept from the moment their chunk is
 * known (recording start, chunk load); when one says it changed, the old contents are written down at
 * the next second, and the new ones kept.</li>
 * <li><b>Moments</b>, every 5 seconds: the creatures and items of the areas around the players, every
 * player's state, the time of day. The rewind goes back to the oldest moment still in the window, and
 * undoes exactly the block changes after it, so blocks and entities match.</li>
 * </ul>
 * Costs: memory grows with how much changes (not with the world); the expensive work is the moment
 * (saving the loaded entities every 5 seconds) and the first baseline of block entities.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class RepriseRecorder {
    /** How far back Reprise can reach at most: 240 seconds (at full mastery). */
    public static final int WINDOW = 20 * 240;
    /** How far back the memory is kept now: as far as the furthest reaching Reprise carried needs. */
    static int window = WINDOW;
    /** How often a moment is taken, in ticks. */
    static final int MOMENT_EVERY = 100;
    /** Changes kept at most; beyond that the oldest are dropped (the reach gets shorter). */
    private static final int MAX_CHANGES = 2_000_000;

    /** Read by the hooks: cheap check before anything else. */
    public static volatile boolean recording;
    /** While the rewind writes the world back: its own changes are not recorded. */
    static boolean rewinding;

    static final Map<ResourceKey<Level>, LevelLog> LOGS = new HashMap<>();
    static final ArrayDeque<Moment> MOMENTS = new ArrayDeque<>();
    /** The chunks loaded on the server, per dimension (kept always: needed to start recording). */
    private static final Map<ResourceKey<Level>, Set<LevelChunk>> LOADED = new HashMap<>();
    static long startedAt;
    /** How long taking the last moment took (shown by the status command). */
    private static long lastMomentNanos;

    /** A moment: entities, players and time of day of every dimension at {@code time}. */
    record Moment(long time, Map<ResourceKey<Level>, LevelMoment> levels, Map<UUID, CompoundTag> players) {
    }

    /** Of one dimension: its time of day, the entities, and the chunks whose entities were taken. */
    record LevelMoment(long dayTime, List<CompoundTag> entities, LongOpenHashSet chunks) {
    }

    private RepriseRecorder() {
    }

    // ---- hooks ----------------------------------------------------------------

    /** Before a block in a loaded chunk changes (see LevelChunkMixin). */
    public static void blockChanging(LevelChunk chunk, BlockPos pos, BlockState next) {
        if (rewinding || !(chunk.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return;
        }
        final BlockState old = chunk.getBlockState(pos);
        if (old == next || old.getBlock() == next.getBlock() && old.getBlock() instanceof LeavesBlock) {
            return; // nothing, or only a leaf's distance to its log (worked out again by the leaves themselves)
        }
        final LevelLog log = log(level);
        final long key = pos.asLong(), now = level.getGameTime();
        // block entity contents changed since they were last written down: those first
        final CompoundTag known = log.known.remove(key);
        CompoundTag blockEntity = null;
        final BlockEntity be = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        if (be != null) {
            blockEntity = be.saveWithFullMetadata(level.registryAccess());
            if (known != null && !known.equals(blockEntity)) {
                log.changes.add(new LevelLog.Change(now, key, null, known));
            }
            log.dirty.remove(key);
            if (old.getBlock() == next.getBlock()) {
                log.known.put(key, blockEntity); // the same block, it keeps its block entity (a furnace lighting up)
            }
        }
        final long second = now / 20;
        if (log.lastSecond.get(key) == second && blockEntity == null) {
            return; // already written down this second: the oldest state of the second counts
        }
        log.lastSecond.put(key, second);
        log.changes.add(new LevelLog.Change(now, key, old, blockEntity));
    }

    /** A block entity said it changed (see BlockEntityMixin): written down at the next second. */
    public static void blockEntityChanged(BlockEntity be) {
        if (!rewinding && be.getLevel() instanceof ServerLevel level && level.getServer().isSameThread()) {
            log(level).dirty.add(be.getBlockPos().asLong());
        }
    }

    // ---- loaded chunks ----------------------------------------------------------------

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            LOADED.computeIfAbsent(level.dimension(), k -> new HashSet<>()).add(chunk);
            if (recording) {
                baseline(level, chunk);
            }
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            final Set<LevelChunk> loaded = LOADED.get(level.dimension());
            if (loaded != null) {
                loaded.remove(chunk);
            }
            final LevelLog log = LOGS.get(level.dimension());
            if (log != null) {
                for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                    log.known.remove(pos.asLong());
                    log.dirty.remove(pos.asLong());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        stop();
        LOADED.clear();
    }

    /** Keeps what the block entities of a chunk hold now: the "before" of their next change. */
    private static void baseline(ServerLevel level, LevelChunk chunk) {
        final LevelLog log = log(level);
        for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
            log.known.put(e.getKey().asLong(), e.getValue().saveWithFullMetadata(level.registryAccess()));
        }
    }

    // ---- every tick ----------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        final MinecraftServer server = event.getServer();
        final long now = server.overworld().getGameTime();
        if (now % 20 == 0) {
            final boolean carried = server.getPlayerList().getPlayers().stream().anyMatch(RepriseRecorder::carries);
            window = server.getPlayerList().getPlayers().stream().mapToInt(RepriseRecorder::reach).max().orElse(WINDOW);
            if (carried && !recording) {
                start(server);
            } else if (!carried && recording) {
                stop();
            }
        }
        if (!recording) {
            return;
        }
        if (now % 20 == 0) {
            for (ServerLevel level : server.getAllLevels()) {
                flushDirty(level, now);
            }
        }
        if (now % MOMENT_EVERY == 0) {
            final long t = System.nanoTime();
            MOMENTS.add(moment(server, now));
            lastMomentNanos = System.nanoTime() - t;
        }
        // forget what has fallen out of the window: keep the oldest moment inside it and what follows
        while (MOMENTS.size() > 1) {
            final java.util.Iterator<Moment> it = MOMENTS.iterator();
            it.next();
            if (it.next().time() > now - window) {
                break; // the second oldest is still inside: the oldest is the one to go back to
            }
            MOMENTS.removeFirst();
        }
        if (!MOMENTS.isEmpty()) {
            final long oldest = MOMENTS.peekFirst().time();
            int total = 0;
            for (LevelLog log : LOGS.values()) {
                log.forgetBefore(oldest);
                total += log.changes.size();
            }
            if (total > MAX_CHANGES && MOMENTS.size() > 1) {
                MOMENTS.removeFirst(); // too much changes: reach less far back
            }
        }
    }

    /** Whether the player has a Reprise anywhere in their inventory. */
    /** How far back the Reprises a player carries reach, in ticks (0 if none). */
    static int reach(ServerPlayer player) {
        int reach = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            final net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof RepriseItem) {
                reach = Math.max(reach, RepriseItem.reachTicks(stack));
            }
        }
        return reach;
    }

    static boolean carries(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).getItem() instanceof RepriseItem) {
                return true;
            }
        }
        return false;
    }

    private static void start(MinecraftServer server) {
        clear();
        recording = true;
        startedAt = server.overworld().getGameTime();
        for (ServerLevel level : server.getAllLevels()) {
            for (LevelChunk chunk : LOADED.getOrDefault(level.dimension(), Set.of())) {
                baseline(level, chunk);
            }
        }
        MOMENTS.add(moment(server, startedAt));
        AbyssalDepths.LOGGER.info("reprise: recording");
    }

    static void stop() {
        if (recording) {
            AbyssalDepths.LOGGER.info("reprise: stopped recording");
        }
        recording = false;
        clear();
    }

    static void clear() {
        LOGS.values().forEach(LevelLog::clear);
        LOGS.clear();
        MOMENTS.clear();
    }

    /** Writes down the block entities that changed: what they held before, and keeps what they hold now. */
    private static void flushDirty(ServerLevel level, long now) {
        final LevelLog log = LOGS.get(level.dimension());
        if (log == null || log.dirty.isEmpty()) {
            return;
        }
        for (long key : log.dirty) {
            final BlockEntity be = level.getBlockEntity(BlockPos.of(key));
            if (be == null) {
                continue;
            }
            final CompoundTag current = be.saveWithFullMetadata(level.registryAccess());
            final CompoundTag before = log.known.put(key, current);
            if (before != null && !before.equals(current)) {
                log.changes.add(new LevelLog.Change(now, key, null, before));
            }
        }
        log.dirty.clear();
    }

    private static LevelLog log(ServerLevel level) {
        return LOGS.computeIfAbsent(level.dimension(), k -> new LevelLog());
    }

    // ---- moments ----------------------------------------------------------------

    private static Moment moment(MinecraftServer server, long now) {
        final Map<ResourceKey<Level>, LevelMoment> levels = new HashMap<>();
        final int distance = server.getPlayerList().getSimulationDistance();
        for (ServerLevel level : server.getAllLevels()) {
            // the area whose creatures move: around the players, as far as the simulation reaches
            final LongOpenHashSet chunks = new LongOpenHashSet();
            for (ServerPlayer player : level.players()) {
                final ChunkPos at = player.chunkPosition();
                for (int dz = -distance; dz <= distance; dz++) {
                    for (int dx = -distance; dx <= distance; dx++) {
                        chunks.add(ChunkPos.asLong(at.x + dx, at.z + dz));
                    }
                }
            }
            final List<CompoundTag> entities = new ArrayList<>();
            if (!chunks.isEmpty()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof ServerPlayer || entity.isPassenger() || entity.isRemoved()
                            || !chunks.contains(ChunkPos.asLong(SectionPos.blockToSectionCoord(entity.getBlockX()), SectionPos.blockToSectionCoord(entity.getBlockZ())))) {
                        continue;
                    }
                    final CompoundTag tag = new CompoundTag();
                    if (entity.save(tag)) { // with its passengers; false for entities that are never saved
                        entities.add(tag);
                    }
                }
            }
            levels.put(level.dimension(), new LevelMoment(level.getDayTime(), entities, chunks));
        }
        final Map<UUID, CompoundTag> players = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            players.put(player.getUUID(), playerState(player));
        }
        return new Moment(now, levels, players);
    }

    /** What of a player goes back: where they are, health, food, experience, inventory, effects. */
    private static CompoundTag playerState(ServerPlayer player) {
        final CompoundTag tag = new CompoundTag();
        tag.putString("dim", player.level().dimension().location().toString());
        tag.putDouble("x", player.getX());
        tag.putDouble("y", player.getY());
        tag.putDouble("z", player.getZ());
        tag.putFloat("yaw", player.getYRot());
        tag.putFloat("pitch", player.getXRot());
        tag.putFloat("health", player.getHealth());
        tag.putFloat("absorption", player.getAbsorptionAmount());
        final CompoundTag food = new CompoundTag();
        player.getFoodData().addAdditionalSaveData(food);
        tag.put("food", food);
        tag.putInt("xpLevel", player.experienceLevel);
        tag.putFloat("xpProgress", player.experienceProgress);
        tag.putInt("xpTotal", player.totalExperience);
        tag.put("inventory", player.getInventory().save(new ListTag()));
        final ListTag effects = new ListTag();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            effects.add(effect.save());
        }
        tag.put("effects", effects);
        tag.putInt("fire", player.getRemainingFireTicks());
        tag.putInt("air", player.getAirSupply());
        return tag;
    }

    /** For the status command: how far back it reaches, and how much is kept. */
    public static String status(MinecraftServer server) {
        if (!recording) {
            return "Reprise is not recording (nobody carries one)";
        }
        int changes = 0, known = 0;
        for (LevelLog log : LOGS.values()) {
            changes += log.changes.size();
            known += log.known.size();
        }
        int entities = 0;
        if (!MOMENTS.isEmpty()) {
            for (LevelMoment level : MOMENTS.peekFirst().levels().values()) {
                entities += level.entities().size();
            }
        }
        final long reach = MOMENTS.isEmpty() ? 0 : server.overworld().getGameTime() - MOMENTS.peekFirst().time();
        // which blocks change the most (to see what fills the log)
        final Map<String, Integer> byBlock = new HashMap<>();
        for (LevelLog log : LOGS.values()) {
            for (LevelLog.Change change : log.changes) {
                if (change.state() != null) {
                    byBlock.merge(change.state().getBlock().getDescriptionId().replace("block.minecraft.", ""), 1, Integer::sum);
                }
            }
        }
        final String top = byBlock.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(4)
                .map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
        return String.format("Reprise reaches %d s back: %d block changes (most: %s), %d block entities known, %d moments (%d entities in the oldest, last took %.1f ms)",
                reach / 20, changes, top, known, MOMENTS.size(), entities, lastMomentNanos / 1e6);
    }
}
