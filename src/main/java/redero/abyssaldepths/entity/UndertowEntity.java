package redero.abyssaldepths.entity;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.Shades;

/**
 * Undertow's anomaly: a point that pulls the creatures within {@link #PULL_RADIUS} blocks in towards it for
 * a while (not players, nor its owner, nor what is across the shadow from the owner). It shows as an event
 * horizon (a black sphere growing, a glowing ring turning round it; drawn by {@code UndertowRenderer}), and
 * in its last half second collapses into a bright point while the sounds around fall silent
 * ({@link #COLLAPSE}); then it bursts, hurting those near it, in an explosion of its own (no blocks broken,
 * {@link #BURST}; drawn by {@code UndertowClient}). A mastered one leaves a smaller second anomaly behind,
 * which pulls once more. Never saved (it lasts seconds).
 */
public class UndertowEntity extends Entity {
    public static final double PULL_RADIUS = 8.0, BURST_RADIUS = 4.5;
    /** Ticks of the collapse before the burst. */
    public static final int COLLAPSE_TICKS = 10;
    /** Entity events: the collapse starts (silence), the burst. */
    public static final byte COLLAPSE = 78, BURST = 77;
    private static final EntityDataAccessor<Integer> LIFETIME = SynchedEntityData.defineId(UndertowEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> SIZE = SynchedEntityData.defineId(UndertowEntity.class, EntityDataSerializers.FLOAT);

    private UUID owner;
    private float damage = 10.0F;
    private boolean secondWave;

    public UndertowEntity(EntityType<? extends UndertowEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    /** Sets it going: who made it, how long it pulls (ticks), what the burst does, whether a second wave follows. */
    public void start(Entity owner, int lifetime, float damage, boolean secondWave) {
        this.owner = owner.getUUID();
        this.damage = damage;
        this.secondWave = secondWave;
        entityData.set(LIFETIME, lifetime);
    }

    /** How long it pulls, in ticks (the clients know it too, for the drawing). */
    public int lifetime() {
        return entityData.get(LIFETIME);
    }

    /** 1 for an anomaly, smaller for the second wave. */
    public float size() {
        return entityData.get(SIZE);
    }

    private Entity owner() {
        return owner != null && level() instanceof ServerLevel server ? server.getEntity(owner) : null;
    }

    private boolean pulls(LivingEntity entity, Entity owner) {
        return entity.isAlive() && !(entity instanceof Player) && !(entity instanceof ArmorStand) && entity != owner
                && (owner == null || !Shades.apart(owner, entity));
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        final Entity owner = owner();
        final Vec3 centre = position();
        final double radius = PULL_RADIUS * size();
        final boolean collapsing = tickCount > lifetime() - COLLAPSE_TICKS;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(radius), e -> pulls(e, owner))) {
            final Vec3 towards = centre.subtract(entity.position().add(0, entity.getBbHeight() / 2, 0));
            final double distance = towards.length();
            if (distance > radius || distance < 0.3) {
                continue;
            }
            // stronger the nearer (but never quite a grip), and against falling; held fast while it collapses
            final double pull = collapsing ? 0.25 : 0.06 + 0.1 * (1.0 - distance / radius);
            entity.setDeltaMovement(entity.getDeltaMovement().scale(collapsing ? 0.3 : 0.8).add(towards.normalize().scale(pull)).add(0, 0.03, 0));
            entity.hurtMarked = true;
            entity.fallDistance = 0;
        }
        if (tickCount % 20 == 1 && !collapsing) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.PORTAL_AMBIENT, SoundSource.PLAYERS, 0.6F, 0.5F + 0.3F * tickCount / lifetime());
        }
        if (tickCount == lifetime() - COLLAPSE_TICKS) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.5F, 0.5F);
            level.broadcastEntityEvent(this, COLLAPSE); // the clients near fall silent until the burst
        }
        if (tickCount >= lifetime()) {
            burst(level, owner);
            discard();
        }
    }

    /** The burst: hurts and throws back what is near, no blocks broken; its look is the clients' ({@link #BURST}). */
    private void burst(ServerLevel level, Entity owner) {
        final double radius = BURST_RADIUS * size();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(radius), e -> pulls(e, owner))) {
            final double distance = entity.position().distanceTo(position());
            if (distance > radius) {
                continue;
            }
            entity.hurt(level.damageSources().explosion(this, owner instanceof LivingEntity living ? living : null), damage * (float) Mth.clamp(1.2 - distance / radius, 0.5, 1.0));
            final Vec3 away = entity.position().subtract(position()).normalize();
            entity.setDeltaMovement(entity.getDeltaMovement().add(away.x * 0.9, 0.5, away.z * 0.9));
            entity.hurtMarked = true;
        }
        level.broadcastEntityEvent(this, BURST);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2.0F, 0.6F);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.4F, 0.5F);
        if (secondWave) { // mastered: what is left pulls once more, smaller
            final UndertowEntity second = new UndertowEntity(AbyssalDepths.UNDERTOW_ENTITY.get(), level);
            second.moveTo(getX(), getY(), getZ());
            second.entityData.set(SIZE, 0.6F);
            if (owner != null) {
                second.start(owner, 60, damage * 0.5F, false);
            } else {
                second.entityData.set(LIFETIME, 60);
                second.damage = damage * 0.5F;
            }
            level.addFreshEntity(second);
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == COLLAPSE) {
            redero.abyssaldepths.client.UndertowClient.silence(position());
        } else if (id == BURST) {
            redero.abyssaldepths.client.UndertowClient.burst(position(), size());
        } else {
            super.handleEntityEvent(id);
        }
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(LIFETIME, 100);
        builder.define(SIZE, 1.0F);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
