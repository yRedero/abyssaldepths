package redero.abyssaldepths.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.Shades;

/**
 * How a shade sees the world: dark and drained of colour (a post effect, {@code shaders/post/shade.json}),
 * for as long as the local player is a shade. Checked every tick, so it comes back if something else
 * (another post effect, a camera change) replaced it.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class ShadeView {
    private static final ResourceLocation EFFECT = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "shaders/post/shade.json");

    private ShadeView() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final PostChain current = mc.gameRenderer.currentEffect();
        final boolean ours = current != null && current.getName().equals(EFFECT.toString());
        final boolean shade = mc.player != null && Shades.isShade(mc.player);
        if (shade && !ours) {
            mc.gameRenderer.loadEffect(EFFECT);
        } else if (!shade && ours) {
            mc.gameRenderer.shutdownEffect();
        }
    }
}
