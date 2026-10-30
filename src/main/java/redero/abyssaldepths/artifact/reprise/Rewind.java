package redero.abyssaldepths.artifact.reprise;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.RepriseItem;

/**
 * Puts the world back to the oldest moment Reprise remembers (up to 120 seconds ago), in one go:
 * <ol>
 * <li>blocks: every recorded change after the moment is undone, newest first, without neighbour updates
 * (so nothing breaks or drops on the way); fluids get a tick to flow again;</li>
 * <li>block entities: their contents as they were;</li>
 * <li>entities in the areas around the players: everything there now goes, everything that was there
 * comes back as it was;</li>
 * <li>players: position, health, food, experience, inventory, effects;</li>
 * <li>the time of day of every dimension.</li>
 * </ol>
 * Reprise itself is not rewound: whoever carries one keeps it (the spent charge stays spent), and copies
 * that the rewind would bring back elsewhere (in a chest, on the ground) are taken away.
 */
public final class Rewind {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private Rewind() {
    }

    /** Rewinds; returns how many seconds back it went, or -1 if there is nothing to go back to. */
    public static int run(MinecraftServer server) {
        if (!RepriseRecorder.recording || RepriseRecorder.MOMENTS.isEmpty()) {
            return -1;
        }
        final RepriseRecorder.Moment moment = RepriseRecorder.MOMENTS.peekFirst();
        final long now = server.overworld().getGameTime();
        final long started = System.nanoTime();
        int blocks = 0, entities = 0;
        // Reprise stays with whoever carries it now
        final Map<UUID, List<ItemStack>> kept = new java.util.HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            kept.put(player.getUUID(), takeReprises(player));
        }
        RepriseRecorder.rewinding = true;
        try {
            for (ServerLevel level : server.getAllLevels()) {
                final LevelLog log = RepriseRecorder.LOGS.get(level.dimension());
                if (log != null) {
                    blocks += undoBlocks(level, log, moment.time());
                }
                final RepriseRecorder.LevelMoment then = moment.levels().get(level.dimension());
                if (then != null) {
                    entities += restoreEntities(level, then);
                    level.setDayTime(then.dayTime());
                }
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                final CompoundTag state = moment.players().get(player.getUUID());
                if (state != null) {
                    restorePlayer(server, player, state);
                }
                // back into their inventory: the Reprise they carried a moment ago, as it is now
                for (ItemStack stack : kept.getOrDefault(player.getUUID(), List.of())) {
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false);
                    }
                }
            }
        } finally {
            RepriseRecorder.rewinding = false;
        }
        AbyssalDepths.LOGGER.info("reprise: rewound {} s ({} blocks, {} entities) in {} ms", (now - moment.time()) / 20, blocks, entities,
                (System.nanoTime() - started) / 1_000_000);
        final int seconds = (int) ((now - moment.time()) / 20);
        // the world as it is now is the new starting point
        RepriseRecorder.clear();
        RepriseRecorder.recording = false; // restarts within a second, with fresh baselines
        return seconds;
    }

    /** Undoes the block changes after {@code time}, newest first. */
    private static int undoBlocks(ServerLevel level, LevelLog log, long time) {
        int undone = 0;
        for (Iterator<LevelLog.Change> it = log.changes.descendingIterator(); it.hasNext();) {
            final LevelLog.Change change = it.next();
            if (change.time() <= time) {
                break;
            }
            final BlockPos pos = BlockPos.of(change.pos());
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (change.state() != null) {
                level.setBlock(pos, change.state(), FLAGS);
                final FluidState fluid = change.state().getFluidState();
                if (!fluid.isEmpty()) {
                    level.scheduleTick(pos, fluid.getType(), fluid.getType().getTickDelay(level));
                }
            }
            if (change.blockEntity() != null) {
                final BlockEntity be = level.getBlockEntity(pos);
                if (be != null) {
                    final Map<Integer, ItemStack> reprisesNow = reprisesIn(be);
                    be.loadWithComponents(change.blockEntity(), level.registryAccess());
                    keepReprises(be, reprisesNow);
                    be.setChanged();
                    final BlockState state = level.getBlockState(pos);
                    level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
                }
            }
            undone++;
        }
        return undone;
    }

    /** Replaces the entities of the moment's area with the ones it saw there. */
    private static int restoreEntities(ServerLevel level, RepriseRecorder.LevelMoment then) {
        final List<Entity> now = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            final boolean reprise = entity instanceof ItemEntity item && item.getItem().getItem() instanceof RepriseItem;
            if (!(entity instanceof ServerPlayer) && !reprise && !entity.isPassenger() && then.chunks().contains(chunkOf(entity))) {
                now.add(entity);
            }
        }
        now.forEach(entity -> entity.discard());
        int back = 0;
        for (CompoundTag tag : then.entities()) {
            final Entity entity = EntityType.loadEntityRecursive(tag, level, e -> e);
            if (entity == null || !level.isLoaded(entity.blockPosition())) {
                continue;
            }
            if (entity instanceof ItemEntity item && item.getItem().getItem() instanceof RepriseItem) {
                continue; // the rewind does not bring a Reprise back
            }
            if (level.tryAddFreshEntityWithPassengers(entity)) {
                back++;
            }
        }
        return back;
    }

    private static long chunkOf(Entity entity) {
        return ChunkPos.asLong(SectionPos.blockToSectionCoord(entity.getBlockX()), SectionPos.blockToSectionCoord(entity.getBlockZ()));
    }

    private static void restorePlayer(MinecraftServer server, ServerPlayer player, CompoundTag state) {
        player.stopUsingItem();
        player.closeContainer();
        player.getInventory().load(state.getList("inventory", Tag.TAG_COMPOUND));
        takeReprises(player); // only the ones carried now come back (added after this)
        player.setHealth(state.getFloat("health"));
        player.setAbsorptionAmount(state.getFloat("absorption"));
        player.getFoodData().readAdditionalSaveData(state.getCompound("food"));
        player.experienceLevel = state.getInt("xpLevel");
        player.experienceProgress = state.getFloat("xpProgress");
        player.totalExperience = state.getInt("xpTotal");
        player.removeAllEffects();
        for (Tag effect : state.getList("effects", Tag.TAG_COMPOUND)) {
            final MobEffectInstance instance = MobEffectInstance.load((CompoundTag) effect);
            if (instance != null) {
                player.addEffect(instance);
            }
        }
        player.setRemainingFireTicks(state.getInt("fire"));
        player.setAirSupply(state.getInt("air"));
        player.fallDistance = 0.0F;
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        final ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(state.getString("dim"))));
        if (level != null) {
            player.teleportTo(level, state.getDouble("x"), state.getDouble("y"), state.getDouble("z"), state.getFloat("yaw"), state.getFloat("pitch"));
        }
        player.resetSentInfo(); // health, food and experience are sent again
        player.inventoryMenu.broadcastFullState();
    }

    /** Takes every Reprise out of the player's inventory and returns them. */
    private static List<ItemStack> takeReprises(ServerPlayer player) {
        final List<ItemStack> taken = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            final ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof RepriseItem) {
                taken.add(stack.copy());
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
        return taken;
    }

    /** The Reprises a container holds now, by slot. */
    private static Map<Integer, ItemStack> reprisesIn(BlockEntity be) {
        final Map<Integer, ItemStack> found = new java.util.HashMap<>();
        if (be instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (container.getItem(i).getItem() instanceof RepriseItem) {
                    found.put(i, container.getItem(i).copy());
                }
            }
        }
        return found;
    }

    /** After its contents went back: no Reprise from back then, the ones it holds now stay. */
    private static void keepReprises(BlockEntity be, Map<Integer, ItemStack> now) {
        if (be instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (container.getItem(i).getItem() instanceof RepriseItem) {
                    container.setItem(i, ItemStack.EMPTY);
                }
            }
            now.forEach(container::setItem);
        }
    }
}
