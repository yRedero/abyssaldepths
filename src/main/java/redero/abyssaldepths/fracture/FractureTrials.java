package redero.abyssaldepths.fracture;

import java.util.HashMap;
import java.util.List;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Fracturing a player: for {@link #ONSET_TICKS} they are held still while cracks run over their screen; then
 * their body stays behind, frozen and untouchable ({@link FracturedBody}), and they are pulled into a trial
 * ({@link Trial}) in the trial dimension, an arena of their own. What they carried, their effects and their
 * health are kept safe meanwhile (saved with the world: a logout or a crash gives them back), and they go into
 * the trial with nothing. When it is passed or failed their screen breaks once more ({@link #LEAVE_TICKS}) and
 * they are back in their body with all of it. A failed trial lets the corruption take them: they die (no totem
 * holds it) and are left with a Corruption Scar ({@link Scars}). In the trial nothing hurts them but the trial,
 * and no block is broken or set.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class FractureTrials {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "fracture"));
    public static final ResourceKey<net.minecraft.world.damagesource.DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "fracture"));
    public static final int ONSET_TICKS = 85, LEAVE_TICKS = 20;
    private static final int SPACING = 512, ARENA_Y = 100;
    private static final ResourceLocation HELD = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "fracture_held");

    /** Players being fractured: the tick they are pulled in, and the trial asked for (or null: by chance). */
    private record Onset(long at, Trial.Type type) {
    }

    private static final Map<UUID, Onset> ONSET = new HashMap<>();
    private static final Map<UUID, Trial> TRIALS = new HashMap<>();
    /** Players on their way out: the tick they are back, and whether they passed. */
    private record Leaving(long at, boolean passed) {
    }

    private static final Map<UUID, Leaving> LEAVING = new HashMap<>();

    private FractureTrials() {
    }

    /** Whether the player is being fractured, in a trial, or on the way out. */
    public static boolean busy(Player player) {
        return ONSET.containsKey(player.getUUID()) || TRIALS.containsKey(player.getUUID()) || LEAVING.containsKey(player.getUUID())
                || DOOMED.containsKey(player.getUUID());
    }

    /** The player's screen has cleared in their trial: it can begin (the looking round first). */
    public static void ready(ServerPlayer player) {
        final Trial trial = TRIALS.get(player.getUUID());
        if (trial != null && !trial.ready) {
            trial.ready = true;
            PacketDistributor.sendToPlayer(player, new EffectPayload("trial_title/" + trial.type().name(), Trial.ORIENT_TICKS));
        }
    }

    /** Fractures a player: the onset first, then the trial (this one, or one by chance). */
    public static void fracture(ServerPlayer player, Trial.Type type) {
        if (busy(player) || player.isSpectator() || player.level().dimension() == DIMENSION) {
            return;
        }
        ONSET.put(player.getUUID(), new Onset(player.serverLevel().getGameTime() + ONSET_TICKS, type));
        hold(player, true);
        PacketDistributor.sendToPlayer(player, new EffectPayload("fracture_onset", ONSET_TICKS));
        Fracturing.showCorrupted(player, ONSET_TICKS + 5);
        player.serverLevel().playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.GLASS_BREAK, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.3F);
    }

    /** Holds the player still (or lets go): no walking, no jumping. */
    private static void hold(ServerPlayer player, boolean held) {
        for (var attribute : java.util.List.of(Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH)) {
            final AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            if (held) {
                instance.addOrUpdateTransientModifier(new AttributeModifier(HELD, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            } else {
                instance.removeModifier(HELD);
            }
        }
    }

    // ---- in and out -------------------------------------------------------------------------

    /** The onset comes to nothing (they died meanwhile, or are gone): let go, the body away, their screen told. */
    private static void cancelOnset(ServerPlayer player) {
        ONSET.remove(player.getUUID());
        PLACED.remove(player.getUUID());
        hold(player, false);
        Fracturing.discardBody(player);
        PacketDistributor.sendToPlayer(player, new EffectPayload("fracture_cancel", 0));
    }

    private static void enter(ServerPlayer player, Trial.Type type) {
        if (!player.isAlive()) { // dead meanwhile: no trial
            cancelOnset(player);
            return;
        }
        hold(player, false);
        final MinecraftServer server = player.getServer();
        final ServerLevel trials = server.getLevel(DIMENSION);
        if (trials == null) {
            AbyssalDepths.LOGGER.error("the fracture dimension is missing");
            return;
        }
        final Kept kept = Kept.get(server);
        kept.store(player);
        if (!PLACED.remove(player.getUUID())) {
            FracturedBody.standIn(player);
        }
        final List<net.minecraft.world.item.ItemStack> things = new java.util.ArrayList<>(); // a few things they carried, to remember
        for (int slot = 0; slot < player.getInventory().getContainerSize() && things.size() < 3; slot++) {
            final net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && things.stream().noneMatch(s -> s.is(stack.getItem()))) {
                things.add(stack.copyWithCount(1));
            }
        }
        player.getInventory().clearContent();
        player.removeAllEffects();
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        final BlockPos origin = new BlockPos(kept.slot(player.getUUID()) * SPACING, ARENA_Y, 0);
        final Trial trial = (type == null ? Trial.Type.pick(player.getRandom()) : type).create(player, trials, origin, Scars.of(player));
        trial.remember(things);
        trial.build();
        final Vec3 spawn = trial.spawn();
        player.teleportTo(trials, spawn.x, spawn.y, spawn.z, trial.spawnYaw(), 10.0F);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        TRIALS.put(player.getUUID(), trial);
        PacketDistributor.sendToPlayer(player, new EffectPayload("trial_start/" + trial.type().name(), Trial.ORIENT_TICKS));
        PacketDistributor.sendToPlayer(player, new EffectPayload("corruption/" + player.getId() + "/0", 0)); // the cracks are spent
    }

    /** Back in the body, with all that was kept; failed, the corruption takes them. */
    private static void leave(ServerPlayer player, boolean passed) {
        final Trial trial = TRIALS.remove(player.getUUID());
        if (trial != null) {
            trial.clear();
        }
        if (passed) {
            Fracturing.recede(player); // told before the move: those by the body see them arrive taken, the corruption drawing back
        }
        Kept.get(player.getServer()).restore(player);
        if (passed) {
            Fracturing.discardBody(player);
        } else { // the body stays, decomposing; the player in it is not seen dying (only the body goes)
            Fracturing.decayBody(player);
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new EffectPayload("fracture_dying/" + player.getId(), 0));
        }
        PacketDistributor.sendToPlayer(player, new EffectPayload("trial_out/" + (passed ? 1 : 0), 0));
        if (!passed) {
            Scars.set(player, Scars.of(player) + 1);
            // the death a moment later: just after a move between worlds a player cannot be hurt
            DOOMED.put(player.getUUID(), player.getServer().overworld().getGameTime() + 10);
        }
    }

    /** Ends the player's trial now, passed or failed (as if it had come to that); false if they are in none. */
    public static boolean end(ServerPlayer player, boolean passed) {
        if (!TRIALS.containsKey(player.getUUID()) || LEAVING.containsKey(player.getUUID())) {
            return false;
        }
        LEAVING.put(player.getUUID(), new Leaving(player.serverLevel().getGameTime() + LEAVE_TICKS, passed));
        PacketDistributor.sendToPlayer(player, new EffectPayload("trial_end/" + (passed ? 1 : 0), LEAVE_TICKS));
        return true;
    }

    /** For the dev tools: no hit counts in the trials (to see them through standing still). */
    public static boolean unharmed;

    /** Where a part of the player's trial is (for the dev tools), or null. */
    public static Vec3 place(ServerPlayer player, String what) {
        return TRIALS.get(player.getUUID()) instanceof ReachTrial reach ? reach.place(what) : null;
    }

    /** Players whose body stands already (the moment before they go). */
    private static final java.util.Set<UUID> PLACED = new java.util.HashSet<>();

    /** Players the corruption takes, with the tick it does (just back in their body). */
    private static final Map<UUID, Long> DOOMED = new HashMap<>();

    private static void take(ServerPlayer player) {
        final DamageSource corruption = new DamageSource(player.serverLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DAMAGE));
        player.hurt(corruption, Float.MAX_VALUE);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        final MinecraftServer server = event.getServer();
        final long now = server.overworld().getGameTime();
        if (!ONSET.isEmpty()) {
            for (Map.Entry<UUID, Onset> entry : new HashMap<>(ONSET).entrySet()) {
                final ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) {
                    ONSET.remove(entry.getKey());
                } else if (player.serverLevel().getGameTime() >= entry.getValue().at() - 3 && !PLACED.contains(entry.getKey())) {
                    PLACED.add(entry.getKey()); // the body a few ticks before the player goes: both there at once, never neither
                    FracturedBody.standIn(player);
                } else if (player.serverLevel().getGameTime() >= entry.getValue().at()) {
                    ONSET.remove(entry.getKey());
                    enter(player, entry.getValue().type());
                }
            }
        }
        if (!TRIALS.isEmpty()) {
            for (Map.Entry<UUID, Trial> entry : new HashMap<>(TRIALS).entrySet()) {
                final ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null || LEAVING.containsKey(entry.getKey())) {
                    continue;
                }
                final Trial.Result result = entry.getValue().tick();
                if (result != Trial.Result.RUNNING) {
                    LEAVING.put(entry.getKey(), new Leaving(now + LEAVE_TICKS, result == Trial.Result.PASSED));
                    PacketDistributor.sendToPlayer(player, new EffectPayload("trial_end/" + (result == Trial.Result.PASSED ? 1 : 0), LEAVE_TICKS));
                }
            }
        }
        if (!DOOMED.isEmpty()) {
            DOOMED.entrySet().removeIf(entry -> {
                if (now < entry.getValue()) {
                    return false;
                }
                final ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null && player.isAlive()) {
                    take(player);
                }
                return true;
            });
        }
        if (!LEAVING.isEmpty()) {
            for (Map.Entry<UUID, Leaving> entry : new HashMap<>(LEAVING).entrySet()) {
                if (now >= entry.getValue().at()) {
                    LEAVING.remove(entry.getKey());
                    final ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                    if (player != null) {
                        leave(player, entry.getValue().passed());
                    }
                }
            }
        }
    }

    /** Coming back (after a logout or a crash in a trial): back in the body's place with what was kept, no harm done. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && Kept.get(player.getServer()).has(player.getUUID())) {
            Kept.get(player.getServer()).restore(player);
        }
    }

    /** Leaving the game in a trial: the trial is undone (what was kept comes back at the next login). */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        final UUID id = event.getEntity().getUUID();
        ONSET.remove(id);
        PLACED.remove(id);
        LEAVING.remove(id);
        final Trial trial = TRIALS.remove(id);
        if (trial != null) {
            trial.clear();
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            hold(player, false);
        }
    }

    /** In the trial dimension nothing hurts a player (the trials have their own ways), nor kills them. */
    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Player player && player.level().dimension() == DIMENSION && !event.getSource().is(DAMAGE)
                && !event.getSource().is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL)) {
            event.setCanceled(true);
        }
        // while it takes them (held still, the view leaving them) nothing else hurts them, but the void and /kill
        if (event.getEntity() instanceof Player player && ONSET.containsKey(player.getUUID())
                && !event.getSource().is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL)
                && !event.getSource().is(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer dying && ONSET.containsKey(dying.getUUID())) {
            cancelOnset(dying); // dead before it took them: no trial
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player && player.level().dimension() == DIMENSION && TRIALS.containsKey(player.getUUID())) {
            event.setCanceled(true); // the trial fails instead (the death follows, back in the body)
            player.setHealth(1.0F);
            LEAVING.put(player.getUUID(), new Leaving(player.serverLevel().getGameTime() + LEAVE_TICKS, false));
            PacketDistributor.sendToPlayer(player, new EffectPayload("trial_end/0", LEAVE_TICKS));
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == DIMENSION && !event.getPlayer().isCreative()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == DIMENSION && !(event.getEntity() instanceof Player p && p.isCreative())) {
            event.setCanceled(true);
        }
    }

    // ---- what is kept while in a trial ---------------------------------------------------------

    /** Per player: where their body stands, what they carried, their effects and health; their arena's slot. */
    static final class Kept extends SavedData {
        private final Map<UUID, CompoundTag> kept = new HashMap<>();
        private final Map<UUID, Integer> slots = new HashMap<>();

        static Kept get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(Kept::new, Kept::load, null), "abyssaldepths_fracture");
        }

        boolean has(UUID player) {
            return kept.containsKey(player);
        }

        int slot(UUID player) {
            return slots.computeIfAbsent(player, k -> {
                setDirty();
                return slots.size() + 1;
            });
        }

        void store(ServerPlayer player) {
            final CompoundTag tag = new CompoundTag();
            tag.putString("dim", player.level().dimension().location().toString());
            tag.putDouble("x", player.getX());
            tag.putDouble("y", player.getY());
            tag.putDouble("z", player.getZ());
            tag.putFloat("yaw", player.getYRot());
            tag.putFloat("pitch", player.getXRot());
            tag.put("inventory", player.getInventory().save(new ListTag()));
            final ListTag effects = new ListTag();
            for (MobEffectInstance effect : player.getActiveEffects()) {
                effects.add(effect.save());
            }
            tag.put("effects", effects);
            tag.putFloat("health", player.getHealth());
            tag.putInt("food", player.getFoodData().getFoodLevel());
            kept.put(player.getUUID(), tag);
            setDirty();
        }

        /** Puts the player back where their body stood, with what they carried (if anything was kept). */
        void restore(ServerPlayer player) {
            final CompoundTag tag = kept.remove(player.getUUID());
            if (tag == null) {
                return;
            }
            setDirty();
            ServerLevel level = player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("dim"))));
            if (level == null) {
                level = player.getServer().overworld();
            }
            player.teleportTo(level, tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"), tag.getFloat("yaw"), tag.getFloat("pitch"));
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
            player.getInventory().clearContent();
            player.getInventory().load(tag.getList("inventory", Tag.TAG_COMPOUND));
            player.removeAllEffects();
            final ListTag effects = tag.getList("effects", Tag.TAG_COMPOUND);
            for (int i = 0; i < effects.size(); i++) {
                final MobEffectInstance effect = MobEffectInstance.load(effects.getCompound(i));
                if (effect != null) {
                    player.addEffect(effect);
                }
            }
            player.setHealth(Math.min(player.getMaxHealth(), Math.max(1.0F, tag.getFloat("health"))));
            player.getFoodData().setFoodLevel(tag.getInt("food"));
            hold(player, false);
        }

        static Kept load(CompoundTag tag, HolderLookup.Provider registries) {
            final Kept kept = new Kept();
            final CompoundTag players = tag.getCompound("kept");
            players.getAllKeys().forEach(k -> kept.kept.put(UUID.fromString(k), players.getCompound(k)));
            final CompoundTag slots = tag.getCompound("slots");
            slots.getAllKeys().forEach(k -> kept.slots.put(UUID.fromString(k), slots.getInt(k)));
            return kept;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            final CompoundTag players = new CompoundTag();
            kept.forEach((k, v) -> players.put(k.toString(), v));
            tag.put("kept", players);
            final CompoundTag slots = new CompoundTag();
            this.slots.forEach((k, v) -> slots.putInt(k.toString(), v));
            tag.put("slots", slots);
            return tag;
        }
    }
}
