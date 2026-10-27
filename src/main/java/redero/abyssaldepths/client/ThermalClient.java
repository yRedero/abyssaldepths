package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.worldgen.TunnelLayer;

/**
 * The heat of the thermal reaches, client side:
 * <ul>
 * <li>Steam you can see: vents send up tall, long-lived plumes (they stop and spread under the
 * ceiling), now and then a slow haze drifts along the ceiling, warm pools steam and bubble.</li>
 * <li>Heat you feel: a faint amber glow at the screen edges, stronger near smouldering rock and vents.</li>
 * <li>Warm air: {@link #warmth()} tints the fog of {@link ThalanFog} reddish brown.</li>
 * </ul>
 * Vents, smouldering rock and pool surfaces around the player are found by a scan every two
 * seconds that skips every chunk section that cannot contain them; no block entity per vent.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class ThermalClient {
    private static final ResourceLocation VIGNETTE =
            ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "textures/misc/heat_vignette.png");
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int SCAN_EVERY = 40, MAX_SOURCES = 160;
    private static final double PLUME_RANGE = 40.0, HEAT_RANGE = 10.0;

    private static final List<BlockPos> VENTS = new ArrayList<>();
    private static final List<BlockPos> HOT = new ArrayList<>();
    private static final List<BlockPos> POOLS = new ArrayList<>();
    private static int scanCooldown;
    private static float heat, warmth;

    private ThermalClient() {
    }

    /** 0 .. 1: how warm the air around the camera is (biome and nearby heat), eased over a few seconds. */
    public static float warmth() {
        return warmth;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc.player;
        final ClientLevel level = mc.level;
        if (player == null || level == null) {
            VENTS.clear();
            HOT.clear();
            POOLS.clear();
            heat = warmth = 0.0F;
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        final boolean deep = player.getBlockY() <= TunnelLayer.LAYER_TOP_Y + 8;
        if (deep && --scanCooldown <= 0) {
            scanCooldown = SCAN_EVERY;
            scan(level, player.blockPosition());
        } else if (!deep) {
            VENTS.clear();
            HOT.clear();
            POOLS.clear();
        }

        // steam plumes and haze
        for (BlockPos vent : VENTS) {
            if (vent.distToCenterSqr(player.position()) < 12 * 12 && RANDOM.nextInt(400) == 0) {
                // a vent close by hisses now and then
                level.playLocalSound(vent.getX() + 0.5, vent.getY() + 1.0, vent.getZ() + 0.5, AbyssalDepths.VENT_HISS.get(), SoundSource.AMBIENT,
                        1.0F, 0.8F + RANDOM.nextFloat() * 0.4F, false);
            }
            if (vent.distToCenterSqr(player.position()) < PLUME_RANGE * PLUME_RANGE && RANDOM.nextFloat() < ClientConfig.particles() / 18) {
                level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, vent.getX() + 0.3 + RANDOM.nextDouble() * 0.4,
                        vent.getY() + 1.1, vent.getZ() + 0.3 + RANDOM.nextDouble() * 0.4, 0.0, 0.05 + RANDOM.nextDouble() * 0.03, 0.0);
            }
        }
        for (BlockPos pool : POOLS) {
            if (RANDOM.nextFloat() < ClientConfig.particles() / 90) {
                level.addParticle(ParticleTypes.WHITE_SMOKE, pool.getX() + RANDOM.nextDouble(), pool.getY() + 1.0,
                        pool.getZ() + RANDOM.nextDouble(), 0.0, 0.03, 0.0);
            }
            if (RANDOM.nextInt(240) == 0) {
                level.addParticle(ParticleTypes.BUBBLE_POP, pool.getX() + RANDOM.nextDouble(), pool.getY() + 0.95,
                        pool.getZ() + RANDOM.nextDouble(), 0.0, 0.01, 0.0);
            }
        }
        if (!VENTS.isEmpty() && RANDOM.nextFloat() < ClientConfig.particles() / 8) {
            haze(level, player);
        }

        // heat: the hot blocks close by, smoothed
        float target = 0.0F;
        for (BlockPos hot : HOT) {
            final double d = Math.sqrt(hot.distToCenterSqr(player.position()));
            if (d < HEAT_RANGE) {
                target += (float) (1.0 - d / HEAT_RANGE);
            }
        }
        for (BlockPos vent : VENTS) {
            final double d = Math.sqrt(vent.distToCenterSqr(player.position()));
            if (d < HEAT_RANGE) {
                target += (float) (0.5 * (1.0 - d / HEAT_RANGE));
            }
        }
        target = Math.min(1.0F, target / 6.0F);
        heat += Mth.clamp(target - heat, -0.01F, 0.01F);
        final boolean inBiome = deep && level.getBiome(player.blockPosition()).is(AbyssalDepths.THERMAL_REACHES);
        final float warmTarget = Math.min(1.0F, (inBiome ? 0.55F : 0.0F) + 0.45F * heat);
        warmth += Mth.clamp(warmTarget - warmth, -0.006F, 0.006F);
    }

    /** A slow cloud drifting along the ceiling above, where the plumes end up. */
    private static void haze(ClientLevel level, LocalPlayer player) {
        final double x = player.getX() + (RANDOM.nextDouble() - 0.5) * 40.0, z = player.getZ() + (RANDOM.nextDouble() - 0.5) * 40.0;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, player.getEyeY(), z);
        for (int k = 0; k < 32; k++, pos.move(0, 1, 0)) {
            if (!level.getBlockState(pos).isAir()) {
                if (k > 3) {
                    level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, x, pos.getY() - 1.2, z,
                            (RANDOM.nextDouble() - 0.5) * 0.02, -0.005, (RANDOM.nextDouble() - 0.5) * 0.02);
                }
                return;
            }
        }
    }

    /** Vents with air above, smouldering rock and pool surfaces in the chunk sections around the player. */
    private static void scan(ClientLevel level, BlockPos around) {
        VENTS.clear();
        HOT.clear();
        POOLS.clear();
        final int cx = SectionPos.blockToSectionCoord(around.getX()), cz = SectionPos.blockToSectionCoord(around.getZ());
        final int minSection = level.getSectionIndex(TunnelLayer.MIN_AIR_Y), maxSection = level.getSectionIndex(TunnelLayer.LAYER_TOP_Y + 16);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int chunks = ClientConfig.thermalScanChunks();
        for (int dz = -chunks; dz <= chunks; dz++) {
            for (int dx = -chunks; dx <= chunks; dx++) {
                if (!level.hasChunk(cx + dx, cz + dz)) {
                    continue;
                }
                final LevelChunk chunk = level.getChunk(cx + dx, cz + dz);
                final LevelChunkSection[] sections = chunk.getSections();
                for (int i = Math.max(0, minSection); i <= maxSection && i < sections.length; i++) {
                    final LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(ThermalClient::interesting)) {
                        continue;
                    }
                    final int baseX = (cx + dx) << 4, baseY = level.getSectionYFromSectionIndex(i) << 4, baseZ = (cz + dz) << 4;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                final BlockState state = section.getBlockState(x, y, z);
                                if (!interesting(state)) {
                                    continue;
                                }
                                pos.set(baseX + x, baseY + y, baseZ + z);
                                if (!level.getBlockState(pos.above()).isAir()) {
                                    continue; // covered: no steam, no heat on the surface
                                }
                                if (state.is(AbyssalDepths.THERMAL_VENT.get())) {
                                    if (!ventBeside(level, pos)) {
                                        add(VENTS, pos); // the tip of a vent cone or a lone vent, not its flanks
                                    }
                                } else if (state.is(AbyssalDepths.SMOULDERING_ROCK.get())) {
                                    add(HOT, pos);
                                } else if (level.getBiome(pos).is(AbyssalDepths.THERMAL_REACHES)) {
                                    add(POOLS, pos); // a warm pool's surface
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean ventBeside(ClientLevel level, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(d)).is(AbyssalDepths.THERMAL_VENT.get())) {
                return true;
            }
        }
        return false;
    }

    private static boolean interesting(BlockState state) {
        return state.is(AbyssalDepths.THERMAL_VENT.get()) || state.is(AbyssalDepths.SMOULDERING_ROCK.get())
                || state.getFluidState().is(FluidTags.WATER);
    }

    private static void add(List<BlockPos> list, BlockPos pos) {
        if (list.size() < MAX_SOURCES) {
            list.add(pos.immutable());
        }
    }

    /** The amber glow at the screen edges. */
    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layers {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "heat_vignette"),
                    (graphics, delta) -> {
                        if (heat <= 0.01F || !ClientConfig.heatVignette()) {
                            return;
                        }
                        RenderSystem.disableDepthTest();
                        RenderSystem.depthMask(false);
                        RenderSystem.enableBlend();
                        RenderSystem.defaultBlendFunc();
                        graphics.setColor(1.0F, 0.5F, 0.18F, 0.28F * heat);
                        final int w = graphics.guiWidth(), h = graphics.guiHeight();
                        graphics.blit(VIGNETTE, 0, 0, -90, 0.0F, 0.0F, w, h, w, h);
                        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
                        RenderSystem.disableBlend();
                        RenderSystem.depthMask(true);
                        RenderSystem.enableDepthTest();
                    });
        }
    }
}
