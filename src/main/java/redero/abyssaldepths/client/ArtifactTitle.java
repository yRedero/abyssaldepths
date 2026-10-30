package redero.abyssaldepths.client;

import java.util.Locale;

import org.joml.Matrix4f;

import net.minecraft.Util;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.artifact.ArtifactRank;

/**
 * The title line of an artifact tooltip: the name in large spaced capitals (size in the client config,
 * 1.25x by default, a little smaller for the lower ranks), between two small ornaments, its letters
 * flowing through the rank's palette with a brighter shimmer sweeping through every few seconds (both can
 * be turned off). {@link ArtifactTooltip} puts it in place of the plain name line, centred.
 */
public record ArtifactTitle(String name, ArtifactRank rank, int ornament) implements TooltipComponent {
    /** One sweep of the shimmer, in milliseconds, and how wide its bright band is, in letters. */
    private static final long SHIMMER_PERIOD = 2600;
    private static final float SHIMMER_WIDTH = 2.5F;
    /** Extra space after every letter and between the ornaments and the name, in GUI pixels. */
    private static final int TRACKING = 1, ORNAMENT_GAP = 4;
    private static final String ORNAMENT = "◈";

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Factory {
        @SubscribeEvent
        public static void register(RegisterClientTooltipComponentFactoriesEvent event) {
            event.register(ArtifactTitle.class, Line::new);
        }
    }

    /** Draws the title. */
    record Line(ArtifactTitle title) implements ClientTooltipComponent {
        private String letters() {
            return title.name.toUpperCase(Locale.ROOT);
        }

        private float scale() {
            return ClientConfig.artifactTitleScale() * title.rank.titleScale();
        }

        @Override
        public int getHeight() {
            return (int) Math.ceil(9 * scale());
        }

        @Override
        public int getWidth(Font font) {
            int width = 2 * (font.width(ORNAMENT) + ORNAMENT_GAP);
            for (char c : letters().toCharArray()) {
                width += advance(font, c);
            }
            return width - TRACKING;
        }

        /** Width of one letter at the title's size, its tracking included. */
        private int advance(Font font, char c) {
            return Math.round(font.width(String.valueOf(c)) * scale()) + TRACKING;
        }

        /**
         * Colour of letter i of n: the rank's colour, brightened where the shimmer band passes. The band
         * crosses the name and then pauses off it for a moment before the next sweep.
         */
        private int letterColour(int i, int n) {
            if (!ClientConfig.artifactTitleShimmer()) {
                return title.rank.colour();
            }
            final long millis = Util.getMillis();
            final int colour = title.rank.flowing(i * 0.09F, millis); // the rank's palette, flowing along the letters
            final float sweep = (millis % SHIMMER_PERIOD) / (float) SHIMMER_PERIOD; // 0 .. 1
            final float band = -SHIMMER_WIDTH + sweep * (n + 2 * SHIMMER_WIDTH + 6); // runs past the end: a pause
            final float t = Math.max(0.0F, 1.0F - Math.abs(i - band) / SHIMMER_WIDTH);
            final float light = 0.5F * t * t * (3 - 2 * t);
            final int r = (colour >> 16) & 255, g = (colour >> 8) & 255, b = colour & 255;
            return (int) (r + (255 - r) * light) << 16 | (int) (g + (255 - g) * light) << 8 | (int) (b + (255 - b) * light);
        }

        @Override
        public void renderText(Font font, int x, int y, Matrix4f matrix, MultiBufferSource.BufferSource buffer) {
            final float scale = scale();
            final int ornamentY = y + Math.round((9 * scale - 9) / 2); // centred on the large letters
            final int full = LightTexture.FULL_BRIGHT;
            font.drawInBatch(ORNAMENT, x, ornamentY, 0xFF000000 | title.ornament, true, matrix, buffer, Font.DisplayMode.NORMAL, 0, full);
            float cursor = x + font.width(ORNAMENT) + ORNAMENT_GAP;
            final char[] letters = letters().toCharArray();
            for (int i = 0; i < letters.length; i++) {
                final Matrix4f letter = new Matrix4f(matrix).translate(cursor, y, 0).scale(scale, scale, 1.0F);
                font.drawInBatch(String.valueOf(letters[i]), 0, 0, 0xFF000000 | letterColour(i, letters.length), true, letter, buffer,
                        Font.DisplayMode.NORMAL, 0, full);
                cursor += advance(font, letters[i]);
            }
            font.drawInBatch(ORNAMENT, cursor - TRACKING + ORNAMENT_GAP, ornamentY, 0xFF000000 | title.ornament, true, matrix, buffer,
                    Font.DisplayMode.NORMAL, 0, full);
        }
    }
}
