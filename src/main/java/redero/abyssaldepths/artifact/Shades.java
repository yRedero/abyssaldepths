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
 * The shadow of this world: players and creatures can be shades. Parallax makes its holder one for as long
 * as it is on ({@link #mark}); Oblivion sends everything around its user there for a while ({@link #sendAway}).
 * A shade stands in the same world as everyone else, but cannot touch it: no breaking, placing, using,
 * picking up or dropping, no pressure plates, tripwires or vibrations, no griefing or exploding (creatures).
 * Between the sides nothing passes: no attacks, damage, targeting, pushing, projectiles or seeing; the world
 * itself does not hurt a shade either (but the void and /kill). Among themselves shades live on: they see,
 * fight and hurt each other (there is only one shadow, so everyone in it can meet). Those outside do not
 * see or hear shades; shades see the world dark and drained, without its creatures and players (unless
 * their artifact allows it: Parallax at full mastery). What a shade shoots is in the shadow too.
 * <p>
 * The pushing, picking, block triggers, projectiles, item touching and sounds are in mixins
 * ({@code ShadeEntityMixin}, ...), which ask {@link #isShade(Entity)}. Clients learn the shades from
 * {@code shade/<entity id>} effects, sent every second (and at once when someone enters or leaves).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class Shades {
    /** Server: shades seen this tick (kept so by their artifact), with the tick. */
    private static final Map<UUID, Long> SERVER = new HashMap<>();
    /** Server: what was sent into the shadow for a while (Oblivion), with where it is and when it comes back. */
    private record Sent(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int id, long until) {
    }

    private static final Map<UUID, Sent> SENT = new HashMap<>();
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
            send(player.getId(), CLIENT_TICKS);
            loseTargets(player.serverLevel(), player);
        }
    }

    /**
     * Sends an entity into the shadow for {@code ticks} (longer if it is there already for less); creatures
     * and players that were after it, or it after them, lose each other.
     */
    public static void sendAway(Entity entity, int ticks) {
        if (!(entity.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        final long until = level.getServer().overworld().getGameTime() + ticks;
        final Sent before = SENT.get(entity.getUUID());
        if (before != null && before.until() >= until) {
            return;
        }
        SENT.put(entity.getUUID(), new Sent(level.dimension(), entity.getId(), until));
        send(entity.getId(), ticks);
        loseTargets(level, entity);
    }

    /** Ends the targeting across the sides around an entity that just changed sides. */
    private static void loseTargets(net.minecraft.server.level.ServerLevel level, Entity entity) {
        final boolean shade = isShade(entity);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, entity.getBoundingBox().inflate(64.0), mob -> mob.getTarget() == entity)) {
            if (isShade(mob) != shade) {
                mob.setTarget(null);
            }
        }
        if (entity instanceof Mob mob && mob.getTarget() != null && isShade(mob.getTarget()) != shade) {
            mob.setTarget(null);
        }
    }

    /** Whether the entity is a shade, on either side. */
    public static boolean isShade(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity.level().isClientSide) {
            return !CLIENT.isEmpty() && CLIENT.get(entity.getId()) >= entity.level().getGameTime();
        }
        return SERVER.containsKey(entity.getUUID()) || SENT.containsKey(entity.getUUID());
    }

    /** Whether two entities are on different sides of the shadow. */
    public static boolean apart(Entity a, Entity b) {
        return isShade(a) != isShade(b);
    }

    /** Server: set while a shade creature makes a sound (the sound is then kept to the shadow). */
    public static final ThreadLocal<Boolean> SOUND_BY_SHADE = ThreadLocal.withInitial(() -> false);

    /** Client: whether this client's view is in the shadow (set by the client every tick). */
    public static volatile boolean clientViewInShadow;

    /** Client: a shade for the next {@code ticks} ticks (0: no longer). */
    public static void clientShade(int entityId, int ticks, long clientTick) {
        if (ticks <= 0) {
            CLIENT.remove(entityId);
        } else {
            CLIENT.put(entityId, clientTick + ticks);
        }
    }

    private static void send(int entityId, int ticks) {
        PacketDistributor.sendToAllPlayers(new EffectPayload("shade/" + entityId, ticks));
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
            if (player != null && !SENT.containsKey(entry.getKey())) {
                send(player.getId(), 0); // back in the world
                loseTargets(player.serverLevel(), player);
            }
            return true;
        });
        // the sent come back when their time is up (or are forgotten when gone)
        final java.util.List<Entity> back = new java.util.ArrayList<>();
        SENT.entrySet().removeIf(entry -> {
            final Sent sent = entry.getValue();
            final net.minecraft.server.level.ServerLevel level = server.getLevel(sent.dimension());
            final Entity entity = level == null ? null : level.getEntity(entry.getKey());
            if (entity == null || entity.isRemoved()) {
                send(sent.id(), 0);
                return true;
            }
            if (sent.until() > now) {
                return false;
            }
            if (!SERVER.containsKey(entry.getKey())) {
                send(sent.id(), 0);
            }
            back.add(entity);
            return true;
        });
        for (Entity entity : back) { // now back on the world's side: they and those after them let go
            loseTargets((net.minecraft.server.level.ServerLevel) entity.level(), entity);
        }
        if (now % 20 == 0) {
            for (UUID id : SERVER.keySet()) {
                final ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) {
                    send(player.getId(), CLIENT_TICKS);
                }
            }
            for (Sent sent : SENT.values()) {
                send(sent.id(), (int) Math.min(Integer.MAX_VALUE, sent.until() - now));
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
        if (apart(event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteractAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (apart(event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
        }
    }

    /** Attacks only within a side. */
    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (apart(event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
        }
    }

    /** Shade creatures do not grief: no picking up, breaking doors, trampling, taking blocks. */
    @SubscribeEvent
    public static void onGrief(net.neoforged.neoforge.event.entity.EntityMobGriefingEvent event) {
        if (isShade(event.getEntity())) {
            event.setCanGrief(false);
        }
    }

    /** What a shade sets off (a creeper, a fireball) does not explode in the world. */
    @SubscribeEvent
    public static void onExplosion(net.neoforged.neoforge.event.level.ExplosionEvent.Start event) {
        final Entity direct = event.getExplosion().getDirectSourceEntity();
        if (isShade(direct) || isShade(event.getExplosion().getIndirectSourceEntity())) {
            event.setCanceled(true);
        }
    }

    /** What a shade shoots or throws is in the shadow too, for as long as it flies (or the shooter stays). */
    @SubscribeEvent
    public static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile
                && isShade(projectile.getOwner())) {
            final Sent owner = SENT.get(projectile.getOwner().getUUID());
            final long now = event.getLevel().getServer().overworld().getGameTime();
            sendAway(projectile, owner != null ? (int) Math.max(20, owner.until() - now) : 200);
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

    // ---- nothing passes between the sides ----------------------------------------------

    /**
     * Damage: a shade only from another shade (and the void, /kill); the world itself does not hurt it.
     * One outside not from a shade.
     */
    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        final Entity source = event.getSource().getEntity() != null ? event.getSource().getEntity() : event.getSource().getDirectEntity();
        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }
        if (isShade(event.getEntity()) ? source == null || !isShade(source) : isShade(source)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() != null && apart(event.getEntity(), event.getNewAboutToBeSetTarget())) {
            event.setCanceled(true);
        }
    }

    /** Creatures do not see across the sides (no looking at them, no noticing them). */
    @SubscribeEvent
    public static void onVisibility(LivingEvent.LivingVisibilityEvent event) {
        final Entity looking = event.getLookingEntity();
        if (looking != null ? apart(looking, event.getEntity()) : isShade(event.getEntity())) {
            event.modifyVisibility(0.0);
        }
    }
}
