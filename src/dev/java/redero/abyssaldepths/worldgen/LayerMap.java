package redero.abyssaldepths.worldgen;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import redero.abyssaldepths.worldgen.LayerFormations.Biomes;

/**
 * Offline map of the thalan layer for one biome (no game needed): as if the whole area were that biome,
 * it draws the cavern (headroom as grey, brighter = higher; dark red = under 8 blocks; rock black) and the formations on top of it (each kind in
 * its own colour, outline = reach), saves a PNG and prints how many of each kind stand in the area.
 * Good for judging density and spacing of formations before going in game.
 *
 * <pre>
 * ./gradlew layerMap -Pargs="[flats|dunes|thermal|depths] [seed] [originX] [originZ] [size] [png]"
 * </pre>
 * Defaults: flats 12345 0 0 512 build/layer-map.png
 */
public final class LayerMap {
    private LayerMap() {
    }

    public static void main(String[] args) throws Exception {
        final String biomeName = args.length > 0 ? args[0] : "flats";
        final long seed = args.length > 1 ? Long.parseLong(args[1]) : 12345L;
        final int ox = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        final int oz = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        final int size = args.length > 4 ? Integer.parseInt(args[4]) : 512;
        final File png = new File(args.length > 5 ? args[5] : "build/layer-map.png");
        final int biome = switch (biomeName) {
            case "flats" -> Biomes.FLATS;
            case "dunes" -> Biomes.DUNES;
            case "thermal" -> Biomes.THERMAL;
            default -> Biomes.DEPTHS;
        };
        final Biomes biomes = (x, z) -> biome;
        final TunnelLayer layer = new TunnelLayer(seed, null, DuneOpenness.of(biomes, new ConcurrentHashMap<>()));
        final LayerFormations formations = new LayerFormations(layer, biomes);

        final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        final TunnelLayer.Column col = new TunnelLayer.Column();
        int open = 0;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                if (layer.column(ox + x, oz + z, col)) {
                    open++;
                    // the headroom decides what fits: brighter = higher ceiling, red = less than 8 blocks
                    final int room = col.ceiling - col.floor + 1;
                    image.setRGB(x, z, room < 8 ? 0x602020 : (60 + Math.min(150, room * 4)) * 0x010101);
                }
            }
        }
        final Map<String, Integer> counts = new TreeMap<>();
        for (LayerFormations.Formation f : formations.near(ox, oz, ox + size - 1, oz + size - 1)) {
            final String kind = f.getClass().getSimpleName();
            final int cx = (int) Math.floor(f.cx) - ox, cz = (int) Math.floor(f.cz) - oz;
            if (cx < 0 || cz < 0 || cx >= size || cz >= size) {
                continue;
            }
            counts.merge(kind, 1, Integer::sum);
            final int colour = 0x404040 | (kind.hashCode() * 0x9E3779B1 & 0xBFBFBF);
            final int r = (int) Math.ceil(f.reach());
            for (int a = 0; a < 360; a += 4) {
                plot(image, cx + (int) Math.round(Math.cos(Math.toRadians(a)) * r), cz + (int) Math.round(Math.sin(Math.toRadians(a)) * r), colour);
            }
            for (int d = -1; d <= 1; d++) {
                plot(image, cx + d, cz, colour);
                plot(image, cx, cz + d, colour);
            }
        }
        png.getAbsoluteFile().getParentFile().mkdirs();
        ImageIO.write(image, "png", png);
        System.out.printf("%s, seed %d, %d x %d from (%d, %d): open cavern %.0f %%%n", biomeName, seed, size, size, ox, oz,
                100.0 * open / (size * size));
        final int cavern = Math.max(1, open);
        counts.forEach((kind, n) -> System.out.printf("  %-12s %5d  (%.1f per 100 x 100 of open cavern)%n", kind, n,
                n * 10000.0 / cavern));
        System.out.println("map: " + png.getAbsolutePath());
    }

    private static void plot(BufferedImage image, int x, int z, int colour) {
        if (x >= 0 && z >= 0 && x < image.getWidth() && z < image.getHeight()) {
            image.setRGB(x, z, colour);
        }
    }
}
