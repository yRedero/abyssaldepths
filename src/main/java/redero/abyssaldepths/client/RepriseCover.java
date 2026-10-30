package redero.abyssaldepths.client;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
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
 * What players see while Reprise turns the world back:
 * <ol>
 * <li>the screen freezes (the last frame stays as a still image) while the server rewinds behind it;</li>
 * <li>from the four corners, a front of big red pixels (half a hotbar slot each) eats the screen towards
 * the middle, leaving black behind with a few dark red pixels in it;</li>
 * <li>at the middle: if the server is not done yet, a small crimson circle turns on the black until it is;</li>
 * <li>then the front quickly withdraws to the corners and shows the world again, as it is now (live).</li>
 * </ol>
 * Drawn above everything, the HUD and any screen.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class RepriseCover {
    private static final ResourceLocation FROZEN = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "reprise_frozen");
    /** Size of one pixel of the animation (GUI pixels: half a hotbar slot). */
    private static final int CELL = 10;
    /** How long the front takes from the corners to the middle, and back. */
    private static final int FORWARD_TICKS = 50, BACK_TICKS = 12;
    /** Width of the red front, in progress (0 .. 1 is corner to middle). */
    private static final float FRONT = 0.09F;
    private static final int RED = 0xFFC3122B, RED_DARK = 0xFF8A0B1E, BLACK = 0xFF050203, EMBER = 0xFF1E0507, CRIMSON = 0xFFB0102A;

    private enum Phase { IDLE, FORWARD, WAITING, BACK }

    private static Phase phase = Phase.IDLE;
    private static float progress, previous;
    private static boolean serverDone, frozen;
    private static int frozenW, frozenH, waitTicks;
    private static DynamicTexture texture;

    private RepriseCover() {
    }

    /** The server starts: freeze the screen, start eating it. */
    static void start() {
        final Minecraft mc = Minecraft.getInstance();
        release();
        try {
            final NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            frozenW = image.getWidth();
            frozenH = image.getHeight();
            texture = new DynamicTexture(image);
            mc.getTextureManager().register(FROZEN, texture);
            frozen = true;
        } catch (RuntimeException e) {
            frozen = false; // without the still image it still works, the live world just shows until covered
        }
        phase = Phase.FORWARD;
        progress = previous = 0.0F;
        serverDone = false;
        waitTicks = 0;
    }

    /** The server has finished rewinding. */
    static void done() {
        serverDone = true;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        previous = progress;
        switch (phase) {
            case FORWARD -> {
                progress = Math.min(1.0F + FRONT, progress + (1.0F + FRONT) / FORWARD_TICKS);
                if (progress >= 1.0F + FRONT) {
                    phase = serverDone ? Phase.BACK : Phase.WAITING;
                }
            }
            case WAITING -> {
                waitTicks++;
                if (serverDone) {
                    phase = Phase.BACK;
                }
            }
            case BACK -> {
                frozen = false; // from here on the world behind is the new one, live
                progress = Math.max(0.0F, progress - (1.0F + FRONT) / BACK_TICKS);
                if (progress <= 0.0F) {
                    phase = Phase.IDLE;
                    release();
                }
            }
            default -> {
            }
        }
    }

    private static void release() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(FROZEN);
            texture = null;
        }
        frozen = false;
    }

    private static void draw(GuiGraphics g, float partialTick) {
        if (phase == Phase.IDLE) {
            return;
        }
        final int w = g.guiWidth(), h = g.guiHeight();
        final float p = Mth.lerp(partialTick, previous, progress);
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 3000.0F); // over everything, tooltips included
        if (frozen && texture != null) {
            g.blit(FROZEN, 0, 0, w, h, 0.0F, 0.0F, frozenW, frozenH, frozenW, frozenH);
        }
        // the cells: how far each is from the nearest corner decides when the front reaches it
        final int cols = (w + CELL - 1) / CELL, rows = (h + CELL - 1) / CELL;
        final float cx = (cols - 1) / 2.0F, cy = (rows - 1) / 2.0F, reach = (float) Math.hypot(cx, cy);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                final float dx = Math.min(col, cols - 1 - col), dy = Math.min(row, rows - 1 - row);
                // 0 at a corner, 1 in the middle, with a ragged edge
                final float at = Mth.clamp(1.0F - (float) Math.hypot(cx - dx, cy - dy) / reach, 0.0F, 1.0F) * 0.92F + 0.08F * noise(col, row);
                if (p < at) {
                    continue;
                }
                final int colour;
                if (p < at + FRONT) {
                    colour = noise(col + 31, row + 7) < 0.5F ? RED : RED_DARK; // the front line
                } else {
                    colour = noise(col + 113, row + 59) < 0.06F ? EMBER : BLACK; // what it leaves behind
                }
                g.fill(col * CELL, row * CELL, col * CELL + CELL, row * CELL + CELL, colour);
            }
        }
        if (phase == Phase.WAITING) {
            spinner(g, w / 2, h / 2, waitTicks + partialTick);
        }
        g.pose().popPose();
    }

    /** A small circle of crimson pixels with a bright head turning round, the tail fading. */
    private static void spinner(GuiGraphics g, int x, int y, float time) {
        final int dots = 12, radius = 18, size = 5;
        final float head = (time * 0.6F) % dots;
        for (int i = 0; i < dots; i++) {
            final double a = 2 * Math.PI * i / dots - Math.PI / 2;
            final int px = x + (int) Math.round(Math.cos(a) * radius) - size / 2, py = y + (int) Math.round(Math.sin(a) * radius) - size / 2;
            final float behind = (head - i + dots) % dots;              // 0 = the head
            final float light = Math.max(0.15F, 1.0F - behind / (dots * 0.8F));
            final int alpha = (int) (light * 255);
            g.fill(px, py, px + size, py + size, alpha << 24 | (CRIMSON & 0xFFFFFF));
        }
    }

    /** A fixed random value 0 .. 1 per cell. */
    private static float noise(int x, int y) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Render.Post event) {
        draw(event.getGuiGraphics(), event.getPartialTick());
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layer {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "reprise_cover"),
                    (graphics, delta) -> draw(graphics, delta.getGameTimeDeltaPartialTick(true)));
        }
    }
}
