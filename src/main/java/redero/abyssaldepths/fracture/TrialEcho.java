package redero.abyssaldepths.fracture;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.entity.AbsentBody;

/**
 * An echo of the player on the threshold of their trial ({@link Threshold}): a pale, see-through figure in their
 * shape that acts out one of the trial's rules again and again (the threshold moves it). It faces where it is turned,
 * can crouch and swing, and is touched by nothing.
 */
public class TrialEcho extends AbsentBody {
    private static final EntityDataAccessor<Float> FACING = SynchedEntityData.defineId(TrialEcho.class, EntityDataSerializers.FLOAT);

    public TrialEcho(EntityType<? extends TrialEcho> type, Level level) {
        super(type, level);
    }

    static TrialEcho of(ServerPlayer player, ServerLevel level, Vec3 at, float yaw) {
        final TrialEcho echo = new TrialEcho(AbyssalDepths.TRIAL_ECHO.get(), level);
        echo.dressAs(player);
        echo.moveTo(at.x, at.y, at.z, yaw, 0.0F);
        echo.entityData.set(FACING, yaw);
        level.addFreshEntity(echo);
        return echo;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(FACING, 0.0F);
    }

    /** Turns it to look along a direction. */
    void faceTowards(Vec3 target) {
        final Vec3 d = target.subtract(position());
        if (d.horizontalDistanceSqr() > 1.0E-4) {
            entityData.set(FACING, (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F);
        }
    }

    void crouch(boolean down) {
        setPose(down ? Pose.CROUCHING : Pose.STANDING);
    }

    @Override
    public void tick() {
        super.tick();
        final float facing = entityData.get(FACING);
        setYRot(facing);
        setYHeadRot(facing);
        yBodyRot = yBodyRotO = facing;
    }

    /** Its swings play out (a plain living thing does not count its swing on by itself). */
    @Override
    public void aiStep() {
        super.aiStep();
        updateSwingTime();
    }

    @Override
    protected boolean easesHead() {
        return false;
    }

    @Override
    public boolean bobs() {
        return false;
    }

    @Override
    protected boolean shouldVanish(Player owner) {
        return owner == null || !FractureTrials.busy(owner);
    }
}
