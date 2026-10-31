package redero.abyssaldepths.artifact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * The blocks Firmament holds under its users' feet. Each side (the server, every client) works them out
 * by itself from the players it knows: while a player holds Firmament ({@link FirmamentItem}, the use is
 * synced to everyone), the free block under their feet becomes one of theirs, and the ones they left stay
 * for a trail of a second or more. Only the user stands on them, on their own client
 * ({@link #addShapes}, the movement is the client's); the server knows them so it does not take the user
 * for flying ({@link #holdsUp}); the other players see their top outline ({@code client.FirmamentView}).
 * Let go, the blocks give way at once and only fade out.
 */
public final class Firmament {
    /** A player's blocks: packed position to the game tick it goes. */
    public static final class Platform {
        final Level level;
        final Long2LongOpenHashMap blocks = new Long2LongOpenHashMap();
        /** Firmament still held (the blocks bear the user). */
        boolean held;
        /** Ticks a block left behind stays; landing softly after letting go (by the mastery). */
        int trail = 20;
        boolean softLanding;

        Platform(Level level) {
            this.level = level;
        }

        public boolean held() {
            return held;
        }

        public int trail() {
            return trail;
        }

        public Long2LongMap blocks() {
            return blocks;
        }
    }

    /** Per side: entity id of the user to their blocks. */
    private static final Map<Integer, Platform> CLIENT = new HashMap<>(), SERVER = new HashMap<>();
    /** Server: players who let go with a soft landing to come (the next fall does no damage). */
    private static final Set<UUID> SOFT = new HashSet<>();

    private Firmament() {
    }

    private static Map<Integer, Platform> side(Level level) {
        return level.isClientSide ? CLIENT : SERVER;
    }

    /** The users' blocks on this client (for drawing them). */
    public static Map<Integer, Platform> clientPlatforms() {
        return CLIENT;
    }

    /** The block a player stands on (or would): the one just under their feet. */
    static BlockPos under(Entity entity) {
        return BlockPos.containing(entity.getX(), Mth.floor(entity.getY() + 1.0E-3) - 1, entity.getZ());
    }

    private static void tick(Player player) {
        final Level level = player.level();
        final Map<Integer, Platform> side = side(level);
        Platform platform = side.get(player.getId());
        final boolean using = player.isUsingItem() && player.getUseItem().getItem() instanceof FirmamentItem && !player.isSpectator();
        if (using) {
            if (platform == null || platform.level != level) {
                platform = new Platform(level);
                side.put(player.getId(), platform);
            }
            final int m = ArtifactItem.milestone(player.getUseItem());
            platform.trail = FirmamentItem.TRAIL[m];
            platform.softLanding = m >= FirmamentItem.SOFT_LANDING;
            platform.held = true;
            if (!level.isClientSide) {
                SOFT.remove(player.getUUID());
            }
            final BlockPos under = under(player);
            if (!player.getAbilities().flying && level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
                platform.blocks.put(under.asLong(), level.getGameTime() + platform.trail);
            }
        } else if (platform != null && platform.held) {
            platform.held = false; // let go: the blocks give way
            if (!level.isClientSide && platform.softLanding) {
                SOFT.add(player.getUUID());
            }
        }
    }

    /** Blocks run out; users gone and their blocks faded are forgotten. */
    private static void expire(Level level) {
        final Map<Integer, Platform> side = side(level);
        if (side.isEmpty()) {
            return;
        }
        final long now = level.getGameTime();
        side.entrySet().removeIf(entry -> {
            final Platform platform = entry.getValue();
            if (platform.level != level) {
                return level.isClientSide; // a client has one world at a time: the others' are gone
            }
            platform.blocks.values().removeIf(until -> until < now);
            return platform.blocks.isEmpty() && (!platform.held || level.getEntity(entry.getKey()) == null);
        });
    }

    /**
     * The movement's collisions, with the user's own blocks added on their own client while they hold
     * Firmament (not the ones they are already in: they would push them out).
     */
    public static List<VoxelShape> addShapes(Entity entity, AABB box, List<VoxelShape> shapes) {
        if (!entity.level().isClientSide || !(entity instanceof Player) || !entity.isControlledByLocalInstance() || CLIENT.isEmpty()) {
            return shapes;
        }
        final Platform platform = CLIENT.get(entity.getId());
        if (platform == null || !platform.held || platform.level != entity.level()) {
            return shapes;
        }
        List<VoxelShape> all = shapes;
        final AABB now = entity.getBoundingBox().deflate(1.0E-4);
        for (long packed : platform.blocks.keySet()) {
            final AABB block = new AABB(BlockPos.of(packed));
            if (block.intersects(box) && !block.intersects(now)) {
                if (all == shapes) {
                    all = new ArrayList<>(shapes);
                }
                all.add(Shapes.create(block));
            }
        }
        return all;
    }

    /** Server: true if the player stands on (or just above) blocks of theirs, so they are not flying. */
    public static boolean holdsUp(Entity entity) {
        if (SERVER.isEmpty()) {
            return false;
        }
        final Platform platform = SERVER.get(entity.getId());
        if (platform == null || !platform.held) {
            return false;
        }
        final AABB near = entity.getBoundingBox().inflate(0.0625).expandTowards(0.0, -0.55, 0.0);
        for (long packed : platform.blocks.keySet()) {
            if (new AABB(BlockPos.of(packed)).intersects(near)) {
                return true;
            }
        }
        return false;
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID)
    public static final class Events {
        private Events() {
        }

        @SubscribeEvent
        public static void onPlayerTick(PlayerTickEvent.Pre event) {
            tick(event.getEntity());
        }

        @SubscribeEvent
        public static void onLevelTick(LevelTickEvent.Post event) {
            expire(event.getLevel());
        }

        /** The soft landing after letting go: the next fall does no harm. */
        @SubscribeEvent
        public static void onFall(LivingFallEvent event) {
            if (!SOFT.isEmpty() && !event.getEntity().level().isClientSide && SOFT.remove(event.getEntity().getUUID())) {
                event.setCanceled(true);
            }
        }
    }
}
