package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.network.TrialReadyPayload;

/**
 * The screen under Fracturing, in blacks and whites (the darks cold, the lights warm) and the greys between:
 * <ol>
 * <li>while the player's corruption builds up, cracks of pixels spread over the whole screen from where it began,
 * as far as it has come (and back as it fades); they are the edges of the pieces the screen will break into;</li>
 * <li>then the screen breaks along them: piece by piece the picture (cracks and all) drops away down the screen,
 * and behind lies black, pixels in it lighting up slowly here and there and going out again;</li>
 * <li>across the move (into a trial, out of it) the black holds until the new place is there, a second longer,
 * then it is washed away from the top down; going into a trial, the server is then told the player sees it.</li>
 * </ol>
 * Drawn over all.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class FractureScreen {
    private static final ResourceLocation FRAME = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "fracture_frame");
    /** One pixel of the cracks and of the black behind, in GUI pixels. */
    private static final int CELL = 3, LAYER_CELL = 8;
    /** The pieces' seeds across and down. */
    private static final int ACROSS = 5, DOWN = 3;
    /** The steps: the cracks complete, the pieces falling, after the move a second's hold, the wash. */
    private static final int GROW_TICKS = 10, FALL_TICKS = 40, HOLD_TICKS = 20, WASH_TICKS = 24;
    private static final int CORE = 0xFF0C0D12, EDGE = 0xFF2E313A, LIGHT = 0xFFE9E2D4, BLACK = 0xFF121419;

    private enum Phase { NONE, BREAKING, SINKING, OVERGROWING, MOVED, HOLDING, WASHING }

    private static Phase phase = Phase.NONE;
    /** The player's corruption as the cracks show it (eased), and as it is. */
    private static float level, previousLevel, target;
    private static int age, phaseTicks, seed;
    private static boolean intoTrial;
    /** Out of a trial: whether it was passed; failed, whether the death has come (the black stays behind its screen). */
    private static boolean passed, deathSeen;
    private static int growTicks = 20;
    private static DynamicTexture frame;
    /** The pieces: each an outline (x, y in 0 .. 1 of the screen), and when it starts to fall, which way it turns. */
    private static final List<float[][]> PIECES = new ArrayList<>();
    private static final List<float[]> FALLS = new ArrayList<>();
    /** The cracks: the pieces' inner edges (x0, y0, x1, y1, from the end nearer where it began) and when each appears (0 .. 1). */
    private static final List<float[]> CRACKS = new ArrayList<>();

    private FractureScreen() {
    }

    private static float noise(int a, int b) {
        return UndertowRenderer.noise(a, b);
    }

    /** The player's corruption (0 .. 100 %): the cracks follow. */
    public static void corruption(float value) {
        if (target <= 0.0F && value > 0.0F && phase == Phase.NONE) {
            newCracks();
        }
        target = Mth.clamp(value, 0.0F, 1.0F);
    }

    /**
     * New pieces: cells round seeds scattered over the screen (each the part of it nearer its seed than any other),
     * their shared edges the cracks; these appear outwards from a point where the corruption begins.
     */
    private static void newCracks() {
        seed++;
        PIECES.clear();
        FALLS.clear();
        CRACKS.clear();
        final Minecraft mc = Minecraft.getInstance();
        final float aspect = mc.getWindow() == null ? 16 / 9.0F : Math.max(0.5F, mc.getWindow().getGuiScaledWidth() / (float) Math.max(1, mc.getWindow().getGuiScaledHeight()));
        final List<float[]> seeds = new ArrayList<>(); // in screen space stretched by the aspect (so the cells are not squashed)
        for (int j = 0; j < DOWN; j++) {
            for (int i = 0; i < ACROSS; i++) {
                seeds.add(new float[] {(i + 0.15F + 0.7F * noise(seed * 11 + i, j)) / ACROSS * aspect, (j + 0.15F + 0.7F * noise(seed * 13 + j, i + 40)) / DOWN});
            }
        }
        // where it begins: a few places at once, scattered over the screen (one in each third, at random)
        final float[][] origins = new float[3][];
        for (int o = 0; o < origins.length; o++) {
            origins[o] = new float[] {(o + 0.1F + 0.8F * noise(seed * 3 + o, 90)) / origins.length * aspect, 0.1F + 0.8F * noise(seed * 3 + o, 91)};
        }
        final java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int k = 0; k < seeds.size(); k++) {
            List<float[]> cell = new ArrayList<>(List.of(new float[] {0, 0}, new float[] {aspect, 0}, new float[] {aspect, 1}, new float[] {0, 1}));
            final float[] a = seeds.get(k);
            for (int n = 0; n < seeds.size(); n++) {
                if (n != k) {
                    cell = clip(cell, a, seeds.get(n));
                }
            }
            final float[][] outline = new float[cell.size()][];
            float cy = 0;
            for (int v = 0; v < cell.size(); v++) {
                outline[v] = new float[] {cell.get(v)[0] / aspect, cell.get(v)[1]};
                cy += outline[v][1];
            }
            PIECES.add(outline);
            // lower pieces drop first, the rest after them, a little at random
            FALLS.add(new float[] {GROW_TICKS + 2 + (1.0F - cy / cell.size()) * 10.0F + noise(seed + k, 5) * 8.0F, (noise(seed + k, 6) - 0.5F) * 2.0F});
            for (int v = 0; v < cell.size(); v++) {
                final float[] p = cell.get(v), q = cell.get((v + 1) % cell.size());
                if (onBorder(p, q, aspect)) {
                    continue; // the screen's own edge: no crack
                }
                final long key = (long) Math.round((p[0] + q[0]) * 500) << 32 | Math.round((p[1] + q[1]) * 500) & 0xFFFFFFFFL;
                if (!seen.add(key)) {
                    continue; // shared with a piece done already
                }
                float dp = Float.MAX_VALUE, dq = Float.MAX_VALUE;
                for (float[] o : origins) { // from the nearest of the places it begins
                    dp = Math.min(dp, (float) Math.hypot(p[0] - o[0], p[1] - o[1]));
                    dq = Math.min(dq, (float) Math.hypot(q[0] - o[0], q[1] - o[1]));
                }
                final float[] near = dp < dq ? p : q, far = dp < dq ? q : p;
                final float when = Math.min(dp, dq) + noise(seed + v, k) * 0.25F; // not quite in order: now here, now there
                CRACKS.add(new float[] {near[0] / aspect, near[1], far[0] / aspect, far[1], when});
            }
        }
        // in order from where it began; each then takes its share of the level: as much shows as there is corruption
        CRACKS.sort((a, b) -> Float.compare(a[4], b[4]));
        for (int c = 0; c < CRACKS.size(); c++) {
            CRACKS.get(c)[4] = c;
        }
    }

    private static boolean onBorder(float[] p, float[] q, float aspect) {
        final float e = 1.0E-4F;
        return Math.abs(p[0]) < e && Math.abs(q[0]) < e || Math.abs(p[0] - aspect) < e && Math.abs(q[0] - aspect) < e
                || Math.abs(p[1]) < e && Math.abs(q[1]) < e || Math.abs(p[1] - 1) < e && Math.abs(q[1] - 1) < e;
    }

    /** Clips a polygon to the side of the line between seeds a and b that is nearer a. */
    private static List<float[]> clip(List<float[]> polygon, float[] a, float[] b) {
        final float mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2, nx = b[0] - a[0], ny = b[1] - a[1];
        final List<float[]> out = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            final float[] p = polygon.get(i), q = polygon.get((i + 1) % polygon.size());
            final float dp = (p[0] - mx) * nx + (p[1] - my) * ny, dq = (q[0] - mx) * nx + (q[1] - my) * ny;
            if (dp <= 0) {
                out.add(p);
            }
            if (dp < 0 != dq < 0 && dp != dq) {
                final float t = dp / (dp - dq);
                out.add(new float[] {p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t});
            }
        }
        return out;
    }

    /** Fractured: the cracks complete, then the screen breaks along them; the move follows after {@code ticks}. */
    public static void breaking(int ticks, boolean intoTrial) {
        if (PIECES.isEmpty() || phase != Phase.NONE) {
            newCracks();
        }
        FractureScreen.intoTrial = intoTrial;
        phase = Phase.BREAKING;
        age = 0;
    }

    /**
     * The trial is over: the black grows over the screen from a few places (no breaking this time); the move follows
     * after {@code ticks}. Passed, it is washed away once back; failed, it stays behind the death screen until the
     * player is back on their feet.
     */
    public static void overgrow(int ticks, boolean passed) {
        seed++;
        FractureScreen.passed = passed;
        intoTrial = false;
        deathSeen = false;
        respawnAsked = false;
        growTicks = Math.max(1, ticks);
        phase = Phase.OVERGROWING;
        age = 0;
        phaseTicks = 0;
    }

    /** Ticks the view drifts out of the body before the screen shatters. */
    private static final int SINK_OUT = 30;
    /** The camera as it was before the view sank (put back once in the trial). */
    private static net.minecraft.client.CameraType savedCamera;

    /**
     * Taken: the view leaves the body, drifting out behind it (the body freezing, turning black and white, the cracks
     * full over the screen), then the screen shatters along them; the move follows after {@code ticks}.
     */
    public static void sinking(int ticks) {
        final Minecraft mc = Minecraft.getInstance();
        seed++;
        intoTrial = true;
        passed = false;
        growTicks = Math.max(1, ticks);
        phase = Phase.SINKING;
        age = 0;
        phaseTicks = 0;
        if (savedCamera == null) {
            savedCamera = mc.options.getCameraType();
        }
        mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
    }

    /** While sinking: the camera out behind the body, then into it. */
    @SubscribeEvent
    public static void onCameraDistance(net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent event) {
        if (phase != Phase.SINKING) {
            return;
        }
        final float p = Math.min(1.0F, phaseTicks / (float) SINK_OUT);
        event.setDistance(Mth.lerp(p * p * (3 - 2 * p), 0.6F, 4.0F)); // out to where the game keeps it
    }

    /** The onset came to nothing (they died meanwhile): the screen and camera let go, the cracks gone. */
    public static void cancel() {
        final Minecraft mc = Minecraft.getInstance();
        if (phase == Phase.SINKING || phase == Phase.BREAKING || phase == Phase.MOVED && intoTrial) {
            phase = Phase.NONE;
            release(mc);
            if (savedCamera != null) {
                mc.options.setCameraType(savedCamera);
                savedCamera = null;
            }
            target = level = previousLevel = 0.0F;
        }
    }

    /** How far the player's corruption is (0 .. 1). */
    public static float corruptionLevel() {
        return target;
    }

    /** Whether nothing of the fracturing covers the screen now. */
    public static boolean clear() {
        return phase == Phase.NONE;
    }

    /** Whether the black is growing over the screen now (a trial ending). */
    public static boolean overgrowing() {
        return phase == Phase.OVERGROWING;
    }

    /** The move happened: the black holds until the new place is ready. */
    public static void moved() {
        if (savedCamera != null) { // in the trial: the camera as the player had it
            Minecraft.getInstance().options.setCameraType(savedCamera);
            savedCamera = null;
        }
        if (phase == Phase.NONE) { // nothing was breaking (a login in a trial): only the black and its wash
            newCracks();
        }
        phase = Phase.MOVED;
        phaseTicks = 0;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        previousLevel = level;
        level += Mth.clamp(target - level, -0.03F, 0.05F);
        if (phase == Phase.NONE) {
            return;
        }
        age++;
        phaseTicks++;
        switch (phase) {
            case SINKING -> {
                level = previousLevel = target = 1.0F;
                if (phaseTicks >= SINK_OUT) { // the body seen taken: now the screen shatters
                    if (PIECES.isEmpty()) {
                        newCracks();
                    }
                    phase = Phase.BREAKING;
                    age = 0;
                }
            }
            case BREAKING -> {
                level = previousLevel = target = 1.0F;
                if (age == GROW_TICKS + 1) {
                    capture(mc); // the picture, cracks and all, to break along them
                }
            }
            case MOVED -> {
                final boolean loading = mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
                        || mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen;
                if (mc.level != null && mc.player != null && !loading && mc.levelRenderer.hasRenderedAllSections()) {
                    phase = Phase.HOLDING;
                    phaseTicks = 0;
                }
            }
            case HOLDING -> {
                final boolean dead = mc.player == null || mc.player.isDeadOrDying() || mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen
                        || mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen;
                final boolean failing = !intoTrial && !passed;
                if (failing && dead) {
                    deathSeen = true;
                    phaseTicks = 0; // the black stays behind the death screen
                }
                final boolean waited = !failing || deathSeen || phaseTicks > 100; // (failed: the death comes a moment after the move)
                if (phaseTicks >= HOLD_TICKS && !dead && waited) {
                    phase = Phase.WASHING;
                    phaseTicks = 0;
                    target = level = previousLevel = 0.0F; // the cracks are spent
                    if (mc.player != null) {
                        FractureView.cracks(mc.player.getId(), 0.0F); // and gone from their body too
                    }
                    if (!intoTrial) {
                        TrialHud.out(passed); // how it went, as the world shows again
                        if (passed && mc.player != null) {
                            FractureView.recede(mc.player.getId()); // the corruption drawing back off oneself, seen as the black does
                        }
                    }
                }
            }
            case WASHING -> {
                if (phaseTicks >= WASH_TICKS) {
                    phase = Phase.NONE;
                    release(mc);
                    if (intoTrial) {
                        PacketDistributor.sendToServer(TrialReadyPayload.INSTANCE); // the player sees the arena now
                    }
                }
            }
            default -> {
            }
        }
    }

    private static void capture(Minecraft mc) {
        try {
            final NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            release(mc);
            frame = new DynamicTexture(image);
            mc.getTextureManager().register(FRAME, frame);
        } catch (RuntimeException e) {
            frame = null;
        }
    }

    private static void release(Minecraft mc) {
        if (frame != null) {
            mc.getTextureManager().release(FRAME);
            frame = null;
        }
    }

    private static void draw(GuiGraphics g, float partial) {
        final int w = g.guiWidth(), h = g.guiHeight();
        final float t = age + partial;
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 2900.0F);
        switch (phase) {
            case NONE -> cracks(g, w, h, Mth.lerp(partial, previousLevel, level));
            case BREAKING -> {
                if (frame == null || t < GROW_TICKS + 1) {
                    cracks(g, w, h, 1.0F);
                } else {
                    layer(g, w, h, t, -1.0F);
                    g.flush();
                    pieces(g, w, h, t);
                }
            }
            case OVERGROWING -> overgrown(g, w, h, t, Math.min(1.0F, (phaseTicks + partial) / growTicks));
            case SINKING -> cracks(g, w, h, 1.0F);
            case MOVED, HOLDING -> layer(g, w, h, t, -1.0F);
            case WASHING -> {
                if (!intoTrial && passed) {
                    receding(g, w, h, t, (phaseTicks + partial) / WASH_TICKS);
                } else {
                    layer(g, w, h, t, (phaseTicks + partial) / WASH_TICKS);
                }
            }
        }
        g.pose().popPose();
    }

    /** The cracks, as far as the corruption has come: each grows from its nearer end once its time is reached. */
    private static void cracks(GuiGraphics g, int w, int h, float p) {
        if (p <= 0.003F) {
            return;
        }
        for (int c = 0; c < CRACKS.size(); c++) {
            final float[] k = CRACKS.get(c);
            final float shown = Mth.clamp(p * CRACKS.size() - k[4], 0.0F, 1.0F);
            if (shown <= 0) {
                continue;
            }
            final float x0 = k[0] * w, y0 = k[1] * h, x1 = x0 + (k[2] * w - x0) * shown, y1 = y0 + (k[3] * h - y0) * shown;
            final int steps = Math.max(1, (int) (Math.hypot(x1 - x0, y1 - y0) / (CELL * 0.5F)));
            int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
            for (int s = 0; s <= steps; s++) {
                final int cx = Math.floorDiv((int) (x0 + (x1 - x0) * s / steps), CELL) * CELL, cy = Math.floorDiv((int) (y0 + (y1 - y0) * s / steps), CELL) * CELL;
                if (cx == lastX && cy == lastY) {
                    continue;
                }
                lastX = cx;
                lastY = cy;
                // a pixel of the crack, a softer one beside it now and then (the black easing into the picture)
                final float n = noise(cx * 7 + cy, seed + c);
                if (n > 0.55F) {
                    final int side = n > 0.78F ? CELL : -CELL;
                    g.fill(cx + (Math.abs(x1 - x0) < Math.abs(y1 - y0) ? side : 0), cy + (Math.abs(x1 - x0) < Math.abs(y1 - y0) ? 0 : side),
                            cx + (Math.abs(x1 - x0) < Math.abs(y1 - y0) ? side : 0) + CELL, cy + (Math.abs(x1 - x0) < Math.abs(y1 - y0) ? 0 : side) + CELL, EDGE & 0xA0FFFFFF);
                }
                g.fill(cx, cy, cx + CELL, cy + CELL, CORE);
                if (noise(cx * 3 + cy * 5, seed) > 0.93F) {
                    g.fill(cx, cy, cx + CELL, cy + CELL, LIGHT & 0xC0FFFFFF); // a glint
                }
            }
        }
    }

    /**
     * The black behind: here and there a pixel lights up slowly to its own brightness (warm when bright, cold when
     * dim) and goes out again; washed away from the top down.
     */
    private static void layer(GuiGraphics g, int w, int h, float t, float wash) {
        final int cols = (w + LAYER_CELL - 1) / LAYER_CELL, rows = (h + LAYER_CELL - 1) / LAYER_CELL;
        final float front = wash < 0 ? -10.0F : wash * (rows + 6) - 3; // the wash's front, in rows
        if (wash < 0) {
            g.fill(0, 0, w, h, BLACK);
        }
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                final float edge = front - noise(col, 77) * 3; // ragged
                if (row < edge - 1) {
                    continue; // washed away: the world shows
                }
                final int x = col * LAYER_CELL, y = row * LAYER_CELL;
                if (row < edge + 1) { // the wash's front: a band of greys running down
                    final float k = Mth.clamp(edge + 1 - row, 0.0F, 2.0F) / 2.0F;
                    g.fill(x, y, x + LAYER_CELL, y + LAYER_CELL, tone(0.1F + 0.28F * k * noise(col + 31, row), 0xFF));
                    continue;
                }
                if (wash >= 0) {
                    g.fill(x, y, x + LAYER_CELL, y + LAYER_CELL, BLACK);
                }
                twinkle(g, x, y, col, row, t);
            }
        }
    }

    /**
     * The black growing over the screen (p from 0 to 1): out from a few places, ragged, a band of greys at its
     * edge; the pixels lighting up in it as in the black behind.
     */
    private static void overgrown(GuiGraphics g, int w, int h, float t, float p) {
        final int cols = (w + LAYER_CELL - 1) / LAYER_CELL, rows = (h + LAYER_CELL - 1) / LAYER_CELL;
        final float reach = p * 1.25F;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                float near = 2.0F; // to the nearest place it grows from (0 .. 1 of the screen's diagonal)
                for (int k = 0; k < 5; k++) {
                    final float sx = noise(seed * 7 + k, 51) * cols, sy = noise(seed * 7 + k, 52) * rows;
                    near = Math.min(near, (float) Math.hypot(col - sx, row - sy) / (float) Math.hypot(cols, rows));
                }
                final float at = near * 0.9F + noise(col * 13 + row, seed) * 0.2F;
                if (at > reach) {
                    continue;
                }
                final int x = col * LAYER_CELL, y = row * LAYER_CELL;
                if (at > reach - 0.08F) { // its edge: greys easing into the picture
                    g.fill(x, y, x + LAYER_CELL, y + LAYER_CELL, tone(0.18F + 0.25F * noise(col + 5, row + seed), 0xE0));
                    continue;
                }
                g.fill(x, y, x + LAYER_CELL, y + LAYER_CELL, BLACK);
                twinkle(g, x, y, col, row, t);
            }
        }
    }

    /** A passed trial: the black draws back from the screen's edges to its middle and is gone (p 0 .. 1). */
    private static void receding(GuiGraphics g, int w, int h, float t, float p) {
        final int cols = (w + LAYER_CELL - 1) / LAYER_CELL, rows = (h + LAYER_CELL - 1) / LAYER_CELL;
        final float edge = 1.0F - p * 1.25F; // what is still covered: nearer the middle than this
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                final float dx = (col + 0.5F) / cols - 0.5F, dy = (row + 0.5F) / rows - 0.5F;
                final float d = (float) Math.sqrt(dx * dx + dy * dy) / 0.7071F + (noise(col * 5 + row, seed + 3) - 0.5F) * 0.15F;
                if (d > edge) {
                    continue; // drawn back: the world shows
                }
                final int x = col * LAYER_CELL, y = row * LAYER_CELL;
                if (d > edge - 0.06F) { // its rim: greys easing into the picture
                    g.fill(x, y, x + LAYER_CELL, y + LAYER_CELL, tone(0.15F + 0.25F * noise(col + 9, row + seed), 0xD0));
                    continue;
                }
                g.fill(x, y, x + LAYER_CELL, y + LAYER_CELL, BLACK);
                twinkle(g, x, y, col, row, t);
            }
        }
    }

    /** Failed: the death screen is not shown; the player is brought back at once (the black stays until they are). */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        final Minecraft mc = Minecraft.getInstance();
        final boolean failing = !intoTrial && !passed && (phase == Phase.OVERGROWING || phase == Phase.MOVED || phase == Phase.HOLDING);
        if (failing && event.getNewScreen() instanceof net.minecraft.client.gui.screens.DeathScreen && mc.player != null) {
            event.setCanceled(true);
            deathSeen = true;
            phaseTicks = 0;
            if (!respawnAsked) {
                respawnAsked = true;
                mc.player.respawn();
            }
        }
    }

    private static boolean respawnAsked;

    /** Here and there a pixel of the black lights up slowly (not far) and goes out again. */
    private static void twinkle(GuiGraphics g, int x, int y, int col, int row, float t) {
        if (noise(col * 17 + 3, row * 29 + 5) > 0.07F) {
            return; // most of it stays black
        }
        final float period = 40.0F + noise(col, row + 300) * 60.0F, phase = noise(col + 300, row) * period;
        final float life = ((t + phase) % period) / period; // up, then down again
        final float bright = (life < 0.5F ? life * 2 : (1 - life) * 2) * (0.12F + 0.26F * noise(col + 7, row + 9)) * Mth.clamp(t / 20.0F, 0.0F, 1.0F);
        if (bright > 0.03F) {
            g.fill(x, y, x + LAYER_CELL, y + LAYER_CELL, tone(0.05F + Math.round(bright * 8) / 8.0F, 0xFF));
        }
    }

    /** A grey of brightness v (0 .. 1): the darks a little cold (blue), the lights a little warm. */
    private static int tone(float v, int alpha) {
        final int base = (int) (v * 235) + 8, tint = (int) ((v - 0.45F) * 14);
        return alpha << 24 | Mth.clamp(base + tint, 0, 255) << 16 | Mth.clamp(base, 0, 255) << 8 | Mth.clamp(base - tint, 0, 255);
    }

    /** The pieces (the picture as it was, cracks and all), dropping one by one down off the screen, turning a little. */
    private static void pieces(GuiGraphics g, int w, int h, float t) {
        if (frame == null) {
            return;
        }
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, FRAME);
        RenderSystem.disableCull();
        for (int n = 0; n < PIECES.size(); n++) {
            final float[][] outline = PIECES.get(n);
            final float[] fall = FALLS.get(n);
            final float since = t - fall[0];
            float cx = 0, cy = 0;
            for (float[] p : outline) {
                cx += p[0];
                cy += p[1];
            }
            cx = cx / outline.length * w;
            cy = cy / outline.length * h;
            final float drop = since > 0 ? 0.5F * 1.6F * since * since : 0.0F;
            if (drop > h * 1.3F || since > FALL_TICKS) {
                continue; // gone below
            }
            final Matrix4f m = new Matrix4f(g.pose().last().pose());
            if (since > 0) {
                m.translate(cx + fall[1] * since * 0.6F, cy + drop, 0);
                m.rotateZ(fall[1] * since * 0.012F);
                m.translate(-cx, -cy, 0);
            }
            final BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX);
            for (int k = 0; k < outline.length; k++) { // a fan round its middle
                final float[] p = outline[k], q = outline[(k + 1) % outline.length];
                buffer.addVertex(m, cx, cy, 0).setUv(cx / w, cy / h);
                buffer.addVertex(m, p[0] * w, p[1] * h, 0).setUv(p[0], p[1]);
                buffer.addVertex(m, q[0] * w, q[1] * h, 0).setUv(q[0], q[1]);
            }
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        }
        RenderSystem.enableCull();
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof net.minecraft.client.gui.screens.DeathScreen || phase == Phase.NONE) {
            return; // drawn with the HUD, under the screen: the death screen over the black; the cracks alone under menus
        }
        draw(event.getGuiGraphics(), event.getPartialTick());
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layer {
        private Layer() {
        }

        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "fracture_screen"),
                    (graphics, delta) -> draw(graphics, delta.getGameTimeDeltaPartialTick(true)));
        }
    }
}
