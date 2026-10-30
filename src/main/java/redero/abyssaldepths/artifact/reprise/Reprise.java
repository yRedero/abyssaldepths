package redero.abyssaldepths.artifact.reprise;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Activating Reprise: every player's screen freezes and the cover animation starts ({@code reprise_out},
 * see the client's RepriseCover); a moment later, once every client has its still image, the world is
 * rewound behind it ({@link Rewind}) and the clients are told it is done ({@code reprise_done}): their
 * animation withdraws as soon as it has reached the middle of the screen.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class Reprise {
    /** Ticks between freezing the screens and the rewind: time for every client to take its still image. */
    private static final int FREEZE_TICKS = 5;
    private static long due = -1;
    /** How far back the rewind under way reaches, in ticks. */
    private static int reach = RepriseRecorder.WINDOW;

    private Reprise() {
    }

    public static boolean pending() {
        return due >= 0;
    }

    /** Starts it: covers every screen, the rewind follows. False if one is already under way. */
    public static boolean activate(MinecraftServer server) {
        return activate(server, 0, RepriseRecorder.WINDOW);
    }

    /** {@code delay}: extra ticks before the rewind, to see the animation wait for a slow one (testing). */
    public static boolean activate(MinecraftServer server, int delay) {
        return activate(server, delay, RepriseRecorder.WINDOW);
    }

    /** As above, reaching {@code reachTicks} back (the Reprise used: its mastery). */
    public static boolean activate(MinecraftServer server, int delay, int reachTicks) {
        if (due >= 0) {
            return false;
        }
        reach = reachTicks;
        due = server.overworld().getGameTime() + FREEZE_TICKS + delay;
        PacketDistributor.sendToAllPlayers(new EffectPayload("reprise_out", 0));
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        final MinecraftServer server = event.getServer();
        if (due < 0 || server.overworld().getGameTime() < due) {
            return;
        }
        due = -1;
        final int seconds = Rewind.run(server, reach);
        PacketDistributor.sendToAllPlayers(new EffectPayload("reprise_done", 0));
        if (seconds < 0) {
            server.getPlayerList().broadcastSystemMessage(Component.translatable("abyssaldepths.reprise.nothing"), true);
        }
    }
}
