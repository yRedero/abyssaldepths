package redero.abyssaldepths.crest;

import java.util.Locale;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Crests (DESIGN.md 10): signs of old houses or lineages from before the catastrophe. Their meaning stays a mystery to
 * uncover; their names describe only the sigil. When a player joins a world for the first time they choose one (a
 * screen before they are set into the world, then a short cutscene round them); its effects are visual: a mark before
 * their name in the player list, its name in the cutscene. It ties them into the story and gives them an identity of their own.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class Crests {
    /** The crests (ids; their order is kept in saves: only ever add at the end) and each one's colour. */
    public static final String[] IDS = {"broken_ring", "twin_eyes", "coiled_wyrm", "veiled_moon"};
    public static final int[] COLORS = {0x96463C, 0x6E7896, 0x6E4682, 0x788CA0};
    public static final Codec<Integer> CODEC = Codec.INT;

    private Crests() {
    }

    /** A player's crest (-1: not chosen yet). */
    public static int of(Player player) {
        final int c = player.getData(AbyssalDepths.CREST);
        return c >= IDS.length ? IDS.length - 1 : c;
    }

    public static ResourceLocation sigil(int crest) {
        return ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "textures/gui/crest/" + IDS[crest] + ".png");
    }

    public static Component name(int crest) {
        return Component.translatable("crest.abyssaldepths." + IDS[crest]);
    }

    /** On first joining: choose one. Every join: everyone learns everyone's crest. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (of(player) < 0) {
            PacketDistributor.sendToPlayer(player, new EffectPayload("crest_choose", 0));
        }
        for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
            if (of(other) >= 0) {
                PacketDistributor.sendToPlayer(player, new EffectPayload(String.format(Locale.ROOT, "crest/%d/%d", other.getId(), of(other)), 0));
            }
        }
        if (of(player) >= 0) {
            share(player);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && of(player) >= 0) {
            share(player);
        }
    }

    @SubscribeEvent
    public static void onTrack(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer other && event.getEntity() instanceof ServerPlayer player && of(other) >= 0) {
            PacketDistributor.sendToPlayer(player, new EffectPayload(String.format(Locale.ROOT, "crest/%d/%d", other.getId(), of(other)), 0));
        }
    }

    /** The mark before their name in the player list. */
    @SubscribeEvent
    public static void onTabName(PlayerEvent.TabListNameFormat event) {
        final int crest = of(event.getEntity());
        if (crest >= 0) {
            event.setDisplayName(Component.literal("◆ ").withStyle(s -> s.withColor(TextColor.fromRgb(COLORS[crest])))
                    .append(Component.literal(event.getEntity().getGameProfile().getName()).withStyle(ChatFormatting.WHITE)));
        }
    }

    private static void share(ServerPlayer player) {
        PacketDistributor.sendToAllPlayers(new EffectPayload(String.format(Locale.ROOT, "crest/%d/%d", player.getId(), of(player)), 0));
        player.refreshTabListName();
    }

    /** A player chose: kept (only the first choice counts), shown to all, and a moment round them. */
    static void chose(ServerPlayer player, int crest) {
        if (of(player) >= 0 || crest < 0 || crest >= IDS.length) {
            return;
        }
        player.setData(AbyssalDepths.CREST, crest);
        AbyssalDepths.LOGGER.info("{} took the crest {}", player.getGameProfile().getName(), IDS[crest]);
        share(player);
        redero.abyssaldepths.cinema.Cutscenes.play(player, "abyssaldepths:crest", player.position());
    }

    /** Client to server: the crest chosen on the crest screen. */
    public record Choice(int crest) implements CustomPacketPayload {
        public static final Type<Choice> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "crest_choice"));
        public static final StreamCodec<ByteBuf, Choice> STREAM = StreamCodec.composite(ByteBufCodecs.VAR_INT, Choice::crest, Choice::new);

        @Override
        public Type<Choice> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Choice.TYPE, Choice.STREAM, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                chose(player, payload.crest());
            }
        }));
    }
}
