package redero.abyssaldepths.artifact;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.entity.AbsentBody;
import redero.abyssaldepths.network.EffectPayload;

/**
 * The Inner Self: one dimension holding a room for every player who has used Inner Haven. A room is a
 * 10 x 10 x 10 space inside a 3 block thick shell of haven wall, built the first time its player comes;
 * rooms lie 1024 blocks apart on a row, so no one ever sees another's.
 * <p>
 * Travelling: the player's screen fades out first ({@link #FADE_TICKS}, hiding the loading screen), then
 * they are moved; the client fades in again on arrival. Going in, the body stays behind ({@link AbsentBody})
 * and the spot is remembered; going out brings them back to that spot (the body vanishes once its owner
 * is back in its world).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class InnerSelf {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "inner_self"));
    /** How long the screen takes to fade out, and how long it then stays white before the move. */
    public static final int FADE_TICKS = 40, WHITE_TICKS = 10;
    private static final int ROOM = 10, WALL = 3, SPACING = 1024, FLOOR_Y = 64;

    /** Players waiting for their move, with the game time it happens. */
    private static final Map<UUID, Long> PENDING = new HashMap<>();

    private InnerSelf() {
    }

    public static boolean travelling(net.minecraft.world.entity.player.Player player) {
        return PENDING.containsKey(player.getUUID());
    }

    /** Starts the crossing: the screen fades out, the move follows. In or out depends on where they are. */
    public static void travel(ServerPlayer player) {
        PENDING.put(player.getUUID(), player.serverLevel().getGameTime() + FADE_TICKS + WHITE_TICKS);
        PacketDistributor.sendToPlayer(player, new EffectPayload("haven_out", FADE_TICKS));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        final MinecraftServer server = event.getServer();
        final long now = server.overworld().getGameTime();
        for (Iterator<Map.Entry<UUID, Long>> it = PENDING.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<UUID, Long> entry = it.next();
            if (entry.getValue() > now) {
                continue;
            }
            it.remove();
            final ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.isAlive()) {
                move(server, player);
            }
        }
    }

    private static void move(MinecraftServer server, ServerPlayer player) {
        final Rooms rooms = Rooms.get(server);
        if (player.level().dimension() == DIMENSION) {
            // back to where the body waits (or the world spawn if that is gone)
            final CompoundTag back = rooms.returns.get(player.getUUID());
            ServerLevel level = server.overworld();
            double x = level.getSharedSpawnPos().getX() + 0.5, y = level.getSharedSpawnPos().getY(), z = level.getSharedSpawnPos().getZ() + 0.5;
            float yaw = player.getYRot(), pitch = player.getXRot();
            if (back != null) {
                final ServerLevel stored = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(back.getString("dim"))));
                if (stored != null) {
                    level = stored;
                    x = back.getDouble("x");
                    y = back.getDouble("y");
                    z = back.getDouble("z");
                    yaw = back.getFloat("yaw");
                    pitch = back.getFloat("pitch");
                }
            }
            player.teleportTo(level, x, y, z, yaw, pitch);
        } else {
            final ServerLevel inner = server.getLevel(DIMENSION);
            if (inner == null) {
                AbyssalDepths.LOGGER.error("the inner_self dimension is missing");
                return;
            }
            // the body stays, the way back is remembered
            final CompoundTag back = new CompoundTag();
            back.putString("dim", player.level().dimension().location().toString());
            back.putDouble("x", player.getX());
            back.putDouble("y", player.getY());
            back.putDouble("z", player.getZ());
            back.putFloat("yaw", player.getYRot());
            back.putFloat("pitch", player.getXRot());
            rooms.returns.put(player.getUUID(), back);
            rooms.setDirty();
            AbsentBody.leave(player);

            final int index = rooms.room(player.getUUID());
            final BlockPos origin = new BlockPos(index * SPACING, FLOOR_Y - WALL, 0);
            if (rooms.built.add(index)) {
                build(inner, origin);
                rooms.setDirty();
            }
            final double half = (ROOM + 2 * WALL) / 2.0;
            player.teleportTo(inner, origin.getX() + half, origin.getY() + WALL, origin.getZ() + half, player.getYRot(), 0.0F);
        }
        PacketDistributor.sendToPlayer(player, new EffectPayload("haven_in", 40));
    }

    /** The empty room: a 3 thick shell of haven wall around a 10 x 10 x 10 space. */
    private static void build(ServerLevel level, BlockPos origin) {
        final BlockState wall = AbyssalDepths.HAVEN_WALL.get().defaultBlockState();
        final int size = ROOM + 2 * WALL;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy < size; dy++) {
            for (int dz = 0; dz < size; dz++) {
                for (int dx = 0; dx < size; dx++) {
                    final boolean inside = dx >= WALL && dx < WALL + ROOM && dy >= WALL && dy < WALL + ROOM && dz >= WALL && dz < WALL + ROOM;
                    if (!inside) {
                        level.setBlock(pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz), wall, 2);
                    }
                }
            }
        }
    }

    /** Which room belongs to whom, which are built, and every player's way back. Saved with the world. */
    static final class Rooms extends SavedData {
        final Map<UUID, Integer> owners = new HashMap<>();
        final java.util.Set<Integer> built = new java.util.HashSet<>();
        final Map<UUID, CompoundTag> returns = new HashMap<>();

        static Rooms get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(Rooms::new, Rooms::load, null), "abyssaldepths_inner_self");
        }

        int room(UUID player) {
            return owners.computeIfAbsent(player, k -> {
                setDirty();
                return owners.size();
            });
        }

        static Rooms load(CompoundTag tag, HolderLookup.Provider registries) {
            final Rooms rooms = new Rooms();
            final CompoundTag owners = tag.getCompound("owners");
            owners.getAllKeys().forEach(k -> rooms.owners.put(UUID.fromString(k), owners.getInt(k)));
            for (int index : tag.getIntArray("built")) {
                rooms.built.add(index);
            }
            final CompoundTag returns = tag.getCompound("returns");
            returns.getAllKeys().forEach(k -> rooms.returns.put(UUID.fromString(k), returns.getCompound(k)));
            return rooms;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            final CompoundTag owners = new CompoundTag();
            this.owners.forEach((k, v) -> owners.putInt(k.toString(), v));
            tag.put("owners", owners);
            tag.putIntArray("built", built.stream().mapToInt(Integer::intValue).toArray());
            final CompoundTag returns = new CompoundTag();
            this.returns.forEach((k, v) -> returns.put(k.toString(), v));
            tag.put("returns", returns);
            return tag;
        }
    }
}
