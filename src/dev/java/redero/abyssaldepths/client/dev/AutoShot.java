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
import net.minecraft.world.level.levelgen.Heightmap;
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
 * value lists the biomes, e.g. {@code thermal_reaches,shale_flats}, or {@code surface} for the world spawn.
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
    /** Run as the player at every spot once its chunks are loaded, e.g. "fill ~-1 ~-2 ~3 ~1 ~ ~3 stone". */
    private static final String SPOT_COMMANDS = System.getProperty("abyssaldepths.autoshot.spotCommands", "");
    private static final int SETTLE_TICKS = 220, TURN_TICKS = 15;

    private enum Step { TITLE, CREATING, LOADING, NEXT, FINDING, SETTLING, SHOOTING, PREVIEW, HAVEN, DONE }

    private static Step step = Step.TITLE;
    private static final Deque<String> BIOMES = new ArrayDeque<>();
    private static String biome;
    private static CompletableFuture<BlockPos> found;
    private static int wait, shot, spotsDone;
    private static boolean reloaded;

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
        if (step.ordinal() > Step.LOADING.ordinal() && step != Step.PREVIEW && step != Step.HAVEN && mc.screen != null) {
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
                            if (!command.isBlank() && !command.trim().startsWith("client:")) {
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
                // "client:reload" in the commands: reload the resources (like F3+T) after the first spot,
                // to test what survives a restart of the sound engine and the renderer
                if (!reloaded && spotsDone > 0 && COMMANDS.contains("client:reload")) {
                    reloaded = true;
                    AbyssalDepths.LOGGER.info("autoshot: reloading resources");
                    mc.reloadResourcePacks();
                    return;
                }
                if (mc.getOverlay() != null) {
                    return; // still reloading
                }
                spotsDone++;
                biome = BIOMES.poll();
                if (biome != null && biome.startsWith("haven#")) {
                    // not a place: Inner Haven's crossing, in and out, shot along the way
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.HAVEN;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("artifacts#")) {
                    // not a place: the tooltip preview screen, one shot of it
                    if (biome.endsWith("#0")) {
                        mc.setScreen(new TooltipPreviewScreen());
                        wait = 0;
                        step = Step.PREVIEW;
                    }
                    return;
                }
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
            case HAVEN -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                ++wait;
                if (wait == 1) {
                    command(server, "tp @s ~ ~30 ~"); // out of trees and terrain, into open air
                }
                if (wait == 10) {
                    // a body to look at: one of a player who is not here, so it stays
                    command(server, "summon abyssaldepths:absent_body ^ ^-1.2 ^3 {Owner:[I;1,2,3,4]}");
                }
                if (wait == 30) {
                    shoot(mc, "haven_0_dir0"); // the body
                    command(server, "abyssaldepths innerhaven");
                }
                if (wait == 42) {
                    shoot(mc, "haven_0_dir1"); // fading out
                }
                if (wait == 120) {
                    shoot(mc, "haven_0_dir2"); // in the room
                    mc.player.setXRot(60.0F);
                }
                if (wait == 130) {
                    shoot(mc, "haven_0_down");
                    command(server, "abyssaldepths innerhaven");
                }
                if (wait == 230) {
                    shoot(mc, "haven_0_dir3"); // back
                    step = Step.NEXT;
                }
            }
            case PREVIEW -> {
                // three shots a quarter second apart: animations (the title shimmer) show in the difference
                if (++wait == 20 || wait == 25 || wait == 30) {
                    Screenshot.grab(mc.gameDirectory, "autoshot_artifacts_0_dir" + (wait - 20) / 5 + ".png", mc.getMainRenderTarget(), msg -> { });
                } else if (wait > 35) {
                    mc.setScreen(null);
                    step = Step.NEXT;
                }
            }
            case SETTLING -> {
                if (wait == SETTLE_TICKS / 2 && !SPOT_COMMANDS.isBlank()) {
                    final MinecraftServer server = mc.getSingleplayerServer();
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        for (String command : SPOT_COMMANDS.split(";")) {
                            if (!command.isBlank()) {
                                server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4), command.trim());
                            }
                        }
                    });
                }
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

    private static void command(MinecraftServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(
                server.getPlayerList().getPlayers().get(0).createCommandSourceStack().withPermission(4), command));
    }

    private static void shoot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "autoshot_" + name + ".png", mc.getMainRenderTarget(), msg -> { });
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
            if (biomeName.equals("surface")) {
                // above the world spawn (the second one 300 blocks further), for anything seen from the surface
                final BlockPos spawn = level.getSharedSpawnPos().offset(n * 300, 0, n * 300);
                level.getChunk(spawn); // generate it
                result.complete(new BlockPos(spawn.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ()) + 12,
                        spawn.getZ()));
                return;
            }
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
