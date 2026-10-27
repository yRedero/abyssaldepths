package redero.abyssaldepths.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Quicksilt of the silt dunes: loose, waterlogged-looking silt in the dips between the dunes. Living
 * things walk into it and slowly sink; holding jump climbs out just as slowly. Once the head is under,
 * it suffocates like sand. Items and everything else rest on top.
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
            if (level.isClientSide && level.random.nextInt(6) == 0 && entity.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4) {
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), entity.getX(), pos.getY() + 1.0, entity.getZ(),
                        0.0, 0.0, 0.0);
            }
        }
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
