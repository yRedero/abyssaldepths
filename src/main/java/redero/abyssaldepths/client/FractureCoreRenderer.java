package redero.abyssaldepths.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import redero.abyssaldepths.fracture.FractureCore;

/**
 * The corrupted core, in pixels of black and white: a black heart full of white static, eight white plates
 * turning round it as its shell; open, the plates draw far apart and the heart flickers bright. Its attacks on
 * the floor below: rings of black and white pixels running out (the shockwave), a low white beam edged in black
 * sweeping round (the sweep); the barrage is marked by the trial itself.
 */
public final class FractureCoreRenderer extends EntityRenderer<FractureCore> {
    private static final float PX = 0.125F;
    /** From the core down to the floor's top. */
    private static final float FLOOR = -2.5F;
    private static final int WHITE = 0xFFF3EEE4, LIGHT = 0xFFC9C3B8, GREY = 0xFF77777C, DARK = 0xFF34373F, BLACK = 0xFF0B0C11;
    /** The big pixels of its attacks on the floor. */
    private static final float BIG = 0.25F;
    /** From black (cold) to white (warm), five steps. */
    private static final int[] TONES = {BLACK, DARK, GREY, LIGHT, WHITE};

    public FractureCoreRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(FractureCore core, Frustum frustum, double x, double y, double z) {
        return true;
    }

    @Override
    public void render(FractureCore core, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        final float t = core.tickCount + partialTick, in = t - core.since();
        final VertexConsumer q = buffers.getBuffer(RenderType.debugQuads());
        final boolean open = core.state() == FractureCore.OPEN;
        final int frame = (int) (t / 2);
        // the heart, facing the eye
        pose.pushPose();
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        final Matrix4f eye = pose.last().pose();
        final float heart = 0.75F + (open ? 0.1F * Mth.sin(t * 0.8F) : 0.0F);
        final int n = (int) Math.ceil(heart / PX);
        for (int j = -n; j <= n; j++) {
            for (int i = -n; i <= n; i++) {
                final float x = (i + 0.5F) * PX, y = (j + 0.5F) * PX;
                if (x * x + y * y > heart * heart) {
                    continue;
                }
                final float noise = UndertowRenderer.noise(i * 31 + j, frame);
                final float warned = core.state() == FractureCore.WARN ? Mth.clamp(in / 32.0F, 0.0F, 1.0F) : 0.0F; // brightening as an attack nears
                final float lit = open ? noise * 1.4F : noise * noise * (0.9F + warned * 1.6F); // static through the greys, brighter open
                pixel(q, eye, x, y, 0.0F, TONES[Mth.clamp((int) (lit * 4.5F), 0, 4)]);
            }
        }
        pose.popPose();
        // the shell: eight plates turning round it, far apart while it is open
        final float spread = open ? Mth.clamp(in / 8.0F, 0.0F, 1.0F) : 0.0F;
        final float radius = open ? 1.2F + 1.2F * spread : 1.15F;
        for (int k = 0; k < 8; k++) {
            final float a = t * 0.03F + k * Mth.TWO_PI / 8;
            final float tilt = k % 2 == 0 ? 0.4F : -0.4F;
            pose.pushPose();
            pose.translate(Mth.cos(a) * radius, Mth.sin(tilt) * radius * 0.6F, Mth.sin(a) * radius);
            pose.mulPose(Axis.YP.rotation(-a + Mth.HALF_PI));
            plate(q, pose.last().pose());
            pose.popPose();
        }
        // its attacks on the floor
        pose.pushPose();
        pose.translate(0.0F, FLOOR + 0.04F, 0.0F);
        final Matrix4f floor = pose.last().pose();
        switch (core.state()) {
            case FractureCore.SHOCKWAVE -> {
                for (int k = 0; k < 3; k++) {
                    if (in >= k * 18) {
                        ring(q, floor, 1.5F + (in - k * 18) * 0.3F * core.pace(), frame);
                    }
                }
            }
            case FractureCore.SWEEP -> {
                final float beam = core.sweepFrom() + in * Mth.TWO_PI / (80.0F / core.pace());
                sweep(q, floor, beam);
            }
            case FractureCore.WARN -> warning(q, floor, core.next(), core.sweepFrom(), Mth.clamp(in / 32.0F, 0.0F, 1.0F), frame);
            default -> {
            }
        }
        pose.popPose();
        super.render(core, yaw, partialTick, pose, buffers, light);
    }

    /**
     * What comes, foretold on the floor: for the shockwave, faint rings pulsing out a little way from under the
     * core; for the sweep, a band of greys where it will start, filling out from the middle to the wall, chevrons
     * beside it pointing the way it turns; for the barrage, pixels rising round the core's foot.
     */
    private static void warning(VertexConsumer q, Matrix4f m, int next, float from, float p, int frame) {
        switch (next) {
            case FractureCore.SHOCKWAVE -> {
                for (int k = 0; k < 2; k++) {
                    final float r = 1.5F + ((p * 2 + k * 0.5F) % 1.0F) * 3.0F;
                    final int count = Math.max(8, (int) (Mth.TWO_PI * r / BIG));
                    for (int i = 0; i < count; i++) {
                        final float a = Mth.TWO_PI * i / count;
                        flat(q, m, Mth.cos(a) * r, 0.0F, Mth.sin(a) * r, BIG, TONES[(i + frame) % 3 == 0 ? 2 : 1]);
                    }
                }
            }
            case FractureCore.SWEEP -> {
                final float cx = Mth.cos(from), cz = Mth.sin(from);
                final float reach = 1.5F + p * 9.5F;
                for (float r = 1.5F; r < reach; r += BIG) { // a band three pixels wide, lighter down its middle
                    for (int w = -1; w <= 1; w++) {
                        final int tone = w == 0 ? (((int) (r / BIG) + frame) % 4 == 0 ? 4 : 3) : 2;
                        flat(q, m, cx * r - cz * w * BIG, 0.0F, cz * r + cx * w * BIG, BIG, TONES[tone]);
                    }
                }
                if (p > 0.4F) { // chevrons the way it will turn
                    final float tx = -cz, tz = cx; // the way the angle grows: the way it turns
                    for (int c = 0; c < 3; c++) {
                        final float r = 4.0F + c * 2.5F;
                        for (int s = -2; s <= 2; s++) { // a V pointing ahead
                            final float ahead = 0.9F - Math.abs(s) * BIG;
                            flat(q, m, cx * (r + s * BIG) + tx * ahead, 0.0F, cz * (r + s * BIG) + tz * ahead, BIG, TONES[(frame / 2 + c) % 3 == 0 ? 4 : 3]);
                        }
                    }
                }
            }
            default -> {
                for (int k = 0; k < 18; k++) {
                    final float a = k * Mth.TWO_PI / 18 + frame * 0.05F, r = 1.8F + (k % 3) * 0.6F;
                    final float y = ((p * 3 + k * 0.17F) % 1.0F) * 1.5F;
                    flat(q, m, Mth.cos(a) * r, y, Mth.sin(a) * r, BIG, TONES[k % 2 == 0 ? 3 : 1]);
                }
            }
        }
    }

    /** A plate of the shell: white, framed in black, standing (facing outwards). */
    private static void plate(VertexConsumer q, Matrix4f m) {
        final int cells = 5;
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                final int edge = Math.min(Math.min(i, j), Math.min(cells - 1 - i, cells - 1 - j)); // 0 at the rim
                pixel(q, m, (i - cells / 2.0F + 0.5F) * PX, (j - cells / 2.0F + 0.5F) * PX, 0.0F, edge == 0 ? GREY : edge == 1 ? LIGHT : WHITE);
            }
        }
    }

    /** A ring of the shockwave on the floor: a band of big pixels, white in its middle easing to dark at its sides, flickering. */
    private static void ring(VertexConsumer q, Matrix4f m, float r, int frame) {
        if (r > 11.2F) {
            return;
        }
        for (int row = 0; row < 5; row++) {
            final float rr = r + (row - 2) * BIG;
            final int count = Math.max(8, (int) (Mth.TWO_PI * rr / BIG));
            final int tone = 4 - Math.abs(row - 2) * 2; // white, grey, black across it
            for (int i = 0; i < count; i++) {
                final float a = Mth.TWO_PI * i / count;
                final int flicker = UndertowRenderer.noise(i * 7 + row, frame) > 0.8F ? 1 : 0;
                flat(q, m, Mth.cos(a) * rr, 0.0F, Mth.sin(a) * rr, BIG, TONES[Mth.clamp(tone - flicker, 0, 4)]);
            }
        }
    }

    /** The sweeping beam: a low wall of white edged in black, from under the core out to the arena's wall. */
    private static void sweep(VertexConsumer q, Matrix4f m, float angle) {
        final float cx = Mth.cos(angle), cz = Mth.sin(angle);
        // a solid bar, half a block thick: its top lit, its sides in bands from dark at the foot to white, its end capped
        final float px = -cz, pz = cx, half = 0.22F, tall = BIG * 0.6F;
        final int[] rows = {DARK, GREY, LIGHT, WHITE, WHITE};
        final float top = 0.05F + rows.length * tall;
        for (float r = 1.5F; r < 11.0F; r += BIG) {
            final float r1 = r + BIG;
            for (int side = -1; side <= 1; side += 2) {
                final float ox = px * half * side, oz = pz * half * side;
                for (int h = 0; h < rows.length; h++) {
                    final float y = 0.05F + h * tall;
                    final int colour = side > 0 ? rows[h] : shade(rows[h]); // the far side a step darker
                    q.addVertex(m, cx * r + ox, y, cz * r + oz).setColor(colour);
                    q.addVertex(m, cx * r1 + ox, y, cz * r1 + oz).setColor(colour);
                    q.addVertex(m, cx * r1 + ox, y + tall, cz * r1 + oz).setColor(colour);
                    q.addVertex(m, cx * r + ox, y + tall, cz * r + oz).setColor(colour);
                }
            }
            final int lid = ((int) (r / BIG)) % 2 == 0 ? WHITE : LIGHT; // the top, a faint run of pixels along it
            q.addVertex(m, cx * r - px * half, top, cz * r - pz * half).setColor(lid);
            q.addVertex(m, cx * r1 - px * half, top, cz * r1 - pz * half).setColor(lid);
            q.addVertex(m, cx * r1 + px * half, top, cz * r1 + pz * half).setColor(lid);
            q.addVertex(m, cx * r + px * half, top, cz * r + pz * half).setColor(lid);
        }
        for (int h = 0; h < rows.length; h++) { // the end at the wall
            final float y = 0.05F + h * tall, r = 11.0F;
            q.addVertex(m, cx * r - px * half, y, cz * r - pz * half).setColor(shade(rows[h]));
            q.addVertex(m, cx * r + px * half, y, cz * r + pz * half).setColor(shade(rows[h]));
            q.addVertex(m, cx * r + px * half, y + tall, cz * r + pz * half).setColor(shade(rows[h]));
            q.addVertex(m, cx * r - px * half, y + tall, cz * r - pz * half).setColor(shade(rows[h]));
        }
    }

    /** A tone one step darker (and colder). */
    private static int shade(int argb) {
        for (int k = TONES.length - 1; k > 0; k--) {
            if (TONES[k] == argb) {
                return TONES[k - 1];
            }
        }
        return argb;
    }

    private static void pixel(VertexConsumer q, Matrix4f m, float x, float y, float z, int argb) {
        final float h = PX / 2;
        q.addVertex(m, x - h, y - h, z).setColor(argb);
        q.addVertex(m, x + h, y - h, z).setColor(argb);
        q.addVertex(m, x + h, y + h, z).setColor(argb);
        q.addVertex(m, x - h, y + h, z).setColor(argb);
    }

    private static void flat(VertexConsumer q, Matrix4f m, float x, float y, float z, float size, int argb) {
        final float h = size / 2;
        q.addVertex(m, x - h, y, z - h).setColor(argb);
        q.addVertex(m, x - h, y, z + h).setColor(argb);
        q.addVertex(m, x + h, y, z + h).setColor(argb);
        q.addVertex(m, x + h, y, z - h).setColor(argb);
    }

    @Override
    public ResourceLocation getTextureLocation(FractureCore core) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
