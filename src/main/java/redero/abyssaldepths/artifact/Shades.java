package redero.abyssaldepths.artifact;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.network.EffectPayload;

/**
 * The shadow of this world: players can be shades (Parallax makes them so; later another artifact too).
 * A shade stands in the same world as everyone else, but cannot touch it: no breaking, placing, using,
 * attacking, picking up or dropping, no pressure plates, tripwires or vibrations, no pushing. Nothing can
 * touch a shade either: no damage (but the void and /kill), no creature sees or targets it, projectiles
 * fly through. Players who are not shades do not see shades or hear their steps; shades see each other
 * (there is only one shadow, so everyone in it can meet), and see the world dark and drained, without
 * its creatures and players (unless their artifact allows it: Parallax at full mastery).
 * <p>
 * The pushing, picking, block triggers, projectiles, item touching and sounds are in mixins
 * ({@code ShadeEntityMixin}, ...), which ask {@link #isShade(Entity)}. Clients learn the shades from
 * {@code shade/<entity id>} effects, sent every second (and at once when someone enters or leaves).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class Shades {
    /** Server: shades seen this tick (kept so by their artifact), with the tick. */
    private static final Map<UUID, Long> SERVER = new HashMap<>();
    /** Client: entity ids of shades, with the client tick until which they count. */
    private static final Int2LongOpenHashMap CLIENT = new Int2LongOpenHashMap();
    /** How long a shade counts on the clients without news (the server repeats every second). */
    private static final int CLIENT_TICKS = 30;

    private Shades() {
    }

    /** The player is a shade this tick (called every tick while it lasts). */
    public static void mark(ServerPlayer player) {
        final boolean entering = !SERVER.containsKey(player.getUUID());
        SERVER.put(player.getUUID(), player.serverLevel().getGameTime());
        if (entering) {
            send(player, CLIENT_TICKS);
            // creatures that were after the player lose them
            for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(64.0), mob -> mob.getTarget() == player)) {
                mob.setTarget(null);
            }
        }
    }

    /** Whether the entity is a shade, on either side. */
    public static boolean isShade(Entity entity) {
        if (!(entity instanceof Player)) {
            return false;
        }
        if (entity.level().isClientSide) {
            return CLIENT.get(entity.getId()) >= entity.level().getGameTime();
        }
        return SERVER.containsKey(entity.getUUID());
    }

    /** Client: a shade for the next {@code ticks} ticks (0: no longer). */
    public static void clientShade(int entityId, int ticks, long clientTick) {
        if (ticks <= 0) {
            CLIENT.remove(entityId);
        } else {
            CLIENT.put(entityId, clientTick + ticks);
        }
    }

    private static void send(ServerPlayer player, int ticks) {
        PacketDistributor.sendToAllPlayers(new EffectPayload("shade/" + player.getId(), ticks));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        final MinecraftServer server = event.getServer();
        final long now = server.overworld().getGameTime();
        SERVER.entrySet().removeIf(entry -> {
            if (entry.getValue() >= now - 2) {
                return false;
            }
            final ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                send(player, 0); // back in the world
            }
            return true;
        });
        if (now % 20 == 0) {
            for (UUID id : SERVER.keySet()) {
                final ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) {
                    send(player, CLIENT_TICKS);
                }
            }
        }
    }

    // ---- a shade cannot touch the world ----------------------------------------------

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (isShade(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** Items cannot be used, but the one that leads out of the shadow. */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (isShade(event.getEntity()) && !(event.getItemStack().getItem() instanceof ParallaxItem)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (isShade(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (isShade(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteractAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (isShade(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (isShade(event.getEntity()) || isShade(event.getTarget())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (isShade(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() != null && isShade(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (isShade(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** Dropping: the item stays with the shade. */
    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        if (isShade(event.getPlayer())) {
            event.setCanceled(true);
            event.getPlayer().getInventory().placeItemBackInInventory(event.getEntity().getItem());
        }
    }

    /** No vibrations (sculk sensors, wardens) from shades. */
    @SubscribeEvent
    public static void onGameEvent(VanillaGameEvent event) {
        if (event.getCause() != null && isShade(event.getCause())) {
            event.setCanceled(true);
        }
    }

    // ---- nothing touches a shade --------------------------------------------------------

    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (isShade(event.getEntity()) && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() != null && isShade(event.getNewAboutToBeSetTarget())) {
            event.setCanceled(true);
        }
    }

    /** Creatures do not see shades (no looking at them, no noticing them). */
    @SubscribeEvent
    public static void onVisibility(LivingEvent.LivingVisibilityEvent event) {
        if (isShade(event.getEntity())) {
            event.modifyVisibility(0.0);
        }
    }
}
