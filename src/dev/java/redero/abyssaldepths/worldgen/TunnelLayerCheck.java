package redero.abyssaldepths.worldgen;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

/**
 * Standalone check for {@link TunnelLayer} (no Minecraft needed).
 *
 * <pre>
 * ./gradlew layerCheck -Pargs="[seed] [originX] [originZ] [size] [pngDir]"
 * </pre>
 *
 * Flood-fills the walkable floor of the great cavern (connector tunnels ignored, pillars count as
 * obstacles) and reports components, clearances and air shares. Exits with code 1 if a hard
 * requirement is violated.
 */
public final class TunnelLayerCheck {
    private static final int NONE = Integer.MIN_VALUE;
    /** Pockets cut off from the main cavern may cover at most this share of the walkable floor. */
    private static final double MAX_ENCLOSED_SHARE = 0.002;

    public static void main(String[] args) throws Exception {
        final long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        final int ox = args.length > 1 ? Integer.parseInt(args[1]) : -1024;
        final int oz = args.length > 2 ? Integer.parseInt(args[2]) : -1024;
        final int size = args.length > 3 ? Integer.parseInt(args[3]) : 2048;
        final String pngDir = args.length > 4 ? args[4] : null;

        final TunnelLayer layer = new TunnelLayer(seed);
        final int[] floor = new int[size * size];  // walkable floor, NONE if rock or pillar
        final int[] ceil = new int[size * size];   // last air block above the floor (without connectors)
        final boolean[] open = new boolean[size * size]; // not rock or massif (pillars ignored)
        final AtomicLong rock = new AtomicLong(), pillar = new AtomicLong(), low = new AtomicLong();
        final AtomicInteger minFloor = new AtomicInteger(Integer.MAX_VALUE), maxFloor = new AtomicInteger(Integer.MIN_VALUE);

        final long t0 = System.nanoTime();
        IntStream.range(0, size).parallel().forEach(row -> {
            final TunnelLayer.Column c = new TunnelLayer.Column();
            for (int col = 0; col < size; col++) {
                final int i = row * size + col;
                final int x = ox + col, z = oz + row;
                floor[i] = NONE;
                if (!layer.column(x, z, c)) {
                    rock.incrementAndGet();
                    continue;
                }
                open[i] = true;
                minFloor.accumulateAndGet(c.floor, Math::min);
                maxFloor.accumulateAndGet(c.floor, Math::max);
                if (!layer.cavernAir(x, c.floor, z, c) || !layer.cavernAir(x, c.floor + 1, z, c)) {
                    pillar.incrementAndGet();
                    continue;
                }
                int top = c.floor + 1;
                while (layer.cavernAir(x, top + 1, z, c)) {
                    top++;
                }
                if (top - c.floor + 1 <= 6) {
                    low.incrementAndGet();
                }
                floor[i] = c.floor;
                ceil[i] = top;
            }
        });
        final double secs = (System.nanoTime() - t0) / 1e9;

        // longest straight line through open cavern along x and along z
        // and the mean free path: average run length seen from a random open column
        int straightX = 0, straightZ = 0;
        long openCols = 0, runSquares = 0;
        for (int a = 0; a < size; a++) {
            int runX = 0, runZ = 0;
            for (int b = 0; b <= size; b++) {
                final boolean openX = b < size && open[a * size + b];
                final boolean openZ = b < size && open[b * size + a];
                if (!openX && runX > 0) {
                    runSquares += (long) runX * runX;
                }
                if (!openZ && runZ > 0) {
                    runSquares += (long) runZ * runZ;
                }
                runX = openX ? runX + 1 : 0;
                runZ = openZ ? runZ + 1 : 0;
                openCols += (openX ? 1 : 0) + (openZ ? 1 : 0);
                straightX = Math.max(straightX, runX);
                straightZ = Math.max(straightZ, runZ);
            }
        }
        final double meanRun = openCols == 0 ? 0.0 : (double) runSquares / openCols;

        // highest air block and minimum clearance in full 3D on a sample
        final int sample = Math.min(size, 256);
        final AtomicInteger maxAir = new AtomicInteger(Integer.MIN_VALUE), minAir = new AtomicInteger(Integer.MAX_VALUE);
        final AtomicLong tunnel = new AtomicLong(), solid3d = new AtomicLong();
        final AtomicInteger tunnelMinY = new AtomicInteger(Integer.MAX_VALUE), tunnelMaxY = new AtomicInteger(Integer.MIN_VALUE);
        IntStream.range(0, sample).parallel().forEach(row -> {
            final TunnelLayer.Column c = new TunnelLayer.Column();
            for (int col = 0; col < sample; col++) {
                final int x = ox + col, z = oz + row;
                layer.column(x, z, c);
                for (int y = TunnelLayer.BOTTOM_Y; y <= TunnelLayer.CONNECTOR_MAX_Y + 8; y++) {
                    final boolean cavern = layer.isAir(x, y, z, c);
                    final boolean connector = connectorAir(layer, x, y, z);
                    if (cavern) {
                        maxAir.accumulateAndGet(y, Math::max);
                        if (y < TunnelLayer.MIN_AIR_Y) {
                            maxAir.set(Integer.MAX_VALUE); // bedrock touched
                        }
                    }
                    if (connector) {
                        tunnelMinY.accumulateAndGet(y, Math::min);
                        tunnelMaxY.accumulateAndGet(y, Math::max);
                        if (!cavern) {
                            tunnel.incrementAndGet();
                        }
                    }
                    if (!cavern && !connector && y >= TunnelLayer.MIN_AIR_Y && y <= TunnelLayer.MAX_AIR_Y) {
                        solid3d.incrementAndGet();
                    }
                }
                final int i = row * size + col;
                if (floor[i] != NONE) {
                    minAir.accumulateAndGet(ceil[i] - floor[i] + 1, Math::min);
                }
            }
        });

        // connected components of the walkable graph
        long walkable = 0;
        final int[] label = new int[floor.length];
        final int[] queue = new int[floor.length];
        int components = 0, enclosed = 0;
        long largest = 0, enclosedCols = 0;
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
            walkable += tail;
            largest = Math.max(largest, tail);
            if (!touchesBorder) {
                enclosed++;
                enclosedCols += tail;
            }
        }

        final double total = floor.length;
        final double layerBlocks = (double) sample * sample * (TunnelLayer.MAX_AIR_Y - TunnelLayer.MIN_AIR_Y + 1);
        System.out.printf("seed=%d origin=(%d,%d) size=%d  (%.1fs)%n", seed, ox, oz, size, secs);
        System.out.printf("walkable floor    : %.1f %% of columns%n", 100.0 * walkable / total);
        System.out.printf("rock masses       : %.1f %% of columns%n", 100.0 * rock.get() / total);
        System.out.printf("pillars (at feet) : %.1f %% of columns%n", 100.0 * pillar.get() / total);
        System.out.printf("low (<=6 air)     : %.1f %% of walkable%n", 100.0 * low.get() / walkable);
        System.out.printf("floor range       : %d .. %d (allowed %d .. %d)%n", minFloor.get(), maxFloor.get(),
                TunnelLayer.MIN_FLOOR_Y, TunnelLayer.MAX_FLOOR_Y);
        System.out.printf("highest air block : %d (allowed <= %d)%n", maxAir.get(), TunnelLayer.MAX_AIR_Y);
        System.out.printf("min air height    : %d%n", minAir.get());
        System.out.printf("longest straight  : x %d, z %d blocks, mean %.0f (pillars ignored)%n", straightX, straightZ, meanRun);
        System.out.printf("layer solid       : %.1f %%, tunnel air: %.2f %% (3D sample %d^2)%n",
                100.0 * solid3d.get() / layerBlocks, 100.0 * tunnel.get() / layerBlocks, sample);
        System.out.printf("tunnel y range    : %d .. %d (allowed %d .. %d)%n", tunnelMinY.get(), tunnelMaxY.get(),
                TunnelLayer.MIN_AIR_Y, TunnelLayer.CONNECTOR_MAX_Y);
        System.out.printf("components        : %d (largest %.3f %% of walkable), enclosed: %d (%.3f %%)%n", components,
                100.0 * largest / walkable, enclosed, 100.0 * enclosedCols / walkable);

        final boolean ok = minFloor.get() >= TunnelLayer.MIN_FLOOR_Y && maxFloor.get() <= TunnelLayer.MAX_FLOOR_Y
                // 2 only occurs below the flare of a pillar, walking there is covered by the flood fill
                && maxAir.get() <= TunnelLayer.MAX_AIR_Y && minAir.get() >= 2
                && (tunnelMinY.get() == Integer.MAX_VALUE || tunnelMinY.get() >= TunnelLayer.MIN_AIR_Y)
                && enclosedCols <= MAX_ENCLOSED_SHARE * walkable;
        System.out.println(ok ? "RESULT: OK" : "RESULT: FAIL");

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

    /** Connector mask of the last chunk asked for, per thread: the scans run row by row. */
    private static final ThreadLocal<Object[]> LAST_MASK = ThreadLocal.withInitial(() -> new Object[] {Long.MIN_VALUE, null});

    private static boolean connectorAir(TunnelLayer layer, int x, int y, int z) {
        if (y < TunnelLayer.MIN_AIR_Y || y > TunnelLayer.CONNECTOR_MAX_Y) {
            return false;
        }
        final Object[] last = LAST_MASK.get();
        final long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL);
        if ((long) last[0] != key) {
            last[0] = key;
            last[1] = layer.connectorMask(x >> 4, z >> 4);
        }
        return ((boolean[]) last[1])[TunnelLayer.maskIndex(x & 15, y, z & 15)];
    }

    private static void writePngs(TunnelLayer layer, File dir, long seed, int ox, int oz) throws Exception {
        dir.mkdirs();
        final int w = 1024;
        final int lowY = TunnelLayer.MIN_FLOOR_Y;

        // top-down: brightness = air height of the cavern, tint = floor height,
        // black = rock, dark red = pillar, blue = connector tunnel
        final BufferedImage top = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
        IntStream.range(0, w).parallel().forEach(z -> {
            final TunnelLayer.Column c = new TunnelLayer.Column();
            for (int x = 0; x < w; x++) {
                final int bx = ox + x, bz = oz + z;
                int rgb = 0x151515;
                if (layer.column(bx, bz, c)) {
                    if (!layer.cavernAir(bx, c.floor, bz, c)) {
                        rgb = 0x6a2020;
                    } else {
                        int air = 0;
                        while (layer.cavernAir(bx, c.floor + air, bz, c)) {
                            air++;
                        }
                        final double b = 0.25 + 0.75 * Math.min(1.0, air / 36.0);
                        final double t = (c.floor - TunnelLayer.MIN_FLOOR_Y)
                                / (double) (TunnelLayer.MAX_FLOOR_Y - TunnelLayer.MIN_FLOOR_Y);
                        rgb = rgb(b * (150 + 105 * t), b * (200 - 30 * t), b * (150 - 100 * t));
                        if (connectorAir(layer, bx, c.floor, bz) || connectorAir(layer, bx, c.floor + air, bz)) {
                            rgb = 0x2050c0;
                        }
                    }
                } else {
                    for (int y = lowY; y <= TunnelLayer.CONNECTOR_MAX_Y; y += 2) {
                        if (connectorAir(layer, bx, y, bz)) {
                            rgb = 0x203a80;
                            break;
                        }
                    }
                }
                top.setRGB(x, z, rgb);
            }
        });
        ImageIO.write(top, "png", new File(dir, "topdown_" + seed + ".png"));

        // vertical cross-sections along z = oz + 512 and oz + 515
        final int sy = 4, h = (TunnelLayer.CONNECTOR_MAX_Y + 4 - TunnelLayer.BOTTOM_Y + 1) * sy;
        for (final int dzs : new int[] {512, 700}) {
            final BufferedImage side = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            final int bz = oz + dzs;
            IntStream.range(0, w).parallel().forEach(x -> {
                final TunnelLayer.Column c = new TunnelLayer.Column();
                layer.column(ox + x, bz, c);
                for (int py = 0; py < h; py++) {
                    final int y = TunnelLayer.CONNECTOR_MAX_Y + 4 - py / sy;
                    int rgb = 0x3a3a3a;
                    if (y == TunnelLayer.BOTTOM_Y) {
                        rgb = 0x000000;
                    } else if (layer.cavernAir(ox + x, y, bz, c) && y <= TunnelLayer.MAX_AIR_Y) {
                        rgb = 0xe8f0ff;
                    } else if (connectorAir(layer, ox + x, y, bz)) {
                        rgb = 0x8fb4ff;
                    }
                    side.setRGB(x, py, rgb);
                }
            });
            ImageIO.write(side, "png", new File(dir, "section" + dzs + "_" + seed + ".png"));
        }

        // horizontal slices through the rock below the floor and in the middle of the cavern
        for (final int y : new int[] {-118, -95}) {
            final BufferedImage slice = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
            IntStream.range(0, w).parallel().forEach(z -> {
                final TunnelLayer.Column c = new TunnelLayer.Column();
                for (int x = 0; x < w; x++) {
                    layer.column(ox + x, oz + z, c);
                    int rgb = 0x2a2a2a;
                    if (layer.cavernAir(ox + x, y, oz + z, c)) {
                        rgb = 0xe8f0ff;
                    } else if (connectorAir(layer, ox + x, y, oz + z)) {
                        rgb = 0x5f8fff;
                    }
                    slice.setRGB(x, z, rgb);
                }
            });
            ImageIO.write(slice, "png", new File(dir, "slice" + (-y) + "_" + seed + ".png"));
        }
        System.out.println("PNGs written to " + dir);
    }

    private static int rgb(double r, double g, double b) {
        return ((int) Math.max(0, Math.min(255, r)) << 16) | ((int) Math.max(0, Math.min(255, g)) << 8)
                | (int) Math.max(0, Math.min(255, b));
    }
}
