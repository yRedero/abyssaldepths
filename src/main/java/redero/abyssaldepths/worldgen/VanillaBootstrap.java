package redero.abyssaldepths.worldgen;

/**
 * Whether Minecraft is building its built-in registries right now (VanillaRegistries.createLookup,
 * run as a self-check at startup and by the data generators). Our biomes do not exist there, so the
 * thalan layer biomes must not be added to the overworld biome list during it.
 */
public final class VanillaBootstrap {
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private VanillaBootstrap() {
    }

    public static void enter() {
        DEPTH.get()[0]++;
    }

    public static void exit() {
        DEPTH.get()[0]--;
    }

    public static boolean running() {
        return DEPTH.get()[0] > 0;
    }
}
