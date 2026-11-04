package redero.abyssaldepths;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.artifact.ArtifactItem;
import redero.abyssaldepths.artifact.InnerSelf;
import redero.abyssaldepths.artifact.reprise.Reprise;
import redero.abyssaldepths.artifact.reprise.RepriseRecorder;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Test commands (operators only), to see the layer's effects without waiting for them:
 * <ul>
 * <li>{@code /abyssaldepths hallucinate footsteps|mob|digging|shadow}: a deep pressure hallucination.</li>
 * <li>{@code /abyssaldepths ambience groan|creature|steps|rockfall|gust}: one of the rare ambient sounds.</li>
 * <li>{@code /abyssaldepths storm start [seconds]} / {@code storm stop}: a storm in the silt dunes.</li>
 * <li>{@code /abyssaldepths mastery <percent>}: the mastery of the artifact in the main hand.</li>
 * <li>{@code /abyssaldepths charge <charge>}: its charge.</li>
 * <li>{@code /abyssaldepths innerhaven}: into the inner self or back, as with Inner Haven (no charge).</li>
 * <li>{@code /abyssaldepths reprise}: the world back as far as Reprise remembers (no charge);
 * {@code reprise status}: how far that is and how much is kept; {@code reprise slow}: the rewind 5 seconds
 * late, to see the animation wait for it.</li>
 * </ul>
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class LayerCommands {
    /** To the nearest set piece of a kind (generated as you arrive), standing off from it, looking at it. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> setPiece(String name, String label,
            java.util.function.BiFunction<net.minecraft.server.level.ServerLevel, net.minecraft.core.BlockPos, net.minecraft.core.BlockPos> nearest,
            double dx, double dy, double dz, float yaw, float pitch) {
        return Commands.literal(name).executes(c -> {
            final net.minecraft.server.level.ServerPlayer player = c.getSource().getPlayerOrException();
            final net.minecraft.core.BlockPos at = nearest.apply(player.serverLevel(), player.blockPosition());
            if (at == null) {
                c.getSource().sendFailure(Component.literal("No " + label.toLowerCase(java.util.Locale.ROOT) + " near"));
                return 0;
            }
            player.teleportTo(player.serverLevel(), at.getX() + dx, at.getY() + dy, at.getZ() + dz, yaw, pitch);
            c.getSource().sendSuccess(() -> Component.literal(label + " at " + at.getX() + " " + at.getY() + " " + at.getZ()), false);
            return 1;
        });
    }

    private static final String[] HALLUCINATIONS = {"footsteps", "mob", "digging", "shadow"};
    private static final String[] AMBIENCE = {"groan", "creature", "steps", "rockfall", "gust"};

    private LayerCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        final LiteralArgumentBuilder<CommandSourceStack> hallucinate = Commands.literal("hallucinate");
        for (String kind : HALLUCINATIONS) {
            hallucinate.then(Commands.literal(kind).executes(c -> send(c, "hallucinate/" + kind)));
        }
        // how shades vanish (client config): set the style, or play one on a copy of yourself
        final LiteralArgumentBuilder<CommandSourceStack> vanishStyle = Commands.literal("style");
        final LiteralArgumentBuilder<CommandSourceStack> vanishPlay = Commands.literal("play").executes(c -> send(c, "vanish_play/"));
        for (ClientConfig.ShadeVanishStyle style : ClientConfig.ShadeVanishStyle.values()) {
            final String name = style.name().toLowerCase(java.util.Locale.ROOT);
            vanishStyle.then(Commands.literal(name).executes(c -> send(c, "vanish_style/" + style.name())));
            if (style != ClientConfig.ShadeVanishStyle.NONE) {
                vanishPlay.then(Commands.literal(name).executes(c -> send(c, "vanish_play/" + style.name())));
            }
        }
        final LiteralArgumentBuilder<CommandSourceStack> shadeLook = Commands.literal("shadelook");
        for (ClientConfig.ShadeLook look : ClientConfig.ShadeLook.values()) {
            shadeLook.then(Commands.literal(look.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "shade_look/" + look.name())));
        }
        final LiteralArgumentBuilder<CommandSourceStack> ambience = Commands.literal("ambience");
        for (String kind : AMBIENCE) {
            ambience.then(Commands.literal(kind).executes(c -> send(c, "ambience/" + kind)));
        }
        event.getDispatcher().register(Commands.literal(AbyssalDepths.MODID)
                .requires(source -> source.hasPermission(2))
                .then(hallucinate)
                .then(ambience)
                .then(Commands.literal("vanish").then(vanishStyle).then(vanishPlay))
                .then(shadeLook)
                .then(Commands.literal("showcase").executes(c -> send(c, "showcase")))
                .then(setPiece("frozenfall", "Frozen fall", redero.abyssaldepths.worldgen.TunnelFeature::nearestFrozenFall, 14.5, 4, 14.5, 135.0F, -10.0F))
                .then(setPiece("grotto", "Gloam grotto", redero.abyssaldepths.worldgen.TunnelFeature::nearestGrotto, 18.5, 6, 18.5, 135.0F, 15.0F))
                .then(Commands.literal("canyon").executes(c -> { // to the end of the nearest fracture canyon, looking along it
                    final net.minecraft.server.level.ServerPlayer player = c.getSource().getPlayerOrException();
                    final double[] view = redero.abyssaldepths.worldgen.TunnelFeature.canyonView(player.serverLevel(), player.blockPosition());
                    if (view == null) {
                        c.getSource().sendFailure(Component.literal("No fracture canyon near"));
                        return 0;
                    }
                    player.teleportTo(player.serverLevel(), view[0], view[1], view[2], (float) view[3], 25.0F);
                    c.getSource().sendSuccess(() -> Component.literal("Fracture canyon at " + (int) view[0] + " " + (int) view[1] + " " + (int) view[2]), false);
                    return 1;
                }))
                .then(setPiece("lavafall", "Lava fall", redero.abyssaldepths.worldgen.TunnelFeature::nearestLavaFall, 16.5, 5, 16.5, 135.0F, -5.0F))
                .then(setPiece("ventlung", "Vent lung", redero.abyssaldepths.worldgen.TunnelFeature::nearestVentLung, 16.5, 7, 16.5, 135.0F, 20.0F))
                .then(setPiece("tidelake", "Tide lake", redero.abyssaldepths.worldgen.TunnelFeature::nearestTideLake, 20.5, 6, 20.5, 135.0F, 20.0F))
                .then(setPiece("skeleton", "Buried skeleton", redero.abyssaldepths.worldgen.TunnelFeature::nearestSkeleton, 26.5, 10, 26.5, 135.0F, 15.0F))
                .then(styles())
                .then(Commands.literal("oblivion").executes(c -> {
                    final java.util.List<String> sent = redero.abyssaldepths.artifact.OblivionItem.cast(c.getSource().getPlayerOrException());
                    c.getSource().sendSuccess(() -> Component.literal("Oblivion: sent " + sent.size() + " into the shadow " + sent), false);
                    return sent.size();
                }))
                .then(Commands.literal("storm")
                        .then(Commands.literal("start")
                                .executes(c -> storm(c, 180))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600))
                                        .executes(c -> storm(c, IntegerArgumentType.getInteger(c, "seconds")))))
                        .then(Commands.literal("stop").executes(c -> storm(c, 0))))
                .then(Commands.literal("mastery")
                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                .executes(c -> mastery(c, IntegerArgumentType.getInteger(c, "percent")))))
                .then(Commands.literal("innerhaven").executes(LayerCommands::innerHaven)
                        .then(Commands.literal("form")
                                .then(Commands.literal("room").executes(c -> form(c, InnerSelf.Form.ROOM)))
                                .then(Commands.literal("islands").executes(c -> form(c, InnerSelf.Form.ISLANDS)))))
                .then(Commands.literal("reprise")
                        .executes(c -> Reprise.activate(c.getSource().getServer()) ? 1 : 0)
                        .then(Commands.literal("slow").executes(c -> Reprise.activate(c.getSource().getServer(), 100) ? 1 : 0))
                        .then(Commands.literal("status").executes(c -> {
                            c.getSource().sendSuccess(() -> Component.literal(RepriseRecorder.status(c.getSource().getServer())), false);
                            return 1;
                        })))
                .then(fracture())
                .then(Commands.literal("scars")
                        .then(Commands.argument("scars", IntegerArgumentType.integer(0, redero.abyssaldepths.fracture.Scars.MOST))
                                .executes(c -> {
                                    redero.abyssaldepths.fracture.Scars.set(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "scars"));
                                    return 1;
                                })))
                .then(Commands.literal("charge")
                        .then(Commands.argument("charge", IntegerArgumentType.integer(0))
                                .executes(c -> charge(c, IntegerArgumentType.getInteger(c, "charge"))))));
    }

    private static int send(CommandContext<CommandSourceStack> context, String effect) throws CommandSyntaxException {
        final ServerPlayer player = context.getSource().getPlayerOrException();
        PacketDistributor.sendToPlayer(player, new EffectPayload(effect, 0));
        context.getSource().sendSuccess(() -> Component.literal("Played " + effect), false);
        return 1;
    }

    /** Sets the mastery of the artifact in the main hand. */
    private static int mastery(CommandContext<CommandSourceStack> context, int percent) throws CommandSyntaxException {
        final ServerPlayer player = context.getSource().getPlayerOrException();
        final ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof ArtifactItem)) {
            context.getSource().sendFailure(Component.literal("Hold an artifact in your main hand"));
            return 0;
        }
        redero.abyssaldepths.artifact.ArtifactOwnership.setMastery(player, held, percent); // the player's, for every copy
        context.getSource().sendSuccess(() -> Component.literal("Mastery set to " + percent + " %"), false);
        return 1;
    }

    /** Crosses into the inner self or back, as if Inner Haven had been used (no charge needed). */
    /**
     * {@code /abyssaldepths fracture [targets [percent]]}: corruption added (the user, or those given; 100 % if not
     * said, which breaks through at once); {@code fracture trial <name>}: the user into that trial.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> fracture() {
        final LiteralArgumentBuilder<CommandSourceStack> fracture = Commands.literal("fracture");
        fracture.executes(c -> {
            redero.abyssaldepths.fracture.Fracturing.corrupt(c.getSource().getPlayerOrException(), 100.0F, null);
            return 1;
        });
        fracture.then(Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.entities()).executes(c -> corrupt(c, 100.0F))
                .then(Commands.argument("percent", IntegerArgumentType.integer(1, 100)).executes(c -> corrupt(c, IntegerArgumentType.getInteger(c, "percent")))));
        final LiteralArgumentBuilder<CommandSourceStack> trial = Commands.literal("trial");
        for (redero.abyssaldepths.fracture.Trial.Type type : redero.abyssaldepths.fracture.Trial.Type.values()) {
            trial.then(Commands.literal(type.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> {
                redero.abyssaldepths.fracture.Fracturing.fracture(c.getSource().getPlayerOrException(), type);
                return 1;
            }));
        }
        final LiteralArgumentBuilder<CommandSourceStack> end = Commands.literal("end"); // ends one's trial at once, passed or failed
        for (String how : new String[] {"pass", "fail"}) {
            end.then(Commands.literal(how).executes(c -> redero.abyssaldepths.fracture.FractureTrials.end(c.getSource().getPlayerOrException(), how.equals("pass")) ? 1 : 0));
        }
        // the level itself: set (0 .. 100: at 100 it breaks through) and read
        fracture.then(Commands.literal("set").then(Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.entities())
                .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100)).executes(c -> {
                    int count = 0;
                    for (net.minecraft.world.entity.Entity entity : net.minecraft.commands.arguments.EntityArgument.getEntities(c, "targets")) {
                        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                            redero.abyssaldepths.fracture.Fracturing.set(living, IntegerArgumentType.getInteger(c, "percent"));
                            count++;
                        }
                    }
                    final int percent = IntegerArgumentType.getInteger(c, "percent"), n = count;
                    c.getSource().sendSuccess(() -> Component.literal("Fracture level set to " + percent + " % (" + n + ")"), false);
                    return count;
                }))));
        fracture.then(Commands.literal("get").executes(c -> level(c, c.getSource().getEntityOrException()))
                .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.entity())
                        .executes(c -> level(c, net.minecraft.commands.arguments.EntityArgument.getEntity(c, "target")))));
        return fracture.then(trial).then(end);
    }

    /** Tells the fracture level of an entity (and returns it, in per cent). */
    private static int level(CommandContext<CommandSourceStack> c, net.minecraft.world.entity.Entity entity) {
        final int percent = Math.round(redero.abyssaldepths.fracture.Fracturing.corruption(entity) * 100);
        c.getSource().sendSuccess(() -> Component.literal(entity.getName().getString() + ": fracture level " + percent + " %"), false);
        return percent;
    }

    private static int corrupt(CommandContext<CommandSourceStack> c, float percent) throws CommandSyntaxException {
        int count = 0;
        for (net.minecraft.world.entity.Entity entity : net.minecraft.commands.arguments.EntityArgument.getEntities(c, "targets")) {
            if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                redero.abyssaldepths.fracture.Fracturing.corrupt(living, percent, null);
                count++;
            }
        }
        return count;
    }

    /** {@code /abyssaldepths style mark|burst|aegis|aegis_overlay|convergence|convergence_apply|convergence_layer|repulsion|oblivion|empyrean|fracture <name>}: sets that client option of the one using it. */
    private static LiteralArgumentBuilder<CommandSourceStack> styles() {
        final LiteralArgumentBuilder<CommandSourceStack> style = Commands.literal("style");
        final LiteralArgumentBuilder<CommandSourceStack> mark = Commands.literal("mark"), burst = Commands.literal("burst");
        for (ClientConfig.WaypointMark value : ClientConfig.WaypointMark.values()) {
            mark.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "style/mark/" + value.name())));
        }
        for (ClientConfig.UndertowBurst value : ClientConfig.UndertowBurst.values()) {
            burst.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "style/burst/" + value.name())));
        }
        final LiteralArgumentBuilder<CommandSourceStack> aegis = Commands.literal("aegis"), convergence = Commands.literal("convergence");
        for (ClientConfig.AegisLook value : ClientConfig.AegisLook.values()) {
            aegis.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "style/aegis/" + value.name())));
        }
        for (ClientConfig.ConvergenceLook value : ClientConfig.ConvergenceLook.values()) {
            convergence.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "style/convergence/" + value.name())));
        }
        final LiteralArgumentBuilder<CommandSourceStack> repulsion = Commands.literal("repulsion");
        for (ClientConfig.RepulsionWave value : ClientConfig.RepulsionWave.values()) {
            repulsion.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "style/repulsion/" + value.name())));
        }
        final LiteralArgumentBuilder<CommandSourceStack> aegisOverlay = Commands.literal("aegis_overlay");
        for (ClientConfig.AegisOverlay value : ClientConfig.AegisOverlay.values()) {
            aegisOverlay.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "style/aegis_overlay/" + value.name())));
        }
        final LiteralArgumentBuilder<CommandSourceStack> apply = Commands.literal("convergence_apply");
        for (ClientConfig.ConvergenceApply value : ClientConfig.ConvergenceApply.values()) {
            apply.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "style/convergence_apply/" + value.name())));
        }
        final LiteralArgumentBuilder<CommandSourceStack> fit = Commands.literal("convergence_layer");
        for (ClientConfig.ConvergenceLayerFit value : ClientConfig.ConvergenceLayerFit.values()) {
            fit.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "style/convergence_layer/" + value.name())));
        }
        final LiteralArgumentBuilder<CommandSourceStack> oblivion = Commands.literal("oblivion");
        for (ClientConfig.OblivionLook value : ClientConfig.OblivionLook.values()) {
            oblivion.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "style/oblivion/" + value.name())));
        }
        final LiteralArgumentBuilder<CommandSourceStack> empyrean = Commands.literal("empyrean");
        for (ClientConfig.EmpyreanBeamLook value : ClientConfig.EmpyreanBeamLook.values()) {
            empyrean.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "style/empyrean/" + value.name())));
        }
        final LiteralArgumentBuilder<CommandSourceStack> fracture = Commands.literal("fracture");
        for (ClientConfig.FractureMarks value : ClientConfig.FractureMarks.values()) {
            fracture.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "style/fracture/" + value.name())));
        }
        final LiteralArgumentBuilder<CommandSourceStack> fractureAura = Commands.literal("fracture_aura");
        for (ClientConfig.FractureAura value : ClientConfig.FractureAura.values()) {
            fractureAura.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "style/fracture_aura/" + value.name())));
        }
        return style.then(mark).then(burst).then(aegis).then(aegisOverlay).then(convergence).then(apply).then(fit).then(repulsion).then(oblivion)
                .then(empyrean).then(fracture).then(fractureAura).then(scarIcons());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> scarIcons() {
        final LiteralArgumentBuilder<CommandSourceStack> scars = Commands.literal("scars");
        for (ClientConfig.ScarIcon value : ClientConfig.ScarIcon.values()) {
            scars.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> send(c, "style/scars/" + value.name())));
        }
        return scars;
    }

    private static int form(CommandContext<CommandSourceStack> context, InnerSelf.Form form) throws CommandSyntaxException {
        InnerSelf.setForm(context.getSource().getPlayerOrException(), form);
        context.getSource().sendSuccess(() -> Component.literal("Inner self: " + form.name().toLowerCase(java.util.Locale.ROOT)), false);
        return 1;
    }

    private static int innerHaven(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final ServerPlayer player = context.getSource().getPlayerOrException();
        if (InnerSelf.travelling(player)) {
            return 0;
        }
        InnerSelf.travel(player);
        return 1;
    }

    /** Sets the charge of the artifact in the main hand (up to its maximum). */
    private static int charge(CommandContext<CommandSourceStack> context, int charge) throws CommandSyntaxException {
        final ServerPlayer player = context.getSource().getPlayerOrException();
        final ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof ArtifactItem artifact) || artifact.maxCharge() == 0) {
            context.getSource().sendFailure(Component.literal("Hold an artifact with a charge in your main hand"));
            return 0;
        }
        artifact.setCharge(held, charge);
        context.getSource().sendSuccess(() -> Component.literal("Charge set to " + artifact.charge(held) + " / " + artifact.maxCharge()), false);
        return 1;
    }

    private static int storm(CommandContext<CommandSourceStack> context, int seconds) {
        DuneStorm.set(context.getSource().getServer(), seconds * 20);
        context.getSource().sendSuccess(() -> Component.literal(seconds > 0
                ? "Dune storm for " + seconds + " s (visible in the silt dunes)" : "Dune storm stopped"), true);
        return 1;
    }
}
