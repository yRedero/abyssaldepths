package redero.abyssaldepths.fracture;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * The corrupted core of {@link CoreTrial}: a black heart in a shell of white plates, floating over its arena.
 * What it does (its state, since when, how much of it is left) the trial decides and the clients draw
 * ({@code client.FractureCoreRenderer}); while its shell is open, a blow from the player cracks it. Never saved.
 */
public class FractureCore extends Entity {
    /** What it is doing: idle, the shockwave, the sweep, the barrage, open, warning of what comes next. */
    public static final int IDLE = 0, SHOCKWAVE = 1, SWEEP = 2, BARRAGE = 3, OPEN = 4, WARN = 5;
    private static final EntityDataAccessor<Integer> NEXT = SynchedEntityData.defineId(FractureCore.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(FractureCore.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SINCE = SynchedEntityData.defineId(FractureCore.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> LEFT = SynchedEntityData.defineId(FractureCore.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> SWEEP_FROM = SynchedEntityData.defineId(FractureCore.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> PACE = SynchedEntityData.defineId(FractureCore.class, EntityDataSerializers.FLOAT);
    /** Blows it takes. */
    public static final int STRENGTH = 10;

    private int cooldown;
    /** Blows taken since its shell last opened (one is all it takes: then it closes). */
    private int openBlows;
    /** Blows taken since the trial last looked. */
    int struck;

    public FractureCore(EntityType<? extends FractureCore> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    public int state() {
        return entityData.get(STATE);
    }

    /** The tick count at which the state began. */
    public int since() {
        return entityData.get(SINCE);
    }

    public int left() {
        return entityData.get(LEFT);
    }

    /** Where the sweep starts (radians). */
    public float sweepFrom() {
        return entityData.get(SWEEP_FROM);
    }

    /** How much quicker than at first its attacks run (it speeds up each round). */
    public float pace() {
        return entityData.get(PACE);
    }

    /** While warning: the attack that comes. */
    public int next() {
        return entityData.get(NEXT);
    }

    void set(int state, float sweepFrom, float pace, int next) {
        openBlows = 0;
        entityData.set(NEXT, next);
        entityData.set(STATE, state);
        entityData.set(SINCE, tickCount);
        entityData.set(SWEEP_FROM, sweepFrom);
        entityData.set(PACE, pace);
    }

    void setLeft(int left) {
        entityData.set(LEFT, left);
    }

    @Override
    public void tick() {
        super.tick();
        if (cooldown > 0) {
            cooldown--;
        }
    }

    /** A blow: it only cracks while its shell is open, and not twice in a moment. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || !(source.getEntity() instanceof Player) || state() != OPEN || cooldown > 0 || openBlows > 0 || left() <= 0) {
            if (!level().isClientSide && source.getEntity() instanceof Player && state() != OPEN) {
                level().playSound(null, blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 0.8F, 1.6F); // the shell holds
            }
            return false;
        }
        cooldown = 6;
        openBlows++;
        struck++;
        setLeft(left() - 1);
        level().playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 1.2F, 0.8F + 0.04F * left());
        return true;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isAttackable() {
        return true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(STATE, IDLE);
        builder.define(SINCE, 0);
        builder.define(LEFT, STRENGTH);
        builder.define(SWEEP_FROM, 0.0F);
        builder.define(PACE, 1.0F);
        builder.define(NEXT, IDLE);
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
