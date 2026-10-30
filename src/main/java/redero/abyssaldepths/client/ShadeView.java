package redero.abyssaldepths.client;

import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.artifact.ParallaxItem;
import redero.abyssaldepths.artifact.Shades;

/**
 * How a shade sees the world, and the crossing into it.
 * <p>
 * The crossing runs from 0 (the world) to 1 (the shadow) in the time the client config gives: from spots
 * all over the screen a quick wave of strong black and purple pixels runs outwards until they cover the
 * screen; then, slower, a second front runs out from the same spots and turns them into translucent purple
 * pixels, which stay while in the shadow
 * (denser at the edges, some of them lighting up now and then). Leaving runs the same backwards.
 * <p>
 * Meanwhile the shadow's own look fades in: a post effect ({@code shaders/program/shade.fsh}) with purple
 * outlines, drained and darkened colours and more. Halfway through, other entities are hidden (but other
 * shades, and everything for a Parallax at full mastery, see {@link ParallaxItem#seesWorld}).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class ShadeView {
    private static final ResourceLocation EFFECT = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "shaders/post/shade.json");
    /** The effect's target holding a copy of the world's depth. */
    private static final String DEPTH_TARGET = "world_depth";
    /** One pixel of the crossing (GUI pixels, as in Reprise's cover). */
    private static final int CELL = 10;
    /** On average one spot per this many pixels. */
    private static final int CELLS_PER_SPOT = 70;
    /**
     * The two fronts, as parts of the crossing: the quick wave covers the screen in the first
     * {@link #FIRST_WAVE}; the slow front clearing it starts at {@link #CLEARING_START}, and behind it the
     * strong pixels thin out over {@link #CLEARING_EDGE} (in reach, 0 .. 1).
     */
    private static final float FIRST_WAVE = 0.3F, CLEARING_START = 0.25F, CLEARING_EDGE = 0.3F;
    /** The strong pixels of the waves (as Reprise's front, in the shadow's colours). */
    private static final int BLACK = 0xFF050307, DEEP = 0xFF2A0F45, STRONG_VIOLET = 0xFF5C2A93, BRIGHT = 0xFF8E52D6;
    /** The pixels that stay. */
    private static final int PURPLE = 0x3A1E50, VIOLET = 0x8E52D6;

    private static float crossing, previous;
    private static boolean seesWorld;
    /** Per pixel: when the waves reach it (0 .. 1), for the current screen and crossing. */
    private static float[] reach = new float[0];
    private static int reachCols, reachRows, reachSeed = -1, seed;

    private ShadeView() {
    }

    /** Whether this client looks at the shadow (from the black moment of the crossing on). */
    public static boolean inShadow() {
        return crossing >= 0.5F;
    }

    /**
     * Whether the entity is hidden from this client: shades from those outside the shadow; in the shadow,
     * everything but other shades (unless the artifact lets it see the world); a shade still appearing.
     */
    public static boolean hidden(Entity entity) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || entity == mc.player) {
            return false;
        }
        if (ShadeVanish.appearing(entity)) {
            return true;
        }
        return seenHidden(entity);
    }

    /** Hidden by the rules alone (without the appearing ones). */
    static boolean seenHidden(Entity entity) {
        return inShadow() ? !Shades.isShade(entity) && !seesWorld : Shades.isShade(entity);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final boolean shade = mc.player != null && Shades.isShade(mc.player);
        previous = crossing;
        if (mc.player == null) {
            crossing = previous = 0.0F;
        } else {
            if (shade && crossing == 0.0F) {
                seed++; // new spots every time
            }
            final float step = 50.0F / ClientConfig.parallaxTransitionMillis();
            crossing = Mth.clamp(crossing + (shade ? step : -step), 0.0F, 1.0F);
        }
        seesWorld = mc.player != null && ParallaxItem.seesWorld(mc.player);

        final boolean wanted = crossing > 0.0F;
        if (wanted && ours() == null) {
            mc.gameRenderer.loadEffect(EFFECT);
        } else if (!wanted && ours() != null) {
            mc.gameRenderer.shutdownEffect();
        }
    }

    /** The shadow's post effect, if it is the one running. */
    private static PostChain ours() {
        final PostChain current = Minecraft.getInstance().gameRenderer.currentEffect();
        return current != null && current.getName().equals(EFFECT.toString()) ? current : null;
    }

    /** Every frame: how far the shadow's look has faded in (most of it while the pixels cover the screen). */
    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        final PostChain effect = ours();
        if (effect != null) {
            final float v = Mth.lerp(event.getPartialTick().getGameTimeDeltaPartialTick(true), previous, crossing);
            final float t = Mth.clamp((v - 0.1F) / 0.6F, 0.0F, 1.0F);
            effect.setUniform("Amount", t * t * (3 - 2 * t));
        }
    }

    private static void draw(GuiGraphics g, float partialTick) {
        final float v = Mth.lerp(partialTick, previous, crossing);
        if (v <= 0.0F) {
            return;
        }
        final int cols = (g.guiWidth() + CELL - 1) / CELL, rows = (g.guiHeight() + CELL - 1) / CELL;
        if (cols != reachCols || rows != reachRows || seed != reachSeed) {
            spots(cols, rows);
        }
        final float time = Util.getMillis() / 1000.0F;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                final int colour = colour(col, row, cols, rows, v, time);
                if (colour >>> 24 != 0) {
                    g.fill(col * CELL, row * CELL, col * CELL + CELL, row * CELL + CELL, colour);
                }
            }
        }
    }

    /**
     * At the end of the world's drawing: its depth copied into the effect's own target, which the outlines
     * are found in. The game clears the depth before it draws the hand, and the effect runs after that.
     */
    @SubscribeEvent
    public static void onLevelDrawn(RenderLevelStageEvent event) {
        final PostChain effect = event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL ? ours() : null;
        final RenderTarget depth = effect == null ? null : effect.getTempTarget(DEPTH_TARGET);
        if (depth != null) {
            final RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            depth.copyDepthFrom(main);
            main.bindWrite(false);
        }
    }

    /** Scatters the spots and works out, for every pixel, how soon the waves reach it. */
    private static void spots(int cols, int rows) {
        reachCols = cols;
        reachRows = rows;
        reachSeed = seed;
        final int count = Math.max(4, cols * rows / CELLS_PER_SPOT);
        final float[] sx = new float[count], sy = new float[count];
        for (int i = 0; i < count; i++) {
            sx[i] = noise(seed * 7919 + i, 1) * cols;
            sy[i] = noise(seed * 7919 + i, 2) * rows;
        }
        reach = new float[cols * rows];
        float max = 0.0F;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                float nearest = Float.MAX_VALUE;
                for (int i = 0; i < count; i++) {
                    final float dx = col + 0.5F - sx[i], dy = row + 0.5F - sy[i];
                    nearest = Math.min(nearest, dx * dx + dy * dy);
                }
                final float d = (float) Math.sqrt(nearest) + 0.9F * noise(col * 31 + seed, row); // ragged edges
                reach[row * cols + col] = d;
                max = Math.max(max, d);
            }
        }
        for (int i = 0; i < reach.length; i++) {
            reach[i] /= max * 1.001F; // 0 .. 1
        }
    }

    /** The colour of a pixel at crossing v. */
    private static int colour(int col, int row, int cols, int rows, float v, float time) {
        final float reached = reach[row * cols + col];
        if (reached > v / FIRST_WAVE) {
            return 0; // the quick wave is not here yet
        }
        // the slow front from the spots: past the last pixel (reach 1) with its edge just as the crossing ends
        final float clearing = (v - CLEARING_START) / (1.0F - CLEARING_START) * (1.0F + CLEARING_EDGE) - reached;
        final float strength = Mth.clamp(1.0F - clearing / CLEARING_EDGE, 0.0F, 1.0F);
        final int flicker = (int) (time * 12.0F); // the strong pixels change about 12 times a second
        if (noise(col * 211 + flicker, row * 89 + seed) < strength) {
            final float r = noise(col * 53 + flicker, row * 197);
            return r < 0.45F ? BLACK : r < 0.75F ? DEEP : r < 0.93F ? STRONG_VIOLET : BRIGHT;
        }
        // what stays: translucent purple, denser towards the edges, some pixels lighting up for a while
        final float ex = Math.abs((col + 0.5F) / cols * 2.0F - 1.0F), ey = Math.abs((row + 0.5F) / rows * 2.0F - 1.0F);
        final float edge = Mth.clamp((Math.max(ex, ey) - 0.55F) / 0.45F, 0.0F, 1.0F);
        float alpha = 0.06F + 0.12F * noise(col, row * 3 + seed) + 0.45F * edge * edge;
        int rgb = PURPLE;
        final int beat = (int) Math.floor(time * 0.8F + noise(col, row)); // every pixel on its own beat of 1.25 s
        if (noise(col * 977 + beat, row * 613) > 0.985F) {
            final float glow = Mth.sin((time * 0.8F + noise(col, row)) % 1.0F * Mth.PI);
            rgb = mix(PURPLE, VIOLET, glow);
            alpha += 0.35F * glow;
        }
        return (int) (Math.min(1.0F, alpha) * 255) << 24 | rgb;
    }

    private static int mix(int a, int b, float t) {
        final int r = (int) Mth.lerp(t, (a >> 16) & 255, (b >> 16) & 255);
        final int g = (int) Mth.lerp(t, (a >> 8) & 255, (b >> 8) & 255);
        final int bl = (int) Mth.lerp(t, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    /** A fixed random value 0 .. 1. */
    private static float noise(int a, int b) {
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
            event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "shade_crossing"),
                    (graphics, delta) -> draw(graphics, delta.getGameTimeDeltaPartialTick(true)));
        }
    }
}
