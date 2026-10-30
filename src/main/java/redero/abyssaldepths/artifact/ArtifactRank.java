package redero.abyssaldepths.artifact;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * How powerful an artifact is, from weakest to strongest. Each rank has a palette of five colours (dark to
 * light) that its tooltip text flows through ({@link #flowing}), and a title size relative to the
 * configured one (the stronger, the larger).
 */
public enum ArtifactRank {
    LESSER(0.88F, 0x444B55, 0x525963, 0x727D87, 0xA0A8AD, 0xC5CDD2),
    GREATER(0.92F, 0x203F4A, 0x294C58, 0x426C78, 0x789AA1, 0xB4CED0),
    EXALTED(0.96F, 0x382941, 0x49344F, 0x725276, 0xA17BA3, 0xD0B5D2),
    /** Deep crimson with a strong pink shimmer. */
    PRIMORDIAL(1.0F, 0x4B0D20, 0x671629, 0x96253E, 0xC4475F, 0xF08A94);

    /** Seconds for the colours to flow once through the palette. */
    private static final float FLOW_SECONDS = 3.2F;

    private final float titleScale;
    private final int[] palette;

    ArtifactRank(float titleScale, int... palette) {
        this.titleScale = titleScale;
        this.palette = palette;
    }

    /** A steady colour of the rank, for plain uses (the item name outside the tooltip). */
    public int colour() {
        return palette[3];
    }

    /** The title's size relative to the configured one. */
    public float titleScale() {
        return titleScale;
    }

    /**
     * The colour at a place of a flowing gradient: {@code offset} is the place (e.g. letter * 0.1),
     * {@code millis} the time. Moves back and forth through the readable part of the palette (the middle
     * and light shades; the dark ones would vanish on the dark tooltip), lightest at the crest.
     */
    public int flowing(float offset, long millis) {
        final float phase = offset + millis / (FLOW_SECONDS * 1000.0F);
        final float wave = 0.5F + 0.5F * (float) Math.sin(phase * 2 * Math.PI); // 0 .. 1
        final float position = 2.0F + wave * 2.0F;                             // palette 2 .. 4
        final int i = Math.min(3, (int) position);
        final float t = position - i;
        return mix(palette[i], palette[i + 1], t);
    }

    private static int mix(int a, int b, float t) {
        final int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        final int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        final int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
    }

    /** "Primordial", translatable (abyssaldepths.artifact.rank.primordial). */
    public MutableComponent title() {
        return Component.translatable("abyssaldepths.artifact.rank." + name().toLowerCase(java.util.Locale.ROOT));
    }
}
