package redero.abyssaldepths.artifact;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.mixin.ServerPlayerAccess;

/**
 * Recall: hold right click for a few seconds ({@link #HOLD}) and its holder is taken home: to their bed or
 * respawn anchor (without using up the anchor's charge), or the world spawn, from any dimension. Taking
 * damage while holding breaks it. One charge; mastered, the hold is shorter and the charge comes back
 * sooner ({@link #REGEN_MINUTES}).
 */
public class RecallItem extends ArtifactItem {
    /** Ticks to hold, and minutes for the charge to come back, per milestone (0 .. 4). */
    static final int[] HOLD = {60, 50, 40, 30, 20}, REGEN_MINUTES = {10, 8, 6, 5, 4};

    public RecallItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
    }

    @Override
    public int regenTicks(ItemStack stack) {
        return REGEN_MINUTES[milestone(stack)] * 60 * 20;
    }

    @Override
    public Component powerLine(ItemStack stack) {
        final int m = milestone(stack);
        return Component.translatable("item.abyssaldepths.recall.power", HOLD[m] / 20.0F, REGEN_MINUTES[m]);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (charge(stack) < 1) {
            player.displayClientMessage(Component.translatable("abyssaldepths.recall.no_charge"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (level.dimension() == InnerSelf.DIMENSION) {
            player.displayClientMessage(Component.translatable("abyssaldepths.recall.inner_self"), true);
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return HOLD[milestone(stack)];
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    /** While held: the portal's motes gather round the holder. */
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (level.isClientSide) {
            for (int i = 0; i < 3; i++) {
                level.addParticle(ParticleTypes.PORTAL, entity.getRandomX(0.8), entity.getRandomY(), entity.getRandomZ(0.8),
                        (level.random.nextDouble() - 0.5) * 1.5, -level.random.nextDouble(), (level.random.nextDouble() - 0.5) * 1.5);
            }
        }
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof ServerPlayer player && charge(stack) >= 1) {
            setCharge(stack, charge(stack) - 1);
            home(player);
            rest(player);
        }
        return stack;
    }

    /** Takes the player to where they would respawn (their bed or anchor if it is there, else the world spawn). */
    public static void home(ServerPlayer player) {
        final ServerLevel from = player.serverLevel();
        from.playSound(null, player.blockPosition(), SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.0F, 0.8F);
        from.sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 40, 0.4, 0.8, 0.4, 0.05);
        final BlockPos pos = player.getRespawnPosition();
        ServerLevel to = player.server.getLevel(player.getRespawnDimension());
        Optional<ServerPlayer.RespawnPosAngle> found = Optional.empty();
        if (pos != null && to != null) {
            found = ServerPlayerAccess.abyssaldepths$findRespawn(to, pos, player.getRespawnAngle(), player.isRespawnForced(), true);
            if (found.isEmpty()) {
                player.displayClientMessage(Component.translatable("abyssaldepths.recall.no_bed"), true);
            }
        }
        final Vec3 at;
        final float yaw;
        if (found.isPresent()) {
            at = found.get().position();
            yaw = found.get().yaw();
        } else {
            to = player.server.overworld();
            at = Vec3.atBottomCenterOf(to.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, to.getSharedSpawnPos()));
            yaw = player.getYRot();
        }
        player.teleportTo(to, at.x, at.y, at.z, yaw, player.getXRot());
        player.fallDistance = 0.0F;
        to.playSound(null, player.blockPosition(), SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.2F);
        to.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1.0, at.z, 40, 0.4, 0.8, 0.4, 0.05);
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID)
    public static final class Events {
        private Events() {
        }

        /** Hurt while holding: the recall breaks. */
        @SubscribeEvent
        public static void onDamage(LivingDamageEvent.Post event) {
            if (event.getEntity() instanceof ServerPlayer player && player.isUsingItem() && player.getUseItem().getItem() instanceof RecallItem) {
                player.stopUsingItem();
                player.displayClientMessage(Component.translatable("abyssaldepths.recall.broken"), true);
            }
        }
    }
}
