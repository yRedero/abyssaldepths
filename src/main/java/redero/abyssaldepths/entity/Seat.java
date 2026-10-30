package redero.abyssaldepths.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.block.MeditationMatBlock;

/**
 * What a player sits on on a mat ({@link MeditationMatBlock}): invisible, it holds them low on the mat and
 * is gone as soon as they get up, or the mat is gone.
 */
public class Seat extends Entity {
    /**
     * How far above the mat's block bottom the seat is: a rider hangs 0.6 below their seat (the game's
     * player vehicle attachment) with the hips at the seat, so this puts the hips just on the mat.
     */
    private static final double HEIGHT = 0.1;

    public Seat(EntityType<? extends Seat> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    /** Sits the player down on the mat at pos; false if someone sits there already. */
    public static boolean sit(Level level, BlockPos pos, Player player) {
        final Vec3 at = Vec3.atBottomCenterOf(pos).add(0.0, HEIGHT, 0.0);
        if (!level.getEntitiesOfClass(Seat.class, new net.minecraft.world.phys.AABB(pos)).isEmpty()) {
            return false;
        }
        final Seat seat = new Seat(AbyssalDepths.SEAT.get(), level);
        seat.moveTo(at.x, at.y, at.z, player.getYRot(), 0.0F);
        level.addFreshEntity(seat);
        return player.startRiding(seat);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && (getPassengers().isEmpty() || !(level().getBlockState(blockPosition().above()).getBlock() instanceof MeditationMatBlock)
                && !(level().getBlockState(blockPosition()).getBlock() instanceof MeditationMatBlock))) {
            discard();
        }
    }

    /** Getting up: onto the mat, not into it. */
    @Override
    public Vec3 getDismountLocationForPassenger(net.minecraft.world.entity.LivingEntity passenger) {
        return Vec3.atBottomCenterOf(blockPosition().above()).add(0.0, 0.1, 0.0);
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
