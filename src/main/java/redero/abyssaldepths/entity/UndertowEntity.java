package redero.abyssaldepths.entity;

import java.util.UUID;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
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
import redero.abyssaldepths.artifact.Shades;

/**
 * Undertow's anomaly: a point that pulls the creatures within {@link #PULL_RADIUS} blocks in towards it for
 * a while (not players, nor its owner, nor what is across the shadow from the owner), then bursts, hurting
 * those near it (no blocks broken). Drawn as particles only; never saved (it lasts seconds).
 */
public class UndertowEntity extends Entity {
    public static final double PULL_RADIUS = 8.0, BURST_RADIUS = 4.5;

    private UUID owner;
    private int lifetime = 100;
    private float damage = 10.0F;

    public UndertowEntity(EntityType<? extends UndertowEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    /** Sets it going: who made it, how long it pulls (ticks) and what the burst does. */
    public void start(Entity owner, int lifetime, float damage) {
        this.owner = owner.getUUID();
        this.lifetime = lifetime;
        this.damage = damage;
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
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(PULL_RADIUS), e -> pulls(e, owner))) {
            final Vec3 towards = centre.subtract(entity.position().add(0, entity.getBbHeight() / 2, 0));
            final double distance = towards.length();
            if (distance > PULL_RADIUS || distance < 0.3) {
                continue;
            }
            // stronger the nearer (but never quite a grip), and against falling
            final double pull = 0.06 + 0.1 * (1.0 - distance / PULL_RADIUS);
            entity.setDeltaMovement(entity.getDeltaMovement().scale(0.8).add(towards.normalize().scale(pull)).add(0, 0.03, 0));
            entity.hurtMarked = true;
            entity.fallDistance = 0;
        }
        // a slow whirl closing in, and a dark core
        final double a = tickCount * 0.35;
        for (int arm = 0; arm < 3; arm++) {
            final double angle = a + arm * 2 * Math.PI / 3, r = 1.5 + 2.5 * ((tickCount * 0.07 + arm * 0.33) % 1.0);
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, getX() + Math.cos(angle) * r, getY() + Math.sin(tickCount * 0.2 + arm) * 0.4, getZ() + Math.sin(angle) * r,
                    1, 0, 0, 0, 0);
        }
        level.sendParticles(ParticleTypes.SQUID_INK, getX(), getY(), getZ(), 2, 0.15, 0.15, 0.15, 0.0);
        if (tickCount % 20 == 1) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.PORTAL_AMBIENT, SoundSource.PLAYERS, 0.6F, 0.5F + 0.3F * tickCount / lifetime);
        }
        if (tickCount >= lifetime) {
            burst(level, owner);
            discard();
        }
    }

    /** The burst: hurts and throws back what is near, no blocks broken. */
    private void burst(ServerLevel level, Entity owner) {
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(BURST_RADIUS), e -> pulls(e, owner))) {
            final double distance = entity.position().distanceTo(position());
            if (distance > BURST_RADIUS) {
                continue;
            }
            entity.hurt(level.damageSources().explosion(this, owner instanceof LivingEntity living ? living : null), damage * (float) Mth.clamp(1.2 - distance / BURST_RADIUS, 0.5, 1.0));
            final Vec3 away = entity.position().subtract(position()).normalize();
            entity.setDeltaMovement(entity.getDeltaMovement().add(away.x * 0.8, 0.45, away.z * 0.8));
            entity.hurtMarked = true;
        }
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY(), getZ(), 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, getX(), getY(), getZ(), 80, 0.5, 0.5, 0.5, 0.6);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.2F, 0.7F);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
