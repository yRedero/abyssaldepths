package redero.abyssaldepths.artifact;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Parallax's slowed time: around every player with an active Parallax ({@link #RADIUS} blocks), everything
 * else runs at {@link #SPEED} of its normal pace.
 * <ul>
 * <li>Creatures, projectiles, items, falling blocks: they only get some of their ticks (every other one
 * at half speed), on the server and on the clients alike, so all about them slows down: movement, AI,
 * attacks, flight.</li>
 * <li>Other players move themselves (their client decides), so they get slowed attributes instead: half
 * the movement, attack and mining speed, a quarter of the gravity and half the jump, which makes jumps
 * and falls play out in slow motion too.</li>
 * </ul>
 * The holder is never slowed. Blocks (fluids, crops, redstone) keep their pace. The active players are
 * sent to the clients every second ({@code parallax/<entity id>}), which also draw their afterimages.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class ParallaxField {
    public static final double RADIUS = 32.0;
    /** Pace of everything in the field; later from the mastery. */
    public static final float SPEED = 0.5F;
    private static final ResourceLocation SLOW = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "parallax_slow");
    private static final Map<Holder<Attribute>, Double> PLAYER_SLOW = Map.of(
            Attributes.MOVEMENT_SPEED, SPEED - 1.0, Attributes.ATTACK_SPEED, SPEED - 1.0, Attributes.BLOCK_BREAK_SPEED, SPEED - 1.0,
            Attributes.GRAVITY, SPEED * SPEED - 1.0, Attributes.JUMP_STRENGTH, SPEED - 1.0);

    /** Server: holders seen this tick (by their Parallax ticking in their inventory), with the tick. */
    private static final Map<UUID, Long> ACTIVE = new HashMap<>();
    private static final Set<UUID> SLOWED_PLAYERS = new HashSet<>();
    /** Client: entity ids of active holders, with the client tick until which they count. */
    static final Int2LongOpenHashMap CLIENT = new Int2LongOpenHashMap();

    private ParallaxField() {
    }

    /** The player's Parallax is on (called every tick by the item while it is in their inventory). */
    static void mark(ServerPlayer player) {
        ACTIVE.put(player.getUUID(), player.serverLevel().getGameTime());
    }

    /** Client: a holder is active for the next {@code ticks} ticks (sent by the server every second). */
    public static void clientActive(int entityId, int ticks, long clientTick) {
        if (ticks <= 0) {
            CLIENT.remove(entityId);
        } else {
            CLIENT.put(entityId, clientTick + ticks);
        }
    }

    /** Client: forgets holders whose activity has run out. */
    public static void clientPrune(long clientTick) {
        CLIENT.values().removeIf(until -> until < clientTick);
    }

    /** Whether the entity with this id is an active holder, as far as this client knows. */
    public static boolean clientIsActive(int entityId, long clientTick) {
        return CLIENT.get(entityId) >= clientTick;
    }

    /** Whether an entity of the level stands in someone else's field. */
    private static boolean inField(Entity entity, Level level) {
        if (level.isClientSide) {
            final long now = level.getGameTime();
            for (Int2LongOpenHashMap.Entry e : CLIENT.int2LongEntrySet()) {
                if (e.getLongValue() >= now && e.getIntKey() != entity.getId()) {
                    final Entity holder = level.getEntity(e.getIntKey());
                    if (holder != null && holder.distanceToSqr(entity) < RADIUS * RADIUS) {
                        return true;
                    }
                }
            }
            return false;
        }
        for (UUID id : ACTIVE.keySet()) {
            if (!id.equals(entity.getUUID())) {
                final Player holder = level.getPlayerByUUID(id);
                if (holder != null && holder.distanceToSqr(entity) < RADIUS * RADIUS) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Skips ticks of everything (but players) in a field: at half speed every other one. */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        final Entity entity = event.getEntity();
        if (entity instanceof Player || ACTIVE.isEmpty() && CLIENT.isEmpty()) {
            return;
        }
        final long t = entity.level().getGameTime();
        final boolean ticksNow = Math.floor((t + 1) * SPEED) > Math.floor(t * SPEED);
        if (!ticksNow && inField(entity, entity.level())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        final MinecraftServer server = event.getServer();
        final long now = server.overworld().getGameTime();
        ACTIVE.values().removeIf(seen -> seen < now - 2); // switched off, empty, or no longer carried
        if (now % 10 == 0) {
            slowPlayers(server);
        }
        if (now % 20 == 0) {
            // tell the clients who is active (with a margin), so they slow the same things and draw afterimages
            for (UUID id : ACTIVE.keySet()) {
                final ServerPlayer holder = server.getPlayerList().getPlayer(id);
                if (holder != null) {
                    PacketDistributor.sendToPlayersInDimension(holder.serverLevel(), new EffectPayload("parallax/" + holder.getId(), 30));
                }
            }
        }
    }

    /** Players in someone else's field get the slowed attributes; those who left lose them. */
    private static void slowPlayers(MinecraftServer server) {
        final Set<UUID> now = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                if (inField(player, level)) {
                    now.add(player.getUUID());
                }
            }
        }
        for (Iterator<UUID> it = SLOWED_PLAYERS.iterator(); it.hasNext();) {
            final UUID id = it.next();
            if (!now.contains(id)) {
                final ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) {
                    PLAYER_SLOW.keySet().forEach(attribute -> {
                        final AttributeInstance instance = player.getAttribute(attribute);
                        if (instance != null) {
                            instance.removeModifier(SLOW);
                        }
                    });
                }
                it.remove();
            }
        }
        for (UUID id : now) {
            if (SLOWED_PLAYERS.add(id)) {
                final ServerPlayer player = server.getPlayerList().getPlayer(id);
                PLAYER_SLOW.forEach((attribute, amount) -> {
                    final AttributeInstance instance = player.getAttribute(attribute);
                    if (instance != null) {
                        instance.addOrUpdateTransientModifier(new AttributeModifier(SLOW, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                    }
                });
            }
        }
    }
}
