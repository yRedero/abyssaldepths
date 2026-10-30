package redero.abyssaldepths.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.artifact.InnerSelf;
import redero.abyssaldepths.worldgen.TunnelLayer;

/** No game music in the thalan layer: only its own ambience; a track that is playing on the way down stops. */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class LayerMusic {
    private LayerMusic() {
    }

    @SubscribeEvent
    public static void onSelectMusic(SelectMusicEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null && mc.level.dimension() == InnerSelf.DIMENSION) {
            event.setMusic(null); // the inner self has only its own ambience
        } else if (ClientConfig.silenceMusic() && mc.player != null && mc.level != null && mc.level.dimension() == Level.OVERWORLD
                && mc.player.getBlockY() <= TunnelLayer.LAYER_TOP_Y) {
            event.setMusic(null);
        }
    }
}
