package redero.abyssaldepths.client;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.util.TriState;
import redero.abyssaldepths.AbyssalDepths;

/** The deep pressure shadow: a client-only figure shaped like a player, completely black, without a name. */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class ShadowFigure extends RemotePlayer {
    private static final PlayerSkin SKIN = new PlayerSkin(ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "textures/entity/shadow.png"),
            null, null, null, PlayerSkin.Model.WIDE, true);

    ShadowFigure(ClientLevel level) {
        super(level, new GameProfile(UUID.randomUUID(), "shadow"));
    }

    @Override
    public PlayerSkin getSkin() {
        return SKIN;
    }

    @SubscribeEvent
    public static void onNameTag(RenderNameTagEvent event) {
        if (event.getEntity() instanceof ShadowFigure) {
            event.setCanRender(TriState.FALSE);
        }
    }
}
