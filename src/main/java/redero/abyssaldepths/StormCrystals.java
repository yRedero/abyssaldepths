package redero.abyssaldepths;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import redero.abyssaldepths.block.StormClusterBlock;
import redero.abyssaldepths.worldgen.TunnelLayer;

/**
 * Keeps the storm crystals of the dunes in step with the storm: while a dune storm rages the crystals in the dunes
 * round players are charged (they glow and can be broken), after it they are dull again; outside the dunes the storm
 * does not reach them, and they stay dull. A crystal placed meanwhile is brought in step too (its chunk looked at again). Chunks round each player are brought in
 * step a few at a time (remembering which already are), so nothing stalls when a storm starts. While charged, they
 * draw the storm's lightning: now and then one near a player crackles (sparks, a rising whine), then is struck,
 * hurting what stands close to it.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class StormCrystals {
    private static final int RADIUS_CHUNKS = 6, CHUNKS_PER_TICK = 8;
    /** Chunks already in step with the storm as it is now (forgotten when it turns). */
    private static final Set<Long> DONE = new HashSet<>();
    /** Charged crystals known (in the chunks brought in step), for the lightning. */
    private static final Set<BlockPos> CHARGED = new HashSet<>();
    private static final List<Strike> STRIKES = new ArrayList<>();
    private static boolean storming;

    private record Strike(ServerLevel level, BlockPos at, long when) {
    }

    /** Warning before a strike lands (ticks), how far it hurts, how much. */
    private static final int WARN_TICKS = 40;
    private static final double HURT_RADIUS = 3.0;
    private static final float DAMAGE = 6.0F;

    private StormCrystals() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        final boolean now = DuneStorm.remaining() > 0;
        if (now != storming) { // the storm turned: every chunk to be brought in step again
            storming = now;
            DONE.clear();
            if (!now) {
                STRIKES.clear();
                CHARGED.clear();
            }
        }
        final ServerLevel level = event.getServer().overworld();
        final long time = level.getGameTime();
        if (time % 5 == 0) {
            int budget = CHUNKS_PER_TICK;
            for (ServerPlayer player : level.players()) {
                if (player.getY() > TunnelLayer.MAX_AIR_Y + 40) {
                    continue; // far above the layer: nothing of it near
                }
                final ChunkPos centre = player.chunkPosition();
                for (int r = 0; r <= RADIUS_CHUNKS && budget > 0; r++) { // near first
                    for (int dz = -r; dz <= r && budget > 0; dz++) {
                        for (int dx = -r; dx <= r && budget > 0; dx++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                                continue;
                            }
                            final long key = ChunkPos.asLong(centre.x + dx, centre.z + dz);
                            if (DONE.contains(key) || !level.hasChunk(centre.x + dx, centre.z + dz)) {
                                continue;
                            }
                            bringInStep(level, level.getChunk(centre.x + dx, centre.z + dz));
                            DONE.add(key);
                            budget--;
                        }
                    }
                }
            }
        }
        if (now) {
            lightning(level, time);
        }
    }

    /** Sets every storm crystal of a chunk (in the layer's heights) to the storm as it is. */
    private static void bringInStep(ServerLevel level, LevelChunk chunk) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            final int baseY = SectionPos.sectionToBlockCoord(level.getSectionYFromSectionIndex(i));
            if (baseY + 15 < TunnelLayer.MIN_AIR_Y - 2 || baseY > TunnelLayer.MAX_AIR_Y + 4) {
                continue;
            }
            final LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> s.hasProperty(StormClusterBlock.CHARGED))) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final BlockState state = section.getBlockState(x, y, z);
                        if (!state.hasProperty(StormClusterBlock.CHARGED)) {
                            continue;
                        }
                        pos.set(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z);
                        final boolean charge = storming && level.getBiome(pos).is(AbyssalDepths.SILT_DUNES); // the storm blows only there
                        if (state.getValue(StormClusterBlock.CHARGED) != charge) {
                            level.setBlock(pos, state.setValue(StormClusterBlock.CHARGED, charge), 2);
                        }
                        if (charge && state.is(AbyssalDepths.STORM_CRYSTAL.get())
                                && !level.getBlockState(pos.above()).is(AbyssalDepths.STORM_CRYSTAL.get())) {
                            CHARGED.add(pos.immutable()); // the lightning goes for the spires' tips
                        } else {
                            CHARGED.remove(pos);
                        }
                    }
                }
            }
        }
    }

    /** The storm's lightning, drawn to charged crystals near players: warned, then struck. */
    private static void lightning(ServerLevel level, long time) {
        for (Iterator<Strike> it = STRIKES.iterator(); it.hasNext(); ) {
            final Strike strike = it.next();
            final BlockState state = strike.level().getBlockState(strike.at());
            if (!state.hasProperty(StormClusterBlock.CHARGED)) {
                it.remove(); // broken off meanwhile: nothing draws it now
                continue;
            }
            final long left = strike.when() - time;
            if (left > 0) { // crackling: sparks round it, faster as it nears
                if (left % (left > 20 ? 6 : 3) == 0) {
                    strike.level().sendParticles(ParticleTypes.ELECTRIC_SPARK, strike.at().getX() + 0.5, strike.at().getY() + 0.6, strike.at().getZ() + 0.5,
                            6, 0.4, 0.5, 0.4, 0.05);
                    strike.level().playSound(null, strike.at(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.WEATHER, 0.8F,
                            1.4F + 0.6F * (1.0F - left / (float) WARN_TICKS));
                }
                continue;
            }
            it.remove();
            final LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(strike.level());
            if (bolt != null) {
                bolt.moveTo(Vec3.atBottomCenterOf(strike.at().above()));
                bolt.setVisualOnly(true); // it sets nothing alight; the crystal takes it
                strike.level().addFreshEntity(bolt);
            }
            final Vec3 c = Vec3.atCenterOf(strike.at());
            for (LivingEntity hit : strike.level().getEntitiesOfClass(LivingEntity.class, new AABB(strike.at()).inflate(HURT_RADIUS),
                    e -> e.position().distanceTo(c) <= HURT_RADIUS)) {
                hit.hurt(strike.level().damageSources().lightningBolt(), DAMAGE);
                final Vec3 away = hit.position().subtract(c).multiply(1, 0, 1);
                if (away.lengthSqr() > 1.0E-4) {
                    hit.push(away.normalize().x * 0.8, 0.35, away.normalize().z * 0.8);
                    hit.hurtMarked = true;
                }
            }
        }
        if (time % 20 != 0 || CHARGED.isEmpty()) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (level.random.nextInt(5) != 0) {
                continue; // now and then, not every second
            }
            final List<BlockPos> near = new ArrayList<>();
            for (BlockPos at : CHARGED) {
                if (at.distSqr(player.blockPosition()) < 28 * 28) {
                    near.add(at);
                }
            }
            if (!near.isEmpty()) {
                final BlockPos at = near.get(level.random.nextInt(near.size()));
                if (STRIKES.stream().noneMatch(s -> s.at().equals(at))) {
                    STRIKES.add(new Strike(level, at, time + WARN_TICKS));
                }
            }
        }
    }

    /** A storm crystal placed: its chunk is looked at again, so it takes the storm's charge (or loses it) like the rest. */
    @SubscribeEvent
    public static void onPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (event.getPlacedBlock().hasProperty(StormClusterBlock.CHARGED)) {
            DONE.remove(ChunkPos.asLong(event.getPos()));
        }
    }

    /** Whether a dune storm charges the crystals now (for the dangers that come with it). */
    static boolean storming() {
        return storming;
    }

    /** For tests: how many charged crystals are known. */
    public static int knownCharged() {
        return CHARGED.size();
    }
}
