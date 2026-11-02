package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.ClientConfig.FractureMarks;

/**
 * Fracturing as it is seen in the world:
 * <ul>
 * <li>The marks of the corruption ({@link ClientConfig#fractureMarks()}: veins, circuits, shards, torn rows, glyphs)
 * grow over an entity's own texture as its corruption builds up, a light running along them. Once it is taken
 * (frozen creatures, a player as the corruption takes them, a fractured body), its texture turns black and white,
 * soft greys where the two meet, the marks grown all over it, shaken a pixel now and then, fragments coming away.</li>
 * <li>The cells a trial is about to strike, flickering in greys, then the strike: white bursting up.</li>
 * </ul>
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class FractureView {
    private static final float PX = 1.0F / 16;
    /** Entities drawn in corruption, until which client tick. */
    private static final Int2LongOpenHashMap CORRUPTED = new Int2LongOpenHashMap();
    /** How far corruption has come in each entity (0 .. 1). */
    private static final Int2FloatOpenHashMap CRACKS = new Int2FloatOpenHashMap();
    /** The frames the marks' light runs in. */
    private static final int VERSIONS = 4;
    /** Per entity: its Corruption Scars (marks that stay on it). */
    private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap SCARRED = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
    /** Players the corruption takes (only their body is seen going): from which tick. */
    private static final Int2LongOpenHashMap DYING = new Int2LongOpenHashMap();
    /** Per texture, style and entity: where its marks lie (each entity its own). */
    private static final Map<String, Marks> MARKS = new HashMap<>();
    /** Per texture, style, step of 10 %, frame and black-and-white or not: the texture so marked. */
    private static final Map<String, ResourceLocation> LOOKS = new HashMap<>();

    /** Mark depths per pixel: none, a mark's core, its margin, its faint glow round that, a pixel torn sideways. */
    private static final byte NONE = -1, CORE = 0, MARGIN = 1, GLOW = 2, TORN = 3;

    /**
     * The marks over a texture: per pixel when it is marked (0 .. 1 of the corruption; above 1 never), how deep
     * (core, margin, glow, torn) and where along its mark it lies (0 .. 1, the light runs along that; for torn
     * pixels: how far they are torn).
     */
    private record Marks(float[] when, byte[] depth, float[] along) {
    }

    /** A strike to come: its cells (middles, size), height, when it was marked and how long the warning is. */
    private record Strike(double y, int size, List<int[]> cells, long marked, int warn) {
    }

    private static final List<Strike> STRIKES = new ArrayList<>();

    private FractureView() {
    }

    public static void corrupt(int entityId, int ticks) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            CORRUPTED.put(entityId, mc.level.getGameTime() + ticks);
        }
    }

    /** How far corruption has come in an entity, as this client has it (0 .. 1). */
    public static float cracksOf(int entityId) {
        return CRACKS.isEmpty() ? 0.0F : CRACKS.get(entityId);
    }

    /** How far corruption has come in an entity (from the server). */
    public static void cracks(int entityId, float value) {
        if (value <= 0.0F) {
            CRACKS.remove(entityId);
        } else {
            CRACKS.put(entityId, value);
        }
    }

    /** An entity's Corruption Scars (from the server): marks that stay on it, more with each. */
    public static void scarred(int entityId, int scars) {
        if (scars <= 0) {
            SCARRED.remove(entityId);
        } else {
            SCARRED.put(entityId, scars);
        }
    }

    /** A player the corruption takes: not drawn while they die (their body stays, decomposing). */
    public static void dying(int entityId) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            DYING.put(entityId, mc.level.getGameTime());
        }
    }

    /** A player the corruption takes dies quietly: no cry of hurt or of death near them (their body goes in silence). */
    @SubscribeEvent
    public static void onSound(net.neoforged.neoforge.client.event.sound.PlaySoundEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (DYING.isEmpty() || event.getSound() == null || mc.level == null) {
            return;
        }
        final ResourceLocation id = event.getSound().getLocation();
        if (!id.equals(net.minecraft.sounds.SoundEvents.PLAYER_DEATH.getLocation()) && !id.equals(net.minecraft.sounds.SoundEvents.PLAYER_HURT.getLocation())) {
            return;
        }
        for (Int2LongOpenHashMap.Entry e : DYING.int2LongEntrySet()) {
            final Entity dying = mc.level.getEntity(e.getIntKey());
            if (mc.level.getGameTime() - e.getLongValue() < 400 && dying != null
                    && dying.distanceToSqr(event.getSound().getX(), event.getSound().getY(), event.getSound().getZ()) < 16.0) {
                event.setSound(null);
                return;
            }
        }
    }

    /** How far the marks of scars reach on the scarred (a quarter, near half, two thirds). */
    private static float scarMarks(int scars) {
        return scars <= 0 ? 0.0F : 0.05F + 0.2F * Math.min(3, scars);
    }

    public static boolean corrupted(Entity entity) {
        return !CORRUPTED.isEmpty() && CORRUPTED.get(entity.getId()) >= entity.level().getGameTime();
    }

    /** A strike marked (from the trial): "y/size/warn/x,z;x,z;...". */
    public static void strike(String data) {
        final Minecraft mc = Minecraft.getInstance();
        final String[] p = data.split("/");
        if (mc.level == null || p.length < 4) {
            return;
        }
        final List<int[]> cells = new ArrayList<>();
        for (String cell : p[3].split(";")) {
            final String[] xz = cell.split(",");
            if (xz.length == 2) {
                cells.add(new int[] {Integer.parseInt(xz[0]), Integer.parseInt(xz[1])});
            }
        }
        STRIKES.add(new Strike(Double.parseDouble(p[0]), Integer.parseInt(p[1]), cells, mc.level.getGameTime(), Integer.parseInt(p[2])));
    }

    // ---- the marks ---------------------------------------------------------------------------------

    /**
     * How a style colours its marks: the core's colour and how far a core pixel goes to it (1: all the way), the colour
     * its margins and glow lean to and how far, the light running along (and whether it does), and whether it drains
     * the colour out (to the pixel's own grey) instead of leaning to a colour.
     */
    private record Tone(int core, float coreMix, int edge, float marginMix, float glowMix, int light, boolean runs, boolean drain) {
    }

    private static Tone tone(FractureMarks style) {
        return switch (style) {
            case ASH -> new Tone(0x5A5D63, 0.55F, 0x8A8C90, 0.7F, 0.35F, 0xD8D2C6, true, true);
            case FROST -> new Tone(0xC9CED6, 0.7F, 0xB4BAC4, 0.35F, 0.15F, 0xF4F0E8, true, false);
            case HAIRLINE -> new Tone(0x2C2F36, 0.8F, 0x50535A, 0.18F, 0.0F, 0x8A8D94, false, false);
            case DITHER -> new Tone(0x2A2D34, 0.6F, 0x4A4D54, 0.22F, 0.08F, 0x9A9DA4, false, false);
            case FADING -> new Tone(0x6A6C70, 0.5F, 0x8C8E92, 0.55F, 0.25F, 0xB0B2B6, false, true);
            default -> new Tone(0x0A0B10, 1.0F, 0x3A3E48, 0.6F, 0.28F, 0xF6F1E4, true, false);
        };
    }

    /** A pixel (ABGR) leaning to a colour, or (draining) to its own grey, so far. */
    private static int lean(int abgr, int rgb, float k, boolean drain, float darker) {
        if (!drain) {
            return blend(abgr, rgb, k);
        }
        final int r = abgr & 255, g = abgr >> 8 & 255, b = abgr >> 16 & 255;
        final int lum = (int) ((0.3F * r + 0.59F * g + 0.11F * b) * darker);
        return blend(abgr, Mth.clamp(lum - 3, 0, 255) << 16 | lum << 8 | Mth.clamp(lum + 4, 0, 255), k); // a cool grey
    }

    private static Marks marks(ResourceLocation original, FractureMarks style, NativeImage source, int salt) {
        return MARKS.computeIfAbsent(original + "#" + style + "#" + salt, k -> {
            final int w = source.getWidth(), h = source.getHeight();
            final Marks m = new Marks(new float[w * h], new byte[w * h], new float[w * h]);
            Arrays.fill(m.when, 2.0F);
            Arrays.fill(m.depth, NONE);
            final Random random = new Random(original.hashCode() * 31L + style.ordinal() * 7919L + salt * 104729L);
            final int count = Math.max(6, w * h / 150);
            switch (style) {
                case VEINS -> walkers(m, w, h, random, starts(source, random, count), 8, false);
                case CIRCUIT -> walkers(m, w, h, random, starts(source, random, count), 4, true);
                case SHARDS -> shards(m, w, h, random, starts(source, random, count + 3));
                case SIGILS -> sigils(m, w, h, random, starts(source, random, count * 2 + 4));
                case GLITCH -> torn(m, w, h, random, starts(source, random, Math.max(10, h)));
                case ASH, FROST -> walkers(m, w, h, random, starts(source, random, count), 8, false);
                case HAIRLINE -> walkers(m, w, h, random, starts(source, random, Math.max(4, count / 2)), 8, false, false);
                case DITHER -> blobs(m, w, h, random, starts(source, random, count), true);
                case FADING -> blobs(m, w, h, random, starts(source, random, count), false);
                case FLOW -> walkers(m, w, h, random, starts(source, random, count), 8, false, true);
                default -> {
                }
            }
            margins(m, w, h);
            return m;
        });
    }

    /**
     * Where marks start: at least one in every part of the texture that is drawn (it is cut into squares of 16
     * pixels, about a body part's face each), in a shuffled order, so every side of the body is reached.
     */
    private static int[][] starts(NativeImage source, Random random, int count) {
        final int w = source.getWidth(), h = source.getHeight(), size = 16;
        final List<int[]> regions = new ArrayList<>();
        for (int ry = 0; ry < h; ry += size) {
            for (int rx = 0; rx < w; rx += size) {
                for (int tries = 0; tries < 16; tries++) { // a drawn pixel in it, if there is one
                    final int x = rx + random.nextInt(Math.min(size, w - rx)), y = ry + random.nextInt(Math.min(size, h - ry));
                    if (source.getPixelRGBA(x, y) >>> 24 != 0) {
                        regions.add(new int[] {x, y});
                        break;
                    }
                }
            }
        }
        if (regions.isEmpty()) {
            regions.add(new int[] {random.nextInt(w), random.nextInt(h)});
        }
        java.util.Collections.shuffle(regions, random);
        final int total = Math.max(count, regions.size());
        final int[][] starts = new int[total][];
        for (int c = 0; c < total; c++) {
            final int[] r = regions.get(c % regions.size());
            starts[c] = c < regions.size() ? r : new int[] {Mth.clamp(r[0] + random.nextInt(9) - 4, 0, w - 1), Mth.clamp(r[1] + random.nextInt(9) - 4, 0, h - 1)};
        }
        return starts;
    }

    /** Marks a pixel as a core of a mark (keeping the earliest). */
    private static void paint(Marks m, int w, int h, int x, int y, float when, float along) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return;
        }
        final int i = y * w + x;
        if (when < m.when[i] || m.depth[i] != CORE) {
            if (m.depth[i] == CORE && when >= m.when[i]) {
                return;
            }
            m.when[i] = Math.min(m.when[i], when);
            m.depth[i] = CORE;
            m.along[i] = along;
        }
    }

    private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    /**
     * Lines walking out from a few starts, branching: at slants (veins), or straight turning at right angles with a
     * square node at each end (circuits).
     */
    private static void walkers(Marks m, int w, int h, Random random, int[][] starts, int ways, boolean circuit) {
        walkers(m, w, h, random, starts, ways, circuit, true);
    }

    /** Soft patches spreading (in a checker of pixels, dithered, or whole). */
    private static void blobs(Marks m, int w, int h, Random random, int[][] starts, boolean dither) {
        final int count = starts.length;
        for (int c = 0; c < count; c++) {
            final float r = 2.0F + random.nextFloat() * Math.max(2.0F, w / 14.0F);
            final int cx = starts[c][0], cy = starts[c][1], n = (int) Math.ceil(r);
            for (int y = cy - n; y <= cy + n; y++) {
                for (int x = cx - n; x <= cx + n; x++) {
                    final float d = (float) Math.hypot(x - cx, y - cy) / r + (UndertowRenderer.noise(x * 7 + y, c) - 0.5F) * 0.4F;
                    if (d < 0.8F && (!dither || Math.floorMod(x + y, 2) == 0 || d < 0.35F)) {
                        paint(m, w, h, x, y, c / (float) count * 0.8F + d * 0.2F, d);
                    }
                }
            }
        }
    }

    private static void walkers(Marks m, int w, int h, Random random, int[][] starts, int ways, boolean circuit, boolean thick) {
        final int longest = Math.max(10, (int) (Math.max(w, h) * 0.5F)), count = starts.length;
        for (int c = 0; c < count; c++) {
            final float start = c / (float) count * 0.35F; // one after another
            final List<int[]> open = new ArrayList<>(); // x, y, direction, steps left, steps from the start
            open.add(new int[] {starts[c][0], starts[c][1], random.nextInt(8) & (circuit ? 6 : 7), longest, 0});
            int branches = 0;
            while (!open.isEmpty()) {
                final int[] walker = open.remove(open.size() - 1);
                int x = walker[0], y = walker[1], dir = walker[2], straight = 0;
                for (int s = 0; s < walker[3]; s++) {
                    final int from = walker[4] + s;
                    final float when = start + from / (float) longest * 0.65F, along = from / (float) longest;
                    paint(m, w, h, x, y, when, along);
                    if (thick && from < longest * 0.6F) { // two pixels thick near its start, thinning out
                        paint(m, w, h, x + (DIRS[dir][1] != 0 ? 1 : 0), y + (DIRS[dir][1] != 0 ? 0 : 1), when, along);
                    }
                    straight++;
                    if (circuit) {
                        if (straight > 3 && random.nextFloat() < 0.16F) {
                            dir = (dir + (random.nextBoolean() ? 2 : 6)) % 8;
                            straight = 0;
                        }
                    } else if (random.nextFloat() < 0.3F) {
                        dir = (dir + (random.nextBoolean() ? 1 : 7)) % 8;
                    }
                    if (branches < 5 && s > 2 && random.nextFloat() < (circuit ? 0.06F : 0.09F)) {
                        branches++;
                        open.add(new int[] {x, y, (dir + (random.nextBoolean() ? 2 : 6)) % 8, (walker[3] - s) / 2, from});
                    }
                    x += DIRS[dir][0];
                    y += DIRS[dir][1];
                    if (x < 0 || y < 0 || x >= w || y >= h) {
                        break;
                    }
                }
                if (circuit) { // a node at the end
                    final float when = start + (walker[4] + walker[3]) / (float) longest * 0.65F;
                    for (int d = 0; d < 4; d++) {
                        paint(m, w, h, x + (d & 1), y + (d >> 1), when, 1.0F);
                    }
                }
            }
        }
    }

    /** Angular shards: long thin triangles, one after another. */
    private static void shards(Marks m, int w, int h, Random random, int[][] starts) {
        final int count = starts.length;
        for (int c = 0; c < count; c++) {
            final float cx = starts[c][0], cy = starts[c][1], a = random.nextFloat() * Mth.TWO_PI;
            final float length = 4 + random.nextFloat() * Math.max(4, w / 7.0F), width = 1.5F + random.nextFloat() * 2.2F;
            final float ax = cx + Mth.cos(a) * length, ay = cy + Mth.sin(a) * length; // its point
            final float bx = cx - Mth.sin(a) * width, by = cy + Mth.cos(a) * width, ex = cx + Mth.sin(a) * width, ey = cy - Mth.cos(a) * width;
            final int r = (int) length + 2;
            for (int y = (int) cy - r; y <= cy + r; y++) {
                for (int x = (int) cx - r; x <= cx + r; x++) {
                    final float px = x + 0.5F, py = y + 0.5F;
                    if (inside(px, py, ax, ay, bx, by, ex, ey)) {
                        final float along = Mth.clamp((float) Math.hypot(px - cx, py - cy) / length, 0.0F, 1.0F);
                        paint(m, w, h, x, y, c / (float) count * 0.8F + along * 0.2F, along);
                    }
                }
            }
        }
    }

    private static boolean inside(float px, float py, float ax, float ay, float bx, float by, float cx, float cy) {
        final float d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by);
        final float d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
        final float d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
        final boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    /** Blocky glyphs (3 x 3 pixels), strung together by lines at right angles. */
    private static void sigils(Marks m, int w, int h, Random random, int[][] starts) {
        final int count = starts.length;
        final int[] glyphs = {0b111101111, 0b010111010, 0b101010101, 0b111010111, 0b110011110, 0b011110011, 0b100111001, 0b111100111};
        int lx = -1, ly = -1;
        for (int c = 0; c < count; c++) {
            final int gx = Math.min(starts[c][0], Math.max(0, w - 3)), gy = Math.min(starts[c][1], Math.max(0, h - 3));
            final float when = c / (float) count * 0.9F;
            final int glyph = glyphs[random.nextInt(glyphs.length)];
            for (int b = 0; b < 9; b++) {
                if ((glyph >> b & 1) == 1) {
                    paint(m, w, h, gx + b % 3, gy + b / 3, when, 0.0F);
                }
            }
            if (lx >= 0 && Math.abs(gx - lx) + Math.abs(gy - ly) < Math.max(w, h) / 3) { // a line from the one before, across then down
                final int steps = Math.abs(gx - lx) + Math.abs(gy - ly);
                int x = lx + 1, y = ly + 1, s = 0;
                while (x != gx + 1) {
                    paint(m, w, h, x, y, when - 0.05F + 0.05F * s / Math.max(1, steps), s++ / (float) Math.max(1, steps));
                    x += Integer.signum(gx + 1 - x);
                }
                while (y != gy + 1) {
                    paint(m, w, h, x, y, when - 0.05F + 0.05F * s / Math.max(1, steps), s++ / (float) Math.max(1, steps));
                    y += Integer.signum(gy + 1 - y);
                }
            }
            lx = gx;
            ly = gy;
        }
    }

    /** Rows torn sideways: runs of pixels shifted, one after another. */
    private static void torn(Marks m, int w, int h, Random random, int[][] starts) {
        final int count = starts.length;
        for (int c = 0; c < count; c++) {
            final int y = starts[c][1], x0 = Math.max(0, starts[c][0] - 3), length = 4 + random.nextInt(Math.max(2, w / 3)), rows = 1 + random.nextInt(3);
            final float shift = (2 + random.nextInt(5)) * (random.nextBoolean() ? 1 : -1);
            final float when = c / (float) count * 0.9F;
            for (int r = 0; r < rows && y + r < h; r++) {
                for (int x = x0; x < Math.min(w, x0 + length); x++) {
                    final int i = (y + r) * w + x;
                    if (when < m.when[i]) {
                        m.when[i] = when;
                        m.depth[i] = TORN;
                        m.along[i] = shift;
                    }
                }
            }
        }
    }

    /** Round each core: a margin beside it, a faint glow beyond (so the marks blend into what they lie on). */
    private static void margins(Marks m, int w, int h) {
        for (int pass = 0; pass < 2; pass++) {
            final byte inner = pass == 0 ? CORE : MARGIN, outer = pass == 0 ? MARGIN : GLOW;
            final byte[] depth = m.depth.clone();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    final int i = y * w + x;
                    if (depth[i] != inner && !(pass == 0 && depth[i] == TORN)) {
                        continue;
                    }
                    for (int d = 0; d < 8; d += pass == 0 ? 2 : 1) {
                        final int nx = x + DIRS[d][0], ny = y + DIRS[d][1];
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                            continue;
                        }
                        final int j = ny * w + nx;
                        if (m.depth[j] == NONE || m.depth[j] > outer && m.depth[j] != TORN) {
                            m.depth[j] = outer;
                            m.when[j] = m.when[i];
                        } else if (m.depth[j] == outer) {
                            m.when[j] = Math.min(m.when[j], m.when[i]);
                        }
                    }
                }
            }
        }
    }

    private static int blend(int abgr, int rgb, float k) {
        final int r = (int) Mth.lerp(k, abgr & 255, rgb >> 16 & 255), g = (int) Mth.lerp(k, abgr >> 8 & 255, rgb >> 8 & 255),
                b = (int) Mth.lerp(k, abgr >> 16 & 255, rgb & 255);
        return abgr & 0xFF000000 | b << 16 | g << 8 | r;
    }

    /** The texture in black and white (by brightness), soft greys where the two meet, a few pixels gone. */
    private static NativeImage blackAndWhite(NativeImage source, int version, int salt) {
        final int w = source.getWidth(), h = source.getHeight();
        final int[] white = new int[w * h]; // 1 white, 0 black, -1 clear
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int abgr = source.getPixelRGBA(x, y);
                if (abgr >>> 24 == 0 || UndertowRenderer.noise(x * 131 + y, version * 17 + salt) < 0.03F) {
                    white[y * w + x] = -1;
                    continue;
                }
                final int r = abgr & 255, g = abgr >> 8 & 255, b = abgr >> 16 & 255;
                white[y * w + x] = 0.3 * r + 0.59 * g + 0.11 * b > 105 ? 1 : 0;
            }
        }
        final NativeImage image = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int self = white[y * w + x];
                if (self < 0) {
                    image.setPixelRGBA(x, y, 0);
                    continue;
                }
                int other = 0;
                for (int d = 0; d < 8; d += 2) {
                    final int nx = x + DIRS[d][0], ny = y + DIRS[d][1];
                    if (nx >= 0 && ny >= 0 && nx < w && ny < h && white[ny * w + nx] >= 0 && white[ny * w + nx] != self) {
                        other++;
                    }
                }
                // where black meets white, a step or two of grey between: the lights warm, the darks cold
                final int rgb = self == 1 ? (other == 0 ? 0xF3EFE6 : other == 1 ? 0xCFC8BC : 0xA49E95) : (other == 0 ? 0x0E1014 : other == 1 ? 0x30343D : 0x575B64);
                image.setPixelRGBA(x, y, 0xFF000000 | swap(rgb));
            }
        }
        return image;
    }

    /** The texture marked as far as the corruption has come (step of 10 %), in a frame of the light; black and white once taken. */
    private static ResourceLocation look(ResourceLocation original, int step, int version, boolean taken, int salt, int decay) {
        final FractureMarks style = ClientConfig.fractureMarks();
        final String key = original + "#" + style + "#" + step + "#" + version + "#" + taken + "#" + salt + "#" + decay;
        if (LOOKS.size() > 2000) { // many looks made: let them all go (the ones still needed are made again)
            for (ResourceLocation id : LOOKS.values()) {
                if (id != null) {
                    Minecraft.getInstance().getTextureManager().release(id);
                }
            }
            LOOKS.clear();
            MARKS.clear();
            ANIMS.clear();
        }
        if (LOOKS.containsKey(key)) {
            return LOOKS.get(key);
        }
        final NativeImage source = SkinPixels.read(original);
        ResourceLocation id = null;
        if (source != null) {
            final int w = source.getWidth(), h = source.getHeight();
            final Marks m = marks(original, style, source, salt);
            final NativeImage base = taken ? blackAndWhite(source, version, original.hashCode()) : copy(source);
            final NativeImage image = copy(base);
            final Tone tone = tone(style);
            if (animated(style)) {
                animate(image, base, style, anim(original, source, salt), m, version, step / 10.0F, w, h);
            }
            final float reach = step / 10.0F, light = version / (float) VERSIONS;
            for (int i = 0; i < w * h; i++) {
                final int x = i % w, y = i / w;
                final int abgr = base.getPixelRGBA(x, y);
                if (animated(style) || m.depth[i] == NONE || m.when[i] > reach || abgr >>> 24 == 0) {
                    continue;
                }
                switch (m.depth[i]) {
                    case CORE -> {
                        final float run = tone.runs() ? (m.along[i] * 3.0F - light + 4.0F) % 1.0F : 1.0F; // the light running out along it
                        if (run < 0.1F) {
                            image.setPixelRGBA(x, y, 0xFF000000 | swap(tone.light()));
                        } else if (run < 0.2F) {
                            image.setPixelRGBA(x, y, 0xFF000000 | swap(mix(tone.edge(), tone.light())));
                        } else {
                            image.setPixelRGBA(x, y, tone.drain() ? lean(abgr, 0, 1.0F, true, 0.6F) : blend(abgr, tone.core(), tone.coreMix()));
                        }
                    }
                    case MARGIN -> image.setPixelRGBA(x, y, lean(abgr, tone.edge(), tone.marginMix(), tone.drain(), 0.85F));
                    case GLOW -> image.setPixelRGBA(x, y, lean(abgr, tone.edge(), tone.glowMix(), tone.drain(), 1.0F));
                    case TORN -> { // torn sideways, back and forth by the frame; turned over now and then
                        final int shift = (int) m.along[i] * (version % 2 == 0 ? 1 : -1);
                        final int from = base.getPixelRGBA(Math.floorMod(x - shift, w), y);
                        final int moved = from >>> 24 == 0 ? abgr : from;
                        image.setPixelRGBA(x, y, version == 3 && (x / 3 + y) % 2 == 0 ? invert(moved) : blend(moved, tone.core(), 0.25F));
                    }
                    default -> {
                    }
                }
            }
            base.close();
            if (decay > 0) { // decomposing: pixels falling away, those about to go greyed first
                final float gone = decay / 10.0F;
                for (int i = 0; i < w * h; i++) {
                    final int x = i % w, y = i / w;
                    final float n = UndertowRenderer.noise(x * 31 + y * 17, salt) * 0.8F + (1.0F - y / (float) h) * 0.2F; // the top a little first
                    if (n < gone) {
                        image.setPixelRGBA(x, y, 0);
                    } else if (n < gone + 0.08F && image.getPixelRGBA(x, y) >>> 24 != 0) {
                        image.setPixelRGBA(x, y, 0xFF000000 | swap(0x585C64));
                    }
                }
            }
            id = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "fracture_marks/" + Integer.toHexString(original.hashCode()) + "_"
                    + style.name().toLowerCase(java.util.Locale.ROOT) + "_" + step + "_" + version + (taken ? "_taken" : "") + "_" + Integer.toHexString(salt) + "_" + decay);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        }
        LOOKS.put(key, id);
        return id;
    }

    private static NativeImage copy(NativeImage source) {
        final NativeImage image = new NativeImage(source.getWidth(), source.getHeight(), false);
        image.copyFrom(source);
        return image;
    }

    /** RGB to the image's ABGR order. */
    private static int swap(int rgb) {
        return (rgb & 255) << 16 | rgb & 0xFF00 | rgb >> 16 & 255;
    }

    private static int mix(int a, int b) {
        return ((a >> 16 & 255) + (b >> 16 & 255)) / 2 << 16 | ((a >> 8 & 255) + (b >> 8 & 255)) / 2 << 8 | ((a & 255) + (b & 255)) / 2;
    }

    private static int invert(int abgr) {
        return abgr & 0xFF000000 | ~abgr & 0xFFFFFF;
    }

    /** How long the corruption takes to recede from a player who passed their trial. */
    private static final int RECEDE_TICKS = 50;
    /** Players the corruption recedes from (they passed): from which tick. */
    private static final Int2LongOpenHashMap RECEDE = new Int2LongOpenHashMap();
    /** Entities drawn with corruption this frame, and how far it has come in them (for the aura drawn after). */
    private static final Int2FloatOpenHashMap AURA = new Int2FloatOpenHashMap();
    /** Where entities with corruption were, a little while back (their echoes). */
    private static final Map<Integer, java.util.ArrayDeque<Vec3>> TRAILS = new HashMap<>();

    /** A player passed their trial: the corruption recedes from them (told before they are back, so they arrive taken). */
    public static void recede(int entityId) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && !RECEDE.containsKey(entityId)) {
            RECEDE.put(entityId, mc.level.getGameTime());
        }
    }

    // ---- the living marks (animated styles) ----------------------------------------------------------

    /** The frames the living marks run through (one every other tick, a loop of 3.2 s). */
    private static final int FRAMES = 32;

    private static boolean animated(FractureMarks style) {
        return style.ordinal() >= FractureMarks.SERPENT.ordinal();
    }

    /** Where a texture's living marks are: closed loops (snakes, mites), eyes, columns of code, ink blots. */
    private record Anim(List<int[]> loops, int[][] eyes, int[][] columns, float[][] blots) {
    }

    private static final Map<String, Anim> ANIMS = new HashMap<>();

    private static Anim anim(ResourceLocation original, NativeImage source, int salt) {
        return ANIMS.computeIfAbsent(original + "#" + salt, k -> {
            final int w = source.getWidth(), h = source.getHeight();
            final Random random = new Random(original.hashCode() * 31L + salt * 104729L + 77);
            final int[][] starts = starts(source, random, Math.max(6, w * h / 300));
            final List<int[]> loops = new ArrayList<>();
            for (int[] s : starts) { // a wobbly closed loop round each start, pixel by pixel
                final float rx = 3.5F + random.nextFloat() * 4.0F, ry = 3.5F + random.nextFloat() * 4.0F, phase = random.nextFloat() * Mth.TWO_PI;
                final List<Integer> path = new ArrayList<>();
                for (int a = 0; a < 96; a++) {
                    final float t = a / 96.0F * Mth.TWO_PI, wobble = 1.0F + 0.25F * Mth.sin(3 * t + phase);
                    final int x = Mth.clamp(Math.round(s[0] + Mth.cos(t) * rx * wobble), 0, w - 1), y = Mth.clamp(Math.round(s[1] + Mth.sin(t) * ry * wobble), 0, h - 1);
                    final int i = y * w + x;
                    if (path.isEmpty() || path.get(path.size() - 1) != i && path.get(0) != i) {
                        path.add(i);
                    }
                }
                if (random.nextBoolean()) {
                    java.util.Collections.reverse(path); // some turn the other way
                }
                loops.add(path.stream().mapToInt(Integer::intValue).toArray());
            }
            final List<int[]> columns = new ArrayList<>();
            for (int x = 0; x < w; x++) {
                if (random.nextFloat() < 0.65F) {
                    columns.add(new int[] {x, random.nextInt(h), 1 + random.nextInt(2)}); // x, where its drop starts, how fast
                }
            }
            java.util.Collections.shuffle(columns, random);
            final float[][] blots = new float[starts.length][];
            for (int b = 0; b < starts.length; b++) {
                blots[b] = new float[] {starts[b][0], starts[b][1], 3.0F + random.nextFloat() * Math.max(3.0F, w / 10.0F), random.nextFloat() * Mth.TWO_PI};
            }
            return new Anim(loops, starts, columns.toArray(new int[0][]), blots);
        });
    }

    /** One frame of a living style over the texture (base: as it is, or black and white once taken), as far as reach. */
    private static void animate(NativeImage image, NativeImage base, FractureMarks style, Anim anim, Marks marks, int frame, float reach, int w, int h) {
        final int[] shade = new int[w * h];
        final float[] mix = new float[w * h];
        final int dark = 0x22252B, mid = 0x5A5E66, light = 0xE8E2D6;
        switch (style) {
            case SERPENT -> { // snakes coiling round, scaled, a pale eye on each head; more of them as it grows
                final int count = Math.max(1, Math.round(reach * anim.loops().size() * 1.5F));
                for (int k = 0; k < count; k++) {
                    final int[] loop = anim.loops().get(k % anim.loops().size());
                    final int length = loop.length, body = Math.max(4, (int) (length * 0.55F));
                    final int head = (int) ((frame / (float) FRAMES + k * 0.37F) * length) % length;
                    for (int j = body - 1; j >= 0; j--) {
                        final int i = loop[Math.floorMod(head - j, length)];
                        final float tail = j > body * 0.7F ? 1.0F - (j - body * 0.7F) / (body * 0.3F) : 1.0F; // the tail thinning out
                        put(shade, mix, i, j == 0 ? light : j % 3 == 1 ? mid : dark, (j == 0 ? 0.9F : 0.85F) * tail);
                        if (j > 0 && j < body * 0.75F) { // two pixels thick but for its tail
                            put(shade, mix, w, h, i % w, i / w + 1, j % 3 == 2 ? mid : dark, 0.8F * tail);
                        }
                    }
                    put(shade, mix, loop[Math.floorMod(head - 1, length)], dark, 0.95F); // the head behind its eye
                }
            }
            case FLOW -> { // the veins faint, dashes of light and dark streaming out along them
                for (int i = 0; i < w * h; i++) {
                    if (marks.depth[i] == NONE || marks.when[i] > reach) {
                        continue;
                    }
                    if (marks.depth[i] == CORE) {
                        final float dash = ((marks.along[i] * 5.0F - frame / (float) FRAMES) % 1.0F + 1.0F) % 1.0F;
                        put(shade, mix, i, dash < 0.12F ? light : dash < 0.35F ? dark : mid, dash < 0.35F ? 0.85F : 0.4F);
                    } else if (marks.depth[i] == MARGIN) {
                        put(shade, mix, i, dark, 0.15F);
                    }
                }
            }
            case SWARM -> { // mites scurrying round, each with paler pixels behind it; more and bigger the further it has come
                final int count = Math.max(1, Math.round(reach * reach * anim.loops().size() * 7));
                final boolean big = reach >= 0.45F;
                for (int k = 0; k < count; k++) {
                    final int[] loop = anim.loops().get(k % anim.loops().size());
                    final int length = loop.length;
                    final int at = (int) ((frame / (float) FRAMES * (2 + k % 2) + k * 0.29F) * length) % length;
                    final int i = loop[at];
                    put(shade, mix, i, dark, 0.97F);
                    if (big) { // a body of two by two pixels
                        put(shade, mix, w, h, i % w + 1, i / w, dark, 0.95F);
                        put(shade, mix, w, h, i % w, i / w + 1, dark, 0.95F);
                        put(shade, mix, w, h, i % w + 1, i / w + 1, k % 3 == 0 ? light : dark, 0.9F); // a glint on some
                    }
                    put(shade, mix, loop[Math.floorMod(at - 1, length)], mid, 0.65F);
                    put(shade, mix, loop[Math.floorMod(at - 2, length)], mid, 0.35F);
                    put(shade, mix, loop[Math.floorMod(at - 3, length)], mid, 0.15F);
                }
            }
            case EYES -> { // eyes opening on the skin, looking about, blinking, closing again
                final int count = Math.max(1, Math.round(reach * anim.eyes().length * 1.3F));
                for (int k = 0; k < count; k++) {
                    final int x = anim.eyes()[k % anim.eyes().length][0], y = anim.eyes()[k % anim.eyes().length][1] + (k / anim.eyes().length) * 4;
                    final int t = (frame + k * 11) % FRAMES;
                    final boolean open = t >= 4 && t < 26 && t != 15 && t != 16, opening = t == 3 || t == 26;
                    if (!open && !opening) {
                        for (int dx = -2; dx <= 2; dx++) {
                            put(shade, mix, w, h, x + dx, y, dark, Math.abs(dx) == 2 ? 0.3F : 0.55F); // shut: a dark line
                        }
                        continue;
                    }
                    final int look = t < 11 ? -1 : t < 20 ? 0 : 1;
                    for (int dx = -1; dx <= 1; dx++) {
                        put(shade, mix, w, h, x + dx, y - 1, dark, 0.85F);
                        put(shade, mix, w, h, x + dx, y + 1, dark, opening ? 0.4F : 0.85F);
                        put(shade, mix, w, h, x + dx, y, opening ? mid : dx == look ? 0x0E0F12 : light, 0.95F);
                    }
                    put(shade, mix, w, h, x - 2, y, dark, 0.7F);
                    put(shade, mix, w, h, x + 2, y, dark, 0.7F);
                }
            }
            case CODE -> { // pixel code raining down the body: a pale head, a dark trail fading behind
                final int count = Math.max(1, Math.round(reach * anim.columns().length));
                for (int k = 0; k < count; k++) {
                    final int[] c = anim.columns()[k % anim.columns().length];
                    final int head = (c[1] + frame * h * c[2] / FRAMES) % h;
                    for (int j = 0; j < 11; j++) {
                        final int y = Math.floorMod(head - j, h);
                        put(shade, mix, y * w + c[0], j == 0 ? light : (j + k) % 3 == 0 ? mid : dark, j == 0 ? 0.9F : 0.85F * (1.0F - j / 11.0F));
                    }
                }
            }
            case INK -> { // blots of ink breathing: swelling and drawing back, ragged at their rims
                final int count = Math.max(1, Math.round(reach * anim.blots().length * 1.3F));
                for (int k = 0; k < count; k++) {
                    final float[] b = anim.blots()[k % anim.blots().length];
                    final float r = b[2] * (0.7F + 0.3F * Mth.sin(frame / (float) FRAMES * Mth.TWO_PI + b[3]));
                    final int n = (int) Math.ceil(b[2]) + 1;
                    for (int y = (int) b[1] - n; y <= b[1] + n; y++) {
                        for (int x = (int) b[0] - n; x <= b[0] + n; x++) {
                            final float d = (float) Math.hypot(x - b[0], y - b[1]) / r + (UndertowRenderer.noise(x * 7 + y, k) - 0.5F) * 0.35F;
                            if (d < 1.0F) {
                                put(shade, mix, w, h, x, y, d < 0.55F ? dark : mid, d < 0.55F ? 0.75F : 0.75F * (1.0F - d) / 0.45F);
                            }
                        }
                    }
                }
            }
            default -> {
            }
        }
        // a soft rim round what is drawn (the marks easing into the skin), then all of it laid over
        final float[] rim = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            if (mix[i] < 0.5F) {
                continue;
            }
            final int x = i % w, y = i / w;
            for (int d = 0; d < 8; d += 2) {
                final int nx = x + DIRS[d][0], ny = y + DIRS[d][1];
                if (nx >= 0 && ny >= 0 && nx < w && ny < h && mix[ny * w + nx] < 0.12F) {
                    rim[ny * w + nx] = 0.14F;
                }
            }
        }
        for (int i = 0; i < w * h; i++) {
            final int x = i % w, y = i / w;
            final int abgr = base.getPixelRGBA(x, y);
            if (abgr >>> 24 == 0) {
                continue;
            }
            if (mix[i] > 0.0F) {
                image.setPixelRGBA(x, y, blend(abgr, shade[i], mix[i]));
            } else if (rim[i] > 0.0F) {
                image.setPixelRGBA(x, y, blend(abgr, dark, rim[i]));
            }
        }
    }

    private static void put(int[] shade, float[] mix, int i, int rgb, float k) {
        if (k > mix[i]) {
            shade[i] = rgb;
            mix[i] = k;
        }
    }

    private static void put(int[] shade, float[] mix, int w, int h, int x, int y, int rgb, float k) {
        if (x >= 0 && y >= 0 && x < w && y < h) {
            put(shade, mix, y * w + x, rgb, k);
        }
    }

    /** The marks a player's body and the player share (so the one looks just like the other). */
    private static int salt(Entity entity) {
        return entity instanceof redero.abyssaldepths.fracture.FracturedBody body ? body.owner().map(java.util.UUID::hashCode).orElse(entity.getId())
                : entity instanceof redero.abyssaldepths.fracture.Hollow hollow ? hollow.owner().map(java.util.UUID::hashCode).orElse(entity.getId())
                : entity instanceof net.minecraft.world.entity.player.Player ? entity.getUUID().hashCode() : entity.getId();
    }

    @SubscribeEvent
    public static void onRender(RenderLivingEvent.Pre<?, ?> event) {
        final LivingEntity entity = event.getEntity();
        if (FadeDraw.active) {
            return;
        }
        final long now = entity.level().getGameTime();
        if (!DYING.isEmpty() && DYING.containsKey(entity.getId())) {
            final long since = now - DYING.get(entity.getId());
            if (since > 400 || since > 20 && !entity.isDeadOrDying()) {
                DYING.remove(entity.getId()); // back (respawned)
            } else {
                event.setCanceled(true); // not seen dying: only the body goes
                return;
            }
        }
        // receding (a passed trial): taken a moment as the body was, then the marks drawing back to nothing
        float recede = -1.0F;
        if (!RECEDE.isEmpty() && RECEDE.containsKey(entity.getId())) {
            recede = (now - RECEDE.get(entity.getId()) + event.getPartialTick()) / RECEDE_TICKS;
            if (recede >= 1.0F) {
                RECEDE.remove(entity.getId());
                recede = -1.0F;
            }
        }
        final boolean taken = corrupted(entity) || entity instanceof redero.abyssaldepths.fracture.FracturedBody || entity instanceof redero.abyssaldepths.fracture.Hollow
                || recede >= 0.0F && recede < 0.25F;
        final float scars = SCARRED.isEmpty() ? 0.0F : scarMarks(SCARRED.get(entity.getId()));
        float value = taken ? 1.0F : Math.max(scars, CRACKS.isEmpty() ? 0.0F : CRACKS.get(entity.getId()));
        if (recede >= 0.25F) {
            value = Math.max(scars, 1.0F - (recede - 0.25F) / 0.75F);
        }
        if (value <= 0.0F) {
            return;
        }
        final float decayed = entity instanceof redero.abyssaldepths.fracture.FracturedBody body ? body.decayed(event.getPartialTick()) : -1.0F;
        final int decay = decayed <= 0.0F ? 0 : Mth.clamp((int) (decayed * 10) + 1, 1, 10);
        @SuppressWarnings("unchecked")
        final ResourceLocation original = ((net.minecraft.client.renderer.entity.EntityRenderer<LivingEntity>) (Object) event.getRenderer()).getTextureLocation(entity);
        final boolean living = animated(ClientConfig.fractureMarks());
        // the living styles run through many frames: their reach in four steps only (fewer textures)
        final int step = step(value, living);
        final int salt = salt(entity);
        final int version = living ? (int) Math.floorMod(now / 2 + salt, FRAMES) : (int) Math.floorMod(now / (taken ? 3 : 4) + salt, VERSIONS);
        if (step <= 0) {
            return; // too little yet to show
        }
        final ResourceLocation texture = look(original, step, version, taken, salt, decay);
        if (texture == null) {
            return; // the texture cannot be read (yet): drawn as it is
        }
        event.setCanceled(true);
        final PoseStack pose = event.getPoseStack();
        pose.pushPose();
        if (taken && UndertowRenderer.noise(salt, (int) (now / 2)) > 0.6F) { // shaken a pixel this way and that, now and then (a body as its player)
            pose.translate((UndertowRenderer.noise(salt, (int) now) - 0.5F) * PX * 2, 0, (UndertowRenderer.noise(salt + 1, (int) now) - 0.5F) * PX * 2);
        }
        FadeDraw.drawHere(entity, event.getPartialTick(), pose, event.getMultiBufferSource(), 1.0F, 0xFFFFFF, texture);
        pose.popPose();
        if (decay == 0) {
            // the aura: full for the taken and with corruption building up; from scars alone only faintly (now and then)
            final float aura = taken || recede >= 0.0F ? value : Math.max(CRACKS.isEmpty() ? 0.0F : CRACKS.get(entity.getId()), scars * 0.3F);
            if (aura > 0.0F) {
                copies(event, entity, original, aura, salt, now);
                AURA.put(entity.getId(), aura);
            }
        }
    }

    /**
     * How far the marks show (tenths) for a corruption: never ahead of it (rounded down), nothing below 10 %; the
     * living styles in fewer steps (each a run of frames).
     */
    private static int step(float value, boolean living) {
        final int tenths = Mth.clamp((int) (value * 10 + 1.0E-4F), 0, 10);
        if (!living) {
            return tenths;
        }
        int step = 0;
        for (int s : new int[] {1, 2, 4, 6, 8, 10}) {
            if (s <= tenths) {
                step = s;
            }
        }
        return step;
    }

    /** The marked texture of an entity as it is now (the same as its body shows), or null if unmarked or unreadable. */
    private static ResourceLocation marked(LivingEntity entity, ResourceLocation original) {
        final long now = entity.level().getGameTime();
        final boolean taken = corrupted(entity);
        final float scars = SCARRED.isEmpty() ? 0.0F : scarMarks(SCARRED.get(entity.getId()));
        final float value = taken ? 1.0F : Math.max(scars, CRACKS.isEmpty() ? 0.0F : CRACKS.get(entity.getId()));
        if (value <= 0.0F) {
            return null;
        }
        final boolean living = animated(ClientConfig.fractureMarks());
        final int step = step(value, living);
        final int salt = salt(entity);
        final int version = living ? (int) Math.floorMod(now / 2 + salt, FRAMES) : (int) Math.floorMod(now / (taken ? 3 : 4) + salt, VERSIONS);
        return step <= 0 ? null : look(original, step, version, taken, salt, 0);
    }

    /** One's own hand in the first person: marked as one's body is (scars, corruption building up). */
    @SubscribeEvent
    public static void onRenderArm(net.neoforged.neoforge.client.event.RenderArmEvent event) {
        final net.minecraft.client.player.AbstractClientPlayer player = event.getPlayer();
        final ResourceLocation texture = marked(player, player.getSkin().texture());
        if (texture == null || !(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player)
                instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer renderer)) {
            return;
        }
        event.setCanceled(true);
        final net.minecraft.client.model.PlayerModel<net.minecraft.client.player.AbstractClientPlayer> model = renderer.getModel();
        final boolean right = event.getArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
        model.attackTime = 0.0F;
        model.crouching = false;
        model.swimAmount = 0.0F;
        model.setupAnim(player, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        final net.minecraft.client.model.geom.ModelPart arm = right ? model.rightArm : model.leftArm, sleeve = right ? model.rightSleeve : model.leftSleeve;
        arm.xRot = 0.0F;
        sleeve.xRot = 0.0F;
        final VertexConsumer buffer = event.getMultiBufferSource().getBuffer(RenderType.entityTranslucent(texture));
        arm.render(event.getPoseStack(), buffer, event.getPackedLight(), net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        sleeve.render(event.getPoseStack(), buffer, event.getPackedLight(), net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
    }

    /** The aura's copies of the entity (glitch doubles, a split, echoes behind it), drawn in black and white. */
    private static void copies(RenderLivingEvent.Pre<?, ?> event, LivingEntity entity, ResourceLocation original, float value, int salt, long now) {
        final ClientConfig.FractureAura aura = ClientConfig.fractureAura();
        if (aura != ClientConfig.FractureAura.DOUBLES && aura != ClientConfig.FractureAura.SPLIT && aura != ClientConfig.FractureAura.ECHOES) {
            return;
        }
        final ResourceLocation ghost = look(original, 10, Math.floorMod(salt + (int) (now / 2), VERSIONS), true, salt, 0);
        if (ghost == null) {
            return;
        }
        final PoseStack pose = event.getPoseStack();
        final Vector3f left = Minecraft.getInstance().gameRenderer.getMainCamera().getLeftVector();
        FadeDraw.translucent = true;
        try {
            copiesOf(event, entity, aura, ghost, pose, left, value, salt, now);
        } finally {
            FadeDraw.translucent = false;
        }
    }

    private static void copiesOf(RenderLivingEvent.Pre<?, ?> event, LivingEntity entity, ClientConfig.FractureAura aura, ResourceLocation ghost, PoseStack pose,
            Vector3f left, float value, int salt, long now) {
        switch (aura) {
            case DOUBLES -> { // now and then a copy or two flickers beside it, for a few frames
                final int frame = (int) (now / 3);
                for (int k = 0; k < 2; k++) {
                    if (UndertowRenderer.noise(salt + k * 31, frame) > 0.6F * value) {
                        continue;
                    }
                    final float side = (UndertowRenderer.noise(salt + k, frame + 7) - 0.5F) * 2.0F * (0.2F + 0.4F * value);
                    final float lift = (UndertowRenderer.noise(salt + k, frame + 9) - 0.5F) * 0.1F;
                    pose.pushPose();
                    pose.translate(left.x() * side, lift, left.z() * side);
                    FadeDraw.drawHere(entity, event.getPartialTick(), pose, event.getMultiBufferSource(), 0.55F, k == 0 ? 0xF4EEE2 : 0x2A2E38, ghost);
                    pose.popPose();
                }
            }
            case SPLIT -> { // always two copies just off it, one warm and light, one cold and dark, jittering
                for (int k = 0; k < 2; k++) {
                    final float side = (k == 0 ? 1 : -1) * 0.13F * value * (0.8F + 0.4F * UndertowRenderer.noise(salt + k, (int) now));
                    pose.pushPose();
                    pose.translate(left.x() * side, 0.0F, left.z() * side);
                    FadeDraw.drawHere(entity, event.getPartialTick(), pose, event.getMultiBufferSource(), k == 0 ? 0.32F : 0.42F, k == 0 ? 0xFFF0DC : 0x1C2028, ghost);
                    pose.popPose();
                }
            }
            default -> { // echoes: where it was a moment ago, fading
                final java.util.ArrayDeque<Vec3> trail = TRAILS.get(entity.getId());
                if (trail == null) {
                    return;
                }
                final Vec3 at = entity.getPosition(event.getPartialTick());
                int k = 0;
                for (Vec3 was : trail) {
                    k++;
                    final Vec3 off = was.subtract(at);
                    if (off.lengthSqr() < 0.01) {
                        continue;
                    }
                    pose.pushPose();
                    pose.translate(off.x, off.y, off.z);
                    FadeDraw.drawHere(entity, event.getPartialTick(), pose, event.getMultiBufferSource(), 0.5F * value * (1.0F - k / 7.0F), 0xE8E2D6, ghost);
                    pose.popPose();
                }
            }
        }
    }

    /** Where the corrupted were (for their echoes), every other tick. */
    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getGameTime() % 2 != 0) {
            return;
        }
        TRAILS.keySet().removeIf(id -> !AURA_SEEN.contains(id));
        for (int id : AURA_SEEN) {
            if (mc.level.getEntity(id) instanceof LivingEntity entity) {
                final java.util.ArrayDeque<Vec3> trail = TRAILS.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
                trail.addFirst(entity.position());
                while (trail.size() > 6) {
                    trail.removeLast();
                }
            }
        }
        AURA_SEEN.clear();
    }

    /** Entities drawn with corruption since the last tick. */
    private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet AURA_SEEN = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    // ---- fragments and strikes in the world ------------------------------------------------------

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.level == null
                || CORRUPTED.isEmpty() && STRIKES.isEmpty() && AURA.isEmpty() && RECEDE.isEmpty()) {
            return;
        }
        final long time = mc.level.getGameTime();
        final float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        final float now = time + partial;
        final Vec3 cam = event.getCamera().getPosition();
        final PoseStack pose = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final VertexConsumer q = buffers.getBuffer(RenderType.debugQuads());
        final Vector3f left = event.getCamera().getLeftVector(), up = event.getCamera().getUpVector();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        final Matrix4f m = pose.last().pose();
        CORRUPTED.int2LongEntrySet().removeIf(e -> e.getLongValue() < time);
        for (int id : CORRUPTED.keySet()) {
            if (mc.level.getEntity(id) instanceof LivingEntity entity && entity.distanceToSqr(cam) < 64 * 64) {
                fragments(q, m, entity, now, left, up, partial);
                if (entity instanceof redero.abyssaldepths.fracture.FracturedBody body && body.decayed(0.0F) > 0.0F) {
                    crumbs(q, m, entity, now, left, up, partial);
                }
            }
        }
        for (Int2LongOpenHashMap.Entry e : RECEDE.int2LongEntrySet()) { // receding: the corruption bursting off them
            final float p = (now - e.getLongValue()) / RECEDE_TICKS;
            if (p > 0.2F && p < 0.75F && mc.level.getEntity(e.getIntKey()) instanceof LivingEntity entity) {
                burstOff(q, m, entity, (p - 0.2F) / 0.55F, left, up, partial);
            }
        }
        final ClientConfig.FractureAura aura = ClientConfig.fractureAura();
        for (it.unimi.dsi.fastutil.ints.Int2FloatMap.Entry e : AURA.int2FloatEntrySet()) {
            AURA_SEEN.add(e.getIntKey());
            if (mc.level.getEntity(e.getIntKey()) instanceof LivingEntity entity) {
                switch (aura) {
                    case STAIN -> stain(q, m, entity, e.getFloatValue(), now, left, up, partial);
                    case GROUND_CRACKS -> groundCracks(q, m, entity, e.getFloatValue(), now, partial);
                    case SMOKE -> smoke(q, m, entity, e.getFloatValue(), now, left, up, partial);
                    default -> {
                    }
                }
            }
        }
        AURA.clear();
        STRIKES.removeIf(s -> now - s.marked() > s.warn() + 10 || now < s.marked() - 1);
        for (Strike strike : STRIKES) {
            strike(q, m, strike, now - strike.marked());
        }
        pose.popPose();
        buffers.endBatch(RenderType.debugQuads());
    }

    /** The corruption bursting off a player who resisted it: shards flying out and fading (p 0 .. 1). */
    private static void burstOff(VertexConsumer q, Matrix4f m, LivingEntity entity, float p, Vector3f left, Vector3f up, float partial) {
        final Vec3 at = entity.getPosition(partial);
        for (int k = 0; k < 28; k++) {
            final float a = UndertowRenderer.noise(entity.getId() + k, 21) * Mth.TWO_PI, y = UndertowRenderer.noise(k, entity.getId() + 22) * entity.getBbHeight();
            final float out = entity.getBbWidth() * 0.4F + p * (1.0F + UndertowRenderer.noise(k, 23) * 1.5F);
            final int shade = k % 3 == 0 ? 0xE8 : 0x18;
            mote(q, m, (float) at.x + Mth.cos(a) * out, (float) at.y + y + p * 0.4F, (float) at.z + Mth.sin(a) * out, PX * (3.0F - 2.0F * p),
                    (int) ((1.0F - p) * 230) << 24 | Mth.clamp(shade + 4, 0, 255) << 16 | shade << 8 | Mth.clamp(shade + (shade < 0x80 ? 6 : -4), 0, 255), left, up);
        }
    }

    /** The ground under them stained black, ragged and greying at its rim, pixels rising out of it. */
    private static void stain(VertexConsumer q, Matrix4f m, LivingEntity entity, float value, float now, Vector3f left, Vector3f up, float partial) {
        final Vec3 at = entity.getPosition(partial);
        final float radius = 1.6F * value, cell = 0.125F;
        final int n = (int) Math.ceil(radius / cell);
        final int seed = entity.getId();
        for (int j = -n; j <= n; j++) {
            for (int i = -n; i <= n; i++) {
                final float x = (i + 0.5F) * cell, z = (j + 0.5F) * cell;
                final float d = (float) Math.sqrt(x * x + z * z) / radius
                        + (UndertowRenderer.noise(i * 7 + j * 3, seed) - 0.5F) * 0.35F + 0.04F * Mth.sin(now * 0.08F + i * 0.4F);
                if (d > 1.0F) {
                    continue;
                }
                final int argb = d > 0.85F ? 0x70585C64 : d > 0.7F ? 0xB02E323A : 0xE00E1016; // greys at the rim, black within
                flat(q, m, (float) at.x + x - cell / 2, (float) at.y + 0.02F, (float) at.z + z - cell / 2, cell, argb);
            }
        }
        for (int k = 0; k < 8; k++) { // pixels rising out of it
            final float life = (now * 0.02F + UndertowRenderer.noise(seed * 3 + k, 31)) % 1.0F;
            final float a = UndertowRenderer.noise(seed + k, 32) * Mth.TWO_PI, r = UndertowRenderer.noise(k, seed + 33) * radius * 0.8F;
            mote(q, m, (float) at.x + Mth.cos(a) * r, (float) at.y + 0.05F + life * 1.4F, (float) at.z + Mth.sin(a) * r, PX * 2,
                    (int) ((1.0F - life) * 200) << 24 | 0x15181F, left, up);
        }
    }

    /** Cracks running out over the ground from under them, a light running out along them now and then. */
    private static void groundCracks(VertexConsumer q, Matrix4f m, LivingEntity entity, float value, float now, float partial) {
        final Vec3 at = entity.getPosition(partial);
        final float cell = 0.125F, length = 2.8F * value;
        final int seed = entity.getId();
        for (int c = 0; c < 6; c++) {
            float a = (c + UndertowRenderer.noise(seed + c, 41) * 0.6F) * Mth.TWO_PI / 6;
            float x = 0, z = 0;
            final int steps = (int) (length / cell);
            for (int s = 0; s < steps; s++) {
                a += (UndertowRenderer.noise(seed * 5 + c, s) - 0.5F) * 0.7F;
                x += Mth.cos(a) * cell;
                z += Mth.sin(a) * cell;
                final float run = ((now * 0.08F - s * 0.05F + c * 0.3F) % 1.0F + 1.0F) % 1.0F; // the light running out
                final int argb = run < 0.06F ? 0xE0E8E2D6 : s > steps * 0.75F ? 0x9034383F : 0xE00E1016;
                final float px = Math.round(x / cell) * cell, pz = Math.round(z / cell) * cell;
                flat(q, m, (float) at.x + px - cell / 2, (float) at.y + 0.02F, (float) at.z + pz - cell / 2, cell, argb);
            }
        }
    }

    /** Dark pixels rising off the body like smoke, greying as they go. */
    private static void smoke(VertexConsumer q, Matrix4f m, LivingEntity entity, float value, float now, Vector3f left, Vector3f up, float partial) {
        final Vec3 at = entity.getPosition(partial);
        final int count = (int) (20 * value), seed = entity.getId();
        for (int k = 0; k < count; k++) {
            final float life = (now * 0.025F + UndertowRenderer.noise(seed * 9 + k, 51)) % 1.0F;
            final float a = UndertowRenderer.noise(seed + k, 52) * Mth.TWO_PI, r = entity.getBbWidth() * 0.45F;
            final float y = UndertowRenderer.noise(k, seed + 53) * entity.getBbHeight() + life * 1.2F;
            final float drift = Mth.sin(life * 4.0F + k) * 0.15F;
            final int shade = (int) Mth.lerp(life, 0x12, 0x6A);
            mote(q, m, (float) at.x + Mth.cos(a) * r + drift, (float) at.y + y, (float) at.z + Mth.sin(a) * r, PX * (2.5F - life),
                    (int) ((1.0F - life) * 210) << 24 | shade << 16 | shade << 8 | Mth.clamp(shade + 6, 0, 255), left, up);
        }
    }

    /** Fragments in greys coming away from the body, drifting off, fading through the greys as they go. */
    private static void fragments(VertexConsumer q, Matrix4f m, LivingEntity entity, float now, Vector3f left, Vector3f up, float partial) {
        final Vec3 at = entity.getPosition(partial);
        for (int k = 0; k < 12; k++) {
            final float life = (now * 0.03F + UndertowRenderer.noise(entity.getId() * 7 + k, 1)) % 1.0F;
            final float a = UndertowRenderer.noise(entity.getId() + k, 2) * Mth.TWO_PI, out = entity.getBbWidth() * 0.5F + life * 0.8F;
            final float y = UndertowRenderer.noise(k, entity.getId()) * entity.getBbHeight() + life * 0.6F;
            final int shade = k % 2 == 0 ? (int) Mth.lerp(life, 0xF0, 0x70) : (int) Mth.lerp(life, 0x10, 0x60);
            final int warm = k % 2 == 0 ? 6 : -6; // the lights warm, the darks cold
            final float size = PX * (life < 0.5F ? 3 : 2);
            mote(q, m, (float) at.x + Mth.cos(a) * out, (float) at.y + y, (float) at.z + Mth.sin(a) * out, size,
                    (int) ((1.0F - life) * 230) << 24 | Mth.clamp(shade + warm, 0, 255) << 16 | shade << 8 | Mth.clamp(shade - warm, 0, 255), left, up);
        }
    }

    /** A decomposing body: pieces of it falling to the ground, greying as they fall, and lying there a moment. */
    private static void crumbs(VertexConsumer q, Matrix4f m, LivingEntity entity, float now, Vector3f left, Vector3f up, float partial) {
        final Vec3 at = entity.getPosition(partial);
        for (int k = 0; k < 16; k++) {
            final float life = (now * 0.025F + UndertowRenderer.noise(entity.getId() * 5 + k, 9)) % 1.0F;
            final float a = UndertowRenderer.noise(entity.getId() + k, 11) * Mth.TWO_PI, out = entity.getBbWidth() * 0.4F * UndertowRenderer.noise(k, 12);
            final float top = UndertowRenderer.noise(k, entity.getId() + 13) * entity.getBbHeight();
            final float y = Math.max(0.03F, top - life * life * 3.0F); // falling, then lying
            final int shade = (int) Mth.lerp(life, k % 3 == 0 ? 0xE0 : 0x20, 0x50);
            mote(q, m, (float) at.x + Mth.cos(a) * out, (float) at.y + y, (float) at.z + Mth.sin(a) * out, PX * 2,
                    (int) ((1.0F - life * life) * 240) << 24 | Mth.clamp(shade + 4, 0, 255) << 16 | shade << 8 | Mth.clamp(shade - 4, 0, 255), left, up);
        }
    }

    /** The warning on its cells: whole blocks flickering through the greys, brighter and quicker as it nears; then the strike. */
    private static void strike(VertexConsumer q, Matrix4f m, Strike s, float t) {
        final float half = s.size() / 2.0F;
        if (t < s.warn()) {
            final float p = t / s.warn();
            final int frame = (int) (t / (p > 0.7F ? 2 : 4));
            final int alpha = (int) ((0.4F + 0.5F * p) * 255) << 24;
            for (int[] cell : s.cells()) {
                final float x0 = cell[0] + 0.5F - half, z0 = cell[1] + 0.5F - half;
                for (int i = 0; i < s.size(); i++) {
                    for (int j = 0; j < s.size(); j++) {
                        final float n = UndertowRenderer.noise(cell[0] * 31 + cell[1] * 7 + i * 3 + j, frame);
                        final int edge = i == 0 || j == 0 || i == s.size() - 1 || j == s.size() - 1 ? 0 : 1; // the cell's edge a step darker
                        final int shade = (int) Mth.lerp(p, 0x30, 0xD0) - (edge == 0 ? 0x28 : 0) + (int) ((n - 0.5F) * 0x40);
                        final int v = Mth.clamp(shade, 0x10, 0xF0);
                        final int tint = (v - 0x80) / 16; // light ones warm, dark ones cold
                        flat(q, m, x0 + i, (float) s.y() + 0.02F, z0 + j, 1.0F, alpha | Mth.clamp(v + tint, 0, 255) << 16 | v << 8 | Mth.clamp(v - tint, 0, 255));
                    }
                }
            }
            return;
        }
        final float after = t - s.warn(), fade = 1.0F - after / 10.0F;
        for (int[] cell : s.cells()) { // the strike: a column of white in a wider one of grey
            final float cx = cell[0] + 0.5F, cz = cell[1] + 0.5F;
            box(q, m, cx, (float) s.y(), cz, half, 3.0F * fade, (int) (fade * 0.9F * 255) << 24 | 0xFFFFFF);
            box(q, m, cx, (float) s.y(), cz, half + 0.2F, 3.4F * fade, (int) (fade * 0.4F * 255) << 24 | 0xA0A0A0);
        }
    }

    private static void mote(VertexConsumer q, Matrix4f m, float x, float y, float z, float size, int argb, Vector3f l, Vector3f u) {
        final float h = size * 0.5F;
        final float lx = l.x() * h, ly = l.y() * h, lz = l.z() * h, ux = u.x() * h, uy = u.y() * h, uz = u.z() * h;
        q.addVertex(m, x - lx - ux, y - ly - uy, z - lz - uz).setColor(argb);
        q.addVertex(m, x + lx - ux, y + ly - uy, z + lz - uz).setColor(argb);
        q.addVertex(m, x + lx + ux, y + ly + uy, z + lz + uz).setColor(argb);
        q.addVertex(m, x - lx + ux, y - ly + uy, z - lz + uz).setColor(argb);
    }

    private static void flat(VertexConsumer q, Matrix4f m, float x, float y, float z, float size, int argb) {
        q.addVertex(m, x, y, z).setColor(argb);
        q.addVertex(m, x, y, z + size).setColor(argb);
        q.addVertex(m, x + size, y, z + size).setColor(argb);
        q.addVertex(m, x + size, y, z).setColor(argb);
    }

    /** The four sides of a box standing on (x, y, z), half wide, high. */
    private static void box(VertexConsumer q, Matrix4f m, float x, float y, float z, float half, float height, int argb) {
        final float x0 = x - half, x1 = x + half, z0 = z - half, z1 = z + half, y1 = y + height;
        q.addVertex(m, x0, y, z0).setColor(argb);
        q.addVertex(m, x1, y, z0).setColor(argb);
        q.addVertex(m, x1, y1, z0).setColor(argb);
        q.addVertex(m, x0, y1, z0).setColor(argb);
        q.addVertex(m, x0, y, z1).setColor(argb);
        q.addVertex(m, x1, y, z1).setColor(argb);
        q.addVertex(m, x1, y1, z1).setColor(argb);
        q.addVertex(m, x0, y1, z1).setColor(argb);
        q.addVertex(m, x0, y, z0).setColor(argb);
        q.addVertex(m, x0, y, z1).setColor(argb);
        q.addVertex(m, x0, y1, z1).setColor(argb);
        q.addVertex(m, x0, y1, z0).setColor(argb);
        q.addVertex(m, x1, y, z0).setColor(argb);
        q.addVertex(m, x1, y, z1).setColor(argb);
        q.addVertex(m, x1, y1, z1).setColor(argb);
        q.addVertex(m, x1, y1, z0).setColor(argb);
    }
}
