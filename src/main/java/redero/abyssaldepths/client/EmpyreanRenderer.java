package redero.abyssaldepths.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.entity.EmpyreanBeam;

/**
 * Empyrean's beam, all in square pixels of an eighth of a block, as the other artifacts are drawn: first the
 * warning (a crimson line thickening, rings sliding down it, a sigil and sights closing in on the ground; see
 * {@link #warning}); then the beam itself,
 * facing the eye, in bands of colour with hard edges and a crackling, dithered rim, pulses of light running down
 * it, in the configured style ({@link ClientConfig.EmpyreanBeamLook}). Every solid block it passes through takes
 * some of it ({@link EmpyreanBeam#COVER}, as for the damage): below each, the beam is narrower by whole pixels and
 * dimmer, and a splash of pixels lies on the block where it went in, so one sees how much is left where it lands;
 * and it goes on down through where it lands (a block, a creature), into the ground, until it is spent.
 * Styles beyond bands: a sigil turning in the sky with the beam falling out of it, six beams falling slantwise
 * to meet where it lands, the light inside out (a black core rimmed white), a beam bursting open with rays over
 * the ground. Streaks of white fall down it; where it lands rings run out every half second and debris arcs
 * away; as it strikes a ring of pixels runs out, the screens of those near flash white, and the ground shakes
 * under them while it burns. It narrows away at its end.
 */
public final class EmpyreanRenderer extends EntityRenderer<EmpyreanBeam> {
    private static final float PX = 0.125F;
    private static final int WHITE = 0xFFFFFF, GOLD = 0xFFD878, ROSE = 0xF08A94, CRIMSON = 0xC3122B, DARK = 0x4B0D20;
    /** The warning's lighter colours: a blush pink and a pale rose. */
    private static final int BLUSH = 0xFF9EB0, PALE = 0xFFD6DE;
    /** How much wider than its bands' pixels the beam is drawn (all of it). */
    private static final float SCALE = 1.6F;
    /** Up to this many blocks above where it lands the beam is drawn in single pixel rows; higher up, coarser. */
    private static final int FINE = 48;

    /** A band of the beam: half its width in pixels (before cover and opening), its colour, its alpha. */
    private record Band(float half, int rgb, float alpha) {
    }

    public EmpyreanRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(EmpyreanBeam beam, Frustum frustum, double x, double y, double z) {
        return true; // a column to the sky: its box is no guide
    }

    /**
     * What the beam has left at each block level, from {@code depth} blocks below where it lands up to the sky (1
     * under open sky, less under each solid block): index 0 is the lowest level.
     */
    private static float[] strength(Level level, Vec3 at, int top, int depth) {
        final float[] left = new float[top + depth + 1];
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int base = Mth.floor(at.y) - depth;
        float strength = 1.0F;
        for (int i = top + depth; i >= 0; i--) { // from the top down: each solid block takes its share for all below it
            left[i] = strength;
            pos.set(Mth.floor(at.x), base + i, Mth.floor(at.z));
            if (base + i < level.getMaxBuildHeight() && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                strength *= (float) EmpyreanBeam.COVER;
            }
        }
        return left;
    }

    @Override
    public void render(EmpyreanBeam beam, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        final float t = beam.tickCount + partialTick;
        final Vec3 cam = entityRenderDispatcher.camera.getPosition();
        final Vec3 at = beam.getPosition(partialTick);
        // the beam's sideways, facing the eye (it turns round its own height), and towards the eye
        double fx = cam.x - at.x, fz = cam.z - at.z;
        final double len = Math.sqrt(fx * fx + fz * fz);
        if (len < 1.0E-3) {
            fx = 0;
            fz = 1;
        } else {
            fx /= len;
            fz /= len;
        }
        final float sx = (float) -fz, sz = (float) fx;
        final int top = beam.level().getMaxBuildHeight() + 32 - Mth.floor(at.y);
        final int depth = Math.max(0, Math.min(EmpyreanBeam.DEPTH, Mth.floor(at.y) - beam.level().getMinBuildHeight()));
        final float[] left = strength(beam.level(), at, top, depth);
        final Matrix4f m = pose.last().pose();
        final VertexConsumer q = buffers.getBuffer(RenderType.debugQuads());
        final int frame = (int) (t / 2);
        final Beam b = new Beam(q, m, sx, sz, (float) fx, (float) fz, top, depth, left, frame, t);
        if (t < EmpyreanBeam.WARN_TICKS) {
            warning(b, t);
            return;
        }
        final float burn = t - EmpyreanBeam.WARN_TICKS;
        final float fade = Mth.clamp((EmpyreanBeam.BURN_TICKS + EmpyreanBeam.FADE_TICKS - burn) / EmpyreanBeam.FADE_TICKS, 0.0F, 1.0F);
        final float open = Mth.clamp(burn / 3.0F, 0.0F, 1.0F) * fade; // opening in three ticks, narrowing as it ends
        final float w = SCALE * open * (burn < 3 ? 1.0F + (3 - burn) * 0.25F : 1.0F); // a little wider as it strikes
        if (burn < EmpyreanBeam.BURN_TICKS) {
            Flash.rumble(beam, burn); // the ground shakes for those near
        }
        switch (ClientConfig.empyreanBeam()) {
            case RADIANT -> column(b, 0.0F, 0.0F, new Band[] {new Band(7, ROSE, 0.55F), new Band(5, GOLD, 0.85F), new Band(3, WHITE, 1.0F)}, w, true);
            case CRIMSON -> {
                final float throb = 1.0F + 0.15F * Mth.sin(t * 0.9F);
                column(b, 0.0F, 0.0F, new Band[] {new Band(8, DARK, 0.75F), new Band(6, ROSE, 0.85F), new Band(4, CRIMSON, 1.0F)}, w * throb, true);
            }
            case HALO -> {
                column(b, 0.0F, 0.0F, new Band[] {new Band(5, GOLD, 0.85F), new Band(3, WHITE, 1.0F)}, w, true);
                for (int k = 0; k < 4; k++) { // four thin beams circling the core
                    final float a = t * 0.12F + k * Mth.HALF_PI;
                    column(b, Mth.cos(a) * 1.3F * w, Mth.sin(a) * 1.3F * w, new Band[] {new Band(1, WHITE, 0.95F)}, Math.min(1.0F, w * 1.5F), false);
                }
                ring(q, m, 1.3F * w, -t * 0.12F, GOLD, 0.9F * fade);
            }
            case SPIRAL -> {
                column(b, 0.0F, 0.0F, new Band[] {new Band(6, ROSE, 0.5F), new Band(4, GOLD, 0.85F), new Band(2, WHITE, 1.0F)}, w, true);
                spiral(b, w);
            }
            case JUDGEMENT -> { // a great sigil turning in the sky, the beam falling out of it, a smaller one answering on the ground
                column(b, 0.0F, 0.0F, new Band[] {new Band(6, GOLD, 0.7F), new Band(4, WHITE, 1.0F)}, w, true);
                final float height = Math.min(36.0F, b.top() - 2.0F);
                sigil(q, m, height, 7.5F * open, 5.0F * open, t, GOLD, WHITE, fade);
                sigil(q, m, 0.08F, 3.2F * open, 2.0F * open, -t, GOLD, ROSE, fade);
            }
            case CATHEDRAL -> { // six beams falling slantwise out of lights in the sky round about, landing in a ring round it
                column(b, 0.0F, 0.0F, new Band[] {new Band(7, ROSE, 0.55F), new Band(5, GOLD, 0.85F), new Band(3, WHITE, 1.0F)}, w, true);
                final float height = Math.min(70.0F, b.top());
                for (int k = 0; k < 6; k++) {
                    final float a = k * Mth.TWO_PI / 6 + t * 0.01F + UndertowRenderer.noise(beam.getId(), k) * 0.3F;
                    final float x = Mth.cos(a) * 14.0F, z = Mth.sin(a) * 14.0F;
                    // each lands on a ring round the main beam (on its own side, not on it)
                    final float ring = 2.2F * Math.max(0.5F, open);
                    slant(b, x, height, z, Mth.cos(a) * ring, Mth.sin(a) * ring, open, k % 2 == 0 ? GOLD : WHITE);
                    skyLight(b, x, height, z, open, k);
                }
            }
            case RIFT -> { // the light turned inside out: a black core rimmed with white, motes of violet drawn into it
                column(b, 0.0F, 0.0F, new Band[] {new Band(8, WHITE, 0.9F), new Band(6, 0x3A1A66, 1.0F), new Band(4, 0x050307, 1.0F)}, w, true);
                for (int k = 0; k < 40; k++) {
                    final float s2 = (t * 0.05F + UndertowRenderer.noise(k, 4)) % 1.0F;
                    final float a = UndertowRenderer.noise(k, 5) * Mth.TWO_PI, r = (4.0F * (1.0F - s2) + 0.5F) * open;
                    strip(b, Mth.cos(a) * r, Mth.sin(a) * r, PX, UndertowRenderer.noise(k, 6) * 10.0F, UndertowRenderer.noise(k, 6) * 10.0F + PX * 2, 0.02F,
                            (int) (0.9F * fade * 255) << 24 | 0x8E52D6);
                }
            }
            case SUPERNOVA -> { // it bursts open to twice its width, settling; rays of light run out over the ground
                final float burst = burn < 6 ? 1.0F + burn / 6.0F : Math.max(1.25F, 2.0F - (burn - 6) / 10.0F);
                column(b, 0.0F, 0.0F, new Band[] {new Band(9, GOLD, 0.6F), new Band(7, WHITE, 0.9F), new Band(4, WHITE, 1.0F)}, w * burst, true);
                for (int k = 0; k < 16; k++) {
                    final float a = k * Mth.TWO_PI / 16 + UndertowRenderer.noise(k, 7) * 0.2F;
                    final float reach = (3.0F + 4.0F * UndertowRenderer.noise(k, b.frame() / 2)) * open;
                    for (float r = 1.5F * open; r < reach; r += PX * 2) {
                        flat(q, m, Mth.cos(a) * r, 0.07F, Mth.sin(a) * r, (int) ((1.0F - r / (reach + 1)) * fade * 255) << 24 | (r < reach * 0.5F ? WHITE : GOLD));
                    }
                }
            }
        }
        // what it throws up where it lands: a ring running out every half second, debris arcing away
        if (fade > 0.05F) {
            final float pulse = burn % 10.0F;
            ring(q, m, 1.5F + pulse * 0.55F, burn * 0.05F, GOLD, 0.8F * (1.0F - pulse / 10.0F) * fade);
            for (int k = 0; k < 18; k++) {
                final float life = (burn * 0.06F + UndertowRenderer.noise(k, 11)) % 1.0F;
                final float a = UndertowRenderer.noise(k, 12) * Mth.TWO_PI, out = (1.0F + 4.0F * life) * open;
                final float up = 3.0F * life - 3.2F * life * life; // up and down again
                strip(b, Mth.cos(a) * out, Mth.sin(a) * out, PX, Math.max(0.0F, up), Math.max(0.0F, up) + PX * 2, 0.02F,
                        (int) ((1.0F - life) * fade * 255) << 24 | (k % 3 == 0 ? WHITE : k % 3 == 1 ? GOLD : ROSE));
            }
        }
        // streaks of white falling down it, and the ring running out as it strikes
        for (int k = 0; k < 24 && fade > 0.05F; k++) {
            final float y = top - ((t * 3.0F + k * 17.0F) % top);
            final float off = Math.round((UndertowRenderer.noise(k, 9) - 0.5F) * 10.0F * open) * PX;
            strip(b, sx * off, sz * off, PX * 0.5F, y, y + 1.0F, 0.01F, (int) (0.9F * fade * leftAt(b, y) * 255) << 24 | WHITE);
        }
        if (burn < 10) {
            ring(q, m, 1.0F + burn * 0.6F, 0.0F, WHITE, 1.0F - burn / 10.0F);
            Flash.strike(beam, burn);
        }
    }

    /**
     * The warning, building up to the strike: a light rose line from the sky, thickening and flickering faster;
     * rings of pixels sliding down it, ever quicker; on the ground a small rose sigil closing in to the beam's width
     * and four bars pointing in at it, like sights; in its last few ticks it all blazes white.
     */
    private static void warning(Beam b, float t) {
        final float p = t / EmpyreanBeam.WARN_TICKS; // 0 .. 1 towards the strike
        final boolean blaze = t > EmpyreanBeam.WARN_TICKS - 4;
        final float flicker = 0.5F + 0.5F * UndertowRenderer.noise((int) (t * (1.0F + p * 2.0F)), 3);
        column(b, 0.0F, 0.0F, new Band[] {new Band(2.0F, PALE, 0.4F * flicker), new Band(1.0F, blaze ? WHITE : BLUSH, 0.6F + 0.4F * flicker)},
                0.6F + 0.8F * p, false);
        for (int k = 0; k < 4; k++) { // rings sliding down the line, faster as it nears
            final float y = (Math.min(60.0F, b.top()) * (1.0F - ((t * (0.02F + 0.05F * p) + k * 0.25F) % 1.0F)));
            final float left = leftAt(b, y);
            ring(b.q(), b.m(), y, 0.5F + 0.2F * k, t * 0.2F, BLUSH, (0.5F + 0.5F * p) * (0.45F + 0.55F * left));
        }
        final float r = (float) EmpyreanBeam.RADIUS;
        sigil(b.q(), b.m(), 0.07F, r * (1.4F - 0.5F * p), r * (1.0F - 0.4F * p), t * 2.0F, BLUSH, blaze ? WHITE : PALE, 0.6F + 0.4F * flicker);
        for (int k = 0; k < 4; k++) { // the sights: bars closing in
            final float a = k * Mth.HALF_PI + Mth.PI / 4, from = r * (1.9F - 0.6F * p);
            for (int i = 0; i < 4; i++) {
                final float d = from + i * PX;
                flat(b.q(), b.m(), Mth.cos(a) * d, 0.08F, Mth.sin(a) * d, (int) ((0.6F + 0.4F * flicker) * 255) << 24 | (blaze ? WHITE : BLUSH));
            }
        }
    }

    /** What a column is drawn with. */
    private record Beam(VertexConsumer q, Matrix4f m, float sx, float sz, float fx, float fz, int top, int depth, float[] left, int frame, float t) {
    }

    /** What the beam has left at this height (blocks above where it lands; below it, negative). */
    private static float leftAt(Beam b, float y) {
        return b.left()[Mth.clamp(Mth.floor(y) + b.depth(), 0, b.left().length - 1)];
    }

    /**
     * A column of the beam from where it lands to the sky, its middle at (ox, oz): row by row (a pixel high near
     * the ground, coarser up high), each band as wide as its pixels times what is left at that height times
     * {@code w}, the outer band's rim dithered; pulses of light running down it; with {@code splash}, a splash
     * of pixels on each block where the beam goes into it.
     */
    private static void column(Beam b, float ox, float oz, Band[] bands, float w, boolean splash) {
        if (w <= 0.01F) {
            return;
        }
        for (float y = -b.depth(); y < b.top();) { // from deep under where it lands (it goes on through) to the sky
            final float rh = Math.abs(y) < FINE ? PX : PX * 4;
            final int level = Mth.floor(y);
            final float left = leftAt(b, y);
            if (y < 0 && left < 0.15F) { // spent, deep in the ground
                y += rh;
                continue;
            }
            final boolean pulse = (y + b.t() * 2.0F) % 14.0F < 0.6F; // a pulse of light running down
            for (int i = 0; i < bands.length; i++) {
                final Band band = bands[i];
                final int cells = Math.round(band.half() * w * left + (pulse && i == bands.length - 1 ? 1 : 0));
                if (cells <= 0) {
                    continue;
                }
                final float alpha = band.alpha() * (0.45F + 0.55F * left);
                strip(b, ox, oz, cells * PX, y, y + rh, i * 0.004F, (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255) << 24 | (pulse ? lighter(band.rgb()) : band.rgb()));
                if (i == 0) { // the rim crackling: a pixel past the edge on either side, now and then
                    for (int side = -1; side <= 1; side += 2) {
                        if (UndertowRenderer.noise((int) (y * 8) * 2 + side, b.frame()) > 0.6F) {
                            final float edge = side * (cells + 0.5F) * PX;
                            strip(b, ox + b.sx() * edge, oz + b.sz() * edge, PX * 0.5F, y, y + rh, 0.0F,
                                    (int) (alpha * 0.8F * 255) << 24 | band.rgb());
                        }
                    }
                }
            }
            // where it goes into a block: a splash of pixels on its top
            if (splash && Math.abs(y - level) < 1.0E-3 && level + 1 <= b.top() && leftAt(b, level) < leftAt(b, level + 1) - 1.0E-4F) {
                splash(b, ox, oz, level + 1.0F, bands[0].half() * w * leftAt(b, level + 1), bands[0].rgb());
            }
            y += rh;
        }
    }

    /** Pixels thrown out flat over a block's top where the beam goes into it, flickering. */
    private static void splash(Beam b, float ox, float oz, float y, float half, int rgb) {
        final int count = 10;
        for (int k = 0; k < count; k++) {
            if (UndertowRenderer.noise(k + (int) y * 31, b.frame()) > 0.6F) {
                continue;
            }
            final float a = k * Mth.TWO_PI / count + y, r = (half + 1 + UndertowRenderer.noise(k, (int) y) * 3) * PX;
            flat(b.q(), b.m(), ox + Mth.cos(a) * r, y + 0.02F, oz + Mth.sin(a) * r, (int) (0.9F * 255) << 24 | rgb);
        }
    }

    /** A sigil lying flat at {@code height}: two rings turning against each other, glyphs of pixels between them. */
    private static void sigil(VertexConsumer q, Matrix4f m, float height, float outer, float inner, float t, int rgb, int glyph, float fade) {
        if (outer <= 0.2F) {
            return;
        }
        final int a8 = (int) (0.9F * fade * 255) << 24;
        for (int ring = 0; ring < 2; ring++) {
            final float r = ring == 0 ? outer : inner, turn = (ring == 0 ? 1 : -1) * t * 0.03F;
            final int count = (int) (Mth.TWO_PI * r / PX);
            for (int i = 0; i < count; i++) {
                if (ring == 1 && i % 4 == 3) {
                    continue;
                }
                final float a = turn + Mth.TWO_PI * i / count;
                flat(q, m, Mth.cos(a) * r, height, Mth.sin(a) * r, a8 | rgb);
            }
        }
        for (int g = 0; g < 8; g++) { // the glyphs: 3 x 3 pixels each, some lit
            final float a = t * 0.03F + g * Mth.TWO_PI / 8, r = (outer + inner) / 2;
            final int bits = (int) (UndertowRenderer.noise(g, 41) * 512);
            for (int k = 0; k < 9; k++) {
                if ((bits >> k & 1) == 0 && k != 4) {
                    continue;
                }
                final float along = (k % 3 - 1) * PX, across = (k / 3 - 1) * PX;
                final float x = Mth.cos(a) * (r + across) - Mth.sin(a) * along, z = Mth.sin(a) * (r + across) + Mth.cos(a) * along;
                flat(q, m, x, height + 0.01F, z, a8 | glyph);
            }
        }
    }

    /**
     * The sky lighting up where a slanting beam comes from: a disc of pixels lying flat (seen from below as a
     * round light), white in the middle, gold, then rose, with a faint dithered halo round it and a few rays
     * flickering out; it swells with the beam and breathes.
     */
    private static void skyLight(Beam b, float x, float y, float z, float open, int seed) {
        if (open <= 0.05F) {
            return;
        }
        final float breath = 0.85F + 0.15F * Mth.sin(b.t() * 0.3F + seed);
        final float r = 2.2F * open * breath, halo = r * 2.2F;
        final int n = (int) Math.ceil(halo / PX);
        for (int j = -n; j <= n; j++) {
            for (int i = -n; i <= n; i++) {
                final float dx = i * PX, dz = j * PX, d = Mth.sqrt(dx * dx + dz * dz);
                final int argb;
                if (d <= r * 0.4F) {
                    argb = 0xFF000000 | WHITE;
                } else if (d <= r * 0.75F) {
                    argb = 0xF0000000 | GOLD;
                } else if (d <= r) {
                    argb = 0xD0000000 | ROSE;
                } else if (d <= halo && UndertowRenderer.noise(i * 31 + j + seed * 977, b.frame()) < 0.35F * (1.0F - (d - r) / (halo - r))) {
                    argb = 0x90000000 | GOLD; // the halo, thinning out
                } else {
                    continue;
                }
                flat(b.q(), b.m(), x + dx, y, z + dz, argb);
            }
        }
        for (int k = 0; k < 8; k++) { // rays flickering out
            if (UndertowRenderer.noise(seed * 13 + k, b.frame()) > 0.6F) {
                continue;
            }
            final float a = k * Mth.TWO_PI / 8 + seed;
            for (float d = r; d < r * 3.0F; d += PX * 2) {
                flat(b.q(), b.m(), x + Mth.cos(a) * d, y - 0.01F, z + Mth.sin(a) * d, (int) (0.8F * (1.0F - (d - r) / (r * 2.0F)) * 255) << 24 | WHITE);
            }
        }
    }

    /** A beam falling slantwise from (x, y, z) above to (toX, toZ) where it lands: pixels stacked along the line, glowing. */
    private static void slant(Beam b, float x, float y, float z, float toX, float toZ, float open, int rgb) {
        if (open <= 0.05F) {
            return;
        }
        final float length = Mth.sqrt((x - toX) * (x - toX) + y * y + (z - toZ) * (z - toZ));
        final int steps = (int) (length / (PX * 2));
        for (int i = 0; i < steps; i++) {
            final float f = i / (float) steps; // 0 at the top .. 1 where it lands
            final float px = x + (toX - x) * f, py = y * (1 - f), pz = z + (toZ - z) * f;
            final float left = leftAt(b, py);
            final boolean lit = (i + (int) (b.t() * 3)) % 9 < 2; // light running down it
            strip(b, px, pz, PX * (1.0F + open), py, py + PX * 2, 0.0F, (int) ((0.55F + 0.45F * left) * open * 255) << 24 | (lit ? WHITE : rgb));
        }
    }

    /** Two strands of pixels winding down round the beam. */
    private static void spiral(Beam b, float w) {
        for (float y = 0; y < Math.min(b.top(), 64); y += PX * 2) {
            final float left = leftAt(b, y);
            for (int s = 0; s < 2; s++) {
                final float a = y * 0.8F + b.t() * 0.35F + s * Mth.PI, r = (0.9F * left + 0.2F) * w;
                final float x = Mth.cos(a) * r, z = Mth.sin(a) * r;
                strip(b, x, z, PX * 0.5F, y, y + PX, 0.02F, (int) (0.95F * (0.45F + 0.55F * left) * 255) << 24 | (s == 0 ? WHITE : GOLD));
            }
        }
    }

    private static int lighter(int rgb) {
        final int r = (rgb >> 16 & 255) + 255 >> 1, g = (rgb >> 8 & 255) + 255 >> 1, bl = (rgb & 255) + 255 >> 1;
        return r << 16 | g << 8 | bl;
    }

    /** A strip facing the eye, its middle at (ox, oz) moved {@code toward} the eye, half as wide as given, from y0 to y1. */
    private static void strip(Beam b, float ox, float oz, float half, float y0, float y1, float toward, int argb) {
        final float cx = ox + b.fx() * toward, cz = oz + b.fz() * toward;
        b.q().addVertex(b.m(), cx - b.sx() * half, y0, cz - b.sz() * half).setColor(argb);
        b.q().addVertex(b.m(), cx + b.sx() * half, y0, cz + b.sz() * half).setColor(argb);
        b.q().addVertex(b.m(), cx + b.sx() * half, y1, cz + b.sz() * half).setColor(argb);
        b.q().addVertex(b.m(), cx - b.sx() * half, y1, cz - b.sz() * half).setColor(argb);
    }

    private static void flat(VertexConsumer q, Matrix4f m, float x, float y, float z, int argb) {
        final float h = PX * 0.5F;
        q.addVertex(m, x - h, y, z - h).setColor(argb);
        q.addVertex(m, x - h, y, z + h).setColor(argb);
        q.addVertex(m, x + h, y, z + h).setColor(argb);
        q.addVertex(m, x + h, y, z - h).setColor(argb);
    }

    /** A ring of flat pixels on the ground, turned by {@code turn}, every third one left out. */
    private static void ring(VertexConsumer q, Matrix4f m, float r, float turn, int rgb, float alpha) {
        ring(q, m, 0.06F, r, turn, rgb, alpha);
    }

    /** The same ring at a height. */
    private static void ring(VertexConsumer q, Matrix4f m, float height, float r, float turn, int rgb, float alpha) {
        final int count = Math.max(8, (int) (Mth.TWO_PI * r / PX));
        final int argb = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255) << 24 | rgb;
        for (int i = 0; i < count; i++) {
            if (i % 3 == 2) {
                continue;
            }
            final float a = turn + Mth.TWO_PI * i / count;
            flat(q, m, Mth.cos(a) * r, height, Mth.sin(a) * r, argb);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(EmpyreanBeam beam) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    /** The white flash on the screens of those near as the beam strikes. */
    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Flash {
        private static float flash, previous;
        private static int lastBeam = -1;

        private Flash() {
        }

        static void strike(EmpyreanBeam beam, float burn) {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || beam.getId() == lastBeam || burn > 2) {
                return;
            }
            lastBeam = beam.getId();
            final double away = Math.sqrt(mc.player.distanceToSqr(beam.getX(), mc.player.getY(), beam.getZ()));
            flash = Math.max(flash, (float) Mth.clamp(1.0 - away / 64.0, 0.0, 1.0));
        }

        /** While it burns: the ground shakes for those near (the nearer, the harder). */
        static void rumble(EmpyreanBeam beam, float burn) {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return;
            }
            final double away = Math.sqrt(mc.player.distanceToSqr(beam.getX(), mc.player.getY(), beam.getZ()));
            final float near = (float) Mth.clamp(1.0 - away / 40.0, 0.0, 1.0);
            shake = Math.max(shake, near * (burn < 4 ? 1.0F : 0.35F));
        }

        private static float shake;

        @SubscribeEvent
        public static void onTick(ClientTickEvent.Post event) {
            previous = flash;
            flash = Math.max(0.0F, flash - 0.12F);
            shake = Math.max(0.0F, shake - 0.08F);
        }

        @SubscribeEvent
        public static void onCamera(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) {
            if (shake > 0.01F) {
                final float time = (float) (net.minecraft.Util.getMillis() / 30.0);
                event.setYaw(event.getYaw() + (UndertowRenderer.noise((int) time, 1) - 0.5F) * 2.4F * shake);
                event.setPitch(event.getPitch() + (UndertowRenderer.noise((int) time, 2) - 0.5F) * 2.4F * shake);
                event.setRoll(event.getRoll() + (UndertowRenderer.noise((int) time, 3) - 0.5F) * 1.6F * shake);
            }
        }

        static void draw(net.minecraft.client.gui.GuiGraphics g, float partial) {
            final float f = Mth.lerp(partial, previous, flash);
            if (f > 0.01F) {
                g.fill(0, 0, g.guiWidth(), g.guiHeight(), (int) (f * 0.65F * 255) << 24 | 0xFFF6E0);
            }
        }
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class FlashLayer {
        private FlashLayer() {
        }

        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "empyrean_flash"),
                    (g, delta) -> Flash.draw(g, delta.getGameTimeDeltaPartialTick(true)));
        }
    }
}
