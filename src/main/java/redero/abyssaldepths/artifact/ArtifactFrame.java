package redero.abyssaldepths.artifact;

/**
 * Look of an artifact's tooltip frame: the same pixelated border in the colours of the region the
 * artifact belongs to. Every artifact picks one.
 */
public enum ArtifactFrame {
    /** The Hollow (the thalan layer and everything below the surface): black and dark purple. */
    HOLLOW(0xF00B0711, 0xFF050308, 0xFF2E1647, 0xFF5A2D86),
    /** The Zenith (a region still to come): white and orange. */
    ZENITH(0xF0140C06, 0xFFF4F0E8, 0xFFB8561A, 0xFFF08A2C);

    /** Inside of the tooltip. */
    public final int background;
    /** The outermost line. */
    public final int edge;
    /** The broken inner line. */
    public final int accent;
    /** The few pixels that reach further in. */
    public final int highlight;

    ArtifactFrame(int background, int edge, int accent, int highlight) {
        this.background = background;
        this.edge = edge;
        this.accent = accent;
        this.highlight = highlight;
    }
}
