package redero.abyssaldepths;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Paralyzed: the body will not answer. It cannot move, jump, attack, break, place or use anything while it lasts, and
 * a creature's mind stops too (it stands as it was, after nothing). Fracturing leaves creatures paralyzed; those
 * Oblivion sends away come back paralyzed for a moment. Its blows, if any land, do nothing.
 * <p>
 * A creature is stopped by switching its AI off; whether it had AI of its own is kept on it (so a save in between
 * keeps it right) and given back when the effect ends, however it ends.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class Paralyzed extends MobEffect {
    private static final String HAD_NO_AI = AbyssalDepths.MODID + ":paralyzed_had_no_ai";

    public Paralyzed() {
        super(MobEffectCategory.HARMFUL, 0xD6D6D6);
        addAttributeModifier(Attributes.MOVEMENT_SPEED, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "paralyzed"), -1.0,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        addAttributeModifier(Attributes.JUMP_STRENGTH, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "paralyzed_jump"), -1.0,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity.level().isClientSide) {
            return true;
        }
        if (entity instanceof Mob mob && !mob.getPersistentData().contains(HAD_NO_AI)) {
            mob.getPersistentData().putBoolean(HAD_NO_AI, mob.isNoAi());
            mob.setNoAi(true);
            mob.setTarget(null);
            mob.getNavigation().stop();
        }
        final Vec3 v = entity.getDeltaMovement();
        entity.setDeltaMovement(0.0, Math.min(0.0, v.y), 0.0); // it may fall, not move
        return true;
    }

    /** Whether the entity is paralyzed now. */
    public static boolean is(Entity entity) {
        return entity instanceof LivingEntity living && living.hasEffect(AbyssalDepths.PARALYZED);
    }

    /** Gives a creature its mind back (whatever it had before). */
    private static void release(LivingEntity entity) {
        if (entity instanceof Mob mob && mob.getPersistentData().contains(HAD_NO_AI)) {
            mob.setNoAi(mob.getPersistentData().getBoolean(HAD_NO_AI));
            mob.getPersistentData().remove(HAD_NO_AI);
        }
    }

    @SubscribeEvent
    public static void onRemove(MobEffectEvent.Remove event) {
        if (event.getEffect() != null && event.getEffect().value() == AbyssalDepths.PARALYZED.get()) {
            release(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onExpired(MobEffectEvent.Expired event) {
        if (event.getEffectInstance() != null && event.getEffectInstance().getEffect().value() == AbyssalDepths.PARALYZED.get()) {
            release(event.getEntity());
        }
    }

    /** Loaded again without the effect (it ran out or was taken away meanwhile): its mind back. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof LivingEntity living && !living.hasEffect(AbyssalDepths.PARALYZED)) {
            release(living);
        }
    }

    /** Its blows (and whatever else it would do to others) come to nothing. */
    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getSource().getEntity() != null && is(event.getSource().getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (is(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (is(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (is(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (is(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (is(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (is(event.getEntity())) {
            event.setNewSpeed(0.0F);
        }
    }
}
