package redero.abyssaldepths.worldgen;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

/**
 * Standalone check for {@link TunnelLayer} (no Minecraft needed).
 *
 * <pre>
 * javac -d out TunnelLayer.java TunnelLayerCheck.java
 * java -cp out redero.abyssaldepths.worldgen.TunnelLayerCheck [seed] [originX] [originZ] [size] [pngDir]
 * </pre>
 *
 * Flood-fills the walkable columns of a square region and reports components, slopes,
 * clearances and cavern share. Exits with code 1 if a hard requirement is violated.
 */
public final class TunnelLayerCheck {
    private static final int NONE = Integer.MIN_VALUE;

    public static void main(String[] args) throws Exception {
        final long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        final int ox = args.length > 1 ? Integer.parseInt(args[1]) : -1024;
        final int oz = args.length > 2 ? Integer.parseInt(args[2]) : -1024;
        final int size = args.length > 3 ? Integer.parseInt(args[3]) : 2048;
        final String pngDir = args.length > 4 ? args[4] : null;

        final TunnelLayer layer = new TunnelLayer(seed);
        final int[] floor = new int[size * size];
        final int[] ceil = new int[size * size];

        final long t0 = System.nanoTime();
        IntStream.range(0, size).parallel().forEach(row -> {
            final TunnelLayer.Column c = new TunnelLayer.Column();
            for (int col = 0; col < size; col++) {
                final int i = row * size + col;
                if (layer.column(ox + col, oz + row, c)) {
                    floor[i] = c.floor;
                    ceil[i] = c.ceiling;
                } else {
                    floor[i] = NONE;
                    ceil[i] = NONE;
                }
            }
        });
        final double secs = (System.nanoTime() - t0) / 1e9;

        boolean ok = true;
        long open = 0;
        int minFloor = Integer.MAX_VALUE, maxFloor = Integer.MIN_VALUE, maxCeil = Integer.MIN_VALUE;
        int minAir = Integer.MAX_VALUE;
        long tall = 0;
        for (int i = 0; i < floor.length; i++) {
            if (floor[i] == NONE) {
                continue;
            }
            open++;
            minFloor = Math.min(minFloor, floor[i]);
            maxFloor = Math.max(maxFloor, floor[i]);
            maxCeil = Math.max(maxCeil, ceil[i]);
            final int air = ceil[i] - floor[i] + 1;
            minAir = Math.min(minAir, air);
            if (air >= 8) {
                tall++;
            }
        }

        // slopes / walkability between neighbouring open columns
        long pairs = 0, blocked = 0;
        int maxStep = 0;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                final int i = row * size + col;
                if (floor[i] == NONE) {
                    continue;
                }
                if (col + 1 < size) {
                    final int j = i + 1;
                    if (floor[j] != NONE) {
                        pairs++;
                        maxStep = Math.max(maxStep, Math.abs(floor[i] - floor[j]));
                        if (!walkable(floor, ceil, i, j)) {
                            blocked++;
                        }
                    }
                }
                if (row + 1 < size) {
                    final int j = i + size;
                    if (floor[j] != NONE) {
                        pairs++;
                        maxStep = Math.max(maxStep, Math.abs(floor[i] - floor[j]));
                        if (!walkable(floor, ceil, i, j)) {
                            blocked++;
                        }
                    }
                }
            }
        }

        // connected components of the walkable graph
        final int[] label = new int[floor.length];
        final int[] queue = new int[floor.length];
        int components = 0, enclosed = 0;
        long largest = 0;
        for (int start = 0; start < floor.length; start++) {
            if (floor[start] == NONE || label[start] != 0) {
                continue;
            }
            components++;
            int head = 0, tail = 0;
            queue[tail++] = start;
            label[start] = components;
            boolean touchesBorder = false;
            while (head < tail) {
                final int i = queue[head++];
                final int row = i / size, col = i % size;
                if (row == 0 || col == 0 || row == size - 1 || col == size - 1) {
                    touchesBorder = true;
                }
                final int[] nb = {col > 0 ? i - 1 : -1, col < size - 1 ? i + 1 : -1,
                        row > 0 ? i - size : -1, row < size - 1 ? i + size : -1};
                for (final int j : nb) {
                    if (j >= 0 && floor[j] != NONE && label[j] == 0 && walkable(floor, ceil, i, j)) {
                        label[j] = components;
                        queue[tail++] = j;
                    }
                }
            }
            largest = Math.max(largest, tail);
            if (!touchesBorder) {
                enclosed++;
            }
        }

        System.out.printf("seed=%d origin=(%d,%d) size=%d  (%.1fs)%n", seed, ox, oz, size, secs);
        System.out.printf("open columns      : %.1f %%%n", 100.0 * open / floor.length);
        System.out.printf("floor range       : %d .. %d (allowed %d .. %d)%n", minFloor, maxFloor,
                TunnelLayer.MIN_FLOOR_Y, TunnelLayer.MAX_FLOOR_Y);
        System.out.printf("highest air block : %d (allowed <= %d)%n", maxCeil, TunnelLayer.MAX_AIR_Y);
        System.out.printf("min air height    : %d%n", minAir);
        System.out.printf("cavern-like cols  : %.1f %% of open (air height >= 8)%n", 100.0 * tall / open);
        System.out.printf("max floor step    : %d between neighbours%n", maxStep);
        System.out.printf("blocked pairs     : %d of %d%n", blocked, pairs);
        System.out.printf("components        : %d (largest %.2f %% of open), enclosed: %d%n", components,
                100.0 * largest / open, enclosed);

        if (minFloor < TunnelLayer.MIN_FLOOR_Y || maxFloor > TunnelLayer.MAX_FLOOR_Y
                || maxCeil > TunnelLayer.MAX_AIR_Y || minAir < 3 || blocked != 0 || enclosed != 0) {
            System.out.println("RESULT: FAIL");
            ok = false;
        } else {
            System.out.println("RESULT: OK");
        }

        if (pngDir != null) {
            writePngs(layer, new File(pngDir), seed, ox, oz);
        }
        System.exit(ok ? 0 : 1);
    }

    /** Both columns have room for a 2 high player at the higher floor and the step is at most 1. */
    private static boolean walkable(int[] floor, int[] ceil, int a, int b) {
        if (Math.abs(floor[a] - floor[b]) > 1) {
            return false;
        }
        final int top = Math.max(floor[a], floor[b]);
        return Math.min(ceil[a], ceil[b]) >= top + 1;
    }

    private static void writePngs(TunnelLayer layer, File dir, long seed, int ox, int oz) throws Exception {
        dir.mkdirs();
        final int w = 1024;
        final BufferedImage top = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
        final TunnelLayer.Column c = new TunnelLayer.Column();
        for (int z = 0; z < w; z++) {
            for (int x = 0; x < w; x++) {
                int rgb = 0x202020;
                if (layer.column(ox + x, oz + z, c)) {
                    final double t = (c.floor - TunnelLayer.MIN_FLOOR_Y)
                            / (double) (TunnelLayer.MAX_FLOOR_Y - TunnelLayer.MIN_FLOOR_Y);
                    final int air = c.ceiling - c.floor + 1;
                    final int r = (int) (40 + 215 * t);
                    final int g = (int) (200 - 90 * t);
                    final int b = (int) (230 - 200 * t);
                    rgb = air >= 8 ? brighten(r, g, b) : (r << 16) | (g << 8) | b;
                }
                top.setRGB(x, z, rgb);
            }
        }
        ImageIO.write(top, "png", new File(dir, "topdown_" + seed + ".png"));

        // vertical cross-section along z = oz + 512, 6 px per block in y
        final int sy = 6, h = (TunnelLayer.MAX_FLOOR_Y + 40 - TunnelLayer.BOTTOM_Y) * sy;
        final BufferedImage side = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            final boolean open = layer.column(ox + x, oz + 512, c);
            for (int py = 0; py < h; py++) {
                final int y = TunnelLayer.BOTTOM_Y + (h - 1 - py) / sy;
                int rgb = 0x303030;
                if (y == TunnelLayer.BOTTOM_Y) {
                    rgb = 0x000000;
                } else if (open && y >= c.floor && y <= c.ceiling) {
                    rgb = 0xe8f0ff;
                }
                side.setRGB(x, py, rgb);
            }
        }
        ImageIO.write(side, "png", new File(dir, "section_" + seed + ".png"));
        System.out.println("PNGs written to " + dir);
    }

    private static int brighten(int r, int g, int b) {
        return (Math.min(255, r + 70) << 16) | (Math.min(255, g + 55) << 8) | Math.min(255, b + 55);
    }
}
