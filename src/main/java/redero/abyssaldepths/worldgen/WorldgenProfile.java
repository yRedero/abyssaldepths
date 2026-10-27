package redero.abyssaldepths.worldgen;

import redero.abyssaldepths.AbyssalDepths;

/**
 * Development aid: with {@code -Dabyssaldepths.profile=true} (the autoshot run sets it with
 * {@code -Pprofile=true}) the layer feature times its phases and logs the average per chunk every 200
 * chunks ("worldgen profile ..."). Off by default; then it costs a single static check per chunk.
 */
final class WorldgenProfile {
    static final boolean ON = Boolean.getBoolean("abyssaldepths.profile");
    static final String[] PHASES = {"columns", "connectors", "carve", "formations.near", "surface", "formations", "walls"};
    private static final long[] SUM = new long[PHASES.length];
    private static long chunks, formations;

    private WorldgenProfile() {
    }

    /** {@code marks}: the start time and the end time of every phase, in order. */
    static synchronized void add(long[] marks, int near) {
        for (int i = 0; i < PHASES.length; i++) {
            SUM[i] += marks[i + 1] - marks[i];
        }
        formations += near;
        if (++chunks % 200 == 0) {
            final StringBuilder line = new StringBuilder("worldgen profile, ms per chunk over " + chunks + " chunks:");
            long total = 0;
            for (int i = 0; i < PHASES.length; i++) {
                line.append(String.format(" %s=%.2f", PHASES[i], SUM[i] / 1e6 / chunks));
                total += SUM[i];
            }
            line.append(String.format(" total=%.2f, formations near=%.1f", total / 1e6 / chunks, formations / (double) chunks));
            AbyssalDepths.LOGGER.info(line.toString());
        }
    }
}
