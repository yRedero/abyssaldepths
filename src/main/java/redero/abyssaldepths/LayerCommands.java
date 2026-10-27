package redero.abyssaldepths;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Test commands (operators only), to see the layer's effects without waiting for them:
 * <ul>
 * <li>{@code /abyssaldepths hallucinate footsteps|mob|digging|shadow}: a deep pressure hallucination.</li>
 * <li>{@code /abyssaldepths ambience groan|creature|steps|rockfall|gust}: one of the rare ambient sounds.</li>
 * <li>{@code /abyssaldepths storm start [seconds]} / {@code storm stop}: a storm in the silt dunes.</li>
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
        final LiteralArgumentBuilder<CommandSourceStack> ambience = Commands.literal("ambience");
        for (String kind : AMBIENCE) {
            ambience.then(Commands.literal(kind).executes(c -> send(c, "ambience/" + kind)));
        }
        event.getDispatcher().register(Commands.literal(AbyssalDepths.MODID)
                .requires(source -> source.hasPermission(2))
                .then(hallucinate)
                .then(ambience)
                .then(Commands.literal("storm")
                        .then(Commands.literal("start")
                                .executes(c -> storm(c, 180))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600))
                                        .executes(c -> storm(c, IntegerArgumentType.getInteger(c, "seconds")))))
                        .then(Commands.literal("stop").executes(c -> storm(c, 0)))));
    }

    private static int send(CommandContext<CommandSourceStack> context, String effect) throws CommandSyntaxException {
        final ServerPlayer player = context.getSource().getPlayerOrException();
        PacketDistributor.sendToPlayer(player, new EffectPayload(effect, 0));
        context.getSource().sendSuccess(() -> Component.literal("Played " + effect), false);
        return 1;
    }

    private static int storm(CommandContext<CommandSourceStack> context, int seconds) {
        DuneStorm.set(context.getSource().getServer(), seconds * 20);
        context.getSource().sendSuccess(() -> Component.literal(seconds > 0
                ? "Dune storm for " + seconds + " s (visible in the silt dunes)" : "Dune storm stopped"), true);
        return 1;
    }
}
