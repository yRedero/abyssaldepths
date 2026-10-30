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

    private enum Step { TITLE, CREATING, LOADING, NEXT, FINDING, SETTLING, SHOOTING, PREVIEW, HAVEN, REPRISE, INTERLUDE, PARALLAX, VANISH, OBLIVION, NEWARTIFACTS, DONE }

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
                        redero.abyssaldepths.artifact.ArtifactItem.setMastery(stack, 100);
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
                } else if (wait == 75) {
                    mc.setScreen(new redero.abyssaldepths.client.WaypointScreen("minecraft:overworld|120|64|-35|42|107;minecraft:the_nether|8|70|-2|900|2;minecraft:overworld|-310|71|88|7300|3"));
                } else if (wait == 97) {
                    Screenshot.grab(mc.gameDirectory, "autoshot_showcase_1_dir0.png", mc.getMainRenderTarget(), msg -> { });
                } else if (wait > 100) {
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
