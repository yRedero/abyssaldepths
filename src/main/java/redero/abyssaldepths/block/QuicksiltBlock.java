package redero.abyssaldepths.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Quicksilt of the silt dunes: loose, waterlogged-looking silt in the dips between the dunes. Living
 * things walk into it and slowly sink; holding jump climbs out just as slowly. Once the head is under,
 * it suffocates like sand. Items and everything else rest on top. Mobs see the danger and walk around it.
 */
public class QuicksiltBlock extends Block {
    /** Movement inside: slowed sideways, a slow sink, and climbing out is slow too. */
    private static final Vec3 STUCK = new Vec3(0.45, 0.12, 0.45);

    public QuicksiltBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext entityContext && entityContext.getEntity() instanceof LivingEntity) {
            return Shapes.empty();
        }
        return super.getCollisionShape(state, level, pos, context);
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (entity instanceof LivingEntity) {
            entity.makeStuckInBlock(state, STUCK);
            if (entity instanceof Mob mob && !level.isClientSide) { // a creature struggles out (as a player would, holding jump), towards firm ground
                mob.setJumping(true);
                if (mob.tickCount % 10 == 0) {
                    final BlockPos firm = firm(level, pos);
                    if (firm != null) {
                        mob.getMoveControl().setWantedPosition(firm.getX() + 0.5, firm.getY(), firm.getZ() + 0.5, 1.0);
                    }
                }
            }
            if (level.isClientSide && level.random.nextInt(6) == 0 && entity.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4) {
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), entity.getX(), pos.getY() + 1.0, entity.getZ(),
                        0.0, 0.0, 0.0);
            }
        }
    }

    /** The nearest firm ground beside quicksilt (solid, not quicksilt, room above), or null. */
    private static BlockPos firm(Level level, BlockPos from) {
        BlockPos best = null;
        double near = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(from.offset(-4, -1, -4), from.offset(4, 2, 4))) {
            final BlockState below = level.getBlockState(p.below());
            if (below.is(state0()) || !below.isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)
                    || !level.getBlockState(p).getCollisionShape(level, p).isEmpty() || level.getBlockState(p).is(state0())
                    || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
                continue;
            }
            final double d = p.distSqr(from);
            if (d < near) {
                near = d;
                best = p.immutable();
            }
        }
        return best;
    }

    private static Block state0() {
        return redero.abyssaldepths.AbyssalDepths.QUICKSILT.get();
    }

    /** Mobs never path into it, and keep away from its edge. */
    @Override
    public PathType getBlockPathType(BlockState state, BlockGetter level, BlockPos pos, @Nullable Mob mob) {
        return PathType.DAMAGE_OTHER;
    }

    @Override
    public PathType getAdjacentBlockPathType(BlockState state, BlockGetter level, BlockPos pos, @Nullable Mob mob, PathType originalType) {
        return originalType == PathType.OPEN || originalType == PathType.WALKABLE ? PathType.DANGER_OTHER : originalType;
    }

    /** Like a ladder: holding jump climbs out, slowed by being stuck. */
    @Override
    public boolean isLadder(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity) {
        return true;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        // now and then the surface settles a little
        if (random.nextInt(40) == 0 && level.getBlockState(pos.above()).isAir()) {
            level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + random.nextDouble(), pos.getY() + 1.02,
                    pos.getZ() + random.nextDouble(), 0.0, 0.0, 0.0);
        }
    }
}
