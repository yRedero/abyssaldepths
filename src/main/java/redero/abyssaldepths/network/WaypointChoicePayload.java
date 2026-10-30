package redero.abyssaldepths.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.ArtifactItem;
import redero.abyssaldepths.artifact.InnerHavenItem;
import redero.abyssaldepths.artifact.InnerSelf;

/**
 * Client to server: where to come back to from the inner self, chosen on the waypoint screen (an index into
 * the remembered places, -1 for where the body waits). Only honoured for a player in their inner self who
 * carries an Inner Haven mastered far enough.
 */
public record WaypointChoicePayload(int index) implements CustomPacketPayload {
    public static final Type<WaypointChoicePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "waypoint_choice"));
    public static final StreamCodec<ByteBuf, WaypointChoicePayload> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, WaypointChoicePayload::index,
            WaypointChoicePayload::new);

    @Override
    public Type<WaypointChoicePayload> type() {
        return TYPE;
    }

    private static void handle(ServerPlayer player, int index) {
        if (player.level().dimension() != InnerSelf.DIMENSION || InnerSelf.travelling(player)) {
            return;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            final ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof InnerHavenItem haven && ArtifactItem.milestone(stack) >= InnerHavenItem.WAYPOINT_MILESTONE
                    && haven.charge(stack) >= InnerHavenItem.cost(stack, player) && !player.getCooldowns().isOnCooldown(haven)) {
                haven.setCharge(stack, haven.charge(stack) - InnerHavenItem.cost(stack, player));
                player.level().playSound(null, player.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.8F, 1.6F);
                player.getCooldowns().addCooldown(haven, 100);
                InnerSelf.returnTo(player, index);
                return;
            }
        }
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID)
    public static final class Registration {
        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("1").playToServer(TYPE, CODEC, (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    handle(player, payload.index());
                }
            }));
        }
    }
}
