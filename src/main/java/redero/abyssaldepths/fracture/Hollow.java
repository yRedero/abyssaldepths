package redero.abyssaldepths.fracture;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import redero.abyssaldepths.AbyssalDepths;

/**
 * A Hollow: the body of a player the corruption took, risen and walking off in their likeness (black and white, its
 * marks crawling). It hunts the living. Killing it takes back what it holds: its player loses the scar it left
 * them (or, if they are not there, a Cleansing Shard is left behind).
 */
public class Hollow extends Monster {
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(Hollow.class, EntityDataSerializers.OPTIONAL_UUID);

    public Hollow(EntityType<? extends Hollow> type, Level level) {
        super(type, level);
        xpReward = 10;
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 30.0).add(Attributes.ATTACK_DAMAGE, 5.0).add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 28.0);
    }

    /** The body rises: a Hollow where it stood, the player's likeness. */
    static Hollow rise(FracturedBody body) {
        final Hollow hollow = new Hollow(AbyssalDepths.HOLLOW.get(), body.level());
        hollow.entityData.set(OWNER, body.owner());
        hollow.moveTo(body.getX(), body.getY(), body.getZ(), body.getYRot(), 0.0F);
        hollow.setPersistenceRequired();
        body.level().addFreshEntity(hollow);
        body.level().playSound(null, body.blockPosition(), SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 1.0F, 0.6F);
        return hollow;
    }

    public Optional<UUID> owner() {
        return entityData.get(OWNER);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(OWNER, Optional.empty());
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.1, false));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 10.0F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && tickCount % 40 == 1) {
            Fracturing.showCorrupted(this, 60); // drawn in the corruption
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!(level() instanceof ServerLevel server) || !(source.getEntity() instanceof Player)) {
            return;
        }
        final ServerPlayer owner = owner().map(id -> server.getServer().getPlayerList().getPlayer(id)).orElse(null);
        if (owner != null && Scars.of(owner) > 0) { // what it held goes back to its player
            Scars.set(owner, Scars.of(owner) - 1);
            owner.sendSystemMessage(Component.translatable("abyssaldepths.hollow.reclaimed"));
        } else {
            spawnAtLocation(new ItemStack(AbyssalDepths.CLEANSING_SHARD.get()));
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        owner().ifPresent(id -> tag.putUUID("Owner", id));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(OWNER, tag.hasUUID("Owner") ? Optional.of(tag.getUUID("Owner")) : Optional.empty());
    }
}
