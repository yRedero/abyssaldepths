package redero.abyssaldepths;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Storms of the silt dunes: now and then (on average every 40 minutes) a storm rises for 2 to 4
 * minutes. It is one storm for the whole world, kept by the server and sent to every player; only
 * players in the dunes see it (dust everywhere, the fog closes in). Not saved: a restart ends it.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class DuneStorm {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int AVERAGE_GAP = 20 * 60 * 40;
    private static int remaining;

    private DuneStorm() {
    }

    /** Starts a storm of the given length, or ends the running one with 0. */
    public static void set(MinecraftServer server, int ticks) {
        remaining = Math.max(0, ticks);
        PacketDistributor.sendToAllPlayers(new EffectPayload("storm", remaining));
    }

    public static int remaining() {
        return remaining;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (remaining > 0) {
            remaining--;
        } else if (RANDOM.nextInt(AVERAGE_GAP) == 0) {
            set(event.getServer(), 2400 + RANDOM.nextInt(2400));
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (remaining > 0 && event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, new EffectPayload("storm", remaining));
        }
    }
}
