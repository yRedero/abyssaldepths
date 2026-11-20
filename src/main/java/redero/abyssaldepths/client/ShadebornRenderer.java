package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import net.minecraft.world.entity.player.Player;
import redero.abyssaldepths.shadeborn.Role;
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
    /** How long one expression eases into the next (ticks); how long a startled look stays after a blow. */
    private static final float FACE_EASE = 6.0F, FACE_HOLD = 10.0F;
    private static final int STARTLED = 25;

    /** Each one's face: the expression now, the one before, since when (game ticks). */
    private static final class Face {
        String now = "face_calm", before = "face_calm", candidate = "face_calm";
        float since = -100, candidateSince = -100;
        long hurtAt = -1000;
    }

    private static final Map<Integer, Face> FACES = new HashMap<>();

    public ShadebornRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.4F;
    }

    @Override
    public ResourceLocation getTextureLocation(Shadeborn entity) {
        final ResourceLocation skin = ShadebornVariants.of(entity).skin();
        if (skin != null) {
            return skin;
        }
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
        // what plays, in layers (the model's animations are split by part: *_legs move only the legs, *_body the body and
        // arms, face_* only the brows; a later layer takes the bones it moves): a cutscene's or a vision over all; else
        // the legs walking or running, the body by what they do (running scared or into a fight, creeping, cowering,
        // sitting), the arms presenting what they want, a gesture over that; and the face, eased between expressions
        final List<BbModel.Layer> layers = new ArrayList<>();
        final long now = s.level().getGameTime();
        final float ticks = now + partial;
        final CutsceneActor.Acting acting = s.acted();
        final float speed = s.walkAnimation.speed(partial), walked = s.walkAnimation.position(partial), stride = Math.min(1, speed * 2);
        final boolean moving = speed > 0.05F, cowering = s.posture() == Shadeborn.COWER;
        final Player near = s.level().getNearestPlayer(s.getX(), s.getY(), s.getZ(), 20, e -> e instanceof Player p && !p.isSpectator() && !p.isCreative());
        final boolean attacking = moving && s.isAggressive(), fleeing = moving && !attacking && cowering;
        // creeping: wary ones moving near a stranger, raiders stalking someone they have not yet gone for
        final boolean sneaking = moving && !attacking && !fleeing && near != null
                && (s.posture() == Shadeborn.GUARDED || s.role() == Role.RAIDER);
        final boolean gesture = !s.gesture().isEmpty() && !s.gesture().startsWith("face_") && now - s.gestureAt() < 36;
        final boolean showing = !s.shown().isEmpty();
        // each part on its own track, easing from one animation into the next (nothing snaps)
        final Motion m = MOTIONS.computeIfAbsent(s.getId(), k -> new Motion());
        if (MOTIONS.size() > 512) {
            MOTIONS.clear();
        }
        // (animations not in the model yet are passed over: what played before plays; see the Obsidian note "Shadeborn Animations")
        final java.util.function.Predicate<String> has = model.animations::containsKey;
        String act = null;
        float actFrom = 0;
        if (s.deathTime > 0 && has.test("death")) { // falling
            act = "death";
            actFrom = ticks - s.deathTime - partial;
        } else if (acting != null && now - acting.from() < 200) { // a cutscene's, a vision, a gesture: over the rest
            act = acting.animation();
            actFrom = acting.from();
        } else if (s.vision() >= 0) {
            act = "zooooooosh";
            actFrom = s.vision();
        } else if (gesture) { // nod, wave, bow, beckon, point, warn
            act = s.gesture();
            actFrom = s.gestureAt();
        } else if (s.swinging && has.test("attack_swing_body")) { // a blow struck
            act = "attack_swing_body";
            actFrom = ticks - s.swingTime - partial;
        } else if (s.hurtTime > 0 && has.test("hurt_body")) { // a blow taken
            act = "hurt_body";
            actFrom = ticks - (s.hurtDuration - s.hurtTime) - partial;
        }
        final String legs = s.sitting() ? ("resting".equals(s.activity()) && has.test("sleeping") ? "sleeping" : "sitting_relaxed") : !moving ? null
                : attacking || fleeing ? "running_legs" : s.young() && has.test("skipping_legs") ? "skipping_legs" : "walking_legs";
        String body = s.sitting() ? null : attacking ? "attack_confident_running_body" : fleeing ? "scared_running_body"
                : cowering ? "cowering_body" : sneaking ? "sneaking_body"
                : "stalking".equals(s.activity()) || "lying_in_wait".equals(s.activity()) ? "sneaking_body" : null; // (crouched, watching)
        if (body == null && !s.sitting()) { // standing about: threatening, on the alert, or at ease
            body = (s.posture() == Shadeborn.THREAT || s.isAggressive()) && has.test("threat_body") ? "threat_body"
                    : s.posture() == Shadeborn.ALERT && has.test("alert_body") ? "alert_body" : !moving && has.test("idle_body") ? "idle_body" : null;
        }
        final String arms = showing && !attacking && !fleeing ? "presenting_item" : null; // what they want, held out to you
        track(layers, m.legs, legs, 0, ticks, 4, walked, stride);
        track(layers, m.body, body, 0, ticks, 6, walked, stride);
        track(layers, m.arms, arms, 0, ticks, 5, walked, stride);
        track(layers, m.act, act, actFrom, ticks, 4, walked, stride);
        layers.addAll(face(s, now, ticks, near, has));
        // on top: the head turned where they look; cowering, they shiver
        final Map<String, float[]> extra = new HashMap<>();
        extra.put("head", new float[] {-headPitch * 0.6F, -headYaw, 0});
        if (cowering && !fleeing) {
            final float shiver = Mth.sin(ticks * 2.9F) * 0.9F + Mth.sin(ticks * 4.7F + s.getId()) * 0.6F;
            extra.put("body", new float[] {shiver * 0.6F, 0, shiver});
            extra.put("head", new float[] {-headPitch * 0.6F + shiver * 0.8F, -headYaw, -shiver * 0.5F});
        }
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - bodyYaw));
        final float scale = (s.young() ? 0.6F : 1.0F) / 16.0F;
        pose.scale(scale, scale, scale);
        for (BbModel.Bone bone : model.bones) {
            model.draw(bone, pose, buffers.getBuffer(RenderType.entityCutoutNoCull(getTextureLocation(s))), light, overlay, layers, extra);
        }
        for (ShadebornVariants.Worn worn : ShadebornVariants.of(s).worn()) { // what they wear: on the body's bones, moving with them
            model.wear(worn.model(), pose, buffers.getBuffer(RenderType.entityCutoutNoCull(worn.texture())), light, overlay, layers, extra);
        }
        for (BbModel.Bone bone : model.bones) { // the light in their eyes, bright in the dark
            model.draw(bone, pose, buffers.getBuffer(RenderType.eyes(EYES)), 0xF000F0, OverlayTexture.NO_OVERLAY, layers, extra);
        }
        pose.pushPose();
        if (showing && model.into("arm1", pose, layers, extra)) { // what they want, on the palm of the hand (it moves with the arm)
            final ItemStack want = s.shown();
            pose.translate(4.5F, 6.0F, -2.6F); // the hand's end, in front of the palm
            pose.mulPose(Axis.XP.rotationDegrees(-90)); // (standing up off the outstretched hand)
            final float big = 16.0F / 1.5F;
            pose.scale(big, big, big);
            Minecraft.getInstance().getItemRenderer().renderStatic(want, ItemDisplayContext.GROUND, light, OverlayTexture.NO_OVERLAY, pose, buffers,
                    s.level(), s.getId());
        }
        pose.popPose();
        pose.popPose();
        super.render(s, entityYaw, partial, pose, buffers, light);
        if (redero.abyssaldepths.ClientConfig.shadebornMarkers() && entityRenderDispatcher.distanceToSqr(s) < 20 * 20 && !s.isInvisible()) {
            marker(s, pose, buffers, light, partial);
        }
    }

    /**
     * The face by mood, only when there is reason for it: angry when fighting, or when threatening with someone near
     * (raiders on their own just look calm); afraid when cowering; wary when guarded with someone near; else calm. A blow
     * startles only those calm or wary (one already afraid or angry keeps the look). A new mood has to last
     * {@link #FACE_HOLD} ticks before the face takes it, then eases in over {@link #FACE_EASE} ticks, so a passing
     * moment does not flip it. A look the server asks for (a gesture named face_..., such as the sceptical look at what
     * they do not want) comes at once.
     */
    private static List<BbModel.Layer> face(Shadeborn s, long now, float ticks, Player near, java.util.function.Predicate<String> has) {
        final Face f = FACES.computeIfAbsent(s.getId(), k -> new Face());
        if (FACES.size() > 512) {
            FACES.clear(); // (forgets the long gone; a face is only a look)
        }
        if (s.hurtTime > 0 && now - f.hurtAt > 10) {
            f.hurtAt = now;
        }
        final boolean close = near != null && near.distanceTo(s) < 12;
        String want;
        boolean atOnce = false;
        if (s.gesture().startsWith("face_") && now - s.gestureAt() < 50 && has.test(s.gesture())) {
            want = s.gesture();
            atOnce = true;
        } else if (s.isAggressive() || s.posture() == Shadeborn.THREAT && close) {
            want = "face_angry";
        } else if (s.posture() == Shadeborn.COWER) {
            want = "face_sad_fear";
        } else if (s.posture() == Shadeborn.GUARDED && close || s.posture() == Shadeborn.ALERT) {
            want = "face_wary";
        } else {
            want = "face_calm";
        }
        if (now - f.hurtAt < STARTLED && (f.now.equals("face_calm") || f.now.equals("face_wary") || f.now.equals("face_surprised"))) {
            want = "face_surprised"; // startled by the blow
            atOnce = true;
        }
        if (!want.equals(f.candidate)) {
            f.candidate = want;
            f.candidateSince = ticks;
        }
        if (!want.equals(f.now) && (atOnce || ticks - f.candidateSince >= FACE_HOLD)) {
            f.before = f.now;
            f.now = want;
            f.since = ticks;
        }
        final float k = Mth.clamp((ticks - f.since) / FACE_EASE, 0, 1), eased = k * k * (3 - 2 * k);
        return eased >= 1 ? List.of(new BbModel.Layer(f.now, 0, 1))
                : List.of(new BbModel.Layer(f.before, 0, 1), new BbModel.Layer(f.now, 0, eased));
    }

    /** An animation's time now (seconds), at its speed from {@code shadeborn/animations.json} (1 if not set there). */
    private static float time(String animation, float ticks) {
        return ticks / 20.0F * ShadebornVariants.speed(animation);
    }

    /** What one does, part by part: an animation now and the one it eases out of. */
    private static final class Track {
        String now, before;
        float since = -100, nowFrom, beforeFrom;
    }

    private static final class Motion {
        final Track legs = new Track(), body = new Track(), arms = new Track(), act = new Track();
    }

    private static final Map<Integer, Motion> MOTIONS = new HashMap<>();

    /**
     * A part's layers: the animation wanted now (from the tick it began, for those played from a start), easing in over
     * so many ticks while the one before eases out; from or to nothing, it eases from or to the model's rest.
     */
    private static void track(List<BbModel.Layer> layers, Track t, String want, float from, float ticks, float ease, float walked, float stride) {
        if (!java.util.Objects.equals(want, t.now) || want != null && from != t.nowFrom) {
            t.before = t.now;
            t.beforeFrom = t.nowFrom;
            t.now = want;
            t.nowFrom = from;
            t.since = ticks;
        }
        final float k = Mth.clamp((ticks - t.since) / ease, 0, 1), e = k * k * (3 - 2 * k);
        final boolean fadingOut = t.before != null && e < 1;
        if (fadingOut) { // the one before, whole while the new one blends in over it (or fading back to what lies under it)
            layers.add(new BbModel.Layer(t.before, timeOf(t.before, t.beforeFrom, ticks, walked), (t.now == null ? 1 - e : 1) * weight(t.before, stride)));
        }
        if (t.now != null) {
            layers.add(new BbModel.Layer(t.now, timeOf(t.now, t.nowFrom, ticks, walked), e * weight(t.now, stride)));
        }
    }

    /** An animation's time now (seconds): the legs by the ground covered, those played from a start since then, the rest running on. */
    private static float timeOf(String animation, float from, float ticks, float walked) {
        return switch (animation) {
            case "running_legs" -> (walked * 0.05F) % 0.5F;
            case "walking_legs" -> (walked * 0.125F) % 1.0F;
            default -> from > 0 ? (ticks - from) / 20.0F * ShadebornVariants.speed(animation) : time(animation, ticks);
        };
    }

    /** How much of it shows: the stride's animations as much as they stride. */
    private static float weight(String animation, float stride) {
        return switch (animation) {
            case "running_legs", "walking_legs", "attack_confident_running_body", "scared_running_body" -> stride;
            default -> 1;
        };
    }

    /** Whether the camera looks at it (within about 8 degrees of its middle). */
    private boolean lookedAt(Shadeborn s) {
        final var cam = entityRenderDispatcher.camera;
        if (cam == null) {
            return false;
        }
        final net.minecraft.world.phys.Vec3 to = s.getBoundingBox().getCenter().subtract(cam.getPosition());
        final org.joml.Vector3f look = cam.getLookVector();
        return to.lengthSqr() > 0.01 && to.normalize().dot(new net.minecraft.world.phys.Vec3(look.x(), look.y(), look.z())) > 0.99;
    }

    /** Where the player stands with each Shadeborn near (from the server): by entity id, the trust stage and minutes of grudge. */
    private static final Map<Integer, int[]> RELATIONS = new HashMap<>();

    public static void relations(String data) {
        RELATIONS.clear();
        for (String part : data.split(",")) {
            final String[] f = part.split(":");
            if (f.length == 3) {
                RELATIONS.put(Integer.parseInt(f[0]), new int[] {Integer.parseInt(f[1]), Integer.parseInt(f[2])});
            }
        }
    }

    /**
     * The marker over its head (until the models tell them apart): its role and what it is doing; nearer (within 10
     * blocks) also its temperament, mood and tiredness, where you stand with it (and any grudge), its camp's progress, and
     * what it wants.
     */
    private void marker(Shadeborn s, PoseStack pose, MultiBufferSource buffers, int light, float partial) {
        final List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
        lines.add(net.minecraft.network.chat.Component.translatable("shadeborn.abyssaldepths.role." + s.role().id));
        lines.add(net.minecraft.network.chat.Component.translatable("shadeborn.abyssaldepths.activity." + s.activity()).withStyle(net.minecraft.ChatFormatting.GRAY));
        if (entityRenderDispatcher.distanceToSqr(s) < 10 * 10 && lookedAt(s)) { // all of it for the one looked at (else a crowd is a heap of text)
            lines.add(net.minecraft.network.chat.Component.translatable("shadeborn.abyssaldepths.info.nature",
                    net.minecraft.network.chat.Component.translatable("shadeborn.abyssaldepths.temper." + s.temper()),
                    net.minecraft.network.chat.Component.translatable("shadeborn.abyssaldepths.mood." + s.posture()),
                    Math.round(s.tired() * 100)).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            final int[] rel = RELATIONS.get(s.getId());
            if (rel != null || s.campProgress() >= 0) {
                net.minecraft.network.chat.MutableComponent l = net.minecraft.network.chat.Component.empty();
                if (rel != null) {
                    l.append(net.minecraft.network.chat.Component.translatable("shadeborn.abyssaldepths.stage." + rel[0]));
                    if (rel[1] > 0) {
                        l.append(" ").append(net.minecraft.network.chat.Component.translatable("shadeborn.abyssaldepths.info.grudge", rel[1])
                                .withStyle(net.minecraft.ChatFormatting.DARK_RED));
                    }
                }
                if (s.campProgress() >= 0) {
                    l.append(rel != null ? "  " : "").append(net.minecraft.network.chat.Component.translatable("shadeborn.abyssaldepths.info.progress", s.campProgress()));
                }
                lines.add(l.withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            }
            if (!s.wants().isEmpty() && s.camp() != 0) {
                lines.add(net.minecraft.network.chat.Component.translatable("shadeborn.abyssaldepths.info.wants", s.wants().getHoverName())
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            }
        }
        for (int i = 0; i < lines.size(); i++) { // from the top down
            pose.pushPose();
            pose.translate(0, 0.27 * (lines.size() - 2 - i), 0);
            renderNameTag(s, lines.get(i), pose, buffers, light, partial);
            pose.popPose();
        }
    }
}
