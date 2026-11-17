package redero.abyssaldepths.client;


import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.entity.BigWorm;

/**
 * Draws the thalan worm from its Blockbench model ({@link BbModel}): the head where the worm is, playing the animation
 * the server set; behind it the body, the model's segment repeated along the way the head went (each turned along it),
 * the last few smaller, a tail. The way is remembered here, per worm, from where its head was tick by tick.
 */
public class BigWormRenderer extends EntityRenderer<BigWorm> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "textures/entity/big_worm.png");
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "models/entity/big_worm.json");
    /** In the model (pixels): its axis (x, y), the head's middle and the segment's middle along it (z). */
    private static final float AXIS_X = 48, AXIS_Y = 48, HEAD_MIDDLE = 76, SEGMENT_MIDDLE = 10;
    /** Blocks along the body: to the first segment's middle behind the head, and between segments (close: they overlap, so it bends whole). */
    private static final double FIRST = -0.3, SPACING = 0.62; // (the first reaching a little into the head, so no seam shows where they meet in a bend)

    public BigWormRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 2.5F;
    }

    @Override
    public ResourceLocation getTextureLocation(BigWorm worm) {
        return TEXTURE;
    }

    /** Its body reaches far past its box: drawn whenever it is near enough. */
    @Override
    public boolean shouldRender(BigWorm worm, Frustum frustum, double x, double y, double z) {
        return worm.distanceToSqr(x, y, z) < 200 * 200;
    }

    @Override
    public void render(BigWorm worm, float entityYaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        final BbModel model = BbModel.get(MODEL);
        final VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        final int overlay = OverlayTexture.pack(0, worm.hurtTime > 0);
        final Vec3 at = worm.getPosition(partial);
        final Vec3 start = BigWorm.centre(at);
        // the head turned along its way, from where it joins the body to its middle (so it sits on the body, whatever
        // the bend; and smooth, unlike the angles sent from the server)
        final Vec3 fallback = Vec3.directionFromRotation(worm.getXRot(), worm.getYRot()).scale(-1);
        final Vec3 chord = start.subtract(worm.along(start, fallback, BigWorm.JOINT)[0]);
        final Vec3 ahead = chord.lengthSqr() > 1.0E-6 ? chord.normalize() : fallback.scale(-1);
        final float yaw = (float) (Mth.atan2(-ahead.x, ahead.z) * Mth.RAD_TO_DEG);
        final float pitch = (float) (Math.asin(Mth.clamp(-ahead.y, -1, 1)) * Mth.RAD_TO_DEG);

        // the head
        pose.pushPose();
        pose.translate(0, AXIS_Y / 16.0, 0);
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.scale(1 / 16.0F, 1 / 16.0F, 1 / 16.0F);
        pose.translate(-AXIS_X, -AXIS_Y, -HEAD_MIDDLE);
        final String animation = worm.animation();
        final float now = worm.level().getGameTime() + partial;
        final float time = (now - worm.animationStart()) / 20.0F;
        final BbModel.Bone head = model.bone("head");
        if (head != null) {
            model.draw(head, pose, out, light, overlay, animation.isEmpty() ? null : animation, time);
        }
        pose.popPose();

        // the body along the way its head went
        final BbModel.Bone segment = model.bone("segment");
        if (segment == null) {
            return;
        }
        final Vec3 back = fallback;
        final double length = worm.length();
        // the tail sweeping (coiling back, lashing across, settling) and the body slamming (a stretch rising, crashing)
        final int sweep = worm.sweep();
        final float sweepT = sweep < 0 ? -1 : now - (sweep >> 1);
        final float sweepSide = (sweep & 1) == 1 ? 1 : -1;
        final float sweepAmount = sweepT < 0 || sweepT > 40 ? 0
                : sweepT < 12 ? -0.35F * Mth.sin(sweepT / 12 * Mth.HALF_PI)
                : sweepT < 20 ? Mth.lerp(smooth((sweepT - 12) / 8), -0.35F, 1.0F)
                : 1.0F - smooth((sweepT - 20) / 20);
        final int slam = worm.slam();
        final float slamT = slam < 0 ? -1 : now - slam;
        final float slamLift = slamT < 0 || slamT > 34 ? 0 : slamT < 18 ? 7 * smooth(slamT / 18) : slamT < 24 ? 7 : slamT < 28 ? 7 * (1 - (slamT - 24) / 4) : 0;
        final double slamAt = BigWorm.JOINT + worm.slamShare() * length;
        // the segments, each spaced by its own thickness (so the narrowing tail stays whole)
        for (double s = BigWorm.JOINT + FIRST; s < BigWorm.JOINT + length; ) {
            final double share = (s - BigWorm.JOINT) / length;
            final float k = (float) BigWorm.thickness(share); // the tail narrowing
            Vec3 p = worm.along(start, back, s)[0];
            // turned along the way over a little stretch (not the single step it lies on), so it bends smoothly
            final Vec3 forward = worm.along(start, back, Math.max(0, s - 1.25))[0].subtract(worm.along(start, back, s + 1.25)[0]).normalize();
            final float sYaw = (float) (Mth.atan2(-forward.x, forward.z) * Mth.RAD_TO_DEG);
            final float sPitch = (float) (Math.asin(Mth.clamp(-forward.y, -1, 1)) * Mth.RAD_TO_DEG);
            if (sweepAmount != 0 && share > 0.7) {
                final double w = Math.pow((share - 0.7) / 0.3, 1.5);
                final Vec3 lat = new Vec3(-forward.z, 0, forward.x).normalize();
                p = p.add(lat.scale(sweepSide * 6.0 * w * sweepAmount));
            }
            if (slamLift > 0) {
                final double d = (s - slamAt) / 7.0;
                p = p.add(0, slamLift * Math.exp(-d * d), 0);
            }
            pose.pushPose();
            pose.translate(p.x - at.x, p.y - at.y, p.z - at.z);
            pose.mulPose(Axis.YP.rotationDegrees(-sYaw));
            pose.mulPose(Axis.XP.rotationDegrees(sPitch));
            pose.scale(k / 16.0F, k / 16.0F, k / 16.0F);
            pose.translate(-AXIS_X, -AXIS_Y, -SEGMENT_MIDDLE);
            final int segLight = LevelRenderer.getLightColor(worm.level(), BlockPos.containing(p));
            model.draw(segment, pose, out, segLight, overlay, null, 0);
            pose.popPose();
            s += SPACING * Math.max(k, 0.25);
        }
        super.render(worm, entityYaw, partial, pose, buffers, light);
    }

    private static float smooth(float x) {
        final float c = Mth.clamp(x, 0, 1);
        return c * c * (3 - 2 * c);
    }
}
