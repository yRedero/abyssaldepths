package redero.abyssaldepths.fracture;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.AbyssalDepths;

/**
 * The player's corrupted double in their trial ({@link ReachTrial}): their likeness in the corruption, gliding
 * after them (the trial moves it), always facing them. A blow staggers it (the trial is told); nothing hurts it.
 */
public class FractureDouble extends FracturedBody {
    private static final EntityDataAccessor<Float> FACING = SynchedEntityData.defineId(FractureDouble.class, EntityDataSerializers.FLOAT);
    private boolean staggered;

    public FractureDouble(EntityType<? extends FractureDouble> type, Level level) {
        super(type, level);
    }

    static FractureDouble of(ServerPlayer player, ServerLevel level, Vec3 at) {
        final FractureDouble twin = new FractureDouble(AbyssalDepths.FRACTURE_DOUBLE.get(), level);
        twin.dressAs(player);
        twin.moveTo(at.x, at.y, at.z, 180.0F, 0.0F);
        level.addFreshEntity(twin);
        return twin;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(FACING, 180.0F);
    }

    /** Turns it to look at a place. */
    void face(Vec3 target) {
        final Vec3 d = target.subtract(position());
        entityData.set(FACING, (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F);
    }

    /** Whether it was struck since the trial last asked. */
    boolean takeStagger() {
        final boolean was = staggered;
        staggered = false;
        return was;
    }

    @Override
    public void tick() {
        super.tick();
        final float facing = entityData.get(FACING);
        setYRot(facing);
        setYHeadRot(facing);
        yBodyRot = yBodyRotO = facing;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide && source.getEntity() instanceof Player) {
            staggered = true;
            level().playSound(null, blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 0.8F, 0.6F);
        }
        return false;
    }

    @Override
    protected boolean shouldVanish(Player owner) {
        return owner == null || !FractureTrials.busy(owner);
    }
}
