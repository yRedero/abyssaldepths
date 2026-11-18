package redero.abyssaldepths.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import redero.abyssaldepths.AbyssalDepths;

/**
 * The worm compass, held (DESIGN.md 5.3: direction and distance by colour and intensity): over the hotbar a small dial,
 * its needle pointing to the nearest worm (turning as the player turns), its rim lit the brighter the nearer it is, the
 * distance under it; when the worm hunts the one holding it, the dial turns red, pulses and trembles. Nothing in the
 * dial but a dim needle when no worm is known. The server sends where the worm is ({@code wormcompass/...}).
 */
public final class WormCompassView {
    /** The worm: x, z, distance, state (0 none, 1 far off, 2 near, 3 hunting you), and when it was last told. */
    private static double x, z, dist;
    private static int state, smarts;
    private static long told;

    private WormCompassView() {
    }

    /** From the server: "x/z/distance/state". */
    public static void told(String data) {
        final String[] p = data.split("/");
        x = Double.parseDouble(p[0]);
        z = Double.parseDouble(p[1]);
        dist = Double.parseDouble(p[2]);
        state = Integer.parseInt(p[3]);
        smarts = p.length > 4 ? Integer.parseInt(p[4]) : 0;
        final Minecraft mc = Minecraft.getInstance();
        told = mc.level == null ? 0 : mc.level.getGameTime();
    }

    private static boolean holding(Minecraft mc) {
        return mc.player != null && (mc.player.getItemInHand(InteractionHand.MAIN_HAND).is(AbyssalDepths.WORM_COMPASS.get())
                || mc.player.getItemInHand(InteractionHand.OFF_HAND).is(AbyssalDepths.WORM_COMPASS.get()));
    }

    static void draw(GuiGraphics g, float partial) {
        final Minecraft mc = Minecraft.getInstance();
        if (!holding(mc) || mc.level == null || mc.options.hideGui) {
            return;
        }
        final boolean known = state > 0 && mc.level.getGameTime() - told < 60;
        final int cx = g.guiWidth() / 2, cy = g.guiHeight() - 62, r = 14;
        final float t = (mc.level.getGameTime() + partial) / 20.0F;
        final boolean hunted = known && state == 3;
        final float near = known ? (float) Mth.clamp(1.0 - dist / 600.0, 0.08, 1.0) : 0;
        final int shake = hunted ? (int) (Mth.sin(t * 40) * 1.5F) : 0;
        // the dial: a dark disc, its rim lit by nearness (red, pulsing, when hunted)
        disc(g, cx + shake, cy, r + 2, 0xE00A080C);
        final float pulse = hunted ? 0.6F + 0.4F * Mth.sin(t * 9) : 1;
        final int rim = hunted ? 0xC4475F : 0xA17BA3;
        ring(g, cx + shake, cy, r + 1, (int) (Mth.clamp((0.25F + 0.75F * near) * pulse, 0, 1) * 255) << 24 | rim);
        // the needle: towards the worm, seen from the way the player looks
        float angle = 0;
        if (known) {
            final double dx = x - mc.player.getX(), dz = z - mc.player.getZ();
            final float worm = (float) Math.toDegrees(Math.atan2(-dx, dz));
            angle = Mth.wrapDegrees(worm - mc.player.getViewYRot(partial));
        } else {
            angle = Mth.sin(t * 0.7F) * 40; // nothing known: it wanders
        }
        final double a = Math.toRadians(angle);
        final int needle = known ? (hunted ? 0xFFF08A94 : (int) ((0.5F + 0.5F * near) * 255) << 24 | 0xD0B5D2) : 0x60808080;
        for (int k = 2; k <= r - 2; k++) { // up the dial is ahead
            final int px = cx + shake + (int) Math.round(Math.sin(a) * k), py = cy - (int) Math.round(Math.cos(a) * k);
            g.fill(px, py, px + 2, py + 2, needle);
        }
        g.fill(cx + shake - 1, cy - 1, cx + shake + 2, cy + 2, 0xFF2A1838);
        if (known) {
            final String text = (int) dist + (hunted ? " !" : "");
            g.drawCenteredString(mc.font, text, cx, cy + r + 5, hunted ? 0xF08A94 : 0xA89CB0);
            for (int i = 0; i < smarts; i++) { // its cunning: a pip each (1 to 4), over the dial
                final int px = cx - (smarts * 4 - 1) / 2 + i * 4;
                g.fill(px, cy - r - 6, px + 3, cy - r - 3, hunted ? 0xFFC4475F : 0xFFA17BA3);
            }
        }
    }

    private static void disc(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            final int w = (int) Math.sqrt(r * r - dy * dy);
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    private static void ring(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int i = 0; i < 64; i++) {
            final double a = i * Math.PI * 2 / 64;
            final int px = cx + (int) Math.round(Math.cos(a) * r), py = cy + (int) Math.round(Math.sin(a) * r);
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layer {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "worm_compass"),
                    (graphics, delta) -> draw(graphics, delta.getGameTimeDeltaPartialTick(true)));
        }
    }
}
