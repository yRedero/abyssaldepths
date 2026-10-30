package redero.abyssaldepths.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import redero.abyssaldepths.entity.UndertowEntity;

/**
 * Undertow's anomaly as a pixelated event horizon (square pixels of an eighth of a block, colours in steps,
 * edges dithered, no gradients): a round black grid facing the eye, its edge breaking into dark violet,
 * growing as it pulls; round it a tilted accretion ring of flat pixels turning, in four steps from dark
 * violet to white (brighter on one side, flickering a step up and down). In the collapse both shrink to
 * a plus of white pixels.
 */
public final class UndertowRenderer extends EntityRenderer<UndertowEntity> {
    /** One pixel, in blocks. */
    static final float PX = 0.125F;
    /** The ring's colours, dark to light. */
    static final int[] STEPS = {0x3A1A66, 0x7A3AC8, 0xE070C0, 0xFFF0FF};
    private static final int BLACK = 0x000000, HAZE = 0x2A0F45;

    public UndertowRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(UndertowEntity anomaly, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        final float age = anomaly.tickCount + partialTick, life = anomaly.lifetime();
        final float grow = Mth.clamp(age / (life * 0.6F), 0.0F, 1.0F);
        final float collapse = Mth.clamp((life - age) / UndertowEntity.COLLAPSE_TICKS, 0.0F, 1.0F); // 1 until the collapse, then to 0
        final float shrink = collapse * collapse; // the collapse shrinks it whole (no pixels popping)
        final float sphere = (0.25F + 0.95F * grow) * anomaly.size();
        final int frame = (int) (age / 2); // the pixels change every other tick, not smoothly
        final VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        pose.pushPose();
        pose.translate(0, 0.25, 0);
        if (shrink > 0.01F) {
            // the accretion ring, lying tilted, turning
            pose.pushPose();
            pose.scale(shrink, shrink, shrink);
            pose.mulPose(Axis.XP.rotationDegrees(68.0F));
            pose.mulPose(Axis.ZP.rotationDegrees(age * 4.5F % 360));
            ring(quads, pose.last().pose(), sphere * 1.4F, sphere * 2.6F, frame);
            pose.popPose();
        }
        // the sphere: a round grid facing the eye
        pose.pushPose();
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        if (shrink > 0.01F) {
            pose.pushPose();
            pose.scale(shrink, shrink, shrink);
            disc(quads, pose.last().pose(), sphere, frame);
            pose.popPose();
        }
        if (collapse < 1.0F) { // a plus of white pixels blazing as all falls into it
            final int arm = 1 + (int) ((1.0F - collapse) * 3);
            for (int k = -arm; k <= arm; k++) {
                pixel(quads, pose.last().pose(), k * PX, 0, 0.001F, 0xFF000000 | (Math.abs(k) == arm ? STEPS[2] : STEPS[3]));
                if (k != 0) {
                    pixel(quads, pose.last().pose(), 0, k * PX, 0.001F, 0xFF000000 | (Math.abs(k) == arm ? STEPS[2] : STEPS[3]));
                }
            }
        }
        pose.popPose();
        pose.popPose();
        super.render(anomaly, yaw, partialTick, pose, buffers, light);
    }

    /** The black grid: pixels inside the radius black, the outermost ones dithered into the violet haze. */
    private static void disc(VertexConsumer quads, Matrix4f m, float radius, int frame) {
        final int n = (int) Math.ceil(radius * 1.3F / PX);
        for (int j = -n; j <= n; j++) {
            for (int i = -n; i <= n; i++) {
                final float x = (i + 0.5F) * PX, y = (j + 0.5F) * PX;
                final float d = Mth.sqrt(x * x + y * y);
                if (d <= radius) {
                    pixel(quads, m, x, y, 0, 0xFF000000 | BLACK);
                } else if (d <= radius * 1.3F && noise(i * 31 + frame, j * 17) > (d - radius) / (radius * 0.3F)) {
                    pixel(quads, m, x, y, 0, 0xFF000000 | HAZE); // the edge breaking up
                }
            }
        }
    }

    /** Rings of flat pixels between two radii, the colour stepped by side and flicker. */
    private static void ring(VertexConsumer quads, Matrix4f m, float inner, float outer, int frame) {
        for (float r = inner; r <= outer; r += PX) {
            final int count = Math.max(8, (int) (Mth.TWO_PI * r / PX));
            final float fade = (r - inner) / Math.max(PX, outer - inner); // 0 inside .. 1 outside
            for (int i = 0; i < count; i++) {
                final float a = i * Mth.TWO_PI / count;
                if (noise(i * 13 + (int) (r * 100), frame / 3) < fade * 0.8F) {
                    continue; // thinning out towards the outside
                }
                final float bright = 0.5F + 0.5F * Mth.cos(a); // the side coming towards you
                int step = (int) (bright * 3.0F + (1.0F - fade) * 1.2F - 0.6F);
                if (noise(i * 7 + (int) (r * 50), frame) > 0.85F) {
                    step += noise(i, frame * 3) > 0.5F ? 1 : -1; // flickering a step
                }
                pixel(quads, m, Mth.cos(a) * r, Mth.sin(a) * r, 0, 0xFF000000 | STEPS[Mth.clamp(step, 0, 3)]);
            }
        }
    }

    /** One square pixel centred at x, y (in the plane of the pose), at depth z. */
    static void pixel(VertexConsumer quads, Matrix4f m, float x, float y, float z, int argb) {
        final float h = PX / 2;
        quads.addVertex(m, x - h, y - h, z).setColor(argb);
        quads.addVertex(m, x + h, y - h, z).setColor(argb);
        quads.addVertex(m, x + h, y + h, z).setColor(argb);
        quads.addVertex(m, x - h, y + h, z).setColor(argb);
    }

    /** A fixed random value 0 .. 1. */
    static float noise(int a, int b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }

    @Override
    public boolean shouldRender(UndertowEntity anomaly, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
        return true; // its ring reaches past its tiny box
    }

    @Override
    public ResourceLocation getTextureLocation(UndertowEntity anomaly) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
