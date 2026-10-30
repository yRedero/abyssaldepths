package redero.abyssaldepths.artifact;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
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
 * The Inner Self: one dimension holding a place for every player who has used Inner Haven, built the first
 * time its player comes; places lie 1024 blocks apart on a row, so no one ever sees another's. Every player
 * chooses its form ({@link Form}, command {@code /abyssaldepths innerhaven form}): a room (a 10 x 10 x 10
 * space inside a 3 block thick shell of haven wall) or islands floating in the white (a first draft: a
 * central island with a meditation mat at its heart, smaller ones around, stepping stones to some; falling
 * off brings them back to the mat).
 * <p>
 * Travelling: the player's screen fades out first ({@link #FADE_TICKS}, hiding the loading screen), then
 * they are moved; the client fades in again on arrival. Going in, the body stays behind ({@link AbsentBody})
 * and the spot is remembered, and their effects are put aside (none in the inner self); going out brings
 * them back to that spot, turned the way the body is, a little above it and sinking down softly
 * ({@link #SOFT_TICKS}), with their effects again (the body vanishes once its owner is back in its world).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class InnerSelf {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "inner_self"));
    /** How long the screen takes to fade out, and how long it then stays white before the move. */
    public static final int FADE_TICKS = 40, WHITE_TICKS = 10;
    private static final int ROOM = 10, WALL = 3, SPACING = 1024, FLOOR_Y = 64;

    /** What a player's inner self is. */
    public enum Form { ROOM, ISLANDS }

    /** Below this height under the floor, someone who fell off the islands is brought back. */
    private static final int FALL_LIMIT = 40;

    /** Players waiting for their move, with the game time it happens. */
    private static final Map<UUID, Long> PENDING = new HashMap<>();
    /** Back from the inner self: how long they sink down softly, from how high above the body's spot. */
    private static final int SOFT_TICKS = 10;
    private static final double SOFT_HEIGHT = 0.35;
    private static final ResourceLocation SOFT = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "haven_soft_fall");
    /** Players sinking down softly, with the game time it ends. */
    private static final Map<UUID, Long> SINKING = new HashMap<>();

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
        final MinecraftServer server = event.getServer();
        final long now = server.overworld().getGameTime();
        SINKING.entrySet().removeIf(entry -> {
            if (entry.getValue() > now) {
                return false;
            }
            final ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            final AttributeInstance gravity = player == null ? null : player.getAttribute(Attributes.GRAVITY);
            if (gravity != null) {
                gravity.removeModifier(SOFT);
            }
            return true;
        });
        if (now % 10 == 0) {
            rescueFallen(server);
        }
        if (PENDING.isEmpty()) {
            return;
        }
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
                    yaw = back.getFloat("yaw"); // the way the body faces, looking straight ahead like it
                    pitch = 0.0F;
                }
            }
            // taking the body's place as it is seen: turned like it (head and body), a little above it
            player.teleportTo(level, x, y + SOFT_HEIGHT, z, yaw, pitch);
            player.setYHeadRot(yaw);
            player.setYBodyRot(yaw);
            final AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);
            if (gravity != null) { // sinking down to the ground over half a second, not dropping
                gravity.addOrUpdateTransientModifier(new AttributeModifier(SOFT, -0.9, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                player.setDeltaMovement(Vec3.ZERO);
                SINKING.put(player.getUUID(), server.overworld().getGameTime() + SOFT_TICKS);
            }
            // the effects put aside going in come back (what they caught inside is dropped)
            player.removeAllEffects();
            if (back != null) {
                final ListTag effects = back.getList("effects", Tag.TAG_COMPOUND);
                for (int i = 0; i < effects.size(); i++) {
                    final MobEffectInstance effect = MobEffectInstance.load(effects.getCompound(i));
                    if (effect != null) {
                        player.addEffect(effect);
                    }
                }
            }
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
            back.putFloat("yaw", player.yBodyRot);
            back.putFloat("pitch", player.getXRot());
            // effects are put aside: none of them reach into the inner self
            final ListTag effects = new ListTag();
            for (MobEffectInstance effect : player.getActiveEffects()) {
                effects.add(effect.save());
            }
            back.put("effects", effects);
            player.removeAllEffects();
            rooms.returns.put(player.getUUID(), back);
            rooms.setDirty();
            AbsentBody.leave(player);

            final int index = rooms.room(player.getUUID());
            final Form form = rooms.form(player.getUUID());
            if (rooms.built.add(index)) {
                build(inner, index, form);
                rooms.setDirty();
            }
            final Vec3 arrival = arrival(index, form);
            player.teleportTo(inner, arrival.x, arrival.y, arrival.z, player.getYRot(), 0.0F);
        }
        PacketDistributor.sendToPlayer(player, new EffectPayload("haven_in", 40));
    }

    /** The corner of a player's place (the room's outer corner; the islands lie around its middle). */
    private static BlockPos origin(int index) {
        return new BlockPos(index * SPACING, FLOOR_Y - WALL, 0);
    }

    /** Where a player arrives in their inner self: in the room's middle, or beside the mat. */
    private static Vec3 arrival(int index, Form form) {
        final BlockPos origin = origin(index);
        final double half = (ROOM + 2 * WALL) / 2.0;
        return new Vec3(origin.getX() + half, FLOOR_Y, origin.getZ() + half + (form == Form.ISLANDS ? 2.0 : 0.0));
    }

    /** Builds the place of the form (anything there before is cleared first). */
    private static void build(ServerLevel level, int index, Form form) {
        clear(level, origin(index));
        if (form == Form.ISLANDS) {
            InnerIslands.build(level, origin(index).offset(ROOM / 2 + WALL, WALL, ROOM / 2 + WALL), index);
        } else {
            build(level, origin(index));
        }
    }

    /** Empties the space a place can take (wide enough for the islands). */
    private static void clear(ServerLevel level, BlockPos origin) {
        final BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int cx = origin.getX() + ROOM / 2 + WALL, cz = origin.getZ() + ROOM / 2 + WALL;
        for (int y = FLOOR_Y - 30; y < FLOOR_Y + 30; y++) {
            for (int z = cz - InnerIslands.REACH; z <= cz + InnerIslands.REACH; z++) {
                for (int x = cx - InnerIslands.REACH; x <= cx + InnerIslands.REACH; x++) {
                    if (!level.getBlockState(pos.set(x, y, z)).isAir()) {
                        level.setBlock(pos, air, 2 | 16);
                    }
                }
            }
        }
    }

    /**
     * Sets the form of a player's inner self and rebuilds it. If they are in it, they are put back at
     * its arrival point.
     */
    public static void setForm(ServerPlayer player, Form form) {
        final MinecraftServer server = player.getServer();
        final ServerLevel inner = server.getLevel(DIMENSION);
        if (inner == null) {
            return;
        }
        final Rooms rooms = Rooms.get(server);
        final int index = rooms.room(player.getUUID());
        rooms.forms.put(player.getUUID(), form);
        rooms.built.add(index);
        rooms.setDirty();
        build(inner, index, form);
        if (player.level() == inner) {
            final Vec3 arrival = arrival(index, form);
            player.teleportTo(inner, arrival.x, arrival.y, arrival.z, player.getYRot(), 0.0F);
        }
    }

    /** Who fell off their islands comes back to where they arrive. */
    private static void rescueFallen(MinecraftServer server) {
        final ServerLevel inner = server.getLevel(DIMENSION);
        if (inner == null || inner.players().isEmpty()) {
            return;
        }
        final Rooms rooms = Rooms.get(server);
        for (ServerPlayer player : inner.players()) {
            if (player.getY() < FLOOR_Y - FALL_LIMIT) {
                final Vec3 arrival = arrival(rooms.room(player.getUUID()), rooms.form(player.getUUID()));
                player.teleportTo(inner, arrival.x, arrival.y, arrival.z, player.getYRot(), player.getXRot());
                player.setDeltaMovement(Vec3.ZERO);
                player.resetFallDistance();
            }
        }
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
        final Map<UUID, Form> forms = new HashMap<>();

        Form form(UUID player) {
            return forms.getOrDefault(player, Form.ROOM);
        }

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
            final CompoundTag forms = tag.getCompound("forms");
            forms.getAllKeys().forEach(k -> rooms.forms.put(UUID.fromString(k), Form.valueOf(forms.getString(k))));
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
            final CompoundTag forms = new CompoundTag();
            this.forms.forEach((k, v) -> forms.putString(k.toString(), v.name()));
            tag.put("forms", forms);
            return tag;
        }
    }
}
