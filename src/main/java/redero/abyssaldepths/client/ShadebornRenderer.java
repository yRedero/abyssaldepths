package redero.abyssaldepths.client;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.cinema.CutsceneActor;
import redero.abyssaldepths.shadeborn.Shadeborn;

/**
 * Draws a Shadeborn from the user's Blockbench model ({@link BbModel}): walking when it moves, its posture laid over it
 * (cowering: bent over, head down, arms drawn in; guarded: a little back, head low; threatening: leaning in, arms up),
 * its head turned where it looks, what it wants held up in its hand, and the light in its eyes drawn bright in the
 * dark. Texture variants: {@code shadeborn.png}, then {@code shadeborn_1.png} ... if present (the user adds them).
 * A Seer's vision (or any cutscene's) plays the model's own animations.
 */
public class ShadebornRenderer extends EntityRenderer<Shadeborn> {
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "models/entity/shadeborn.json");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "textures/entity/shadeborn.png");
    private static final ResourceLocation EYES = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "textures/entity/shadeborn_eyes.png");
    private static final float WALK = 0.875F;

    public ShadebornRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.4F;
    }

    @Override
    public ResourceLocation getTextureLocation(Shadeborn entity) {
        if (entity.variant() > 0) {
            final ResourceLocation v = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "textures/entity/shadeborn_" + entity.variant() + ".png");
            if (Minecraft.getInstance().getResourceManager().getResource(v).isPresent()) {
                return v;
            }
        }
        return TEXTURE;
    }

    @Override
    public void render(Shadeborn s, float entityYaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        final BbModel model = BbModel.get(MODEL);
        final float bodyYaw = Mth.rotLerp(partial, s.yBodyRotO, s.yBodyRot);
        final float headYaw = Mth.wrapDegrees(Mth.rotLerp(partial, s.yHeadRotO, s.yHeadRot) - bodyYaw);
        final float headPitch = Mth.lerp(partial, s.xRotO, s.getXRot());
        final int overlay = LivingEntityRenderer.getOverlayCoords(s, 0.0F);
        // the animation: a cutscene's or a vision's, else walking while it moves
        String animation = null;
        float time = 0;
        final long now = s.level().getGameTime();
        final CutsceneActor.Acting acting = s.acted();
        if (acting != null && now - acting.from() < 200) {
            animation = acting.animation();
            time = (now - acting.from() + partial) / 20.0F;
        } else if (s.vision() >= 0) {
            animation = "zooooooosh";
            time = (now - s.vision() + partial) / 20.0F;
        } else if (!s.gesture().isEmpty() && now - s.gestureAt() < 36) { // a gesture: nod, wave, bow, beckon, point, warn
            animation = s.gesture();
            time = (now - s.gestureAt() + partial) / 20.0F;
        } else if (s.sitting()) {
            animation = "sit";
            time = 0.5F;
        } else {
            final float speed = s.walkAnimation.speed(partial);
            if (speed > 0.05F) {
                animation = "walk";
                time = (s.walkAnimation.position(partial) * 0.18F) % WALK;
            }
        }
        // the posture, over the animation
        final Map<String, float[]> extra = new HashMap<>();
        final boolean showing = !s.shown().isEmpty();
        switch (s.posture()) {
            case Shadeborn.COWER -> {
                extra.put("body", new float[] {-22, 0, 0});
                extra.put("arm1", new float[] {50, 0, -15}); // (arms forward: a turn the positive way; drawn in before them)
                extra.put("arm2", new float[] {50, 0, 15});
            }
            case Shadeborn.GUARDED -> extra.put("body", new float[] {4, 0, 0});
            case Shadeborn.THREAT -> {
                extra.put("body", new float[] {-8, 0, 0});
                extra.put("arm1", new float[] {80, 0, 0});
                extra.put("arm2", new float[] {80, 0, 0});
            }
            default -> {
            }
        }
        final float down = s.posture() == Shadeborn.COWER ? -25 : s.posture() == Shadeborn.GUARDED ? -8 : 0;
        extra.put("head", new float[] {down - headPitch * 0.6F, -headYaw, 0});
        final boolean cower = s.posture() == Shadeborn.COWER;
        final boolean gesturing = animation != null && !animation.equals("walk") && !animation.equals("sit");
        if (showing && !gesturing) { // held out before them: low and close when cowering, else up to you
            extra.put("arm1", new float[] {cower ? 60 : 95, 0, 0});
        }
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - bodyYaw));
        final float scale = (s.young() ? 0.6F : 1.0F) / 16.0F;
        pose.scale(scale, scale, scale);
        for (BbModel.Bone bone : model.bones) {
            model.draw(bone, pose, buffers.getBuffer(RenderType.entityCutoutNoCull(getTextureLocation(s))), light, overlay, animation, time, extra);
        }
        for (BbModel.Bone bone : model.bones) { // the light in their eyes, bright in the dark
            model.draw(bone, pose, buffers.getBuffer(RenderType.eyes(EYES)), 0xF000F0, OverlayTexture.NO_OVERLAY, animation, time, extra);
        }
        pose.pushPose();
        if (showing && model.into("arm1", pose, animation, time, extra)) { // what they want, on the palm of the hand (it moves with the arm)
            final ItemStack want = s.shown();
            pose.translate(5.5F, 6.0F, -2.6F); // the hand's end, in front of the palm
            pose.mulPose(Axis.XP.rotationDegrees(-90)); // (standing up off the outstretched hand)
            final float big = cower ? 16.0F / 1.3F : 16.0F / 1.6F;
            pose.scale(big, big, big);
            Minecraft.getInstance().getItemRenderer().renderStatic(want, ItemDisplayContext.GROUND, light, OverlayTexture.NO_OVERLAY, pose, buffers,
                    s.level(), s.getId());
        }
        pose.popPose();
        pose.popPose();
        super.render(s, entityYaw, partial, pose, buffers, light);
    }
}
