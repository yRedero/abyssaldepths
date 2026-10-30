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
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.artifact.ArtifactItem;
import redero.abyssaldepths.artifact.InnerHavenItem;

/**
 * The marks of the places Inner Haven remembers (where its holder left into the inner self): a sigil on
 * the ground, seen only by its owner, and only while they carry an Inner Haven mastered far enough to come
 * back there. In one of several styles (client config): turning rings of lights, a column of rising
 * pixels, a ring of glyphs, a dark tear in the ground; warm white and crimson, all in square pixels.
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
            switch (ClientConfig.waypointMark()) {
                case SIGIL -> sigil(quads, m, time, event.getCamera());
                case BEACON -> beacon(quads, m, time, event.getCamera());
                case RUNES -> runes(quads, m, time);
                case RIFT -> rift(quads, m, time, mark.pos());
                case HALO -> halo(quads, m, time, event.getCamera());
                case SPIRAL -> spiral(quads, m, time);
                case EMBERS -> embers(quads, m, time, event.getCamera());
                case CROWN -> crown(quads, m, time, event.getCamera());
            }
            pose.popPose();
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    /** Two rings turning against each other, four breathing rays, motes rising. */
    private static void sigil(VertexConsumer quads, Matrix4f m, float time, net.minecraft.client.Camera camera) {
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
            mote(quads, m, Mth.cos(a) * 0.3F * t, t * 1.6F, Mth.sin(a) * 0.3F * t, 0.03F * (1.0F - t), WARM, (int) (220 * (1.0F - t)), camera);
        }
    }

    /** A column of pixels rising from a small crimson square on the ground, going out as they climb. */
    private static void beacon(VertexConsumer quads, Matrix4f m, float time, net.minecraft.client.Camera camera) {
        for (int i = -2; i <= 2; i++) { // the base: a hollow square of pixels
            flat(quads, m, i * 0.125F, -0.25F, 0.05F, CRIMSON, 220);
            flat(quads, m, i * 0.125F, 0.25F, 0.05F, CRIMSON, 220);
            flat(quads, m, -0.25F, i * 0.125F, 0.05F, CRIMSON, 220);
            flat(quads, m, 0.25F, i * 0.125F, 0.05F, CRIMSON, 220);
        }
        for (int i = 0; i < 24; i++) { // pixels climbing, each on its own loop, blinking off higher up
            final float t = (time * 0.35F + noise(i, 1)) % 1.0F;
            if (noise(i * 7, (int) (time * 6)) < t * 0.7F) {
                continue;
            }
            final float x = (noise(i, 2) - 0.5F) * 0.35F, z = (noise(i, 3) - 0.5F) * 0.35F;
            mote(quads, m, x, 0.1F + t * 3.0F, z, 0.05F, t < 0.5F ? WARM : CRIMSON, 230, camera);
        }
    }

    /** Six glyphs of pixels in a ring on the ground, turning slowly, lighting up one after another. */
    private static void runes(VertexConsumer quads, Matrix4f m, float time) {
        final int lit = (int) (time * 1.5F) % 6;
        for (int g = 0; g < 6; g++) {
            final float a = time * 0.15F + g * Mth.TWO_PI / 6;
            final float cx = Mth.cos(a) * 1.1F, cz = Mth.sin(a) * 1.1F;
            final int rgb = g == lit ? WARM : CRIMSON, alpha = g == lit ? 240 : 150;
            for (int j = 0; j < 3; j++) { // a 3 x 3 glyph, its own fixed pattern
                for (int i = 0; i < 3; i++) {
                    if (i == 1 && j == 1 || noise(g * 9 + j * 3 + i, 77) > 0.45F) {
                        flat(quads, m, cx + (i - 1) * 0.11F, cz + (j - 1) * 0.11F, 0.05F, rgb, alpha);
                    }
                }
            }
        }
    }

    /** A dark tear in the ground, its edge dithered crimson, pixels spiralling into it. */
    private static void rift(VertexConsumer quads, Matrix4f m, float time, Vec3 at) {
        final int frame = (int) (time * 8);
        final float px = 0.125F;
        for (int j = -8; j <= 8; j++) {
            for (int i = -12; i <= 12; i++) {
                final float x = i * px, z = j * px;
                final float d = Mth.sqrt(x * x / 2.25F + z * z); // an oval, wider than deep
                if (d < 0.75F) {
                    flat(quads, m, x, z, px / 2, 0x0A0510, 235);
                } else if (d < 1.0F && noise(i * 13 + frame, j * 7) > (d - 0.75F) / 0.25F) {
                    flat(quads, m, x, z, px / 2, noise(i, j * 3 + frame) > 0.7F ? WARM : CRIMSON, 220);
                }
            }
        }
        for (int i = 0; i < 10; i++) { // pixels drawn in along a spiral
            final float t = (time * 0.4F + noise(i, 5)) % 1.0F;
            final float a = noise(i, 6) * Mth.TWO_PI + t * 4.0F, r = 1.6F * (1.0F - t);
            flat(quads, m, Mth.cos(a) * r * 1.5F, Mth.sin(a) * r, px / 2, t > 0.7F ? CRIMSON : WARM, (int) (220 * (1.0F - t * 0.5F)));
        }
    }

    /** A ring of pixels floating at chest height, bobbing and turning, its shadow a fainter ring below. */
    private static void halo(VertexConsumer quads, Matrix4f m, float time, net.minecraft.client.Camera camera) {
        final float bob = 1.2F + 0.12F * Mth.sin(time * 1.4F);
        for (int i = 0; i < 20; i++) {
            final float a = time * 0.5F + i * Mth.TWO_PI / 20;
            final boolean lit = (i + (int) (time * 4)) % 5 == 0;
            mote(quads, m, Mth.cos(a) * 0.55F, bob, Mth.sin(a) * 0.55F, 0.05F, lit ? WARM : CRIMSON, 235, camera);
            if (i % 2 == 0) {
                flat(quads, m, Mth.cos(a) * 0.55F, Mth.sin(a) * 0.55F, 0.04F, CRIMSON, 90);
            }
        }
    }

    /** Four arms of pixels curling out from the middle, turning, brighter towards the centre. */
    private static void spiral(VertexConsumer quads, Matrix4f m, float time) {
        for (int arm = 0; arm < 4; arm++) {
            for (int k = 0; k < 12; k++) {
                final float r = 0.15F + k * 0.1F, a = time * 0.8F + arm * Mth.HALF_PI + r * 2.4F;
                if (noise(arm * 31 + k, (int) (time * 5)) < k / 16.0F) {
                    continue; // the tips flickering out
                }
                flat(quads, m, Mth.cos(a) * r, Mth.sin(a) * r, 0.05F, k < 4 ? WARM : CRIMSON, 230 - k * 10);
            }
        }
    }

    /** A small crimson glow on the ground, embers drifting up from it and going out. */
    private static void embers(VertexConsumer quads, Matrix4f m, float time, net.minecraft.client.Camera camera) {
        for (int j = -2; j <= 2; j++) {
            for (int i = -2; i <= 2; i++) {
                if (i * i + j * j <= 5 && noise(i * 5 + (int) (time * 3), j) > 0.25F) {
                    flat(quads, m, i * 0.125F, j * 0.125F, 0.06F, Math.abs(i) + Math.abs(j) < 2 ? WARM : CRIMSON, 200);
                }
            }
        }
        for (int i = 0; i < 14; i++) {
            final float t = (time * 0.3F + noise(i, 9)) % 1.0F;
            final float sway = 0.15F * Mth.sin(time * 2.0F + i);
            mote(quads, m, (noise(i, 2) - 0.5F) * 0.5F + sway * t, t * 1.8F, (noise(i, 3) - 0.5F) * 0.5F, 0.035F * (1.0F - t * 0.6F),
                    t < 0.4F ? WARM : CRIMSON, (int) (230 * (1.0F - t)), camera);
        }
    }

    /** A ring of upright spikes of pixels, rising and falling in a wave running round. */
    private static void crown(VertexConsumer quads, Matrix4f m, float time, net.minecraft.client.Camera camera) {
        for (int i = 0; i < 12; i++) {
            final float a = i * Mth.TWO_PI / 12;
            final int height = 1 + (int) (3.5F * (0.5F + 0.5F * Mth.sin(time * 3.0F - i * 0.9F)));
            for (int k = 0; k < height; k++) {
                mote(quads, m, Mth.cos(a) * 0.8F, 0.06F + k * 0.13F, Mth.sin(a) * 0.8F, 0.055F, k == height - 1 ? WARM : CRIMSON, 230, camera);
            }
        }
    }

    /** A fixed random value 0 .. 1. */
    private static float noise(int a, int b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
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
