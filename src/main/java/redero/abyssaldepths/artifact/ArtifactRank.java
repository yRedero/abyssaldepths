package redero.abyssaldepths.artifact;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** How powerful an artifact is, from weakest to strongest. The colour is used for its name and rank line. */
public enum ArtifactRank {
    LESSER(0x9A93A8),
    GREATER(0x6C8FD6),
    EXALTED(0xA25FE0),
    PRIMORDIAL(0xE0457A);

    private final int colour;

    ArtifactRank(int colour) {
        this.colour = colour;
    }

    public int colour() {
        return colour;
    }

    /** "Primordial", translatable (abyssaldepths.artifact.rank.primordial). */
    public MutableComponent title() {
        return Component.translatable("abyssaldepths.artifact.rank." + name().toLowerCase(java.util.Locale.ROOT));
    }
}
