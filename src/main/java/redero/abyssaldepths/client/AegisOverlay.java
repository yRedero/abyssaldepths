package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.List;

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
import redero.abyssaldepths.artifact.AegisItem;

/**
 * Aegis's screen effect, for its holder only, in the configured look ({@link ClientConfig.AegisOverlay}): a
 * honeycomb of small pixel hexagons along the edges of the screen, sharing their sides, that light up in
 * turn, round and round, faster while something presses against the field, the ones on the side of a hit
 * flashing; a thin ragged frame of blue pixels like Interlude's ({@link InterludeBorder}), leaping far in on
 * the side of a hit, and held in for as long as something is in the field, the further the closer it is; or nothing while all is quiet, and pixel ripples running in from the side of each hit. The server tells the holder where each hit
 * came from ({@code aegis_hit/...}). It grows in when the field goes up, recedes when it goes down.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class AegisOverlay {
    /** One pixel, in GUI pixels. */
    private static final int CELL = 2;
    /** The field's blues, dark to light. */
    private static final int[] BLUES = {0x2F6FA8, 0x5AA8E0, 0x9FD8FF, 0xE8F7FF};
    /** The frame's colours: deep navy, blue, light blue, ice (as Interlude's black, purple, violet, bright). */
    private static final int[] FRAME = {0xF0061020, 0xF0123A66, 0xF02F6FA8, 0xF09FD8FF};
    /** A hexagon, flat on top: 8 x 6 pixels. */
    private static final String[] HEX = {"..XXXX..", ".X....X.", "X......X", "X......X", ".X....X.", "..XXXX.."};
    private static final int HEX_W = 8 * CELL, HEX_H = 6 * CELL;
    private static final int RIPPLE_TICKS = 16;

    /** A hit: where it came from (radians, 0 ahead, more to the right), when, and how fast it came (blocks a tick). */
    private record Hit(float angle, long tick, float speed) {
    }

    private static final List<Hit> HITS = new ArrayList<>();

    /** Something in the field: from where (as a hit's angle), how deep (0 .. 1), how fast it comes on (blocks a tick). */
    private record Press(float angle, float depth, float speed) {
    }

    /** What is in the field now (the server repeats it every few ticks), and when it was told. */
    private static List<Press> pressing = List.of();
    private static long pressedAt;
    private static float strength, previous, pressure, travelled;

    private AegisOverlay() {
    }

    /** What is in the field now: "dx,dz,depth;" for each (from the server); nothing when it is clear. */
    public static void press(String data) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        final List<Press> now = new ArrayList<>();
        for (String one : data.split(";")) {
            final String[] p = one.split(",");
            if (p.length >= 3) {
                final float yaw = (float) Math.toDegrees(Math.atan2(-Double.parseDouble(p[0]), Double.parseDouble(p[1])));
                now.add(new Press(Mth.wrapDegrees(yaw - mc.player.getYRot()) * Mth.DEG_TO_RAD, Float.parseFloat(p[2]), p.length > 3 ? Float.parseFloat(p[3]) : 0.0F));
            }
        }
        pressing = now;
        pressedAt = mc.level.getGameTime();
    }

    /** A hit on the field from this direction (from the holder to what hit it, in the world). */
    public static void hit(double dx, double dz, float speed) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        final float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)); // the yaw that would face it
        HITS.add(new Hit(Mth.wrapDegrees(yaw - mc.player.getYRot()) * Mth.DEG_TO_RAD, mc.level.getGameTime(), speed));
        pressure = Math.min(1.0F, pressure + 0.35F);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final boolean on = mc.player != null && mc.level != null && AegisItem.clientHolds(mc.player);
        previous = strength;
        strength = Mth.clamp(strength + (on ? 0.1F : -0.07F), 0.0F, 1.0F);
        pressure = Math.max(0.0F, pressure - 0.03F);
        travelled += 0.004F + pressure * 0.02F; // the light runs round (a share of the way a tick), faster under pressure
        if (mc.level != null) {
            HITS.removeIf(hit -> mc.level.getGameTime() - hit.tick() > RIPPLE_TICKS || hit.tick() > mc.level.getGameTime());
        }
    }

    private static void draw(GuiGraphics g, float partialTick) {
        final float s = Mth.lerp(partialTick, previous, strength);
        final Minecraft mc = Minecraft.getInstance();
        if (s <= 0.01F || mc.level == null) {
            return;
        }
        final float now = mc.level.getGameTime() + partialTick;
        switch (ClientConfig.aegisOverlay()) {
            case HEX -> hexes(g, s, now);
            case FRAME -> frame(g, s, now);
            case RIPPLES -> ripples(g, s, now);
            case NONE -> {
            }
        }
    }

    /** Where on the screen's edge a hit from this angle shows. */
    private static float[] edgePoint(GuiGraphics g, float angle) {
        final float w = g.guiWidth(), h = g.guiHeight();
        float x = Mth.sin(angle), y = -Mth.cos(angle);
        final float scale = Math.min(Math.abs(x) < 1.0E-4F ? Float.MAX_VALUE : 0.5F / Math.abs(x), Math.abs(y) < 1.0E-4F ? Float.MAX_VALUE : 0.5F / Math.abs(y));
        return new float[] {w * (0.5F + x * scale), h * (0.5F + y * scale)};
    }

    /**
     * The honeycomb: along the top and bottom two staggered rows of hexagons, down the sides one column, each
     * sharing its sides with the next, so the frame runs on unbroken; a light running round (by the angle
     * round the screen's middle), those near a hit flashing.
     */
    private static void hexes(GuiGraphics g, float s, float now) {
        final int w = g.guiWidth(), h = g.guiHeight();
        final int across = 6 * CELL, half = 3 * CELL, down = 5 * CELL; // a hexagon's step sideways, half its height, its step down
        final List<int[]> places = new ArrayList<>();
        for (int c = 0; c * across <= w; c++) {
            places.add(new int[] {c * across, c % 2 * half});
            places.add(new int[] {c * across, h - HEX_H - c % 2 * half});
        }
        for (int y = HEX_H + half - CELL; y + HEX_H <= h - HEX_H - half + CELL; y += down) {
            places.add(new int[] {0, y});
            places.add(new int[] {w - HEX_W, y});
        }
        final float lead = travelled % 1.0F;
        for (int[] at : places) {
            final float around = (float) (Math.atan2(at[1] + HEX_H / 2.0F - h / 2.0F, at[0] + HEX_W / 2.0F - w / 2.0F) / Mth.TWO_PI + 1.0F) % 1.0F;
            float behind = lead - around;
            if (behind < 0) {
                behind += 1.0F;
            }
            float light = Math.max(0.0F, 1.0F - behind / 0.12F); // the light and its tail
            for (Hit hit : HITS) {
                final float age = (now - hit.tick()) / RIPPLE_TICKS;
                final float[] p = edgePoint(g, hit.angle());
                final float d = Mth.sqrt((p[0] - at[0] - HEX_W / 2.0F) * (p[0] - at[0] - HEX_W / 2.0F) + (p[1] - at[1] - HEX_H / 2.0F) * (p[1] - at[1] - HEX_H / 2.0F));
                light = Math.max(light, (1.0F - age) * Math.max(0.0F, 1.0F - d / 70.0F));
            }
            if (now - pressedAt < 8) { // lit for as long as something is in the field, the deeper the wider
                for (Press press : pressing) {
                    final float[] p = edgePoint(g, press.angle());
                    final float d = Mth.sqrt((p[0] - at[0] - HEX_W / 2.0F) * (p[0] - at[0] - HEX_W / 2.0F) + (p[1] - at[1] - HEX_H / 2.0F) * (p[1] - at[1] - HEX_H / 2.0F));
                    light = Math.max(light, (0.5F + 0.5F * press.depth()) * Math.max(0.0F, 1.0F - d / (50.0F + 120.0F * press.depth())));
                }
            }
            final int step = light > 0.75F ? 3 : light > 0.35F ? 2 : 1;
            final float alpha = s * (0.35F + 0.6F * light);
            hex(g, at[0], at[1], (int) (alpha * 255) << 24 | BLUES[step]);
        }
    }

    /**
     * The frame: Interlude's ragged edge of pixels in blues, thin (one pixel deep, now and then two), leaping far
     * in where something presses against the field: a moment for a hit, the further the faster it came; and for
     * as long as something is in it, the further the closer it has come and the harder it presses on.
     */
    private static void frame(GuiGraphics g, float s, float now) {
        final int cell = 7;
        final float time = now / 20.0F;
        final int cols = (g.guiWidth() + cell - 1) / cell, rows = (g.guiHeight() + cell - 1) / cell;
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), (int) (s * 0x22) << 24 | 0x2F6FA8); // a faint cold tint
        for (int edge = 0; edge < 4; edge++) {
            final int length = edge < 2 ? cols : rows;
            for (int i = 0; i < length; i++) {
                final int seed = edge * 1000 + i + 500_000;
                final int w = g.guiWidth(), h = g.guiHeight();
                // the cell at the edge: the right and bottom edges counted from the screen's edge (whole pixels)
                final int x0 = edge == 0 || edge == 1 ? i * cell : edge == 2 ? 0 : w - cell, y0 = edge == 0 ? 0 : edge == 1 ? h - cell : i * cell;
                float boost = 0.0F;
                for (Hit hit : HITS) {
                    final float[] p = edgePoint(g, hit.angle());
                    final float d = Mth.sqrt((p[0] - x0) * (p[0] - x0) + (p[1] - y0) * (p[1] - y0));
                    // the faster it came, the further in: a slow one a little, an arrow far
                    boost = Math.max(boost, (2.0F + Math.min(10.0F, hit.speed() * 4.0F)) * (1.0F - (now - hit.tick()) / RIPPLE_TICKS)
                            * Math.max(0.0F, 1.0F - d / 90.0F));
                }
                if (now - pressedAt < 8) { // held in for as long as something is in the field, the deeper the further
                    for (Press press : pressing) {
                        final float[] p = edgePoint(g, press.angle());
                        final float d = Mth.sqrt((p[0] - x0) * (p[0] - x0) + (p[1] - y0) * (p[1] - y0));
                        final float jitter = 0.8F + 0.2F * InterludeBorder.smoothNoise(seed + 77, time * 3.0F);
                        final float force = 0.7F + Math.min(0.8F, press.speed() * 3.0F); // pressing on harder: further in
                        boost = Math.max(boost, (2.0F + 12.0F * press.depth()) * force * jitter * Math.max(0.0F, 1.0F - d / 80.0F));
                    }
                }
                final int depth = Math.round(s * (frameDepth(seed, time) + boost));
                for (int d = 0; d < depth; d++) {
                    final int x = edge == 2 ? d * cell : edge == 3 ? w - (d + 1) * cell : x0, y = edge == 0 ? d * cell : edge == 1 ? h - (d + 1) * cell : y0;
                    final float r = InterludeBorder.noise(seed * 31 + d, (int) Math.floor(time / 0.15F));
                    final int colour = d == depth - 1 ? (r < 0.3F ? FRAME[3] : r < 0.75F ? FRAME[2] : FRAME[1]) : r < 0.25F ? FRAME[1] : FRAME[0];
                    g.fill(x, y, x + cell, y + cell, colour);
                }
            }
        }
    }

    /** Depth at a place of the frame: 1, sometimes 2 pixels, drifting; rarely a short leap in and back. */
    private static float frameDepth(int seed, float time) {
        float depth = 0.6F + 0.9F * InterludeBorder.smoothNoise(seed, time * 0.9F);
        final int beat = (int) Math.floor(time * 2.5F);
        if (InterludeBorder.noise(seed, beat) > 0.97F) {
            depth += 1.5F * Mth.sin((time * 2.5F - beat) * Mth.PI);
        }
        return depth;
    }

    private static void hex(GuiGraphics g, int x, int y, int argb) {
        for (int row = 0; row < HEX.length; row++) {
            for (int col = 0; col < HEX[row].length(); col++) {
                if (HEX[row].charAt(col) == 'X') {
                    g.fill(x + col * CELL, y + row * CELL, x + col * CELL + CELL, y + row * CELL + CELL, argb);
                }
            }
        }
    }

    /** Each hit: three rings of pixels running in from the edge where it came from, fading. */
    private static void ripples(GuiGraphics g, float s, float now) {
        final int w = g.guiWidth(), h = g.guiHeight(), size = CELL * 2;
        for (Hit hit : HITS) {
            final float age = now - hit.tick();
            final float[] p = edgePoint(g, hit.angle());
            final float fade = s * (1.0F - age / RIPPLE_TICKS);
            for (int ring = 0; ring < 3; ring++) {
                final float r = (age - ring * 3) * 7.0F;
                if (r <= 0) {
                    continue;
                }
                final int count = (int) (Mth.TWO_PI * r / (size * 1.5F));
                for (int k = 0; k < count; k++) {
                    final float a = Mth.TWO_PI * k / count;
                    final int x = (int) (p[0] + Mth.cos(a) * r) / size * size, y = (int) (p[1] + Mth.sin(a) * r) / size * size;
                    if (x < 0 || y < 0 || x >= w || y >= h) {
                        continue;
                    }
                    g.fill(x, y, x + size, y + size, (int) (fade * (0.9F - ring * 0.25F) * 255) << 24 | BLUES[3 - ring]);
                }
            }
        }
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layer {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "aegis_overlay"),
                    (graphics, delta) -> draw(graphics, delta.getGameTimeDeltaPartialTick(true)));
        }
    }
}
