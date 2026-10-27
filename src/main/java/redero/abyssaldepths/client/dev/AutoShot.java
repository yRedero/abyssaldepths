package redero.abyssaldepths.client.dev;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

import com.mojang.datafixers.util.Pair;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.registries.Registries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.TunnelLayer;

/**
 * Development only: takes screenshots of the layer biomes without anyone at the keyboard. Inert unless the
 * system property {@code abyssaldepths.autoshot} is set (the {@code runAutoshot} Gradle run does that); its
 * value lists the biomes, e.g. {@code thermal_reaches,shale_flats}.
 * <p>
 * It creates a world "abyss-autoshot" with a fixed seed, then for every biome finds the nearest place
 * with a tall open hall, flies there in spectator mode with night vision and the HUD hidden, waits for
 * the chunks, and saves a screenshot in four directions plus one looking down to run/screenshots.
 * At the end it closes the game.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class AutoShot {
    private static final String PLAN = System.getProperty("abyssaldepths.autoshot", "");
    private static final String SEED = System.getProperty("abyssaldepths.autoshot.seed", "12345");
    private static final String COMMANDS = System.getProperty("abyssaldepths.autoshot.commands", "");
    private static final int SETTLE_TICKS = 220, TURN_TICKS = 15;

    private enum Step { TITLE, CREATING, LOADING, NEXT, FINDING, SETTLING, SHOOTING, DONE }

    private static Step step = Step.TITLE;
    private static final Deque<String> BIOMES = new ArrayDeque<>();
    private static String biome;
    private static CompletableFuture<BlockPos> found;
    private static int wait, shot;

    private AutoShot() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PLAN.isEmpty() || step == Step.DONE) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        try {
            tick(mc);
        } catch (Exception e) {
            AbyssalDepths.LOGGER.error("autoshot failed in step {}", step, e);
            step = Step.DONE;
            mc.stop();
        }
    }

    private static void tick(Minecraft mc) throws Exception {
        if (step.ordinal() > Step.LOADING.ordinal() && mc.screen != null) {
            mc.setScreen(null); // a pause menu that opened anyway
        }
        switch (step) {
            case TITLE -> {
                if (mc.screen instanceof TitleScreen) {
                    for (String b : PLAN.split(",")) {
                        BIOMES.add(b.trim() + "#0");   // two places per biome: formations are a matter of chance
                        BIOMES.add(b.trim() + "#1");
                    }
                    CreateWorldScreen.openFresh(mc, mc.screen);
                    step = Step.CREATING;
                }
            }
            case CREATING -> {
                if (mc.screen instanceof CreateWorldScreen screen) {
                    final WorldCreationUiState ui = screen.getUiState();
                    ui.setName("abyss-autoshot");
                    ui.setSeed(SEED);
                    ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    ui.setAllowCommands(true);
                    final Method create = CreateWorldScreen.class.getDeclaredMethod("onCreate");
                    create.setAccessible(true);
                    create.invoke(screen);
                    step = Step.LOADING;
                    wait = 0;
                }
            }
            case LOADING -> {
                if (mc.player != null && mc.getSingleplayerServer() != null && ++wait > 60) {
                    final MinecraftServer server = mc.getSingleplayerServer();
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        p.setGameMode(GameType.SPECTATOR);
                        p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false));
                        // commands to set the scene, separated by ';', e.g. "abyssaldepths storm start 600"
                        for (String command : COMMANDS.split(";")) {
                            if (!command.isBlank()) {
                                server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4), command.trim());
                            }
                        }
                    });
                    mc.options.hideGui = true;
                    mc.options.pauseOnLostFocus = false; // keeps running while another window has focus
                    step = Step.NEXT;
                }
            }
            case NEXT -> {
                biome = BIOMES.poll();
                if (biome == null) {
                    AbyssalDepths.LOGGER.info("autoshot: all done");
                    step = Step.DONE;
                    mc.stop();
                    return;
                }
                found = findSpot(mc.getSingleplayerServer(), biome.substring(0, biome.indexOf('#')),
                        Integer.parseInt(biome.substring(biome.indexOf('#') + 1)));
                step = Step.FINDING;
            }
            case FINDING -> {
                if (found.isDone()) {
                    final BlockPos at = found.join();
                    if (at == null) {
                        AbyssalDepths.LOGGER.warn("autoshot: no place found for {}", biome);
                        step = Step.NEXT;
                        return;
                    }
                    final MinecraftServer server = mc.getSingleplayerServer();
                    server.execute(() -> server.getPlayerList().getPlayers().get(0)
                            .teleportTo(server.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F));
                    AbyssalDepths.LOGGER.info("autoshot: {} at {}", biome, at);
                    wait = 0;
                    shot = 0;
                    step = Step.SETTLING;
                }
            }
            case SETTLING -> {
                if (++wait > SETTLE_TICKS) {
                    wait = 0;
                    step = Step.SHOOTING;
                }
            }
            case SHOOTING -> {
                if (wait == 0) {
                    // four directions slightly downwards, then one steep view of the ground
                    mc.player.setYRot(shot * 90.0F);
                    mc.player.setXRot(shot < 4 ? 12.0F : 55.0F);
                }
                if (++wait > TURN_TICKS) {
                    final String name = "autoshot_" + biome.replace('#', '_') + "_" + (shot < 4 ? "dir" + shot : "down") + ".png";
                    Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> { });
                    wait = 0;
                    if (++shot > 4) {
                        step = Step.NEXT;
                    }
                }
            }
            default -> {
            }
        }
    }

    /**
     * The n-th place of the biome below y=-64 (searched on the server thread; the second one about 600
     * blocks further), then the column around it with the most open hall nearby, camera at about 60 %
     * of the hall height for an overview.
     */
    private static CompletableFuture<BlockPos> findSpot(MinecraftServer server, String biomeName, int n) {
        final CompletableFuture<BlockPos> result = new CompletableFuture<>();
        server.execute(() -> {
            final ServerLevel level = server.overworld();
            final ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, biomeName));
            final BlockPos origin = new BlockPos(n * 600, -100, n * 600);
            final Pair<BlockPos, Holder<Biome>> hit = level.findClosestBiome3d(h -> h.is(key), origin, 6400, 32, 64);
            if (hit == null) {
                result.complete(null);
                return;
            }
            final TunnelLayer layer = new TunnelLayer(level.getSeed());
            final TunnelLayer.Column col = new TunnelLayer.Column();
            BlockPos best = null;
            double bestScore = 0;
            for (int dz = -64; dz <= 64; dz += 8) {
                for (int dx = -64; dx <= 64; dx += 8) {
                    final int x = hit.getFirst().getX() + dx, z = hit.getFirst().getZ() + dz;
                    if (!layer.column(x, z, col) || !level.getBiome(new BlockPos(x, -100, z)).is(key)) {
                        continue;
                    }
                    final int floor = col.floor, room = col.ceiling - col.floor;
                    int open = 0;
                    for (int sz = -24; sz <= 24; sz += 6) {
                        for (int sx = -24; sx <= 24; sx += 6) {
                            if (layer.column(x + sx, z + sz, col) && col.ceiling - col.floor > 6) {
                                open++;
                            }
                        }
                    }
                    final double score = open + Math.min(room, 24) * 0.5;
                    if (score > bestScore) {
                        bestScore = score;
                        best = new BlockPos(x, floor + Math.max(3, Math.min(room * 3 / 5, 16)), z);
                    }
                }
            }
            result.complete(best);
        });
        return result;
    }
}
