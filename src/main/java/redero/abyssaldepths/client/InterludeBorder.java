package redero.abyssaldepths.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.artifact.InterludeField;

/**
 * Interlude's screen effect, for whoever uses it: a ragged frame of big black and purple pixels (the size
 * of Reprise's) along the edges of the screen. It covers little, one or two pixels deep; its depth keeps
 * shifting along the edge, the pixels keep changing, and now and then a short spike leaps further in and
 * draws back. It grows in when Interlude is switched on and recedes when it goes off. Drawn under the HUD.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class InterludeBorder {
    /** One pixel of the frame (GUI pixels, as in Reprise's cover). */
    private static final int CELL = 10;
    private static final int BLACK = 0xF0050307, PURPLE = 0xF02A0F45, VIOLET = 0xF05C2A93, BRIGHT = 0xF08E52D6;
    private static float strength, previous;

    private InterludeBorder() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final boolean on = mc.player != null && mc.level != null && InterludeField.clientIsActive(mc.player.getId(), mc.level.getGameTime());
        previous = strength;
        strength = Mth.clamp(strength + (on ? 0.1F : -0.07F), 0.0F, 1.0F); // in over half a second, out a little slower
    }

    private static void draw(GuiGraphics g, float partialTick) {
        final float s = Mth.lerp(partialTick, previous, strength);
        if (s <= 0.01F || ClientConfig.interludeOverlay() == ClientConfig.InterludeOverlay.NONE) {
            return;
        }
        final ClientConfig.InterludeOverlay overlay = ClientConfig.interludeOverlay();
        if (overlay == ClientConfig.InterludeOverlay.TINT || overlay == ClientConfig.InterludeOverlay.BOTH) {
            // a violet over everything, breathing a little
            final float breath = 0.85F + 0.15F * Mth.sin(Util.getMillis() / 1600.0F * Mth.TWO_PI);
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), (int) (s * breath * 0x58) << 24 | 0x5A2A9A);
            if (overlay == ClientConfig.InterludeOverlay.TINT) {
                return;
            }
        }
        final float time = Util.getMillis() / 1000.0F;
        final int cols = (g.guiWidth() + CELL - 1) / CELL, rows = (g.guiHeight() + CELL - 1) / CELL;
        // the four edges, each walked along its length: depth in pixels at every position
        for (int edge = 0; edge < 4; edge++) {
            final int length = edge < 2 ? cols : rows;
            for (int i = 0; i < length; i++) {
                final int seed = edge * 1000 + i;
                final float depth = s * depth(seed, time);
                for (int d = 0; d < Math.round(depth); d++) {
                    final int col = edge == 0 || edge == 1 ? i : edge == 2 ? d : cols - 1 - d;
                    final int row = edge == 0 ? d : edge == 1 ? rows - 1 - d : i;
                    g.fill(col * CELL, row * CELL, col * CELL + CELL, row * CELL + CELL, colour(seed, d, Math.round(depth), time));
                }
            }
        }
    }

    /** Depth at a place of the edge: 1 to 2 pixels, drifting, with a rare short spike further in. */
    static float depth(int seed, float time) {
        float depth = 0.8F + 1.3F * smoothNoise(seed, time * 0.9F);
        final int beat = (int) Math.floor(time * 2.5F); // spikes come and go in steps of 0.4 s
        if (noise(seed, beat) > 0.94F) {
            final float within = time * 2.5F - beat;
            depth += 2.5F * Mth.sin(within * Mth.PI); // leaps in and draws back
        }
        return depth;
    }

    /** Pixels change every 0.15 s: mostly black, purple among them, the innermost one lit. */
    private static int colour(int seed, int d, int depth, float time) {
        final int step = (int) Math.floor(time / 0.15F);
        final float r = noise(seed * 31 + d, step);
        if (d == depth - 1) {
            return r < 0.3F ? BRIGHT : r < 0.75F ? VIOLET : PURPLE; // the inner edge
        }
        return r < 0.25F ? PURPLE : BLACK;
    }

    /** Smooth value noise over time for one place: 0 .. 1. */
    static float smoothNoise(int seed, float t) {
        final int i = (int) Math.floor(t);
        final float f = t - i, u = f * f * (3 - 2 * f);
        return Mth.lerp(u, noise(seed, i), noise(seed, i + 1));
    }

    /** A fixed random value 0 .. 1. */
    static float noise(int a, int b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layer {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "interlude_border"),
                    (graphics, delta) -> draw(graphics, delta.getGameTimeDeltaPartialTick(true)));
        }
    }
}
