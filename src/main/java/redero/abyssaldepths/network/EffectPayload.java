package redero.abyssaldepths.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Server to client: run a client side effect of the layer, e.g. {@code storm} (with its remaining
 * ticks, 0 = over), {@code hallucinate/shadow} or {@code ambience/groan}. Sent by the dune storms and
 * the test commands.
 */
public record EffectPayload(String effect, int ticks) implements CustomPacketPayload {
    public static final Type<EffectPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "effect"));
    public static final StreamCodec<ByteBuf, EffectPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, EffectPayload::effect, ByteBufCodecs.VAR_INT, EffectPayload::ticks, EffectPayload::new);

    @Override
    public Type<EffectPayload> type() {
        return TYPE;
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID)
    public static final class Registration {
        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            // the client classes are only touched when a payload arrives, so a dedicated server never loads them
            event.registrar("1").playToClient(TYPE, CODEC,
                    (payload, context) -> context.enqueueWork(() -> redero.abyssaldepths.client.ClientEffects.handle(payload)));
        }
    }
}
