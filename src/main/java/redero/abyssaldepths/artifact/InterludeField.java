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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Interlude's slowed time: around every player with an active Interlude ({@link #RADIUS} blocks), everything
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
 * sent to the clients every second ({@code interlude/<entity id>}), which also draw their afterimages.
 * <p>
 * A field keeps to its side of the shadow ({@link Shades}): a shade's field slows only other shades, the
 * others' fields only what is not a shade. Two holders weaken each other: a holder is slowed only by as much
 * as the other's field is stronger than their own ({@link #strength}; for now all are equal, so they cancel).
 * Players slowed also turn their view slower (client); particles in a field slow down too (client config).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class InterludeField {
    public static final double RADIUS = 32.0;
    /** Pace of everything in the field; later from the mastery. */
    public static final float SPEED = 0.5F;
    private static final ResourceLocation SLOW = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "interlude_slow");
    /** What is slowed on players in a field. */
    private static final java.util.List<Holder<Attribute>> SLOWED = java.util.List.of(
            Attributes.MOVEMENT_SPEED, Attributes.ATTACK_SPEED, Attributes.BLOCK_BREAK_SPEED, Attributes.GRAVITY, Attributes.JUMP_STRENGTH);

    /** Server: holders seen this tick (by their Interlude ticking in their inventory), with the tick. */
    private static final Map<UUID, Long> ACTIVE = new HashMap<>();
    /** Players slowed, with their pace. */
    private static final Map<UUID, Float> SLOWED_PLAYERS = new HashMap<>();
    /** Client: a step being spread out per entity id: where it ends, and the part taken per tick. */
    private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<Vec3[]> SPREAD = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    /** Client: entity ids of active holders, with the client tick until which they count. */
    static final Int2LongOpenHashMap CLIENT = new Int2LongOpenHashMap();

    private InterludeField() {
    }

    /** The player's Interlude is on (called every tick by the item while it is in their inventory). */
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
        if (CLIENT.isEmpty()) {
            SPREAD.clear();
        }
    }

    /** Whether the entity with this id is an active holder, as far as this client knows. */
    public static boolean clientIsActive(int entityId, long clientTick) {
        return CLIENT.get(entityId) >= clientTick;
    }

    /**
     * How strongly a holder's field slows (0 .. 1: the share of the pace taken away). The same for all for
     * now; it will grow with the mastery.
     */
    static float strength(Player holder) {
        return 1.0F - SPEED;
    }

    /** Whether the player is an active holder (on the side of the level). */
    private static boolean isHolder(Entity entity, Level level) {
        return entity instanceof Player && (level.isClientSide ? clientIsActive(entity.getId(), level.getGameTime()) : ACTIVE.containsKey(entity.getUUID()));
    }

    /**
     * The pace an entity of the level runs at (1: untouched): the strongest field of another holder on its
     * side of the shadow, less the strength of its own field if it is a holder too.
     */
    private static float pace(Entity entity, Level level) {
        final boolean shade = Shades.isShade(entity);
        final float own = isHolder(entity, level) ? strength((Player) entity) : 0.0F;
        float strongest = 0.0F;
        if (level.isClientSide) {
            final long now = level.getGameTime();
            for (Int2LongOpenHashMap.Entry e : CLIENT.int2LongEntrySet()) {
                if (e.getLongValue() >= now && e.getIntKey() != entity.getId()
                        && level.getEntity(e.getIntKey()) instanceof Player holder && reaches(holder, entity, shade)) {
                    strongest = Math.max(strongest, strength(holder));
                }
            }
        } else {
            for (UUID id : ACTIVE.keySet()) {
                if (!id.equals(entity.getUUID()) && level.getPlayerByUUID(id) instanceof Player holder && reaches(holder, entity, shade)) {
                    strongest = Math.max(strongest, strength(holder));
                }
            }
        }
        return 1.0F - Math.max(0.0F, strongest - own);
    }

    private static boolean reaches(Player holder, Entity entity, boolean shade) {
        return Shades.isShade(holder) == shade && holder.distanceToSqr(entity) < RADIUS * RADIUS;
    }

    /** Whether an entity of the level stands in someone else's field. */
    private static boolean inField(Entity entity, Level level) {
        return pace(entity, level) < 1.0F;
    }

    /**
     * Client: the pace at a place for what belongs to no one (particles): the strongest field of a holder
     * outside the shadow reaching it, 1 if none.
     */
    public static float clientPaceAt(Level level, double x, double y, double z) {
        if (CLIENT.isEmpty()) {
            return 1.0F;
        }
        final long now = level.getGameTime();
        float strongest = 0.0F;
        for (Int2LongOpenHashMap.Entry e : CLIENT.int2LongEntrySet()) {
            if (e.getLongValue() >= now && level.getEntity(e.getIntKey()) instanceof Player holder && !Shades.isShade(holder)
                    && holder.distanceToSqr(x, y, z) < RADIUS * RADIUS) {
                strongest = Math.max(strongest, strength(holder));
            }
        }
        return 1.0F - strongest;
    }

    /**
     * Skips ticks of everything (but players) in a field: at half speed every other one.
     * <p>
     * On the clients only what they move themselves is skipped (items, projectiles, falling blocks: they
     * are predicted there); creatures are moved by the server and glide smoothly between its positions.
     * Before every tick the game takes the current position as the "previous" one and draws the frames
     * between the two, so a skipped tick alone would stand still and the next one jump: the one real step
     * is therefore spread over the ticks around it ({@link #SPREAD}), so they glide at half speed.
     */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        final Entity entity = event.getEntity();
        if (entity instanceof Player || ACTIVE.isEmpty() && CLIENT.isEmpty()) {
            return;
        }
        final Level level = entity.level();
        if (level.isClientSide && entity instanceof LivingEntity) {
            return;
        }
        final float pace = pace(entity, level);
        if (pace >= 1.0F) {
            return;
        }
        final long t = level.getGameTime();
        final boolean ticksNow = Math.floor((t + 1) * pace) > Math.floor(t * pace);
        if (!ticksNow) {
            event.setCanceled(true);
            if (level.isClientSide) {
                final Vec3[] spread = SPREAD.get(entity.getId());
                if (spread != null) { // the next part of the last real step
                    final Vec3 next = entity.position().add(spread[1]);
                    entity.setPos(next.distanceToSqr(spread[0]) < 1.0E-6 || spread[1].lengthSqr() < 1.0E-9 ? spread[0] : next);
                    if (entity.position().distanceToSqr(spread[0]) < 1.0E-6) {
                        SPREAD.remove(entity.getId());
                    }
                }
            }
        }
    }

    /** Client: after a real tick of a predicted entity in a field, only part of its step is taken now. */
    @SubscribeEvent
    public static void onEntityTickDone(EntityTickEvent.Post event) {
        final Entity entity = event.getEntity();
        final Level level = entity.level();
        if (!level.isClientSide || CLIENT.isEmpty() || entity instanceof LivingEntity || !inField(entity, level)) {
            return;
        }
        final Vec3 from = new Vec3(entity.xo, entity.yo, entity.zo), to = entity.position();
        final Vec3 step = to.subtract(from).scale(pace(entity, level));
        if (step.lengthSqr() < 1.0E-9) {
            SPREAD.remove(entity.getId());
            return;
        }
        SPREAD.put(entity.getId(), new Vec3[] {to, step});
        entity.setPos(from.add(step));
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
                    PacketDistributor.sendToPlayersInDimension(holder.serverLevel(), new EffectPayload("interlude/" + holder.getId(), 30));
                }
            }
        }
    }

    /**
     * Players in someone else's field get slowed attributes for their pace (updated as it changes); those
     * who left lose them.
     */
    private static void slowPlayers(MinecraftServer server) {
        final Map<UUID, Float> now = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                final float pace = pace(player, level);
                if (pace < 1.0F) {
                    now.put(player.getUUID(), pace);
                }
            }
        }
        for (Iterator<Map.Entry<UUID, Float>> it = SLOWED_PLAYERS.entrySet().iterator(); it.hasNext();) {
            final UUID id = it.next().getKey();
            if (!now.containsKey(id)) {
                final ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) {
                    for (Holder<Attribute> attribute : SLOWED) {
                        final AttributeInstance instance = player.getAttribute(attribute);
                        if (instance != null) {
                            instance.removeModifier(SLOW);
                        }
                    }
                }
                it.remove();
            }
        }
        now.forEach((id, pace) -> {
            final Float before = SLOWED_PLAYERS.put(id, pace);
            if (before != null && before.floatValue() == pace) {
                return;
            }
            final ServerPlayer player = server.getPlayerList().getPlayer(id);
            for (Holder<Attribute> attribute : SLOWED) {
                final AttributeInstance instance = player.getAttribute(attribute);
                if (instance != null) {
                    // gravity by the square: falls and jumps play out at the pace; the rest by the pace
                    final double amount = attribute == Attributes.GRAVITY ? pace * pace - 1.0 : pace - 1.0;
                    instance.addOrUpdateTransientModifier(new AttributeModifier(SLOW, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                }
            }
        });
    }

    /**
     * Client: the pace the local player is slowed to (from the modifier the server gave them), 1 if not.
     * Their view turns slower by it ({@code InterludeClient}).
     */
    public static float clientOwnPace(Player player) {
        final AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        final AttributeModifier modifier = speed == null ? null : speed.getModifier(SLOW);
        return modifier == null ? 1.0F : (float) (1.0 + modifier.amount());
    }
}
