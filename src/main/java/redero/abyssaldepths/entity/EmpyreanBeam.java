package redero.abyssaldepths.entity;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import redero.abyssaldepths.artifact.Shades;

/**
 * Empyrean's beam: a column of light falling from the sky onto a point. First a thin warning beam for
 * {@link #WARN_TICKS} (time to run), then it burns for {@link #BURN_TICKS}, hurting everything in it
 * ({@link #RADIUS} round its middle, from the sky down through the point and {@link #DEPTH} blocks on; not its
 * owner, nor what is across the shadow from them) in {@link #HITS} blows that add up to its damage. It goes through blocks, but each solid
 * block above a creature takes a little off ({@link #COVER}, one after another). Locked onto a creature, it
 * follows it. Drawn by {@code client.EmpyreanRenderer}; never saved (it lasts seconds).
 */
public class EmpyreanBeam extends Entity {
    public static final int WARN_TICKS = 30, BURN_TICKS = 40, FADE_TICKS = 10, HITS = 4;
    public static final double RADIUS = 1.3;
    /** How far it goes on down through where it lands (into the ground, under the creature it follows). */
    public static final int DEPTH = 48;
    /** What is left of the damage under each solid block above. */
    public static final double COVER = 0.96;

    private UUID owner;
    private int target = -1;
    private float damage = 40.0F;

    public EmpyreanBeam(EntityType<? extends EmpyreanBeam> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    /** Sets it going: who called it down, its whole damage, and what it is locked onto (null: the point). */
    public void start(Entity owner, float damage, Entity target) {
        this.owner = owner.getUUID();
        this.damage = damage;
        this.target = target == null ? -1 : target.getId();
    }

    public boolean burning() {
        return tickCount >= WARN_TICKS && tickCount < WARN_TICKS + BURN_TICKS;
    }

    private Entity owner() {
        return owner != null && level() instanceof ServerLevel server ? server.getEntity(owner) : null;
    }

    /** Solid blocks between the point and the sky: each takes a little off. */
    private int cover(LivingEntity entity) {
        final Level level = level();
        int blocks = 0;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = (int) Math.floor(entity.getEyeY()) + 1; y < level.getMaxBuildHeight(); y++) {
            pos.set(entity.getBlockX(), y, entity.getBlockZ());
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                blocks++;
            }
        }
        return blocks;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            if (burning() && random.nextInt(2) == 0) { // embers and sparks where it falls
                level().addParticle(random.nextBoolean() ? ParticleTypes.FLAME : ParticleTypes.END_ROD, getX() + (random.nextDouble() - 0.5) * 2.0, getY() + 0.1,
                        getZ() + (random.nextDouble() - 0.5) * 2.0, 0.0, 0.08 + random.nextDouble() * 0.1, 0.0);
            }
            return;
        }
        final ServerLevel level = (ServerLevel) level();
        if (target >= 0 && tickCount < WARN_TICKS + BURN_TICKS) { // locked on: it follows
            final Entity followed = level.getEntity(target);
            if (followed != null && followed.isAlive()) {
                moveTo(followed.getX(), followed.getY(), followed.getZ());
            }
        }
        if (tickCount == 1) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 2.0F, 1.6F);
        } else if (tickCount == WARN_TICKS) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 3.0F, 1.4F);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 2.0F, 0.6F);
        } else if (burning() && tickCount % 20 == 0) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 2.0F, 0.5F);
        }
        if (burning() && (tickCount - WARN_TICKS) % (BURN_TICKS / HITS) == 0) {
            burn(level);
        }
        if (tickCount >= WARN_TICKS + BURN_TICKS + FADE_TICKS) {
            discard();
        }
    }

    /** One blow to everything in the column, less under cover; it sets them alight. */
    private void burn(ServerLevel level) {
        final Entity owner = owner();
        final AABB column = new AABB(getX() - RADIUS, getY() - DEPTH, getZ() - RADIUS, getX() + RADIUS, level.getMaxBuildHeight(), getZ() + RADIUS);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, column, e -> e.isAlive() && e != owner && !(e instanceof ArmorStand)
                && !(e instanceof Player p && (p.isCreative() || p.isSpectator())) && (owner == null || !Shades.apart(owner, e)))) {
            final double dx = entity.getX() - getX(), dz = entity.getZ() - getZ();
            if (dx * dx + dz * dz > (RADIUS + entity.getBbWidth() / 2) * (RADIUS + entity.getBbWidth() / 2)) {
                continue;
            }
            final float blow = (float) (damage / HITS * Math.pow(COVER, cover(entity)));
            entity.hurt(level.damageSources().indirectMagic(this, owner), blow);
            entity.igniteForSeconds(4.0F);
        }
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 512 * 512; // seen from far off
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

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
