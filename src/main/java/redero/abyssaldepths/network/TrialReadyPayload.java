package redero.abyssaldepths.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.fracture.FractureTrials;

/**
 * Client to server: the player's screen has cleared in their trial (the layer of the breaking washed away), so
 * the trial's looking round can begin.
 */
public record TrialReadyPayload() implements CustomPacketPayload {
    public static final TrialReadyPayload INSTANCE = new TrialReadyPayload();
    public static final Type<TrialReadyPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "trial_ready"));
    public static final StreamCodec<ByteBuf, TrialReadyPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<TrialReadyPayload> type() {
        return TYPE;
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("1").playToServer(TYPE, CODEC, (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    FractureTrials.ready(player);
                }
            }));
        }
    }
}
