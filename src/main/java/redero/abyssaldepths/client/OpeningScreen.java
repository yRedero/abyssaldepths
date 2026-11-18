package redero.abyssaldepths.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;

/**
 * The opening (DESIGN.md 10), played once when the game has finished loading, before the title screen: light and dark
 * meeting. Its motion is meant to be fluid and never still (like a studio's animated end card): the whole scene drifts
 * and slowly zooms; the motes of the two forces come in on three layers of depth, the near ones larger and faster; they
 * draw together, circle each other and touch; a ring and a burst of little squares break out, and the title's letters
 * fly in one after another, light ones from the light's side, dark ones from the darkness's, each landing with a small
 * overshoot and settling; a line grows under it, the subtitle rises in; it all eases out to black. Any key or click
 * skips it; the config ({@code opening}) turns it off. The name is the lang key's (a placeholder for now).
 */
public class OpeningScreen extends Screen {
    private static final float MEET = 4.4F, TITLE = 4.9F, OUT = 9.2F, END = 10.2F;
    private static final int MOTES = 150, SPARKS = 46;
    private static boolean shown;
    private final Screen next;
    private final long start = Util.getMillis();
    /** Motes: start height (share), delay, speed, swirl phase, depth (0 far .. 2 near). */
    private final float[][] light = new float[MOTES][5], dark = new float[MOTES][5];
    /** Sparks of the meeting: angle, speed, size, light or dark. */
    private final float[][] sparks = new float[SPARKS][4];
    private boolean met;
    /** Shards (real blocks, in 3D): side (-1 light, 1 dark), height, depth, delay, spin axis x/y/z, spin speed, size. */
    private static final int SHARDS = 28;
    private final float[][] shards = new float[SHARDS][9];

    public OpeningScreen(Screen next) {
        super(Component.empty());
        this.next = next;
        final RandomSource r = RandomSource.create(7);
        for (int i = 0; i < MOTES; i++) {
            light[i] = new float[] {r.nextFloat(), r.nextFloat() * 1.4F, 0.8F + r.nextFloat() * 0.4F, r.nextFloat() * 6.28F, r.nextInt(3)};
            dark[i] = new float[] {r.nextFloat(), r.nextFloat() * 1.4F, 0.8F + r.nextFloat() * 0.4F, r.nextFloat() * 6.28F, r.nextInt(3)};
        }
        for (int i = 0; i < SHARDS; i++) {
            final float[] s = shards[i];
            s[0] = i % 2 == 0 ? -1 : 1;
            s[1] = (r.nextFloat() - 0.5F) * 1.6F;
            s[2] = 1.2F + r.nextFloat() * 2.6F;
            s[3] = r.nextFloat() * 1.2F;
            s[4] = r.nextFloat() - 0.5F;
            s[5] = r.nextFloat() - 0.5F;
            s[6] = r.nextFloat() - 0.5F;
            s[7] = 30 + r.nextFloat() * 70;
            s[8] = 0.35F + r.nextFloat() * 0.5F;
        }
        for (float[] s : sparks) {
            s[0] = r.nextFloat() * Mth.TWO_PI;
            s[1] = 60 + r.nextFloat() * 140;
            s[2] = 1 + r.nextInt(3);
            s[3] = r.nextBoolean() ? 1 : 0;
        }
    }

    private float time() {
        return (Util.getMillis() - start) / 1000.0F;
    }

    /** Easing: out (fast, then settling), in-out, and out with a small overshoot (a landing). */
    static float out(float x) {
        final float c = Mth.clamp(x, 0, 1);
        return 1 - (1 - c) * (1 - c) * (1 - c);
    }

    static float inOut(float x) {
        final float c = Mth.clamp(x, 0, 1);
        return c < 0.5F ? 4 * c * c * c : 1 - (float) Math.pow(-2 * c + 2, 3) / 2;
    }

    static float back(float x) {
        final float c = Mth.clamp(x, 0, 1), s = 1.70158F * 1.2F;
        return 1 + (s + 1) * (float) Math.pow(c - 1, 3) + s * (float) Math.pow(c - 1, 2);
    }

    @Override
    protected void init() {
        if (minecraft != null && time() < 0.2F) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BEACON_AMBIENT, 0.5F, 0.6F));
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        final float t = time();
        if (t >= END) {
            onClose();
            return;
        }
        final int w = width, h = height;
        g.fill(0, 0, w, h, 0xFF050407);
        // the whole scene drifts and slowly zooms in (never still)
        final float zoom = 1.0F + 0.08F * inOut(t / END), drift = Mth.sin(t * 0.35F) * 6;
        g.pose().pushPose();
        g.pose().translate(w / 2.0F + drift, h / 2.0F + Mth.cos(t * 0.3F) * 3, 0);
        g.pose().scale(zoom, zoom, 1);
        g.pose().translate(-w / 2.0F, -h / 2.0F, 0);
        final float cx = w / 2.0F, cy = h / 2.0F;
        shards(g, t, cx, cy, Math.min(w, h));
        for (int depth = 0; depth < 3; depth++) { // far to near
            for (int i = 0; i < MOTES; i++) {
                if (light[i][4] == depth) {
                    mote(g, light[i], t, cx, cy, w, h, -1);
                }
                if (dark[i][4] == depth) {
                    mote(g, dark[i], t, cx, cy, w, h, 1);
                }
            }
        }
        if (t > MEET) {
            if (!met && minecraft != null) {
                met = true;
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.5F, 1.0F));
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BEACON_ACTIVATE, 0.4F, 1.0F));
            }
            final float u = (t - MEET) / 1.2F;
            if (u < 1) { // the ring, easing out
                final float r = out(u) * Math.max(w, h) * 0.55F;
                final int a = (int) ((1 - u) * (1 - u) * 220);
                ring(g, cx, cy, r, 2, a << 24 | 0xF2EDE6);
                ring(g, cx, cy, r * 0.86F, 2, a << 24 | 0x2A1838);
            }
            final float v = (t - MEET) / 2.2F;
            if (v < 1) { // little squares bursting out, slowing, fading
                for (float[] s : sparks) {
                    final float d = out(v) * s[1];
                    final int px = (int) (cx + Mth.cos(s[0]) * d), py = (int) (cy + Mth.sin(s[0]) * d * 0.62F);
                    final int sz = (int) s[2];
                    final int a = (int) ((1 - v) * 230);
                    g.fill(px, py, px + sz + 1, py + sz + 1, a << 24 | (s[3] > 0 ? 0xF7F2EA : 0x1A0E24));
                }
            }
        }
        if (t > TITLE) {
            title(g, t - TITLE, cx, cy, w);
        }
        g.pose().popPose();
        final float fade = t < 0.7F ? 1 - out(t / 0.7F) : t > OUT ? inOut((t - OUT) / (END - OUT)) : 0;
        if (fade > 0.002F) {
            g.fill(0, 0, w, h, (int) (Mth.clamp(fade, 0, 1) * 255) << 24);
        }
    }

    /**
     * The shards: real blocks of the layer in 3D, tumbling, light ones (gloam crystal: pure light settled into stone)
     * from the left and dark ones (thalan, storm crystal) from the right, far ones small and slow; they drift in towards
     * the middle, and when the forces meet they are flung outwards past the camera. Seen in perspective: farther, smaller
     * and nearer the middle; drawn far to near.
     */
    private void shards(GuiGraphics g, float t, float cx, float cy, int span) {
        if (minecraft == null) {
            return;
        }
        final java.util.List<float[]> drawn = new java.util.ArrayList<>(); // screen x, y, size, depth, index
        for (int i = 0; i < SHARDS; i++) {
            final float[] s = shards[i];
            final float local = Math.max(0, t - s[3]);
            float x, y = s[1], z = s[2];
            if (t < MEET) { // in from its side towards the middle, slowing
                final float e = out(local / (MEET - s[3]));
                x = s[0] * Mth.lerp(e, 3.2F, 0.35F + 0.25F * (i % 3));
                y = s[1] * Mth.lerp(e, 1.0F, 0.45F) + Mth.sin(local * 0.9F + i) * 0.08F;
            } else { // flung out, past the camera
                final float k = (t - MEET) / 1.8F, e = (float) Math.pow(Mth.clamp(k, 0, 1), 1.6);
                final float a = (float) Math.atan2(s[1] * 0.45F, s[0] * (0.35F + 0.25F * (i % 3)));
                final float r = 0.5F + e * 6.0F;
                x = Mth.cos(a) * r;
                y = Mth.sin(a) * r;
                z = Mth.lerp(e, s[2], 0.25F);
            }
            if (z <= 0.2F) {
                continue;
            }
            final float f = span * 0.45F;
            drawn.add(new float[] {cx + x / z * f, cy + y / z * f, s[8] * f * 0.22F / z, z, i});
        }
        drawn.sort((a, b) -> Float.compare(b[3], a[3]));
        com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
        for (float[] d : drawn) {
            final float[] s = shards[(int) d[4]];
            final net.minecraft.world.level.block.state.BlockState state = s[0] < 0 ? AbyssalDepths.GLOAM_CRYSTAL.get().defaultBlockState()
                    : (int) d[4] % 4 == 1 ? AbyssalDepths.STORM_CRYSTAL.get().defaultBlockState() : AbyssalDepths.THALAN.get().defaultBlockState();
            g.pose().pushPose();
            g.pose().translate(d[0], d[1], 200);
            g.pose().scale(d[2], -d[2], d[2]);
            final float spin = (t * s[7]) % 360;
            g.pose().mulPose(new org.joml.Quaternionf().rotationAxis(spin * Mth.DEG_TO_RAD, new org.joml.Vector3f(s[4], s[5], s[6]).normalize()));
            g.pose().translate(-0.5F, -0.5F, -0.5F);
            minecraft.getBlockRenderer().renderSingleBlock(state, g.pose(), g.bufferSource(), s[0] < 0 ? 0xF000F0 : 0x500050,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            g.pose().popPose();
        }
        g.flush();
    }

    /**
     * The title: its letters fly in one after another, light ones from the left, dark ones from the right (alternating),
     * each landing with a small overshoot; then a line grows under it and the subtitle rises in.
     */
    private void title(GuiGraphics g, float u, float cx, float cy, int w) {
        final String text = Component.translatable("opening.abyssaldepths.title").getString();
        final float scale = Math.max(2.0F, Math.min(w / 110.0F, 5.5F));
        final float total = font.width(text) * scale;
        float x = cx - total / 2;
        for (int i = 0; i < text.length(); i++) {
            final String ch = text.substring(i, i + 1);
            final float cw = font.width(ch) * scale;
            final float local = (u - i * 0.06F) / 0.55F;
            if (local > 0 && !ch.isBlank()) {
                final boolean fromLight = i % 2 == 0;
                final float k = back(local);
                final float off = (1 - k) * (fromLight ? -1 : 1) * 70;
                final float rise = (1 - out(local)) * 18;
                final float alpha = Mth.clamp(local * 2.5F, 0, 1);
                g.pose().pushPose();
                g.pose().translate(x + cw / 2 + off, cy - 4 * scale + rise, 0);
                final float pop = 0.6F + 0.4F * k;
                g.pose().scale(scale * pop, scale * pop, 1);
                final int color = fromLight ? 0xE9E3DA : 0xB8A8C8;
                g.drawString(font, ch, -font.width(ch) / 2, 0, (int) (alpha * 255) << 24 | color, false);
                g.pose().popPose();
            }
            x += cw;
        }
        final float line = out((u - 1.1F) / 0.9F);
        if (line > 0) {
            final int half = (int) (total / 2 * line);
            g.fill((int) cx - half, (int) (cy + 6 * scale), (int) cx + half, (int) (cy + 6 * scale) + 1, (int) (line * 160) << 24 | 0x8E8698);
        }
        final float sub = out((u - 1.5F) / 1.0F);
        if (sub > 0) {
            final Component s = Component.translatable("opening.abyssaldepths.subtitle");
            g.drawCenteredString(font, s, (int) cx, (int) (cy + 9 * scale + (1 - sub) * 10), (int) (sub * 190) << 24 | 0xA89CB0);
        }
    }

    /**
     * A mote of one force on its layer of depth (near: larger, faster, swinging wider): from its side towards the
     * middle, swirling as it comes; near the middle circling; once they meet, circling outwards in a widening ring.
     */
    private static void mote(GuiGraphics g, float[] m, float t, float cx, float cy, int w, int h, int side) {
        final float depth = m[4], par = 0.7F + 0.3F * depth;
        final float local = Math.max(0, t - m[1]) * m[2] * par;
        final float sx = side < 0 ? -30 : w + 30, sy = h * (0.12F + 0.76F * m[0]);
        float x, y;
        if (t < MEET) {
            final float e = inOut(local / (MEET - 0.4F));
            final float swirl = (1 - e) * (18 + 14 * depth) * Mth.sin(local * 2.0F + m[3]);
            x = Mth.lerp(e, sx, cx + side * 10 * (1 - m[0]));
            y = Mth.lerp(e, sy, cy + (m[0] - 0.5F) * 30) + swirl;
            if (e > 0.82F) {
                final float a = m[3] + local * 1.6F + (side < 0 ? 0 : Mth.PI);
                final float r = (16 + 28 * m[0]) * (0.8F + 0.2F * depth);
                final float q = inOut((e - 0.82F) / 0.18F);
                x = Mth.lerp(q, x, cx + Mth.cos(a) * r);
                y = Mth.lerp(q, y, cy + Mth.sin(a) * r * 0.6F);
            }
        } else {
            final float a = m[3] + (t - m[1]) * 1.6F * par + (side < 0 ? 0 : Mth.PI);
            final float r = (16 + 28 * m[0]) * (0.8F + 0.2F * depth) * (1 + out((t - MEET) / 3.0F) * 2.2F);
            x = cx + Mth.cos(a) * r;
            y = cy + Mth.sin(a) * r * 0.6F;
        }
        final float alpha = Mth.clamp(local * 2, 0, 1) * (0.45F + 0.275F * depth)
                * (t > MEET + 2.0F ? Mth.clamp(1 - (t - MEET - 2.0F) / 2.0F, 0, 1) : 1);
        if (alpha <= 0.01F) {
            return;
        }
        final int px = (int) x, py = (int) y, size = 1 + (int) depth;
        if (side < 0) {
            g.fill(px - size, py - size, px + size * 2 + 1, py + size * 2 + 1, (int) (alpha * 35) << 24 | 0xF2E8D8);
            g.fill(px, py, px + size + 1, py + size + 1, (int) (alpha * 255) << 24 | 0xF7F2EA);
        } else {
            g.fill(px - 1, py - 1, px + size + 2, py + size + 2, (int) (alpha * 150) << 24 | 0x2A1838);
            g.fill(px, py, px + size + 1, py + size + 1, (int) (alpha * 255) << 24);
        }
    }

    private static void ring(GuiGraphics g, float cx, float cy, float r, int size, int color) {
        final int n = Math.max(24, (int) (r * 0.7F));
        for (int i = 0; i < n; i++) {
            final float a = i * Mth.TWO_PI / n;
            final int x = (int) (cx + Mth.cos(a) * r), y = (int) (cy + Mth.sin(a) * r * 0.62F);
            g.fill(x, y, x + size, y + size, color);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        onClose();
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        onClose();
        return true;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.getSoundManager().stop();
            minecraft.setScreen(next);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Once, the first moment the title screen shows with the loading done: the opening comes before it. */
    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Hook {
        @SubscribeEvent
        public static void onTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
            final Minecraft mc = Minecraft.getInstance();
            if (!shown && mc.screen instanceof TitleScreen title && mc.getOverlay() == null) {
                shown = true;
                if (ClientConfig.opening()) {
                    mc.setScreen(new OpeningScreen(title));
                }
            }
        }
    }
}
