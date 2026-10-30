package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.ArtifactItem;
import redero.abyssaldepths.artifact.InnerHavenItem;

/**
 * The marks of the places Inner Haven remembers (where its holder left into the inner self): a sigil on
 * the ground, seen only by its owner, and only while they carry an Inner Haven mastered far enough to come
 * back there. Two rings of small square lights turning against each other, warm white outside and crimson
 * inside, four rays between them breathing in and out, and motes rising slowly from the middle.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class WaypointMarks {
    private static final double RANGE = 64.0;
    private static final int WARM = 0xFFF4E0, CRIMSON = 0xC3122B;
    private record Mark(String dimension, Vec3 pos) {
    }

    private static final List<Mark> MARKS = new ArrayList<>();

    private WaypointMarks() {
    }

    /** The places, as the server sends them: "dimension|x|y|z" each, joined by ';'. */
    public static void set(String places) {
        MARKS.clear();
        for (String place : places.isEmpty() ? new String[0] : places.split(";")) {
            final String[] p = place.split("\\|");
            if (p.length >= 4) {
                MARKS.add(new Mark(p[0], new Vec3(Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]))));
            }
        }
    }

    private static boolean mastered(Minecraft mc) {
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            final net.minecraft.world.item.ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof InnerHavenItem && ArtifactItem.milestone(stack) >= InnerHavenItem.WAYPOINT_MILESTONE) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || MARKS.isEmpty() || mc.level == null || mc.player == null || !mastered(mc)) {
            return;
        }
        final String here = mc.level.dimension().location().toString();
        final Vec3 cam = event.getCamera().getPosition();
        final float time = Util.getMillis() / 1000.0F;
        final PoseStack pose = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        for (Mark mark : MARKS) {
            if (!mark.dimension().equals(here) || mark.pos().distanceToSqr(cam) > RANGE * RANGE) {
                continue;
            }
            pose.pushPose();
            pose.translate(mark.pos().x - cam.x, mark.pos().y - cam.y + 0.03, mark.pos().z - cam.z);
            final Matrix4f m = pose.last().pose();
            final float breath = 0.6F + 0.4F * Mth.sin(time * 1.3F);
            ring(quads, m, 1.25F, 24, time * 0.35F, 0.07F, WARM, (int) (200 * breath));
            ring(quads, m, 0.75F, 12, -time * 0.6F, 0.06F, CRIMSON, (int) (220 * breath));
            for (int i = 0; i < 4; i++) { // four rays between the rings, swelling and fading
                final float a = time * 0.35F + i * Mth.HALF_PI;
                final float reach = 0.85F + 0.3F * (0.5F + 0.5F * Mth.sin(time * 2.0F + i));
                for (float r = 0.85F; r < reach; r += 0.09F) {
                    flat(quads, m, Mth.cos(a) * r, Mth.sin(a) * r, 0.03F, WARM, (int) (160 * breath));
                }
            }
            for (int i = 0; i < 6; i++) { // motes rising from the middle, each on its own loop
                final float t = (time * 0.25F + i / 6.0F) % 1.0F;
                final float a = i * 1.7F + time * 0.2F;
                mote(quads, m, Mth.cos(a) * 0.3F * t, t * 1.6F, Mth.sin(a) * 0.3F * t, 0.03F * (1.0F - t), WARM, (int) (220 * (1.0F - t)), event.getCamera());
            }
            pose.popPose();
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    private static void ring(VertexConsumer quads, Matrix4f m, float radius, int count, float turn, float size, int rgb, int alpha) {
        for (int i = 0; i < count; i++) {
            final float a = turn + i * Mth.TWO_PI / count;
            flat(quads, m, Mth.cos(a) * radius, Mth.sin(a) * radius, size, rgb, alpha);
        }
    }

    /** A small square lying on the ground. */
    private static void flat(VertexConsumer quads, Matrix4f m, float x, float z, float size, int rgb, int alpha) {
        final int c = alpha << 24 | rgb;
        quads.addVertex(m, x - size, 0, z - size).setColor(c);
        quads.addVertex(m, x - size, 0, z + size).setColor(c);
        quads.addVertex(m, x + size, 0, z + size).setColor(c);
        quads.addVertex(m, x + size, 0, z - size).setColor(c);
    }

    /** A small square facing the camera. */
    private static void mote(VertexConsumer quads, Matrix4f m, float x, float y, float z, float size, int rgb, int alpha, net.minecraft.client.Camera camera) {
        final org.joml.Vector3f right = new org.joml.Vector3f(camera.getLeftVector()).negate().mul(size), up = new org.joml.Vector3f(camera.getUpVector()).mul(size);
        final int c = alpha << 24 | rgb;
        quads.addVertex(m, x - right.x - up.x, y - right.y - up.y, z - right.z - up.z).setColor(c);
        quads.addVertex(m, x + right.x - up.x, y + right.y - up.y, z + right.z - up.z).setColor(c);
        quads.addVertex(m, x + right.x + up.x, y + right.y + up.y, z + right.z + up.z).setColor(c);
        quads.addVertex(m, x - right.x + up.x, y - right.y + up.y, z - right.z + up.z).setColor(c);
    }
}
