package redero.abyssaldepths.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * The fade of Inner Haven: the screen turns white before the move ({@code haven_out}), stays white over
 * the loading screen, and clears again once the new world is there and ready ({@code haven_in}). Drawn
 * above everything, the HUD and any screen.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class HavenFade {
    private static final int COLOUR = 0xF4F2EC;
    private static float alpha, previous, step;
    /** Waiting to fade in: until the world is loaded and no screen is open any more, then a moment longer. */
    private static boolean holding;
    private static int settle;
    private static final int SETTLE_TICKS = 10;

    private HavenFade() {
    }

    static void out(int ticks) {
        step = 1.0F / Math.max(1, ticks);
        holding = false;
    }

    static void in(int ticks) {
        holding = true;
        settle = 0;
        alpha = previous = 1.0F;
        step = -1.0F / Math.max(1, ticks);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        previous = alpha;
        if (holding) {
            final boolean ready = mc.level != null && mc.player != null && mc.screen == null && mc.levelRenderer.hasRenderedAllSections();
            settle = ready ? settle + 1 : 0;
            if (settle > SETTLE_TICKS) {
                holding = false; // the new place is there and settled: start clearing
            }
            return;
        }
        alpha = Mth.clamp(alpha + step, 0.0F, 1.0F);
        if (alpha <= 0.0F) {
            step = 0.0F;
        }
    }

    private static void draw(GuiGraphics g, float partialTick) {
        final float a = Mth.lerp(partialTick, previous, alpha);
        if (a > 0.003F) {
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 3000.0F); // over everything, tooltips included
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), (int) (a * 255) << 24 | COLOUR);
            g.pose().popPose();
        }
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Render.Post event) {
        draw(event.getGuiGraphics(), event.getPartialTick());
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layer {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "haven_fade"),
                    (graphics, delta) -> draw(graphics, delta.getGameTimeDeltaPartialTick(true)));
        }
    }
}
