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
import redero.abyssaldepths.ClientConfig;
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

    private enum Step { TITLE, CREATING, LOADING, NEXT, FINDING, SETTLING, SHOOTING, PREVIEW, HAVEN, REPRISE, INTERLUDE, PARALLAX, VANISH, OBLIVION, NEWARTIFACTS, FIRMAMENT, SMALL, LOOKS, EMPYREAN, MARKS, WAKE, FRACTURE, FRACMARKS, VARIANTS, DONE }

    private static Step step = Step.TITLE;
    private static final Deque<String> BIOMES = new ArrayDeque<>();
    private static String biome;
    private static CompletableFuture<BlockPos> found;
    private static int wait, shot, spotsDone;
    private static boolean reloaded;
    /** Where the TNT checks put their stone (under it). */
    private static BlockPos tntAt = BlockPos.ZERO;
    /** The fracture check: which trial, the tick it was asked for, the tick the player came back (-1: not yet). */
    private static int fractureTrial, fractureAt = -1, fractureBack = -1, deathShown = -1, endSeen = -1;

    private AutoShot() {
    }

    private static boolean moved;

    /** On the very first frame (the loading screen), the window goes to the second monitor, out of the way. */
    @SubscribeEvent
    public static void onFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        if (!moved && !PLAN.isEmpty()) {
            moved = true;
            toSecondMonitor(Minecraft.getInstance());
        }
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
        if (step.ordinal() > Step.LOADING.ordinal() && step != Step.PREVIEW && step != Step.HAVEN && step != Step.REPRISE && mc.screen != null) {
            mc.setScreen(null); // a pause menu that opened anyway
        }
        switch (step) {
            case TITLE -> {
                if (mc.screen instanceof TitleScreen) {
                    toSecondMonitor(mc);
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
                if (biome != null && biome.startsWith("variants#")) {
                    // not a place: every style of Inner Haven's place marks, then of Undertow's burst
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.VARIANTS;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("fracture#")) {
                    // not a place: a creature fractured, then the player into each trial, standing still (failing) ("fracture check" lines)
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        fractureTrial = 0;
                        fractureAt = -1;
                        step = Step.FRACTURE;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("fracmarks#")) {
                    // not a place: every style of Fracturing's marks on creatures part corrupted and taken, and the screen's cracks
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.FRACMARKS;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("wake#")) {
                    // not a place: into the inner self, Inner Haven lost there, waking by holding sneak ("wake check" lines)
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.WAKE;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("marks#")) {
                    // not a place: a mastered Parallax shade, creatures of the world marked, one sent into the shadow; leaving ("marks check" lines)
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.MARKS;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("empyrean#")) {
                    // not a place: Empyrean's beam on creatures in the open, under ten blocks of stone, locked on ("empyrean check" lines)
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.EMPYREAN;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("looks#")) {
                    // not a place: every look of Aegis's field and of Convergence's inside view ("looks check" lines)
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.LOOKS;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("small#")) {
                    // not a place: Anchor, Repulsion, Aegis, Convergence, Recall used and checked ("small check" lines)
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.SMALL;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("firmament#")) {
                    // not a place: Firmament held in the air: hovering, climbing, walking, letting go ("firmament check" lines)
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.FIRMAMENT;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("newartifacts#")) {
                    // not a place: Undertow, Counterweight, Severance, Mirage used and checked ("artifact check" lines)
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.NEWARTIFACTS;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("oblivion#")) {
                    // not a place: Oblivion sends creatures away, the strong for less ("oblivion check" lines)
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.OBLIVION;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("vanish#")) {
                    // not a place: how a shade vanishes and comes back, every style (a fake player on this client)
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.VANISH;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("parallax#")) {
                    // not a place: Parallax's shadow, checked through the log ("parallax check" lines)
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.PARALLAX;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("interlude#")) {
                    // not a place: Interlude's slowed time (measured in the log) and the afterimages (shots)
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.INTERLUDE;
                    }
                    return;
                }
                if (biome != null && biome.startsWith("reprise#")) {
                    // not a place: Reprise's rewind, checked through the log ("reprise check" lines)
                    if (biome.endsWith("#0")) {
                        wait = 0;
                        step = Step.REPRISE;
                    }
                    return;
                }
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
            case INTERLUDE -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final String sand = "summon falling_block ~3 ~10 ~6 {BlockState:{Name:\"minecraft:sand\"},Time:1,Tags:[\"%s\"]}";
                switch (++wait) {
                    case 1 -> command(server, "tp @s ~ ~40 ~");
                    case 10 -> command(server, String.format(sand, "normal"));
                    case 30 -> {
                        command(server, "data get entity @e[tag=normal,limit=1] Pos[1]"); // 20 ticks of falling, normal time
                        command(server, "kill @e[tag=normal]");
                    }
                    case 35 -> command(server, "give @s abyssaldepths:interlude[abyssaldepths:artifact_active=true]");
                    case 45 -> command(server, String.format(sand, "slowed"));
                    case 65 -> {
                        command(server, "data get entity @e[tag=slowed,limit=1] Pos[1]"); // 20 ticks, half of them skipped
                        command(server, "kill @e[tag=slowed]");
                        mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                        mc.player.setYRot(90.0F);
                    }
                    case 60 -> shoot(mc, "interlude_0_dir1"); // first person: the border while it runs
                    case 120 -> shoot(mc, "interlude_0_dir0"); // the trail
                    case 125 -> {
                        mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                        step = Step.NEXT;
                    }
                    default -> {
                    }
                }
                if (wait > 70 && wait < 120) {
                    command(server, "tp @s ~-0.3 ~ ~"); // walk sideways: the afterimages trail behind
                }
            }
            case VARIANTS -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final int t = ++wait;
                final net.minecraft.world.phys.Vec3 ahead = mc.player.position().add(0, 0, 4);
                final int marks = redero.abyssaldepths.ClientConfig.WaypointMark.values().length, bursts = redero.abyssaldepths.ClientConfig.UndertowBurst.values().length;
                final int burstStart = 30 + marks * 25, end = burstStart + bursts * 20;
                if (t == 1) {
                    command(server, "tp @s ~ ~40 ~ 0 35");
                    command(server, "give @s abyssaldepths:inner_haven[abyssaldepths:mastery=100]");
                } else if (t == 8) {
                    command(server, "fill ~-6 ~-1 ~-2 ~6 ~-1 ~12 stone");
                } else if (t == 14) {
                    redero.abyssaldepths.client.WaypointMarks.set(mc.level.dimension().location() + "|" + ahead.x + "|" + ahead.y + "|" + ahead.z);
                } else if (t >= 20 && t < 20 + marks * 25 && (t - 20) % 25 == 0) {
                    redero.abyssaldepths.ClientConfig.setStyle("mark", redero.abyssaldepths.ClientConfig.WaypointMark.values()[(t - 20) / 25].name());
                } else if (t >= 20 && t < 20 + marks * 25 && (t - 20) % 25 == 22) {
                    shoot(mc, "variants_0_dir" + (t - 20) / 25);
                } else if (t == 22 + marks * 25) {
                    redero.abyssaldepths.client.WaypointMarks.set("");
                    mc.player.setXRot(15.0F);
                } else if (t >= burstStart && t < end && (t - burstStart) % 20 == 0) {
                    redero.abyssaldepths.ClientConfig.setStyle("burst", redero.abyssaldepths.ClientConfig.UndertowBurst.values()[(t - burstStart) / 20].name());
                } else if (t >= burstStart && t < end && (t - burstStart) % 20 == 2) {
                    redero.abyssaldepths.client.UndertowClient.burst(ahead.add(0, 1, 2), 1.0F);
                } else if (t >= burstStart && t < end && (t - burstStart) % 20 == 7) {
                    shoot(mc, "variants_" + (1 + (t - burstStart) / 20) + "_dir0");
                } else if (t >= burstStart && t < end && (t - burstStart) % 20 == 13) {
                    shoot(mc, "variants_" + (1 + (t - burstStart) / 20) + "_dir1");
                } else if (t == end) {
                    // Severance at full mastery against hunger: saturation in its place
                    command(server, "gamemode survival");
                    command(server, "effect give @s hunger 30 40");
                } else if (t == end + 30) {
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        AbyssalDepths.LOGGER.info("artifact check severance hunger: food before {}", p.getFoodData().getFoodLevel());
                        final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(AbyssalDepths.SEVERANCE.get());
                        redero.abyssaldepths.artifact.ArtifactOwnership.setMastery(p, stack, 100);
                        AbyssalDepths.SEVERANCE.get().setCharge(stack, 3);
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                        stack.use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                        AbyssalDepths.LOGGER.info("artifact check severance hunger: effects {}", p.getActiveEffects().stream().map(e -> e.getEffect().getRegisteredName()).toList());
                    });
                } else if (t == end + 50) {
                    server.execute(() -> AbyssalDepths.LOGGER.info("artifact check severance hunger: food after {}",
                            server.getPlayerList().getPlayers().get(0).getFoodData().getFoodLevel()));
                    // the mat: sitting on it
                    command(server, "setblock ~ ~ ~2 abyssaldepths:meditation_mat");
                } else if (t == end + 55) {
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        redero.abyssaldepths.entity.Seat.sit(p.serverLevel(), p.blockPosition().offset(0, 0, 2), p);
                    });
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
                } else if (t == end + 75) {
                    shoot(mc, "variants_6_dir0"); // sitting on the mat
                } else if (t == end + 80) {
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    redero.abyssaldepths.ClientConfig.setStyle("burst", "VOID");
                    redero.abyssaldepths.ClientConfig.setStyle("mark", "SIGIL");
                    command(server, "gamemode spectator");
                    step = Step.NEXT;
                }
            }
            case FRACTURE -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final int t = ++wait;
                final String[] trials = {"collapse", "breakdown", "core"};
                final boolean inTrial = mc.level.dimension() == redero.abyssaldepths.fracture.FractureTrials.DIMENSION;
                if (mc.player.isDeadOrDying() && mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen) {
                    if (deathShown < 0) {
                        deathShown = t;
                    }
                    if (t - deathShown == 10) {
                        shoot(mc, "fracture_" + fractureTrial + "_dir5"); // the death screen over the black
                    }
                    if (t - deathShown >= 30) {
                        AbyssalDepths.LOGGER.info("fracture check: died, respawning");
                        mc.player.respawn();
                        mc.setScreen(null);
                        deathShown = -1;
                    }
                }
                if (t == 1) {
                    command(server, "gamemode survival");
                    command(server, "spawnpoint @s ~ ~40 ~");
                    command(server, "tp @s ~ ~40 ~ 0 15");
                    command(server, "time set noon");
                    command(server, "gamerule keepInventory true");
                } else if (t == 6) {
                    command(server, "fill ~-6 ~-1 ~-2 ~6 ~-1 ~10 stone");
                    command(server, "summon zombie ~ ~ ~4 {PersistenceRequired:1b,Tags:[\"frac_test\"]}");
                    command(server, "give @s diamond 3");
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                } else if (t == 12) {
                    command(server, "abyssaldepths fracture @e[tag=frac_test]");
                } else if (t == 20) {
                    server.execute(() -> {
                        for (net.minecraft.world.entity.Entity e : server.getPlayerList().getPlayers().get(0).serverLevel().getAllEntities()) {
                            if (e.getTags().contains("frac_test") && e instanceof net.minecraft.world.entity.Mob mob) {
                                AbyssalDepths.LOGGER.info("fracture check creature: frozen {} (no AI {})", redero.abyssaldepths.fracture.Fracturing.frozen(mob), mob.isNoAi());
                            }
                        }
                    });
                    shoot(mc, "fracmob_0_dir0");
                } else if (t >= 30 && fractureTrial < trials.length) {
                    if (fractureAt < 0) { // into the next trial
                        fractureAt = t;
                        fractureBack = -1;
                        mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                        command(server, "abyssaldepths fracture trial " + trials[fractureTrial]);
                    }
                    final int in = t - fractureAt;
                    if (in == 25 || in == 44) {
                        shoot(mc, "fracture_" + fractureTrial + "_dir" + (in == 25 ? 0 : 1)); // the cracks; the pieces falling
                    } else if (in == 95) {
                        shoot(mc, "fracture_" + fractureTrial + "_dir2"); // arrived: the arena, the title
                        AbyssalDepths.LOGGER.info("fracture check {}: in {} inventory empty {}", trials[fractureTrial], mc.level.dimension().location(),
                                mc.player.getInventory().isEmpty());
                    } else if (in == 140) {
                        mc.player.setXRot(-15.0F); // up a little: the core, if there is one
                        shoot(mc, "fracture_" + fractureTrial + "_dir3"); // under way
                    }
                    if (in > 60 && endSeen < 0 && redero.abyssaldepths.client.FractureScreen.overgrowing()) {
                        endSeen = t;
                    }
                    if (endSeen >= 0 && t - endSeen == 8) {
                        shoot(mc, "fracture_" + fractureTrial + "_dir4"); // the black growing over the trial
                    }
                    if (in > 60 && !inTrial && fractureBack < 0) {
                        fractureBack = t;
                        endSeen = -1;
                    }
                    if (fractureBack >= 0 && t - fractureBack == 75) { // after the respawn: the body left behind, decomposing
                        server.execute(() -> {
                            final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                            for (net.minecraft.world.entity.Entity e : p.serverLevel().getAllEntities()) {
                                if (e instanceof redero.abyssaldepths.fracture.FracturedBody) {
                                    p.teleportTo(p.serverLevel(), e.getX(), e.getY(), e.getZ() + 3.0, 180.0F, 15.0F);
                                    AbyssalDepths.LOGGER.info("fracture check: the body is still there, decomposing {}", ((redero.abyssaldepths.fracture.FracturedBody) e).decayed(0));
                                }
                            }
                        });
                    }
                    if (fractureBack >= 0 && t - fractureBack == 85) {
                        shoot(mc, "fracture_" + fractureTrial + "_dir6");
                    }
                    if (fractureBack >= 0 && t - fractureBack == 15) {
                        AbyssalDepths.LOGGER.info("fracture check {}: back after {} s, dead {}", trials[fractureTrial], (t - fractureAt) / 20, mc.player.isDeadOrDying());
                        if (mc.player.isDeadOrDying()) {
                            mc.player.respawn();
                        }
                    }
                    if (fractureBack >= 0 && t - fractureBack == 50) {
                        server.execute(() -> {
                            final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                            AbyssalDepths.LOGGER.info("fracture check {}: scars {} max health {} diamonds {}", trials[fractureTrial], redero.abyssaldepths.fracture.Scars.of(p),
                                    p.getMaxHealth(), p.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND));
                        });
                    }
                    if (fractureBack >= 0 && t - fractureBack == 95) {
                        fractureTrial++;
                        fractureAt = -1;
                    }
                    if (in > 2400) { // stuck: give up on this one
                        AbyssalDepths.LOGGER.info("fracture check {}: no end after 120 s", trials[fractureTrial]);
                        fractureTrial++;
                        fractureAt = -1;
                    }
                } else if (fractureTrial >= trials.length) {
                    command(server, "abyssaldepths scars 0");
                    command(server, "gamerule keepInventory false");
                    command(server, "gamemode spectator");
                    step = Step.NEXT;
                }
            }
            case FRACMARKS -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final int t = ++wait;
                final ClientConfig.FractureMarks[] styles = ClientConfig.FractureMarks.values();
                final ClientConfig.FractureAura[] auras = ClientConfig.FractureAura.values();
                final int a0 = 20 + styles.length * 12 + auras.length * 12;
                if (t == 1) {
                    command(server, "gamemode survival");
                    command(server, "tp @s ~ ~40 ~ 0 10");
                    command(server, "effect give @s resistance 600 4 true");
                    command(server, "time set noon");
                } else if (t == 6) {
                    command(server, "fill ~-8 ~-2 ~-2 ~8 ~8 ~12 air");
                    command(server, "fill ~-8 ~-1 ~-2 ~8 ~-1 ~12 stone");
                    final String[] mobs = {"husk", "villager", "wither_skeleton", "pig", "husk", "cow"}; // none burning in the sun
                    for (int k = 0; k < mobs.length; k++) {
                        command(server, "summon " + mobs[k] + " ~" + (k % 3 * 2 - 2) + " ~ ~" + (k < 3 ? 4 : 6)
                                + " {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f],Tags:[\"fm" + (k < 3 ? "a" : "b") + "\"]}");
                    }
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                } else if (t == 10) {
                    command(server, "abyssaldepths fracture @e[tag=fma] 60"); // part way (front row)
                    command(server, "abyssaldepths fracture @e[tag=fmb] 100"); // taken (back row)
                    command(server, "abyssaldepths scars 3");
                } else if (t >= 20 && t < 20 + styles.length * 12) {
                    final int k = (t - 20) / 12;
                    final ClientConfig.ScarIcon[] icons = ClientConfig.ScarIcon.values();
                    if ((t - 20) % 12 == 0) {
                        ClientConfig.setStyle("fracture", styles[k].name());
                        ClientConfig.setStyle("scars", icons[k % icons.length].name()); // the scars' icons by the hotbar, by turns
                        mc.options.hideGui = false;
                    } else if ((t - 20) % 12 == 6) {
                        shoot(mc, "fracmarks_" + styles[k].name().toLowerCase(java.util.Locale.ROOT) + "_dir0");
                    } else if ((t - 20) % 12 == 10) {
                        shoot(mc, "fracmarks_" + styles[k].name().toLowerCase(java.util.Locale.ROOT) + "_dir1"); // a moment on: the living ones moved
                    }
                } else if (t >= 20 + styles.length * 12 && t < 20 + styles.length * 12 + auras.length * 12) { // the auras, on the same creatures
                    final int k = (t - 20 - styles.length * 12) / 12, in = (t - 20 - styles.length * 12) % 12;
                    if (in == 0) {
                        mc.options.hideGui = true;
                        ClientConfig.setStyle("fracture", "VEINS");
                        ClientConfig.setStyle("fracture_aura", auras[k].name());
                    } else if (in == 10) {
                        shoot(mc, "fracaura_" + auras[k].name().toLowerCase(java.util.Locale.ROOT) + "_dir0");
                    }
                } else if (t == a0) { // the Core: its warnings
                    ClientConfig.setStyle("fracture_aura", "DOUBLES");
                    command(server, "kill @e[tag=fma]");
                    command(server, "kill @e[tag=fmb]");
                    command(server, "abyssaldepths fracture trial core");
                    command(server, "effect give @s resistance 600 4 true");
                } else if (t > a0 + 290 && t <= a0 + 390 && (t - a0) % 10 == 0) {
                    mc.player.setXRot(35.0F);
                    shoot(mc, "fraccore_" + (t - a0) + "_dir0");
                } else if (t == a0 + 400) { // passed: the black recedes, so does the corruption on the player
                    command(server, "abyssaldepths fracture end pass");
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
                } else if (t == a0 + 412) {
                    shoot(mc, "fracpass_0_dir0"); // the black growing over
                } else if (t > a0 + 412 && t <= a0 + 530 && (t - a0) % 8 == 0) {
                    if (mc.level.dimension() != redero.abyssaldepths.fracture.FractureTrials.DIMENSION) {
                        shoot(mc, "fracpass_" + (t - a0) + "_dir0");
                    }
                } else if (t > a0 + 540) {
                    if (mc.level.dimension() != redero.abyssaldepths.fracture.FractureTrials.DIMENSION) {
                        mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                        command(server, "abyssaldepths scars 0");
                        command(server, "gamemode spectator");
                        step = Step.NEXT;
                    }
                }
                if (mc.player.isDeadOrDying() && mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen) {
                    mc.player.respawn();
                    mc.setScreen(null);
                }
            }
            case WAKE -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final int t = ++wait;
                if (t == 1) {
                    command(server, "gamemode survival");
                    command(server, "abyssaldepths innerhaven");
                } else if (t == 80) {
                    command(server, "clear @s abyssaldepths:inner_haven");
                    AbyssalDepths.LOGGER.info("wake check in: {}", mc.level.dimension().location());
                } else if (t == 90) {
                    mc.options.keyShift.setDown(true);
                } else if (t == 200) {
                    mc.options.keyShift.setDown(false);
                } else if (t == 210) {
                    AbyssalDepths.LOGGER.info("wake check after holding sneak: {}", mc.level.dimension().location());
                    command(server, "gamemode spectator");
                    step = Step.NEXT;
                }
            }
            case MARKS -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final int t = ++wait;
                if (t == 1) {
                    command(server, "gamemode survival");
                    command(server, "tp @s ~ ~40 ~ 0 15");
                    command(server, "effect give @s resistance 600 4 true");
                    command(server, "time set noon");
                } else if (t == 6) {
                    command(server, "fill ~-8 ~-2 ~-4 ~8 ~8 ~14 air");
                    command(server, "fill ~-8 ~-1 ~-4 ~8 ~-1 ~14 stone");
                    command(server, "summon zombie ~-2 ~ ~5 {NoAI:1b,PersistenceRequired:1b,Tags:[\"marks_test\"],CustomName:'\"sent\"'}");
                    command(server, "summon zombie ~2 ~ ~5 {NoAI:1b,PersistenceRequired:1b,Tags:[\"marks_test\"],CustomName:'\"stays\"'}");
                    command(server, "summon iron_golem ~0 ~ ~8 {NoAI:1b,PersistenceRequired:1b,Tags:[\"marks_test\"],CustomName:'\"golem\"'}");
                } else if (t == 8) {
                    server.execute(() -> { // the golem sent into the shadow before the player goes there (Oblivion first)
                        for (net.minecraft.world.entity.Entity e : server.getPlayerList().getPlayers().get(0).serverLevel().getAllEntities()) {
                            if (e.getCustomName() != null && e.getCustomName().getString().equals("golem")) {
                                redero.abyssaldepths.artifact.Shades.sendAway(e, 600);
                            }
                        }
                    });
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                } else if (t == 10) {
                    server.execute(() -> { // Parallax mastered, switched on
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(AbyssalDepths.PARALLAX.get());
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                        redero.abyssaldepths.artifact.ArtifactOwnership.setMastery(p, stack, 100);
                        stack.use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                    });
                } else if (t == 70 || t == 90 || t >= 112 && t <= 140 && t % 4 == 0) {
                    final String when = t == 70 ? "in the shadow" : t == 90 ? "one sent" : "leaving, tick " + (t - 110);
                    for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                        if (e.getCustomName() != null && java.util.List.of("sent", "stays", "golem").contains(e.getCustomName().getString())) {
                            AbyssalDepths.LOGGER.info("marks check {}: {} shade {} hidden {} marked {} (view in shadow {})", when, e.getCustomName().getString(),
                                    redero.abyssaldepths.artifact.Shades.isShade(e), redero.abyssaldepths.client.ShadeView.hidden(e),
                                    redero.abyssaldepths.artifact.Shades.clientViewInShadow && !redero.abyssaldepths.artifact.Shades.isShade(e)
                                            && !redero.abyssaldepths.client.ShadeView.hidden(e),
                                    redero.abyssaldepths.artifact.Shades.clientViewInShadow);
                        }
                    }
                    if (t == 70) {
                        shoot(mc, "marks_0_dir0");
                    } else if (t == 90) {
                        shoot(mc, "marks_0_dir1");
                    } else if (t == 116) {
                        shoot(mc, "marks_0_dir2"); // leaving: the world should still be there
                    }
                } else if (t == 80) {
                    server.execute(() -> { // the left one sent into the shadow, as Oblivion does
                        for (net.minecraft.world.entity.Entity e : server.getPlayerList().getPlayers().get(0).serverLevel().getAllEntities()) {
                            if (e.getCustomName() != null && e.getCustomName().getString().equals("sent")) {
                                redero.abyssaldepths.artifact.Shades.sendAway(e, 400);
                            }
                        }
                    });
                } else if (t == 110) {
                    server.execute(() -> { // Parallax off: leaving the shadow
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        p.getMainHandItem().use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                    });
                } else if (t > 145) {
                    command(server, "kill @e[tag=marks_test]");
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    command(server, "gamemode spectator");
                    step = Step.NEXT;
                }
            }
            case EMPYREAN -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final int t = ++wait;
                final String tough = "{NoAI:1b,PersistenceRequired:1b,Tags:[\"empyrean_test\"],Health:300f,attributes:[{id:\"minecraft:generic.max_health\",base:300}]";
                if (t == 1) {
                    command(server, "gamemode survival");
                    command(server, "tp @s ~ ~40 ~ 0 15");
                    command(server, "effect give @s resistance 600 4 true");
                    command(server, "time set noon");
                } else if (t == 6) {
                    command(server, "fill ~-10 ~-2 ~-4 ~10 ~14 ~20 air");
                    command(server, "fill ~-10 ~-1 ~-4 ~10 ~-1 ~20 stone");
                    command(server, "fill ~3 ~3 ~7 ~5 ~12 ~9 stone"); // ten blocks over the second
                    command(server, "summon zombie ~ ~ ~8 " + tough + ",CustomName:'\"open\"'}");
                    command(server, "summon zombie ~4 ~ ~8 " + tough + ",CustomName:'\"covered\"'}");
                    command(server, "summon zombie ~-6 ~ ~10 " + tough + ",CustomName:'\"locked\"'}");
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                } else if (t == 15) {
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        for (net.minecraft.world.entity.Entity e : p.serverLevel().getAllEntities()) {
                            if (e.getTags().contains("empyrean_test") && e.getCustomName() != null) {
                                final String name = e.getCustomName().getString();
                                if (name.equals("locked")) { // mastered, right clicked on: locked
                                    final net.minecraft.world.phys.Vec3 look = e.position().add(0, 1.2, 0).subtract(p.getEyePosition());
                                    p.setYRot((float) Math.toDegrees(Math.atan2(-look.x, look.z)));
                                    p.setXRot((float) -Math.toDegrees(Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z))));
                                    final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(AbyssalDepths.EMPYREAN.get());
                                    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                                    redero.abyssaldepths.artifact.ArtifactOwnership.setMastery(p, stack, 100);
                                    stack.use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                                } else { // the open and the covered: a 0 % beam each (40 in all)
                                    final redero.abyssaldepths.entity.EmpyreanBeam beam = new redero.abyssaldepths.entity.EmpyreanBeam(AbyssalDepths.EMPYREAN_BEAM.get(),
                                            p.serverLevel());
                                    beam.moveTo(e.getX(), e.getY(), e.getZ());
                                    beam.start(p, 40.0F, null);
                                    p.serverLevel().addFreshEntity(beam);
                                }
                            }
                        }
                    });
                } else if (t == 25) {
                    command(server, "tp @e[name=locked] ~-3 ~ ~12"); // it runs: the locked beam follows
                } else if (t == 28) {
                    shoot(mc, "empyrean_0_dir0"); // the warning
                } else if (t >= 46 && t < 46 + 8 * 4) { // each style burning: set, a shot two ticks later
                    final int k = (t - 46) / 4;
                    if ((t - 46) % 4 == 0) {
                        redero.abyssaldepths.ClientConfig.setStyle("empyrean", redero.abyssaldepths.ClientConfig.EmpyreanBeamLook.values()[k].name());
                    } else if ((t - 46) % 4 == 2) {
                        shoot(mc, "empyreanlooks_" + k / 4 + "_dir" + k % 4);
                    }
                } else if (t == 79) {
                    redero.abyssaldepths.ClientConfig.setStyle("empyrean", "CATHEDRAL");
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    mc.player.setXRot(-60.0F);
                } else if (t == 81) {
                    shoot(mc, "empyrean_0_dir2"); // looking up the column
                } else if (t == 100) {
                    server.execute(() -> {
                        for (net.minecraft.world.entity.Entity e : server.getPlayerList().getPlayers().get(0).serverLevel().getAllEntities()) {
                            if (e.getTags().contains("empyrean_test") && e instanceof net.minecraft.world.entity.LivingEntity living) {
                                AbyssalDepths.LOGGER.info("empyrean check {}: health {} of 300 (lost {})", e.getCustomName().getString(),
                                        String.format("%.1f", living.getHealth()), String.format("%.1f", 300 - living.getHealth()));
                            }
                        }
                    });
                } else if (t > 105) {
                    command(server, "kill @e[tag=empyrean_test]");
                    command(server, "gamemode spectator");
                    step = Step.NEXT;
                }
            }
            case LOOKS -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final int t = ++wait;
                final ClientConfig.AegisLook[] aegis = ClientConfig.AegisLook.values();
                final ClientConfig.ConvergenceLook[] convergence = ClientConfig.ConvergenceLook.values();
                if (t == 1) {
                    command(server, "gamemode survival");
                    command(server, "tp @s ~ ~40 ~ 0 20");
                    command(server, "effect give @s resistance 600 4 true");
                    command(server, "time set noon");
                } else if (t == 6) {
                    command(server, "fill ~-8 ~-2 ~-8 ~8 ~8 ~14 air");
                    command(server, "fill ~-8 ~-1 ~-8 ~8 ~-1 ~14 stone");
                    command(server, "fill ~-3 ~ ~4 ~3 ~3 ~10 stone");
                    server.execute(() -> tntAt = server.getPlayerList().getPlayers().get(0).blockPosition()); // the platform's middle
                } else if (t == 12) {
                    server.execute(() -> { // Aegis at no mastery: a small field, arrows slow
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(AbyssalDepths.AEGIS.get());
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                        stack.use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                        p.setYRot(180.0F);
                    });
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                } else if (t == 20) {
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        final net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(net.minecraft.world.entity.EntityType.ARROW,
                                p.serverLevel());
                        arrow.setPos(p.getX(), p.getY() + 1.0, p.getZ() - 6.0);
                        arrow.setDeltaMovement(0, 0, 1.0);
                        arrow.addTag("looks_arrow");
                        p.serverLevel().addFreshEntity(arrow);
                    });
                } else if (t == 24) {
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        String seen = "gone (it hit the player)";
                        for (net.minecraft.world.entity.Entity e : p.serverLevel().getAllEntities()) {
                            if (e.getTags().contains("looks_arrow")) {
                                seen = String.format("at z %.1f from the player, speed %.2f (it flew in at 1)", e.getZ() - p.getZ(), e.getDeltaMovement().length());
                                e.discard();
                            }
                        }
                        AbyssalDepths.LOGGER.info("looks check aegis at 0 %: arrow {}", seen);
                    });
                    AbyssalDepths.LOGGER.info("looks check aegis at 0 %: radius on the client {}", redero.abyssaldepths.artifact.AegisItem.clientRadius(mc.player));
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        redero.abyssaldepths.artifact.ArtifactOwnership.setMastery(p, p.getMainHandItem(), 100);
                    });
                } else if (t >= 30 && t < 30 + aegis.length * 10) {
                    final int k = (t - 30) / 10;
                    if ((t - 30) % 10 == 0) {
                        ClientConfig.setStyle("aegis", aegis[k].name());
                    } else if ((t - 30) % 10 == 6) {
                        shoot(mc, "aegislooks_0_dir" + k);
                    }
                } else if (t == 80 || t == 86 || t == 92) {
                    // the screen effects: shots at the holder from three sides
                    mc.options.hideGui = false;
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    ClientConfig.setStyle("aegis_overlay", t == 80 ? "FRAME" : t == 86 ? "RIPPLES" : "HEX");
                    if (t == 92) {
                        return; // no shots this time: the zombie
                    }
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        for (double[] from : new double[][] {{-5, -2}, {6, 0}, {0, 6}}) {
                            final net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(net.minecraft.world.entity.EntityType.ARROW,
                                    p.serverLevel());
                            arrow.setPos(p.getX() + from[0], p.getY() + 1.0, p.getZ() + from[1]);
                            arrow.setDeltaMovement(new net.minecraft.world.phys.Vec3(-from[0], 0, -from[1]).normalize().scale(1.5));
                            p.serverLevel().addFreshEntity(arrow);
                        }
                    });
                } else if (t == 84 || t == 89 || t == 95) {
                    shoot(mc, "aegisoverlay_0_dir" + (t == 84 ? 0 : t == 89 ? 1 : 2));
                    if (t == 89) { // a zombie kept in the field, in front and to the right (walled in), for the next frame
                        command(server, "summon zombie ~2 ~ ~2 {NoAI:1b,PersistenceRequired:1b,Tags:[\"looks_press\"]}");
                    }
                } else if (t == 93) {
                    command(server, "tp @e[tag=looks_press] ~1.5 ~ ~2");
                } else if (t == 94) {
                    ClientConfig.setStyle("aegis_overlay", "FRAME");
                } else if (t == 97) {
                    shoot(mc, "aegisoverlay_1_dir0");
                    command(server, "kill @e[tag=looks_press]");
                } else if (t == 96) {
                    mc.options.hideGui = true;
                    ClientConfig.setStyle("aegis_overlay", "FRAME");
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        p.getMainHandItem().use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND); // off
                        final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(AbyssalDepths.CONVERGENCE.get());
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                        stack.use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                    });
                    ClientConfig.setStyle("aegis", "PIXELS");
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                } else if (t == 99) {
                    command(server, "tp @s ~ ~ ~7 0 25"); // in the middle of the wall, facing south, a little down
                } else if (t >= 100 && t < 100 + convergence.length * 10) {
                    final int k = (t - 100) / 10;
                    if ((t - 100) % 10 == 0) {
                        ClientConfig.setStyle("convergence", convergence[k].name());
                    } else if ((t - 100) % 10 == 6) {
                        shoot(mc, "convlooks_0_dir" + k);
                    }
                } else if (t == 152) {
                    ClientConfig.setStyle("convergence", "PIXEL_EDGES");
                    command(server, "tp @s ~ ~ ~-2.4 180 45"); // at the wall's edge, looking out and down
                } else if (t == 160) {
                    shoot(mc, "convlooks_1_dir0");
                    command(server, "tp @s ~ ~ ~ 180 -30");
                } else if (t == 166) {
                    shoot(mc, "convlooks_1_dir1"); // looking up and out
                    command(server, "tp @s ~ ~-0.6 ~-1.2 90 10"); // on the ground beside the wall, sunk a little: no view
                } else if (t == 172) {
                    shoot(mc, "convlooks_1_dir2");
                    command(server, "tp @s " + tntAt.getX() + ".5 " + tntAt.getY() + " " + (tntAt.getZ() - 4) + ".5 0 15"); // out in the open, the camera facing the player: the layer on them
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
                } else if (t == 175) {
                    shoot(mc, "convlayer_0_dir0");
                    ClientConfig.setStyle("convergence_layer", "OVER");
                } else if (t == 178) {
                    shoot(mc, "convlayer_0_dir1");
                    ClientConfig.setStyle("convergence_layer", "SKIN");
                } else if (t >= 180 && t < 300) {
                    // the layer coming on, in each style: off, on again, a shot halfway
                    final ClientConfig.ConvergenceApply[] applies = ClientConfig.ConvergenceApply.values();
                    final int k = (t - 180) / 30, at = (t - 180) % 30; // off, gone, on again, a shot halfway
                    if (at == 0) {
                        ClientConfig.setStyle("convergence_apply", applies[k].name());
                    }
                    if (at == 0 || at == 18) {
                        server.execute(() -> {
                            final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                            p.getMainHandItem().use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                        });
                    } else if (at == 26) {
                        shoot(mc, "convapply_0_dir" + k);
                    }
                } else if (t == 300) {
                    ClientConfig.setStyle("convergence_apply", "DISSOLVE");
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        p.getMainHandItem().use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                    });
                    command(server, "tp @s " + tntAt.getX() + ".5 " + tntAt.getY() + " " + (tntAt.getZ() - 2) + ".5 0 30"); // back on the platform, facing the wall
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                } else if (t >= 310 && t < 310 + ClientConfig.RepulsionWave.values().length * 25) {
                    final ClientConfig.RepulsionWave[] waves = ClientConfig.RepulsionWave.values();
                    final int k = (t - 310) / 25;
                    if ((t - 310) % 25 == 0) {
                        ClientConfig.setStyle("repulsion", waves[k].name());
                    } else if ((t - 310) % 25 == 2) {
                        server.execute(() -> {
                            final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                            redero.abyssaldepths.artifact.RepulsionItem.wave(p.serverLevel(), p, 6.0F, 1.2F);
                        });
                    } else if ((t - 310) % 25 == 7) {
                        shoot(mc, "repulsionlooks_0_dir" + k);
                    }
                } else if (t > 310 + ClientConfig.RepulsionWave.values().length * 25) {
                    ClientConfig.setStyle("repulsion", "SHOCK");
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    command(server, "gamemode spectator");
                    step = Step.NEXT;
                }
            }
            case SMALL -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final int t = ++wait;
                if (t == 1) {
                    command(server, "gamemode survival");
                    command(server, "tp @s ~ ~40 ~ 0 20");
                    command(server, "effect give @s resistance 600 4 true");
                    command(server, "effect give @s saturation 600 1 true");
                } else if (t == 6) {
                    command(server, "fill ~-12 ~-2 ~-12 ~12 ~8 ~16 air");
                    command(server, "fill ~-12 ~-1 ~-12 ~12 ~-1 ~16 stone");
                    command(server, "spawnpoint @s ~ ~ ~-6");
                } else if (t == 12) {
                    // Anchor: a blow does not move the player
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        use(p, AbyssalDepths.ANCHOR.get(), 0);
                        p.knockback(2.0, 1.0, 0.0);
                        AbyssalDepths.LOGGER.info("small check anchor: anchored {}, knockback resistance {}, motion after a blow {}",
                                p.hasEffect(AbyssalDepths.ANCHORED), p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE),
                                p.getDeltaMovement().horizontalDistance());
                        p.removeEffect(AbyssalDepths.ANCHORED);
                    });
                    for (int i = 0; i < 3; i++) {
                        command(server, "summon zombie ~" + (i * 2 - 2) + " ~ ~3 {PersistenceRequired:1b,Tags:[\"small_test\"]}");
                    }
                } else if (t == 20) {
                    smallCheck(server, "repulsion before");
                    server.execute(() -> use(server.getPlayerList().getPlayers().get(0), AbyssalDepths.REPULSION.get(), 0));
                } else if (t == 22) {
                    shoot(mc, "small_0_dir0"); // the wave
                } else if (t == 35) {
                    smallCheck(server, "repulsion after");
                    command(server, "tp @e[tag=small_test] ~ ~ ~2");
                } else if (t == 40) {
                    // Aegis, mastered: zombies close by are pushed out, an arrow flies back
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(AbyssalDepths.AEGIS.get());
                        redero.abyssaldepths.artifact.ArtifactOwnership.setMastery(p, stack, 100);
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                        stack.use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                    });
                    command(server, "summon skeleton ~ ~ ~12 {NoAI:1b,PersistenceRequired:1b,Tags:[\"small_shooter\"]}");
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                } else if (t == 60) {
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        for (net.minecraft.world.entity.Entity e : p.serverLevel().getAllEntities()) {
                            if (e.getTags().contains("small_shooter")) {
                                final net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(p.serverLevel(),
                                        (net.minecraft.world.entity.LivingEntity) e, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
                                arrow.setPos(p.getX(), p.getY() + 1.0, p.getZ() + 7.0);
                                arrow.setDeltaMovement(0, 0, -2.0);
                                arrow.addTag("small_arrow");
                                p.serverLevel().addFreshEntity(arrow);
                            }
                        }
                    });
                } else if (t == 62) {
                    shoot(mc, "small_0_dir1"); // the field
                    AbyssalDepths.LOGGER.info("small check aegis client: holds {}", redero.abyssaldepths.artifact.AegisItem.clientHolds(mc.player));
                } else if (t == 66) {
                    smallCheck(server, "aegis");
                    server.execute(() -> {
                        for (net.minecraft.world.entity.Entity e : server.getPlayerList().getPlayers().get(0).serverLevel().getAllEntities()) {
                            if (e.getTags().contains("small_arrow")) {
                                AbyssalDepths.LOGGER.info("small check aegis arrow: motion z {} (it flew in at -2), owner {}",
                                        String.format("%.2f", e.getDeltaMovement().z), ((net.minecraft.world.entity.projectile.Projectile) e).getOwner() instanceof ServerPlayer);
                            }
                        }
                    });
                } else if (t == 70) {
                    command(server, "kill @e[tag=small_test]");
                    command(server, "kill @e[tag=small_shooter]");
                    command(server, "clear @s abyssaldepths:aegis");
                    // Convergence: a wall in front, walk into it
                    command(server, "fill ~-3 ~ ~2 ~3 ~3 ~8 stone");
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(AbyssalDepths.CONVERGENCE.get());
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                        stack.use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                        p.setYRot(0.0F);
                        p.setXRot(10.0F);
                    });
                } else if (t == 76) {
                    mc.player.setYRot(0.0F);
                    mc.player.setXRot(10.0F);
                    mc.options.keyUp.setDown(true);
                } else if (t == 100) {
                    mc.options.keyUp.setDown(false);
                    shoot(mc, "small_1_dir0"); // inside the wall
                    AbyssalDepths.LOGGER.info("small check convergence: client z {} y {} pose {} in wall {}", String.format("%.2f", mc.player.getZ()),
                            String.format("%.2f", mc.player.getY()), mc.player.getPose(), !mc.level.noCollision(mc.player, mc.player.getBoundingBox().deflate(0.01)));
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        AbyssalDepths.LOGGER.info("small check convergence: server z {} health {}", String.format("%.2f", p.getZ()), p.getHealth());
                    });
                } else if (t == 104) {
                    mc.player.setXRot(-20.0F);
                } else if (t == 106) {
                    shoot(mc, "small_1_dir1"); // inside, looking up a little
                } else if (t == 110) {
                    server.execute(() -> { // switched off inside: out into the open
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        p.getMainHandItem().use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                    });
                } else if (t == 115) {
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        AbyssalDepths.LOGGER.info("small check convergence off: server z {} y {} in wall {}", String.format("%.2f", p.getZ()), String.format("%.2f", p.getY()),
                                !p.level().noCollision(p, p.getBoundingBox().deflate(0.01)));
                        
                        final net.minecraft.world.item.ItemStack recall = new net.minecraft.world.item.ItemStack(AbyssalDepths.RECALL.get());
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, recall);
                    });
                } else if (t == 120) {
                    command(server, "tp @s ~ ~ ~-12");
                } else if (t == 125) {
                    mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
                } else if (t == 200) {
                    mc.options.keyUse.setDown(false);
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        AbyssalDepths.LOGGER.info("small check recall: at {} spawn {} charge {}", p.blockPosition().toShortString(),
                                p.getRespawnPosition() == null ? "none" : p.getRespawnPosition().toShortString(), AbyssalDepths.RECALL.get().charge(p.getMainHandItem()));
                    });
                } else if (t > 205) {
                    command(server, "gamemode spectator");
                    step = Step.NEXT;
                }
            }
            case FIRMAMENT -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final int t = ++wait;
                if (t == 1) {
                    command(server, "gamemode survival");
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(AbyssalDepths.FIRMAMENT.get());
                        redero.abyssaldepths.artifact.ArtifactOwnership.setMastery(p, stack, 100); // a soft landing, Speed II
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                        p.setHealth(p.getMaxHealth());
                    });
                } else if (t == 5) { // switched on, then put away in the inventory: it works from there
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        p.getMainHandItem().use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                        p.getInventory().setItem(20, p.getMainHandItem().copy());
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                    });
                } else if (t == 8) {
                    command(server, "tp @s ~ ~40 ~ 0 35");
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                } else if (t == 10) {
                    command(server, "fill ~-6 ~-12 ~-6 ~6 ~4 ~16 air");
                    command(server, "fill ~-6 ~-12 ~-6 ~6 ~-12 ~16 stone");
                } else if (t == 40 || t == 75 || t == 100 || t == 104 || t == 160) {
                    final String when = t == 40 ? "hovering" : t == 75 ? "after climbing" : t == 100 ? "after walking" : t == 104 ? "let go" : "landed";
                    AbyssalDepths.LOGGER.info("firmament check {}: client y {} x {} z {} onGround {} using {}", when, String.format("%.2f", mc.player.getY()),
                            String.format("%.2f", mc.player.getX()), String.format("%.2f", mc.player.getZ()), mc.player.onGround(), mc.player.isUsingItem());
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        AbyssalDepths.LOGGER.info("firmament check {}: server y {} health {} charge {} speed {} connected {}", when, String.format("%.2f", p.getY()), p.getHealth(),
                                AbyssalDepths.FIRMAMENT.get().charge(p.getInventory().getItem(20)), p.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED),
                                !p.hasDisconnected());
                    });
                    if (t == 40) {
                        shoot(mc, "firmament_0_dir0");
                        mc.options.keyJump.setDown(true);
                    } else if (t == 75) {
                        mc.options.keyJump.setDown(false);
                        mc.options.keyUp.setDown(true);
                    } else if (t == 100) {
                        mc.options.keyUp.setDown(false);
                        shoot(mc, "firmament_0_dir1");
                        server.execute(() -> { // switched off from the inventory slot (as the item's own use would)
                            final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                            final net.minecraft.world.item.ItemStack stack = p.getInventory().getItem(20);
                            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                            stack.use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                            p.getInventory().setItem(20, stack);
                            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                        });
                    } else if (t == 104) {
                        shoot(mc, "firmament_0_dir2"); // the steps fading
                    }
                } else if (t == 170 || t == 230) {
                    // TNT lit by the player, who then crosses into the shadow (it should still blow up the world);
                    // then TNT sent into the shadow by Oblivion (it should not)
                    final boolean oblivion = t == 230;
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        tntAt = p.blockPosition().offset(oblivion ? -4 : 4, 0, 0);
                        p.serverLevel().setBlockAndUpdate(tntAt.above(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                        final net.minecraft.world.entity.item.PrimedTnt tnt = new net.minecraft.world.entity.item.PrimedTnt(p.serverLevel(),
                                tntAt.getX() + 0.5, tntAt.getY(), tntAt.getZ() + 1.5, oblivion ? null : p);
                        tnt.setFuse(40);
                        p.serverLevel().addFreshEntity(tnt);
                        if (oblivion) {
                            AbyssalDepths.LOGGER.info("firmament check tnt oblivion sent {}", redero.abyssaldepths.artifact.OblivionItem.cast(p));
                        }
                    });
                } else if (t > 170 && t < 220) {
                    server.execute(() -> redero.abyssaldepths.artifact.Shades.mark(server.getPlayerList().getPlayers().get(0)));
                } else if (t == 225 || t == 285) {
                    final String when = t == 225 ? "lit, then parallax (stone should be gone)" : "oblivion (stone should stay)";
                    server.execute(() -> AbyssalDepths.LOGGER.info("firmament check tnt {}: stone there {}", when,
                            server.getPlayerList().getPlayers().get(0).serverLevel().getBlockState(tntAt.above()).is(net.minecraft.world.level.block.Blocks.STONE)));
                } else if (t > 290) {
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    command(server, "gamemode spectator");
                    step = Step.NEXT;
                }
            }
            case NEWARTIFACTS -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final int t = ++wait;
                if (t == 1) {
                    command(server, "tp @s ~ ~40 ~ 0 20");
                    command(server, "gamemode survival");
                    command(server, "effect give @s resistance 600 4 true");
                } else if (t == 8) {
                    command(server, "fill ~-8 ~-1 ~-2 ~8 ~-1 ~16 stone");
                } else if (t == 12) {
                    for (int i = 0; i < 3; i++) {
                        command(server, "summon zombie ~" + (i * 3 - 3) + " ~ ~10 {PersistenceRequired:1b,Tags:[\"undertow_test\"],ArmorItems:[{},{},{},{id:\"leather_helmet\",count:1}]}");
                    }
                } else if (t == 20) {
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        final redero.abyssaldepths.entity.UndertowEntity anomaly = new redero.abyssaldepths.entity.UndertowEntity(AbyssalDepths.UNDERTOW_ENTITY.get(), p.serverLevel());
                        anomaly.moveTo(p.getX(), p.getY() + 1.0, p.getZ() + 6.0);
                        anomaly.start(p, 60, 10.0F, true);
                        p.serverLevel().addFreshEntity(anomaly);
                        artifactCheck(server, "undertow before", e -> e.getTags().contains("undertow_test"), p.position().add(0, 1, 6));
                    });
                } else if (t == 50) {
                    shoot(mc, "newartifacts_0_dir0"); // pulling
                    server.execute(() -> artifactCheck(server, "undertow pulling", e -> e.getTags().contains("undertow_test"),
                            server.getPlayerList().getPlayers().get(0).position().add(0, 1, 6)));
                } else if (t == 77) {
                    shoot(mc, "newartifacts_1_dir0"); // collapsing
                } else if (t == 81) {
                    shoot(mc, "newartifacts_1_dir1"); // the burst
                } else if (t == 84) {
                    shoot(mc, "newartifacts_1_dir2"); // the shockwave, the second wave starting
                } else if (t == 85) {
                    server.execute(() -> artifactCheck(server, "undertow after the burst", e -> e.getTags().contains("undertow_test"),
                            server.getPlayerList().getPlayers().get(0).position().add(0, 1, 6)));
                    command(server, "kill @e[tag=undertow_test]");
                } else if (t == 90) {
                    command(server, "give @s abyssaldepths:counterweight[abyssaldepths:artifact_active=true]");
                } else if (t == 100) {
                    server.execute(() -> AbyssalDepths.LOGGER.info("artifact check counterweight: gravity {}",
                            server.getPlayerList().getPlayers().get(0).getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY)));
                    command(server, "clear @s abyssaldepths:counterweight");
                } else if (t == 105) {
                    command(server, "effect give @s poison 60 1");
                    command(server, "effect give @s speed 60 1");
                } else if (t == 110) {
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(AbyssalDepths.SEVERANCE.get());
                        redero.abyssaldepths.artifact.ArtifactOwnership.setMastery(p, stack, 100);
                        AbyssalDepths.SEVERANCE.get().setCharge(stack, 3);
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                        stack.use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                        p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, 600)); // immune now
                        AbyssalDepths.LOGGER.info("artifact check severance: effects after {}", p.getActiveEffects().stream()
                                .map(e -> e.getEffect().getRegisteredName()).toList());
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                    });
                } else if (t == 120) {
                    command(server, "summon zombie ~2 ~ ~10 {PersistenceRequired:1b,Tags:[\"mirage_test\"]}");
                } else if (t == 160) {
                    server.execute(() -> {
                        final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(AbyssalDepths.MIRAGE.get());
                        AbyssalDepths.MIRAGE.get().setCharge(stack, 2);
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                        stack.use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                    });
                    command(server, "tp @s ~4 ~ ~");
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                } else if (t == 190) {
                    shoot(mc, "newartifacts_0_dir1"); // the decoy, the purple player
                    server.execute(() -> {
                        for (net.minecraft.world.entity.Entity e : server.getPlayerList().getPlayers().get(0).serverLevel().getAllEntities()) {
                            if (e.getTags().contains("mirage_test") && e instanceof net.minecraft.world.entity.Mob mob) {
                                AbyssalDepths.LOGGER.info("artifact check mirage: zombie targets {}", mob.getTarget() == null ? "nothing" : mob.getTarget().getType().toShortString());
                            }
                        }
                    });
                } else if (t == 200) {
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    command(server, "kill @e[tag=mirage_test]");
                    command(server, "gamemode spectator");
                    step = Step.NEXT;
                }
            }
            case OBLIVION -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final int t = ++wait;
                final int cast = 40;
                if (t == 1) {
                    command(server, "tp @s ~ ~40 ~ 0 15");
                } else if (t == 8) {
                    command(server, "fill ~-6 ~-1 ~2 ~6 ~-1 ~14 stone");
                } else if (t == 12) {
                    command(server, "summon zombie ~2 ~ ~5 {NoAI:1b,PersistenceRequired:1b,Tags:[\"oblivion_test\"]}");
                    command(server, "summon cow ~-2 ~ ~5 {NoAI:1b,Tags:[\"oblivion_test\"]}");
                    command(server, "summon wither ~0 ~ ~10 {NoAI:1b,Tags:[\"oblivion_test\"]}");
                } else if (t == cast - 5) {
                    shoot(mc, "oblivion_0_dir0"); // before
                } else if (t == cast) {
                    command(server, "abyssaldepths oblivion");
                } else if (t == cast + 8) {
                    shoot(mc, "oblivion_1_dir0"); // dissolving
                } else if (t == cast + 20 * 15 + 12) {
                    shoot(mc, "oblivion_1_dir1"); // the wither coming back, pixel by pixel
                } else if (t == cast + 40) {
                    shoot(mc, "oblivion_0_dir1"); // gone
                    oblivionCheck(server, "just after");
                } else if (t == cast + 20 * 16) {
                    shoot(mc, "oblivion_0_dir2"); // the wither (300 health: 15 s) back, the others still away
                    oblivionCheck(server, "after 16 s");
                } else if (t == cast + 20 * 31) {
                    shoot(mc, "oblivion_0_dir3"); // all back
                    oblivionCheck(server, "after 31 s");
                } else if (t == cast + 20 * 31 + 5) {
                    command(server, "kill @e[tag=oblivion_test]");
                    mc.options.hideGui = false; // the screen effects show on the GUI layer
                } else if (t > cast + 20 * 31 + 5 && t <= cast + 20 * 31 + 5 + 4 * 40) {
                    // every style of the cast: zombies round, cast, shots early and late
                    final int at = t - (cast + 20 * 31 + 6), k = at / 40, in = at % 40;
                    final redero.abyssaldepths.ClientConfig.OblivionLook look = redero.abyssaldepths.ClientConfig.OblivionLook.values()[k];
                    if (in == 0) { // the charge-up, held as a player would
                        redero.abyssaldepths.ClientConfig.setStyle("oblivion", look.name());
                        mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                        server.execute(() -> {
                            final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(AbyssalDepths.OBLIVION.get()));
                        });
                    } else if (in == 3) {
                        mc.options.keyUse.setDown(true);
                        mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
                    } else if (in == 18 || in == 34) {
                        shoot(mc, "oblivionlooks_" + (in == 18 ? 0 : 1) + "_dir" + k); // halfway; just cast
                    } else if (in == 37) {
                        mc.options.keyUse.setDown(false);
                        mc.player.getCooldowns().removeCooldown(AbyssalDepths.OBLIVION.get());
                        server.execute(() -> server.getPlayerList().getPlayers().get(0).getCooldowns().removeCooldown(AbyssalDepths.OBLIVION.get()));
                    }
                } else if (t > cast + 20 * 31 + 5 + 4 * 40) {
                    redero.abyssaldepths.ClientConfig.setStyle("oblivion", "INK");
                    mc.options.hideGui = true;
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    step = Step.NEXT;
                }
            }
            case VANISH -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final int t = ++wait;
                if (t == 1) {
                    command(server, "tp @s ~ ~40 ~ 0 15"); // facing south, a little down
                } else if (t == 10) {
                    command(server, "fill ~-4 ~-1 ~2 ~4 ~-1 ~9 stone");
                } else if (t == 15) {
                    fake = new net.minecraft.client.player.RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Shade"));
                    fake.setPos(mc.player.getX(), mc.player.getY(), mc.player.getZ() + 4.0);
                    fake.setYRot(180.0F);
                    fake.yBodyRot = fake.yBodyRotO = fake.yHeadRot = fake.yHeadRotO = 180.0F; // facing the camera
                    mc.level.addEntity(fake);
                    fake.tickCount = 100; // not "just joined": that skips the vanishing
                } else if (t >= 20 && t < 620) {
                    // six styles, 100 ticks each, the fake one walking sideways: vanish, a shot halfway, come back, a shot halfway
                    final int k = (t - 20) / 100, at = (t - 20) % 100;
                    final redero.abyssaldepths.ClientConfig.ShadeVanishStyle style = redero.abyssaldepths.ClientConfig.ShadeVanishStyle.values()[k];
                    final int half = new int[] {13, 18, 8, 23, 17, 13}[k]; // about 45 % of each style's time, in ticks
                    if (at < 90) { // walks to the side and back, moved like the server moves remote players
                        final double x = mc.player.getX() + 1.5 * Math.sin((at < 45 ? at : 90 - at) * 0.07);
                        fake.lerpTo(x, fake.getY(), fake.getZ(), 180.0F + 20.0F * (float) Math.sin(at * 0.2), 0.0F, 3);
                        fake.lerpHeadTo(180.0F + 40.0F * (float) Math.sin(at * 0.15), 3);
                    }
                    if (k == 5 && at >= 4 && at <= 12 && at % 2 == 0) { // dissolve: a few frames in a row, to judge the motion
                        shoot(mc, "vanishmotion_" + (at / 2) + "_dir0");
                    }
                    if (at == 0) {
                        redero.abyssaldepths.ClientConfig.setShadeVanish(style);
                        redero.abyssaldepths.artifact.Shades.clientShade(fake.getId(), 1_000_000, mc.level.getGameTime());
                    } else if (at == half) {
                        shoot(mc, "vanish_" + k + "_dir0");
                    } else if (at == 60) {
                        redero.abyssaldepths.artifact.Shades.clientShade(fake.getId(), 0, mc.level.getGameTime());
                    } else if (at == 60 + half) {
                        shoot(mc, "vanish_" + k + "_dir1");
                    }
                } else if (t == 620) {
                    // inside the shadow, the fake one a shade too: seen, in the shadow's look
                    redero.abyssaldepths.artifact.Shades.clientShade(fake.getId(), 1_000_000, mc.level.getGameTime());
                    command(server, "give @s abyssaldepths:parallax[abyssaldepths:artifact_active=true]");
                } else if (t == 670 || t == 710) {
                    // seen from inside: the fake one leaves the shadow and comes back, in the shadow's look all the while
                    redero.abyssaldepths.artifact.Shades.clientShade(fake.getId(), t == 670 ? 0 : 1_000_000, mc.level.getGameTime());
                } else if (t == 680 || t == 720) {
                    shoot(mc, "vanish_7_dir" + (t == 680 ? 0 : 1));
                } else if (t >= 740 && t < 860 && (t - 740) % 30 == 0) {
                    // every look of shades, the fake one seen from inside the shadow
                    final redero.abyssaldepths.ClientConfig.ShadeLook look = redero.abyssaldepths.ClientConfig.ShadeLook.values()[(t - 740) / 30];
                    redero.abyssaldepths.ClientConfig.setShadeLook(look);
                } else if (t >= 740 && t < 860 && (t - 740) % 30 == 25) {
                    shoot(mc, "vanish_6_dir" + (t - 740) / 30);
                } else if (t == 865) {
                    redero.abyssaldepths.ClientConfig.setShadeLook(redero.abyssaldepths.ClientConfig.ShadeLook.SILHOUETTE);
                    command(server, "clear @s abyssaldepths:parallax");
                    fake.discard();
                    redero.abyssaldepths.ClientConfig.setShadeVanish(redero.abyssaldepths.ClientConfig.ShadeVanishStyle.DISSOLVE);
                    step = Step.NEXT;
                }
            }
            case PARALLAX -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                final net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                switch (++wait) {
                    case 1 -> {
                        command(server, "tp @s ~ ~40 ~");
                        command(server, "gamemode survival");
                    }
                    case 10 -> {
                        command(server, "fill ~-1 ~-1 ~-1 ~1 ~-1 ~1 stone");
                        command(server, "setblock ~2 ~ ~2 gold_block");
                        command(server, "effect give @s resistance 30 4 true"); // no fall damage before the test
                    }
                    case 20 -> shoot(mc, "parallax_0_dir0"); // before: colours as usual
                    case 25 -> command(server, "give @s abyssaldepths:parallax[abyssaldepths:artifact_active=true]");
                    case 30 -> {
                        command(server, "effect clear @s");
                        command(server, "summon item ~ ~ ~ {Item:{id:\"minecraft:diamond\",count:1},PickupDelay:0,Tags:[\"parallax_test\"]}");
                        command(server, "summon zombie ~1 ~ ~4 {PersistenceRequired:1b,Tags:[\"parallax_test\"]}"); // in view: hidden in the shadow
                    }
                    case 40 -> {
                        final net.minecraft.core.BlockPos gold = player.blockPosition().offset(2, 0, 2);
                        AbyssalDepths.LOGGER.info("parallax check: shade on server {}, on client {}, breaking the gold works {}",
                                redero.abyssaldepths.artifact.Shades.isShade(player), redero.abyssaldepths.artifact.Shades.isShade(mc.player),
                                player.gameMode.destroyBlock(gold));
                        final float health = player.getHealth();
                        player.hurt(player.damageSources().generic(), 5.0F);
                        AbyssalDepths.LOGGER.info("parallax check: health {} -> {} after 5 damage", health, player.getHealth());
                    }
                    case 31 -> shoot(mc, "parallax_0_dir1"); // the crossing (1.6 s by default): black spots spreading
                    case 41 -> shoot(mc, "parallax_0_dir2"); // all black
                    case 47 -> shoot(mc, "parallax_0_dir3"); // falling apart into purple pixels
                    case 70 -> shoot(mc, "parallax_1_dir0"); // the shadow: dark, drained, no zombie, purple pixels
                    case 80 -> {
                        command(server, "execute if entity @e[type=item,tag=parallax_test] run say parallax check: the diamond is still on the ground");
                        command(server, "execute unless entity @e[type=item,tag=parallax_test] run say parallax check: the diamond was picked up");
                        final var zombie = player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie.class, player.getBoundingBox().inflate(16));
                        AbyssalDepths.LOGGER.info("parallax check: zombie targets the shade {}", zombie.stream().anyMatch(z -> z.getTarget() == player));
                        command(server, "kill @e[tag=parallax_test]");
                        command(server, "clear @s abyssaldepths:parallax");
                    }
                    case 90 -> AbyssalDepths.LOGGER.info("parallax check: after: shade on server {}, on client {}",
                            redero.abyssaldepths.artifact.Shades.isShade(player), redero.abyssaldepths.artifact.Shades.isShade(mc.player));
                    case 88 -> shoot(mc, "parallax_1_dir1"); // leaving: the pixels turning black
                    case 125 -> shoot(mc, "parallax_1_dir2"); // back: colours again
                    case 130 -> {
                        command(server, "gamemode spectator");
                        step = Step.NEXT;
                    }
                    default -> {
                    }
                }
            }
            case REPRISE -> {
                final MinecraftServer server = mc.getSingleplayerServer();
                switch (++wait) {
                    case 1 -> command(server, "tp @s ~ ~40 ~"); // open air, so what is built can be seen
                    case 10 -> {
                        command(server, "setblock ~2 ~-2 ~2 chest");
                        command(server, "item replace block ~2 ~-2 ~2 container.0 with diamond 5");
                    }
                    case 20 -> command(server, "give @s abyssaldepths:reprise"); // starts the recording
                    case 80 -> {
                        // the world changes: the chest's contents, new blocks, a creature, the player
                        command(server, "item replace block ~2 ~-2 ~2 container.0 with emerald 3");
                        command(server, "fill ~-2 ~-2 ~4 ~2 ~2 ~6 gold_block");
                        command(server, "summon pig ~ ~ ~8 {NoAI:1b,Tags:[\"reprise_test\"]}");
                        command(server, "xp add @s 10 levels");
                    }
                    case 110 -> {
                        shoot(mc, "reprise_0_dir0"); // before
                        command(server, "data get block ~2 ~-2 ~2 Items");
                        command(server, "xp query @s levels");
                        command(server, "abyssaldepths reprise status");
                    }
                    case 120 -> command(server, "abyssaldepths reprise slow"); // late on purpose: the waiting circle shows
                    case 140, 160, 175, 200, 229, 233 -> shoot(mc, "reprise_0_anim" + wait); // eating, middle, waiting, withdrawing
                    case 260 -> {
                        shoot(mc, "reprise_0_dir1"); // after
                        command(server, "data get block ~2 ~-2 ~2 Items");
                        command(server, "xp query @s levels");
                        command(server, "execute if block ~ ~ ~5 gold_block run say reprise check: gold is still there");
                        command(server, "execute unless block ~ ~ ~5 gold_block run say reprise check: gold is gone");
                        command(server, "execute if entity @e[tag=reprise_test] run say reprise check: the pig is still there");
                        command(server, "execute unless entity @e[tag=reprise_test] run say reprise check: the pig is gone");
                        command(server, "abyssaldepths reprise status");
                        step = Step.NEXT;
                    }
                    default -> {
                    }
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
                if (wait == 160) {
                    shoot(mc, "haven_0_dir2"); // in the room (or on the islands)
                    mc.player.setXRot(60.0F);
                }
                if (wait == 170) {
                    shoot(mc, "haven_0_down");
                    mc.player.setXRot(-20.0F);
                }
                if (wait == 175) {
                    shoot(mc, "haven_1_dir1"); // the sky
                    command(server, "abyssaldepths innerhaven");
                }
                if (wait == 290) {
                    shoot(mc, "haven_0_dir3"); // back
                    mc.player.setXRot(80.0F);
                }
                if (wait == 300) {
                    shoot(mc, "haven_1_dir0"); // the mark of the place left (Inner Haven mastered in the inventory)
                    step = Step.NEXT;
                }
            }
            case PREVIEW -> {
                // three shots a quarter second apart: animations (the title shimmer) show in the difference
                if (++wait == 20 || wait == 25 || wait == 30) {
                    Screenshot.grab(mc.gameDirectory, "autoshot_artifacts_0_dir" + (wait - 20) / 5 + ".png", mc.getMainRenderTarget(), msg -> { });
                } else if (wait >= 32 && wait < 72 && (wait - 32) % 10 == 0) {
                    // the showcase, the new artifacts at full mastery
                    final String id = new String[] {"undertow", "counterweight", "severance", "mirage"}[(wait - 32) / 10];
                    mc.setScreen(new redero.abyssaldepths.client.ArtifactShowcaseScreen().show(id, 100));
                } else if (wait >= 32 && wait < 72 && (wait - 32) % 10 == 6) {
                    Screenshot.grab(mc.gameDirectory, "autoshot_showcase_0_dir" + (wait - 32) / 10 + ".png", mc.getMainRenderTarget(), msg -> { });
                } else if (wait >= 32 + 40 + 100 && wait < 32 + 40 + 100 + 48 && (wait - 172) % 4 == 0) {
                    Screenshot.grab(mc.gameDirectory, "autoshot_snake_" + (wait - 172) / 4 / 4 + "_dir" + (wait - 172) / 4 % 4 + ".png", mc.getMainRenderTarget(), msg -> { });
                } else if (wait == 75) {
                    mc.setScreen(new redero.abyssaldepths.client.WaypointScreen("minecraft:overworld|120|64|-35|42|107;minecraft:the_nether|8|70|-2|900|2;minecraft:overworld|-310|71|88|7300|3"));
                } else if (wait == 97) {
                    Screenshot.grab(mc.gameDirectory, "autoshot_showcase_1_dir0.png", mc.getMainRenderTarget(), msg -> { });
                } else if (wait == 101) {
                    mc.setScreen(new redero.abyssaldepths.client.ArtifactShowcaseScreen());
                } else if (wait > 225) {
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

    /** Uses an artifact (charged, at this mastery) as the player would, from the main hand. */
    private static void use(ServerPlayer p, redero.abyssaldepths.artifact.ArtifactItem item, int mastery) {
        final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
        redero.abyssaldepths.artifact.ArtifactOwnership.setMastery(p, stack, mastery);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
        stack.use(p.serverLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
    }

    /** Logs how far the small artifacts' test zombies are from the player. */
    private static void smallCheck(MinecraftServer server, String when) {
        server.execute(() -> {
            final ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            final java.util.List<String> d = new java.util.ArrayList<>();
            for (net.minecraft.world.entity.Entity e : p.serverLevel().getAllEntities()) {
                if (e.getTags().contains("small_test")) {
                    d.add(String.format("%.1f", e.distanceTo(p)));
                }
            }
            AbyssalDepths.LOGGER.info("small check {}: zombies at {}", when, d);
        });
    }

    /** Logs distance to a point and health of the test's creatures. */
    private static void artifactCheck(MinecraftServer server, String when, java.util.function.Predicate<net.minecraft.world.entity.Entity> which,
            net.minecraft.world.phys.Vec3 centre) {
        final StringBuilder out = new StringBuilder();
        for (net.minecraft.world.entity.Entity e : server.getPlayerList().getPlayers().get(0).serverLevel().getAllEntities()) {
            if (which.test(e) && e instanceof net.minecraft.world.entity.LivingEntity living) {
                out.append(String.format("%.1f blocks, %.0f hp; ", living.position().distanceTo(centre), living.getHealth()));
            }
        }
        AbyssalDepths.LOGGER.info("artifact check {}: {}", when, out);
    }

    /** Logs which of the Oblivion test's creatures are in the shadow. */
    private static void oblivionCheck(MinecraftServer server, String when) {
        final StringBuilder out = new StringBuilder();
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                if (e.getTags().contains("oblivion_test")) {
                    out.append(e.getType().getDescription().getString()).append('=').append(redero.abyssaldepths.artifact.Shades.isShade(e) ? "shade" : "world").append(' ');
                }
            }
        }
        AbyssalDepths.LOGGER.info("oblivion check {}: {}", when, out);
    }

    /** The fake player of the vanishing test. */
    private static net.minecraft.client.player.RemotePlayer fake;

    /** Puts the window in the middle of a monitor other than the main one, if there is one (out of the way). */
    private static void toSecondMonitor(Minecraft mc) {
        final long window = mc.getWindow().getWindow();
        final org.lwjgl.PointerBuffer monitors = org.lwjgl.glfw.GLFW.glfwGetMonitors();
        final long primary = org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor();
        for (int i = 0; monitors != null && i < monitors.limit(); i++) {
            final long monitor = monitors.get(i);
            final org.lwjgl.glfw.GLFWVidMode mode = org.lwjgl.glfw.GLFW.glfwGetVideoMode(monitor);
            if (monitor == primary || mode == null) {
                continue;
            }
            final int[] mx = new int[1], my = new int[1];
            org.lwjgl.glfw.GLFW.glfwGetMonitorPos(monitor, mx, my);
            final int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
            org.lwjgl.glfw.GLFW.glfwSetWindowPos(window, mx[0] + Math.max(0, (mode.width() - w) / 2), my[0] + Math.max(30, (mode.height() - h) / 2));
            return;
        }
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
