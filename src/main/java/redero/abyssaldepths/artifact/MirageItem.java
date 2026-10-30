package redero.abyssaldepths.artifact;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.entity.MirageDecoy;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Mirage: right click leaves a likeness of its holder where they stand ({@link MirageDecoy}: their look,
 * equipment, health and armour) for {@link #SECONDS} (longer with the mastery). Creatures within
 * {@link #RADIUS} blocks turn to it; its holder, drawn translucent purple for everyone meanwhile
 * ({@code mirage/<id>} to the clients), is not seen or targeted by creatures until it ends, or until they
 * attack something. Two charges, one back every three minutes.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public class MirageItem extends ArtifactItem {
    public static final double RADIUS = 24.0;
    /** Seconds the decoy stands, per milestone (0 .. 4). */
    static final int[] SECONDS = {10, 13, 15, 18, 20};

    /** Server: a holder's mirage under way: its decoy, and whether creatures still overlook the holder. */
    private static final class Active {
        final UUID decoy;
        boolean unseen = true;

        Active(UUID decoy) {
            this.decoy = decoy;
        }
    }

    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    public MirageItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
    }

    @Override
    public Component powerLine(ItemStack stack) {
        return Component.translatable("item.abyssaldepths.mirage.power", SECONDS[milestone(stack)]);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (charge(stack) < 1) {
            player.displayClientMessage(Component.translatable("abyssaldepths.mirage.no_charge"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (player instanceof ServerPlayer server) {
            end(server); // a new one replaces the last
            final MirageDecoy decoy = MirageDecoy.of(server, SECONDS[milestone(stack)] * 20);
            server.serverLevel().addFreshEntity(decoy);
            ACTIVE.put(player.getUUID(), new Active(decoy.getUUID()));
            turnTo(server.serverLevel(), server, decoy);
            setCharge(stack, charge(stack) - 1);
            server.serverLevel().sendParticles(ParticleTypes.WITCH, player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.4, 0.8, 0.4, 0.05);
            level.playSound(null, player.blockPosition(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1.0F, 0.8F);
            send(server, SECONDS[milestone(stack)] * 20 + 20);
            server.addEffect(new net.minecraft.world.effect.MobEffectInstance(AbyssalDepths.VEILED, SECONDS[milestone(stack)] * 20, 0, false, false, true));
            rest(player);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static void send(ServerPlayer player, int ticks) {
        PacketDistributor.sendToPlayersInDimension(player.serverLevel(), new EffectPayload("mirage/" + player.getId(), ticks));
    }

    /** Ends a holder's mirage (the decoy, the overlooking, the look). */
    private static void end(ServerPlayer player) {
        final Active active = ACTIVE.remove(player.getUUID());
        if (active != null) {
            final Entity decoy = player.serverLevel().getEntity(active.decoy);
            if (decoy != null) {
                decoy.discard();
            }
            send(player, 0);
            player.removeEffect(AbyssalDepths.VEILED);
        }
    }

    /** Creatures around that were after the holder (or after nothing) turn to the decoy. */
    private static void turnTo(ServerLevel level, ServerPlayer holder, MirageDecoy decoy) {
        for (Mob mob : level.getEntitiesOfClass(Mob.class, decoy.getBoundingBox().inflate(RADIUS),
                mob -> mob.getTarget() == holder || mob.getTarget() == null && mob instanceof net.minecraft.world.entity.monster.Enemy)) {
            if (!Shades.apart(mob, decoy)) {
                mob.setTarget(decoy);
            }
        }
    }

    private static MirageDecoy decoy(Entity holder) {
        final Active active = ACTIVE.get(holder.getUUID());
        return active != null && holder.level() instanceof ServerLevel level && level.getEntity(active.decoy) instanceof MirageDecoy decoy
                && decoy.isAlive() ? decoy : null;
    }

    private static boolean unseen(Entity holder) {
        final Active active = ACTIVE.get(holder.getUUID());
        return active != null && active.unseen;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        final MinecraftServer server = event.getServer();
        for (UUID id : java.util.List.copyOf(ACTIVE.keySet())) {
            final ServerPlayer holder = server.getPlayerList().getPlayer(id);
            final MirageDecoy decoy = holder == null ? null : decoy(holder);
            if (holder == null || decoy == null) { // its time is up, it was killed, or they left
                if (holder != null) {
                    end(holder);
                } else {
                    ACTIVE.remove(id);
                }
                continue;
            }
            if (server.overworld().getGameTime() % 10 == 0) {
                turnTo(holder.serverLevel(), holder, decoy);
            }
        }
    }

    /** While overlooked: what would go for the holder goes for the decoy instead. */
    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        final LivingEntity target = event.getNewAboutToBeSetTarget();
        if (target instanceof Player holder && unseen(holder)) {
            final MirageDecoy decoy = decoy(holder);
            if (decoy != null && !Shades.apart(event.getEntity(), decoy)) {
                event.setNewAboutToBeSetTarget(decoy);
            } else {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onVisibility(LivingEvent.LivingVisibilityEvent event) {
        if (event.getLookingEntity() instanceof Mob && unseen(event.getEntity())) {
            event.modifyVisibility(0.0);
        }
    }

    /** Attacking gives the holder away. */
    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        final Active active = ACTIVE.get(event.getEntity().getUUID());
        if (active != null) {
            active.unseen = false;
        }
    }

    @SubscribeEvent
    public static void onHurt(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (event.getSource().getEntity() instanceof Player attacker && event.getEntity() != attacker) {
            final Active active = ACTIVE.get(attacker.getUUID());
            if (active != null) {
                active.unseen = false; // a shot arrow, a thrown potion: given away too
            }
        }
    }
}
