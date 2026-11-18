package redero.abyssaldepths.worldgen;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.DeepPressure;

/**
 * What the set pieces do while players are near (the rest is laid at generation):
 * <ul>
 * <li>the Stilled Fall ({@link FrozenFall}): pure darkness seeping out of it as a haze of dark motes, consuming light and
 * warmth: a light set down near it dies at once, and those close by freeze (leather keeps the cold out); the fall grows,
 * every new drop freezing as it falls;</li>
 * <li>the Wellspring ({@link GloamGrotto}): pure light rising through the lake as motes; standing in it eases Deep
 * Pressure; a glass bottle dipped in it fills with pure light;</li>
 * <li>the dead worm ({@link BuriedSkeleton.Live}): the storms strip the silt off it, the calm covers it again.</li>
 * </ul>
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class SetPieceLife {
    /** How far a stilled fall's darkness reaches (blocks from its axis). */
    private static final double DARK = 12.0, COLD = 8.0;
    private static final Map<Long, Object> FALLS = new HashMap<>(), SPRINGS = new HashMap<>(), WORMS = new HashMap<>();
    private static final Object NONE = new Object();
    private static final DustParticleOptions DARK_MOTE = new DustParticleOptions(new Vector3f(0.02F, 0.02F, 0.03F), 1.6F);

    private SetPieceLife() {
    }

    /** The spot of a kind in a cell, cached (NONE where there is none). */
    @SuppressWarnings("unchecked")
    private static <T> T cell(Map<Long, Object> cache, int gx, int gz, Function<long[], T> find) {
        final Object found = cache.computeIfAbsent(((long) gx << 32) ^ (gz & 0xffffffffL), k -> {
            final T t = find.apply(new long[] {gx, gz});
            return t == null ? NONE : t;
        });
        return found == NONE ? null : (T) found;
    }

    /** The stilled fall whose darkness reaches a place, or null. */
    static FrozenFall.Spot fallAt(TunnelFeature.Context c, double x, double z) {
        final int cx = Math.floorDiv((int) x, FrozenFall.CELL), cz = Math.floorDiv((int) z, FrozenFall.CELL);
        for (int gx = cx - 1; gx <= cx + 1; gx++) {
            for (int gz = cz - 1; gz <= cz + 1; gz++) {
                final FrozenFall.Spot s = cell(FALLS, gx, gz, g -> FrozenFall.spot(c.layer, c.biomes, (int) g[0], (int) g[1]));
                if (s != null && Math.hypot(s.x() - x, s.z() - z) < DARK + 8) {
                    return s;
                }
            }
        }
        return null;
    }

    /** The wellspring whose light reaches a place, or null. */
    static GloamGrotto.Spot springAt(TunnelFeature.Context c, double x, double z) {
        final int cx = Math.floorDiv((int) x, GloamGrotto.CELL), cz = Math.floorDiv((int) z, GloamGrotto.CELL);
        for (int gx = cx - 1; gx <= cx + 1; gx++) {
            for (int gz = cz - 1; gz <= cz + 1; gz++) {
                final GloamGrotto.Spot s = cell(SPRINGS, gx, gz, g -> GloamGrotto.spot(c.layer, c.biomes, (int) g[0], (int) g[1]));
                if (s != null && Math.hypot(s.x() - x, s.z() - z) < s.lake() + 3) {
                    return s;
                }
            }
        }
        return null;
    }

    private static BuriedSkeleton.Spot wormNear(TunnelFeature.Context c, double x, double z) {
        final int cx = Math.floorDiv((int) x, BuriedSkeleton.CELL), cz = Math.floorDiv((int) z, BuriedSkeleton.CELL);
        for (int gx = cx - 1; gx <= cx + 1; gx++) {
            for (int gz = cz - 1; gz <= cz + 1; gz++) {
                final BuriedSkeleton.Spot s = cell(WORMS, gx, gz, g -> BuriedSkeleton.spot(c.layer, c.biomes, (int) g[0], (int) g[1]));
                if (s != null && Math.hypot(s.x() - x, s.z() - z) < 120) {
                    return s;
                }
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        final ServerLevel level = event.getServer().overworld();
        if (level.players().isEmpty()) {
            return;
        }
        final TunnelFeature.Context c = TunnelFeature.Context.of(level);
        final java.util.Set<Object> done = new java.util.HashSet<>();
        for (ServerPlayer p : level.players()) {
            if (p.getY() > TunnelLayer.LAYER_TOP_Y + 8 || p.isSpectator() && level.getGameTime() % 2 == 1) {
                continue;
            }
            final FrozenFall.Spot fall = fallAt(c, p.getX(), p.getZ());
            if (fall != null) {
                if (done.add(fall)) {
                    stilled(level, fall);
                }
                cold(p, fall);
            }
            final GloamGrotto.Spot spring = springAt(c, p.getX(), p.getZ());
            if (spring != null) {
                if (done.add(spring)) {
                    rising(level, spring);
                }
                if (Math.abs(p.getY() - spring.floor()) < 8 && !p.isSpectator()) {
                    DeepPressure.ease(p, 40); // in the pure light, the pressure recedes
                }
            }
            final BuriedSkeleton.Spot worm = wormNear(c, p.getX(), p.getZ());
            if (worm != null && done.add(worm)) {
                BuriedSkeleton.Live.tick(level, c.layer, worm);
            }
        }
    }

    // ------------------------------------------------------------------ the stilled fall

    /** Where the fall's axis is at a height. */
    private static double[] axis(FrozenFall.Spot s, double y) {
        return new double[] {s.x() + 0.5 + s.leanX() * (s.ceiling() - y), s.z() + 0.5 + s.leanZ() * (s.ceiling() - y)};
    }

    /** The darkness seeping out (motes drifting off the ice), and the fall growing now and then. */
    private static void stilled(ServerLevel level, FrozenFall.Spot s) {
        for (int i = 0; i < 4; i++) {
            final double y = s.floor() + level.random.nextDouble() * (s.ceiling() - s.floor());
            final double[] a = axis(s, y);
            final double ang = level.random.nextDouble() * Math.PI * 2, r = s.width() + level.random.nextDouble() * 6;
            level.sendParticles(DARK_MOTE, a[0] + Math.cos(ang) * r, y, a[1] + Math.sin(ang) * r, 1, 0.3, 0.3, 0.3, 0.0);
        }
        if (level.getGameTime() % 1200 == 0) { // a drop froze as it fell: the foot grows by a block
            final double[] foot = axis(s, s.floor());
            final double ang = level.random.nextDouble() * Math.PI * 2, r = level.random.nextDouble() * (7.0 + s.width() * 1.5);
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(foot[0] + Math.cos(ang) * r, s.floor() + 6, foot[1] + Math.sin(ang) * r);
            if (!level.isLoaded(pos)) {
                return;
            }
            while (pos.getY() > s.floor() - 4 && level.getBlockState(pos).isAir()) {
                pos.move(0, -1, 0);
            }
            final BlockState top = level.getBlockState(pos);
            if (top.is(AbyssalDepths.DARK_ICE.get()) && pos.getY() < s.floor() + 4 && level.getBlockState(pos.above()).isAir()) {
                level.setBlock(pos.above(), AbyssalDepths.DARK_ICE.get().defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    /** Near the fall the darkness takes a body's warmth: they freeze (what keeps out powder snow's cold keeps this out). */
    private static void cold(ServerPlayer p, FrozenFall.Spot s) {
        final double[] a = axis(s, p.getY());
        if (p.isSpectator() || p.isCreative() || !p.canFreeze() || Math.hypot(p.getX() - a[0], p.getZ() - a[1]) > COLD
                || p.getY() < s.floor() - 3 || p.getY() > s.ceiling()) {
            return;
        }
        p.setTicksFrozen(Math.min(p.getTicksRequiredToFreeze() + 20, p.getTicksFrozen() + 4)); // (it thaws by 2 a tick on its own)
    }

    /** A light set down in a stilled fall's darkness dies at once (the gloam's own glow excepted). */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            return;
        }
        final BlockState placed = event.getPlacedBlock();
        final BlockPos at = event.getPos();
        if (placed.getLightEmission(level, at) <= 0 || at.getY() > TunnelLayer.LAYER_TOP_Y || placed.is(AbyssalDepths.GLOAM_CRYSTAL.get())
                || placed.is(AbyssalDepths.GLOAM_CLUSTER.get()) || placed.is(AbyssalDepths.GLOAM_MOSS.get()) || placed.is(AbyssalDepths.STILLED_GLOAM.get())) {
            return;
        }
        final FrozenFall.Spot s = fallAt(TunnelFeature.Context.of(level), at.getX(), at.getZ());
        if (s == null) {
            return;
        }
        final double[] a = axis(s, at.getY());
        if (Math.hypot(at.getX() + 0.5 - a[0], at.getZ() + 0.5 - a[1]) < DARK) {
            event.setCanceled(true);
            level.playSound(null, at, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.8F, 0.6F);
            level.sendParticles(ParticleTypes.SMOKE, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 8, 0.2, 0.2, 0.2, 0.01);
        }
    }

    // ------------------------------------------------------------------ the wellspring

    /** Pure light rising through the lake as motes. */
    private static void rising(ServerLevel level, GloamGrotto.Spot s) {
        for (int i = 0; i < 3; i++) {
            final double ang = level.random.nextDouble() * Math.PI * 2, r = Math.sqrt(level.random.nextDouble()) * s.lake();
            level.sendParticles(ParticleTypes.END_ROD, s.x() + 0.5 + Math.cos(ang) * r, s.floor() - 0.5, s.z() + 0.5 + Math.sin(ang) * r,
                    0, 0, 0.04 + level.random.nextDouble() * 0.03, 0, 1.0);
        }
    }

    /** A glass bottle dipped in the wellspring fills with pure light. */
    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickItem event) {
        final Player p = event.getEntity();
        final ItemStack held = event.getItemStack();
        if (!held.is(Items.GLASS_BOTTLE) || !(p.level() instanceof ServerLevel level) || p.getY() > TunnelLayer.LAYER_TOP_Y) {
            return;
        }
        final GloamGrotto.Spot s = springAt(TunnelFeature.Context.of(level), p.getX(), p.getZ());
        if (s == null || Math.hypot(p.getX() - s.x(), p.getZ() - s.z()) > s.lake() || Math.abs(p.getY() - s.floor()) > 4) {
            return;
        }
        held.shrink(1);
        final ItemStack vessel = new ItemStack(AbyssalDepths.VESSEL_OF_LIGHT.get());
        if (!p.getInventory().add(vessel)) {
            p.drop(vessel, false);
        }
        level.playSound(null, p.blockPosition(), SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 1.0F, 1.4F);
        level.playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.6F);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }
}
