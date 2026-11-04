package redero.abyssaldepths.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Vent of the thermal reaches. Every vent lets a little steam rise now and then, rarely with a soft
 * hiss (client side only, like campfire smoke). Some vents are {@link #ACTIVE}: every 20 to 40
 * seconds they hiss as a warning and then blow a burst of hot steam that hurts whatever stands
 * directly above them (1 heart, fire resistance protects). Placed vents are always calm.
 * <p>
 * Vents of a vent lung have a {@link #WAVE}: the ring they stand in, counted from the lung's middle. They do not
 * keep their own time but the world's: every {@link #BREATH} ticks the whole field hisses, then bursts ring after
 * ring outwards from the middle, a breath rolling across it. So every lung vent agrees without knowing the others.
 */
public class ThermalVentBlock extends Block {
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    /** The warning phase before a burst. */
    public static final BooleanProperty ERUPTING = BlockStateProperties.TRIGGERED;
    /** 0: an ordinary vent; 1 to 15: a vent of a lung, in that ring from its middle. */
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty WAVE =
            net.minecraft.world.level.block.state.properties.IntegerProperty.create("wave", 0, 15);
    /** A lung's breath (ticks from one to the next) and how long the burst takes to roll one ring further. */
    public static final int BREATH = 400, RING_TICKS = 6;
    public static final ResourceKey<DamageType> STEAM =
            ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "steam"));

    private static final int WARNING_TICKS = 30, PAUSE_MIN = 400, PAUSE_VAR = 400;
    private static final float DAMAGE = 2.0F;
    private static final double REACH = 3.0, LUNG_REACH = 5.0; // blocks of steam above the vent

    public ThermalVentBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false).setValue(ERUPTING, false).setValue(WAVE, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE, ERUPTING, WAVE);
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return state.getValue(ACTIVE) || state.getValue(WAVE) > 0;
    }

    /** Ticks from now until a lung vent of this ring has to start its warning, to burst in time with the breath. */
    public static int untilBreath(long now, int wave) {
        final long burst = (long) (wave - 1) * RING_TICKS; // when in the breath this ring bursts
        int delay = (int) Math.floorMod(burst - WARNING_TICKS - now, (long) BREATH);
        if (delay < 20) {
            delay += BREATH;
        }
        return delay;
    }

    /** Vents from world generation get no scheduled tick; a random tick starts their cycle. */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.getBlockTicks().hasScheduledTick(pos, this)) {
            level.scheduleTick(pos, this, state.getValue(WAVE) > 0 ? untilBreath(level.getGameTime(), state.getValue(WAVE))
                    : PAUSE_MIN + random.nextInt(PAUSE_VAR));
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        final int wave = state.getValue(WAVE);
        if (!state.getValue(ACTIVE) && wave == 0) {
            return;
        }
        if (wave > 0 && !state.getValue(ERUPTING) && Math.floorMod((long) (wave - 1) * RING_TICKS - WARNING_TICKS - level.getGameTime(), (long) BREATH) != 0) {
            level.scheduleTick(pos, this, untilBreath(level.getGameTime(), wave)); // off the beat (first tick, or the time was changed): wait for it
            return;
        }
        if (!state.getValue(ERUPTING)) {
            level.setBlock(pos, state.setValue(ERUPTING, true), Block.UPDATE_CLIENTS);
            level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 0.6F);
            level.scheduleTick(pos, this, WARNING_TICKS);
            return;
        }
        // the burst
        level.setBlock(pos, state.setValue(ERUPTING, false), Block.UPDATE_CLIENTS);
        final double reach = wave > 0 ? LUNG_REACH : REACH;
        level.playSound(null, pos, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, wave > 0 ? 1.2F : 0.9F, wave > 0 ? 0.5F : 0.7F);
        level.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 1.0 + reach / 2, pos.getZ() + 0.5, wave > 0 ? 40 : 25,
                0.2, reach / 4, 0.2, 0.05);
        if (level.getBlockState(pos.above()).isAir()) {
            final AABB steam = new AABB(pos.getX(), pos.getY() + 1, pos.getZ(), pos.getX() + 1, pos.getY() + 1 + reach, pos.getZ() + 1);
            for (Entity entity : level.getEntities(null, steam)) {
                entity.hurt(level.damageSources().source(STEAM), DAMAGE);
            }
        }
        level.scheduleTick(pos, this, wave > 0 ? untilBreath(level.getGameTime(), wave) : PAUSE_MIN + random.nextInt(PAUSE_VAR));
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!level.getBlockState(pos.above()).isAir()) {
            return;
        }
        if (state.getValue(ERUPTING)) { // building up: lots of steam
            for (int i = 0; i < 4; i++) {
                level.addParticle(ParticleTypes.WHITE_SMOKE, pos.getX() + 0.2 + random.nextDouble() * 0.6, pos.getY() + 1.02,
                        pos.getZ() + 0.2 + random.nextDouble() * 0.6, 0.0, 0.08 + random.nextDouble() * 0.06, 0.0);
            }
            return;
        }
        if (state.getValue(WAVE) > 0 && random.nextInt(4) != 0) {
            return; // a lung lies quiet between its breaths
        }
        final int puffs = 1 + random.nextInt(2);
        for (int i = 0; i < puffs; i++) {
            level.addParticle(ParticleTypes.WHITE_SMOKE, pos.getX() + 0.3 + random.nextDouble() * 0.4, pos.getY() + 1.02,
                    pos.getZ() + 0.3 + random.nextDouble() * 0.4, 0.0, 0.03 + random.nextDouble() * 0.04, 0.0);
        }
        if (random.nextInt(10) == 0) { // now and then a taller plume
            level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                    0.0, 0.02, 0.0);
        }
        if (random.nextInt(40) == 0) {
            level.playLocalSound(pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.06F, 0.5F + random.nextFloat() * 0.3F, false);
        }
    }
}
