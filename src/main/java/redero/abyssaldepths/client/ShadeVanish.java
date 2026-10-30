package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.ClientConfig.ShadeVanishStyle;

/**
 * How shades vanish and come back for those who see it ({@link ClientConfig#shadeVanish}): when a player
 * near this client turns hidden (stepping into the shadow, while this client stays out; or leaving it, seen
 * from inside), a copy of them plays the vanishing where they stood; when one turns visible, it plays
 * backwards on them, and they are drawn only once it is done.
 * <ul>
 * <li>PIXELS: the body darkens and fades while it falls apart into black and purple pixels flying out, which
 * go out one by one;</li>
 * <li>ASH: the body darkens to a black silhouette, then fades while ash rises off it, from the feet up;</li>
 * <li>SLIDE: the body slides sideways out of the world, thinning to a violet line;</li>
 * <li>SHADOW: the body fades into the dark and leaves a pixelated shadow on the ground, which shrinks away.</li>
 * </ul>
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class ShadeVanish {
    /** Only players this close play it. */
    private static final double RANGE = 64.0;
    private static final int BLACK = 0x050307, DEEP = 0x2A0F45, VIOLET = 0x5C2A93, BRIGHT = 0x8E52D6;
    private static final int ASH = 0x1A181C, ASH_LIGHT = 0x34303A, EMBER = 0x4A2A6A, LINE = 0xB8A6FF;

    /** One vanishing (or appearing, played backwards). */
    private record Fade(int entityId, boolean appear, long start, ShadeVanishStyle style, AbstractClientPlayer ghost, Vec3 pos, float width, float height, int seed) {
        long duration() {
            return switch (style) {
                case PIXELS -> 1400;
                case ASH -> 2000;
                case SLIDE -> 900;
                case SHADOW -> 2600;
                case NONE -> 0;
            };
        }

        /** How far it has vanished: 0 .. 1 (backwards when appearing). */
        float progress(long now) {
            final float t = Mth.clamp((now - start) / (float) duration(), 0.0F, 1.0F);
            return appear ? 1.0F - t : t;
        }

        boolean done(long now) {
            return now - start >= duration();
        }
    }

    private static final List<Fade> FADES = new ArrayList<>();
    /** Whether each player was hidden last tick. */
    private static final Map<Integer, Boolean> HIDDEN = new HashMap<>();
    private static boolean wasInShadow;
    private static ClientLevel lastLevel;
    private static int seeds;

    private ShadeVanish() {
    }

    /** Whether the entity is still appearing (and only its fade is drawn). */
    static boolean appearing(Entity entity) {
        for (Fade fade : FADES) {
            if (fade.appear() && fade.entityId() == entity.getId()) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null || mc.player == null || level != lastLevel) {
            FADES.clear();
            HIDDEN.clear();
            lastLevel = level;
            return;
        }
        final long now = Util.getMillis();
        FADES.removeIf(fade -> fade.done(now));
        // the client's own crossing changes who it sees all at once: that is the crossing's business
        final boolean viewerChanged = ShadeView.inShadow() != wasInShadow;
        wasInShadow = ShadeView.inShadow();
        final ShadeVanishStyle style = ClientConfig.shadeVanish();
        final Set<Integer> present = new HashSet<>();
        for (Player player : level.players()) {
            if (player == mc.player || !(player instanceof AbstractClientPlayer other)) {
                continue;
            }
            present.add(player.getId());
            final boolean hidden = ShadeView.seenHidden(player);
            final Boolean before = HIDDEN.put(player.getId(), hidden);
            if (before != null && before != hidden && !viewerChanged && style != ShadeVanishStyle.NONE
                    && player.distanceToSqr(mc.player) < RANGE * RANGE) {
                FADES.removeIf(fade -> fade.entityId() == player.getId());
                FADES.add(new Fade(player.getId(), !hidden, now, style, hidden ? copy(level, other) : other, player.position(),
                        player.getBbWidth(), player.getBbHeight(), seeds++));
            }
        }
        HIDDEN.keySet().retainAll(present);
    }

    /** A still copy of the player as they are now, to vanish in their place. */
    private static AbstractClientPlayer copy(ClientLevel level, AbstractClientPlayer player) {
        final RemotePlayer ghost = new RemotePlayer(level, player.getGameProfile());
        ghost.setPos(player.position());
        ghost.yBodyRot = ghost.yBodyRotO = player.yBodyRot;
        ghost.yHeadRot = ghost.yHeadRotO = player.yHeadRot;
        ghost.setXRot(player.getXRot());
        ghost.xRotO = player.getXRot();
        ghost.setPose(player.getPose());
        return ghost;
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || FADES.isEmpty() || mc.level == null) {
            return;
        }
        final long now = Util.getMillis();
        final float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        final Camera camera = event.getCamera();
        final Vec3 cam = camera.getPosition();
        final Vector3f right = new Vector3f(camera.getLeftVector()).negate(), up = new Vector3f(camera.getUpVector());
        final PoseStack pose = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (Fade fade : FADES) {
            final AbstractClientPlayer subject = fade.ghost();
            if (subject.isRemoved() && fade.appear()) {
                continue;
            }
            final float p = fade.progress(now);
            // appearing follows the player; vanishing stays where they were
            final Vec3 at = fade.appear() ? subject.getPosition(partial) : fade.pos();
            final float subjectPartial = fade.appear() ? partial : 1.0F;
            pose.pushPose();
            pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
            final Matrix4f matrix = pose.last().pose();
            switch (fade.style()) {
                case PIXELS -> {
                    final float alpha = 1.0F - smooth(p / 0.55F);
                    body(subject, subjectPartial, pose, buffers, alpha, mix(0xFFFFFF, 0x6A4A8A, smooth(p / 0.3F)));
                    pixels(quads(buffers), matrix, fade, p, right, up);
                }
                case ASH -> {
                    final float alpha = 1.0F - smooth((p - 0.35F) / 0.45F);
                    body(subject, subjectPartial, pose, buffers, alpha, mix(0xFFFFFF, 0x000000, smooth(p / 0.35F)));
                    ash(quads(buffers), matrix, fade, p, right, up);
                }
                case SLIDE -> {
                    final float e = smooth(p);
                    final float angle = (float) Math.atan2(-right.z(), right.x());
                    pose.pushPose();
                    pose.translate(right.x() * 0.9F * e, 0.0F, right.z() * 0.9F * e);
                    pose.mulPose(Axis.YP.rotation(angle));
                    pose.scale(Math.max(0.02F, 1.0F - e), 1.0F, 1.0F); // thinning across the view
                    pose.mulPose(Axis.YP.rotation(-angle));
                    body(subject, subjectPartial, pose, buffers, 1.0F - smooth((p - 0.75F) / 0.25F), mix(0xFFFFFF, LINE, e));
                    pose.popPose();
                }
                case SHADOW -> {
                    body(subject, subjectPartial, pose, buffers, 1.0F - smooth(p / 0.3F), mix(0xFFFFFF, 0x000000, smooth(p / 0.25F)));
                    shadow(quads(buffers), matrix, fade, p);
                }
                case NONE -> {
                }
            }
            pose.popPose();
        }
        buffers.endBatch();
    }

    /** The buffer for the pixels: fetched after the body, as fetching another one closes it. */
    private static VertexConsumer quads(MultiBufferSource buffers) {
        return buffers.getBuffer(RenderType.debugQuads());
    }

    private static void body(AbstractClientPlayer subject, float partial, PoseStack pose, MultiBufferSource buffers, float alpha, int tint) {
        if (alpha > 0.01F) {
            GhostRenderer.draw(subject, subject.yBodyRot, partial, pose, buffers, alpha, tint);
        }
    }

    /** Pixels flying out of the body, each let go at its own moment and going out at its own. */
    private static void pixels(VertexConsumer quads, Matrix4f matrix, Fade fade, float p, Vector3f right, Vector3f up) {
        for (int i = 0; i < 170; i++) {
            final int n = fade.seed() * 1000 + i;
            final float release = noise(n, 1) * 0.55F;
            final float life = (p - release) / 0.45F;
            if (life < 0.0F || life > 0.6F + 0.4F * noise(n, 2)) {
                continue;
            }
            final float sx = (noise(n, 3) - 0.5F) * fade.width(), sy = noise(n, 4) * fade.height(), sz = (noise(n, 5) - 0.5F) * fade.width();
            final Vector3f dir = new Vector3f(sx, 0.25F + 0.5F * noise(n, 6), sz).normalize();
            final float distance = (0.6F + 1.0F * noise(n, 7)) * (1.0F - (1.0F - life) * (1.0F - life));
            final float size = 0.06F * (1.0F - 0.5F * life) * (0.7F + 0.6F * noise(n, 8));
            final float r = noise(n, 9);
            final int rgb = r < 0.45F ? BLACK : r < 0.75F ? DEEP : r < 0.93F ? VIOLET : BRIGHT;
            billboard(quads, matrix, sx + dir.x() * distance, sy + dir.y() * distance, sz + dir.z() * distance, right, up, size, 0xFF000000 | rgb);
        }
    }

    /** Ash rising off the body, from the feet up, drifting and shrinking. */
    private static void ash(VertexConsumer quads, Matrix4f matrix, Fade fade, float p, Vector3f right, Vector3f up) {
        for (int i = 0; i < 220; i++) {
            final int n = fade.seed() * 1000 + i;
            final float height = noise(n, 4);
            final float release = 0.2F + height * 0.45F + noise(n, 1) * 0.1F; // the feet first
            final float life = (p - release) / 0.4F;
            if (life < 0.0F || life > 1.0F) {
                continue;
            }
            final float sx = (noise(n, 3) - 0.5F) * fade.width(), sz = (noise(n, 5) - 0.5F) * fade.width();
            final float rise = (0.8F + 0.8F * noise(n, 6)) * life * life + 0.2F * life;
            final float sway = 0.15F * Mth.sin(life * 5.0F + noise(n, 7) * Mth.TWO_PI);
            final float size = 0.045F * (1.0F - life) * (0.6F + 0.8F * noise(n, 8));
            final float r = noise(n, 9);
            final int rgb = r < 0.6F ? ASH : r < 0.95F ? ASH_LIGHT : EMBER;
            final int alpha = (int) ((1.0F - life * life) * 255);
            billboard(quads, matrix, sx + sway, height * fade.height() + rise, sz + sway * 0.5F, right, up, size, alpha << 24 | rgb);
        }
    }

    /** A pixelated shadow on the ground: grows under the fading body, then shrinks away. */
    private static void shadow(VertexConsumer quads, Matrix4f matrix, Fade fade, float p) {
        final float radius = 0.75F * (p < 0.15F ? smooth(p / 0.15F) : 1.0F - smooth((p - 0.15F) / 0.85F));
        final float cell = 1.0F / 8.0F;
        for (int i = -6; i < 6; i++) {
            for (int j = -6; j < 6; j++) {
                final float x = (i + 0.5F) * cell, z = (j + 0.5F) * cell;
                final float d = (float) Math.sqrt(x * x + z * z);
                if (d > radius * (0.75F + 0.25F * noise(fade.seed() * 31 + i, j))) {
                    continue;
                }
                final int alpha = (int) (200 * (1.0F - 0.5F * d / 0.75F));
                final float y = 0.02F;
                final int colour = alpha << 24 | BLACK;
                quads.addVertex(matrix, x - cell / 2, y, z - cell / 2).setColor(colour);
                quads.addVertex(matrix, x - cell / 2, y, z + cell / 2).setColor(colour);
                quads.addVertex(matrix, x + cell / 2, y, z + cell / 2).setColor(colour);
                quads.addVertex(matrix, x + cell / 2, y, z - cell / 2).setColor(colour);
            }
        }
    }

    /** A square facing the camera. */
    private static void billboard(VertexConsumer quads, Matrix4f matrix, float x, float y, float z, Vector3f right, Vector3f up, float size, int argb) {
        final float rx = right.x() * size, ry = right.y() * size, rz = right.z() * size;
        final float ux = up.x() * size, uy = up.y() * size, uz = up.z() * size;
        quads.addVertex(matrix, x - rx - ux, y - ry - uy, z - rz - uz).setColor(argb);
        quads.addVertex(matrix, x + rx - ux, y + ry - uy, z + rz - uz).setColor(argb);
        quads.addVertex(matrix, x + rx + ux, y + ry + uy, z + rz + uz).setColor(argb);
        quads.addVertex(matrix, x - rx + ux, y - ry + uy, z - rz + uz).setColor(argb);
    }

    private static float smooth(float t) {
        final float c = Mth.clamp(t, 0.0F, 1.0F);
        return c * c * (3 - 2 * c);
    }

    private static int mix(int a, int b, float t) {
        final int r = (int) Mth.lerp(t, (a >> 16) & 255, (b >> 16) & 255);
        final int g = (int) Mth.lerp(t, (a >> 8) & 255, (b >> 8) & 255);
        final int bl = (int) Mth.lerp(t, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    /** A fixed random value 0 .. 1. */
    private static float noise(int a, int b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }
}
