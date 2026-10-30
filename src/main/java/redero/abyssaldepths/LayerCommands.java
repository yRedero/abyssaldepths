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
                .then(Commands.literal("storm")
                        .then(Commands.literal("start")
                                .executes(c -> storm(c, 180))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600))
                                        .executes(c -> storm(c, IntegerArgumentType.getInteger(c, "seconds")))))
                        .then(Commands.literal("stop").executes(c -> storm(c, 0))))
                .then(Commands.literal("mastery")
                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                .executes(c -> mastery(c, IntegerArgumentType.getInteger(c, "percent")))))
                .then(Commands.literal("innerhaven").executes(LayerCommands::innerHaven))
                .then(Commands.literal("reprise")
                        .executes(c -> Reprise.activate(c.getSource().getServer()) ? 1 : 0)
                        .then(Commands.literal("slow").executes(c -> Reprise.activate(c.getSource().getServer(), 100) ? 1 : 0))
                        .then(Commands.literal("status").executes(c -> {
                            c.getSource().sendSuccess(() -> Component.literal(RepriseRecorder.status(c.getSource().getServer())), false);
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
        ArtifactItem.setMastery(held, percent);
        context.getSource().sendSuccess(() -> Component.literal("Mastery set to " + percent + " %"), false);
        return 1;
    }

    /** Crosses into the inner self or back, as if Inner Haven had been used (no charge needed). */
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
