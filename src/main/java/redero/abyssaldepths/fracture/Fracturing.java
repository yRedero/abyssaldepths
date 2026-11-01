package redero.abyssaldepths.fracture;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Fracturing: corruption that breaks the structure of what it touches. It builds up ({@link #corrupt}: so many per
 * cent at a time; it fades again, {@link #FADE} every {@link #FADE_EVERY} ticks once nothing has added to it for
 * {@link #FADE_AFTER}), shown as cracks spreading (over a player's screen, over a creature); at 100 % it breaks
 * through. A creature is frozen solid for
 * {@link #CREATURE_TICKS} (a boss for half), unable to move, attack or do anything, drawn in black and white
 * corruption, fragments coming away ({@code client.FractureView}). A player goes through a trial
 * ({@link FractureTrials}). Rare by design: for now only {@code /abyssaldepths fracture} brings it.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class Fracturing {
    public static final int CREATURE_TICKS = 20 * 20;

    /** Creatures frozen: until which tick, and whether they had no AI of their own before. */
    private record Frozen(ServerLevel level, long until, boolean hadNoAi) {
    }

    private static final Map<UUID, Frozen> FROZEN = new HashMap<>();

    /** How fast corruption fades: so much every so many ticks, once nothing has added to it for a while. */
    public static final float FADE = 0.01F;
    public static final int FADE_EVERY = 40, FADE_AFTER = 200;

    /** Corruption building up in an entity: how much (0 .. 1), and when it was last added to. */
    private static final class Corruption {
        final ServerLevel level;
        float value;
        long last;

        Corruption(ServerLevel level) {
            this.level = level;
        }
    }

    private static final Map<UUID, Corruption> CORRUPTION = new HashMap<>();

    /** Adds corruption (per cent) to an entity; at 100 % it breaks through: {@link #fracture}. */
    public static void corrupt(LivingEntity target, float percent, Trial.Type type) {
        if (!(target.level() instanceof ServerLevel level) || target instanceof ServerPlayer player && (FractureTrials.busy(player) || player.isSpectator())
                || frozen(target)) {
            return;
        }
        final Corruption corruption = CORRUPTION.computeIfAbsent(target.getUUID(), k -> new Corruption(level));
        corruption.value = Math.min(1.0F, corruption.value + percent / 100.0F);
        corruption.last = level.getGameTime();
        tell(target, corruption.value);
        if (corruption.value >= 1.0F) {
            CORRUPTION.remove(target.getUUID());
            tell(target, 0.0F); // spent: the cracks go (taken, it is drawn otherwise)
            fracture(target, type);
        }
    }

    /** Sets the corruption of an entity (per cent); at 100 % it breaks through, at 0 it is gone. */
    public static void set(LivingEntity target, float percent) {
        if (!(target.level() instanceof ServerLevel level)) {
            return;
        }
        if (percent <= 0.0F) {
            CORRUPTION.remove(target.getUUID());
            tell(target, 0.0F);
            return;
        }
        final Corruption corruption = CORRUPTION.computeIfAbsent(target.getUUID(), k -> new Corruption(level));
        corruption.value = 0.0F;
        corrupt(target, percent, null); // (as if added at once: the same checks, the same breaking through)
    }

    /** How corrupted an entity is now (0 .. 1). */
    public static float corruption(Entity entity) {
        final Corruption corruption = CORRUPTION.get(entity.getUUID());
        return corruption == null ? 0.0F : corruption.value;
    }

    /** Those who see it (and itself) learn how far its corruption is (its cracks). */
    private static void tell(Entity entity, float value) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, new EffectPayload("corruption/" + entity.getId() + "/" + Math.round(value * 100), 0));
    }

    private Fracturing() {
    }

    /** Fractures what is there: a player into a trial (this one, or by chance), a creature frozen. */
    public static void fracture(LivingEntity target, Trial.Type type) {
        if (target instanceof ServerPlayer player) {
            FractureTrials.fracture(player, type);
        } else if (target instanceof Mob mob && mob.level() instanceof ServerLevel level) {
            final int ticks = mob.getType().is(Tags.EntityTypes.BOSSES) ? CREATURE_TICKS / 2 : CREATURE_TICKS;
            final Frozen before = FROZEN.get(mob.getUUID());
            FROZEN.put(mob.getUUID(), new Frozen(level, level.getGameTime() + ticks, before != null ? before.hadNoAi() : mob.isNoAi()));
            mob.setNoAi(true);
            mob.setDeltaMovement(Vec3.ZERO);
            mob.setTarget(null);
            showCorrupted(mob, ticks);
            level.playSound(null, mob.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.0F, 0.4F);
        }
    }

    /** Whether the creature is frozen by Fracturing now. */
    public static boolean frozen(Entity entity) {
        return !FROZEN.isEmpty() && FROZEN.containsKey(entity.getUUID());
    }

    /** Tells those who see it (and itself) that it is drawn in corruption for that long. */
    static void showCorrupted(Entity entity, int ticks) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, new EffectPayload("fracture_mob/" + entity.getId(), ticks));
    }

    /** The player's frozen body stays, decomposing (they failed: the corruption takes them). */
    static void decayBody(ServerPlayer player) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            for (FracturedBody body : level.getEntities(AbyssalDepths.FRACTURED_BODY.get(), b -> b.owner().filter(player.getUUID()::equals).isPresent())) {
                body.decay();
            }
        }
    }

    /** The player's frozen body goes (they are back in it): a few ticks on, so the two overlap, not leaving a gap. */
    static void discardBody(ServerPlayer player) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            for (FracturedBody body : level.getEntities(AbyssalDepths.FRACTURED_BODY.get(), b -> b.owner().filter(player.getUUID()::equals).isPresent())) {
                body.fadeAway(4);
            }
        }
    }

    /** Those who see the player's body (and the player) learn the corruption recedes from them as they come back. */
    static void recede(ServerPlayer player) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            for (FracturedBody body : level.getEntities(AbyssalDepths.FRACTURED_BODY.get(), b -> b.owner().filter(player.getUUID()::equals).isPresent())) {
                PacketDistributor.sendToPlayersTrackingEntity(body, new EffectPayload("fracture_recede/" + player.getId(), 0));
            }
        }
        PacketDistributor.sendToPlayer(player, new EffectPayload("fracture_recede/" + player.getId(), 0));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        CORRUPTION.entrySet().removeIf(entry -> { // fading, without more
            final Corruption corruption = entry.getValue();
            final Entity entity = corruption.level.getEntity(entry.getKey());
            if (entity == null || !entity.isAlive()) {
                if (entity != null) {
                    tell(entity, 0.0F);
                }
                return true;
            }
            final long now = corruption.level.getGameTime();
            if (now - corruption.last >= FADE_AFTER && (now - corruption.last) % FADE_EVERY == 0) {
                corruption.value -= FADE;
                tell(entity, Math.max(0.0F, corruption.value));
                return corruption.value <= 0.0F;
            }
            if (now % 40 == 0) {
                tell(entity, corruption.value); // for those who come near meanwhile
            }
            return false;
        });
        if (FROZEN.isEmpty()) {
            return;
        }
        final MinecraftServer server = event.getServer();
        FROZEN.entrySet().removeIf(entry -> {
            final Frozen frozen = entry.getValue();
            final Entity entity = frozen.level().getEntity(entry.getKey());
            if (entity == null || !entity.isAlive()) {
                return true;
            }
            if (frozen.level().getGameTime() < frozen.until()) {
                if (entity.tickCount % 40 == 0) {
                    showCorrupted(entity, (int) (frozen.until() - frozen.level().getGameTime())); // for those who come near meanwhile
                }
                return false;
            }
            if (entity instanceof Mob mob) {
                mob.setNoAi(frozen.hadNoAi());
            }
            return true;
        });
    }

    /** A frozen creature does no harm: its blows (and whatever else it would do to others) come to nothing. */
    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (!FROZEN.isEmpty() && event.getSource().getEntity() != null && frozen(event.getSource().getEntity())) {
            event.setCanceled(true);
        }
    }
}
