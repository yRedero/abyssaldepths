package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.entity.UndertowEntity;

/**
 * Undertow on the client, around its anomaly ({@link UndertowRenderer} draws the anomaly itself):
 * <ul>
 * <li>creatures caught in the pull stretch towards it;</li>
 * <li>its collapse: half a second in which no new sound is heard near it;</li>
 * <li>its burst, an explosion of its own, pixelated like everything of it (square pixels, colours in steps,
 * dithered instead of fading): a ball of pixels swelling and breaking up, a shockwave ring on a grid racing
 * out along the ground, pixels of the dark flung out and falling, and a white flash over the screen of those
 * close by that goes out in blocks.</li>
 * </ul>
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class UndertowClient {
    private static final long BURST_MILLIS = 900;
    private static final int[] DEBRIS = {0x000000, UndertowRenderer.STEPS[0], UndertowRenderer.STEPS[1], UndertowRenderer.STEPS[2], UndertowRenderer.STEPS[3]};
    /** The shockwave's grid cell on the ground, and the screen flash's blocks (GUI pixels). */
    private static final float CELL = 0.25F;
    private static final int FLASH_BLOCK = 12;

    private record Burst(Vec3 pos, float size, long start, int seed) {
    }

    private static final List<Burst> BURSTS = new ArrayList<>();
    private static Vec3 silentAt;
    private static long silentUntil, flashStart;
    private static float flash;
    private static int seeds;
    private static final Set<Integer> STRETCHED = new HashSet<>();

    private UndertowClient() {
    }

    /** The collapse begins: no new sound near it until the burst. */
    public static void silence(Vec3 pos) {
        silentAt = pos;
        silentUntil = Util.getMillis() + UndertowEntity.COLLAPSE_TICKS * 50L + 60L;
    }

    /** The burst: its look, and a flash for those close by. */
    public static void burst(Vec3 pos, float size) {
        silentUntil = 0; // the burst itself is heard
        BURSTS.add(new Burst(pos, size, Util.getMillis(), seeds++));
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            final double d = mc.player.getEyePosition().distanceTo(pos);
            if (d < 20.0) {
                flash = (float) (0.75 * (1.0 - d / 20.0)) * size;
                flashStart = Util.getMillis();
            }
        }
    }

    @SubscribeEvent
    public static void onSound(PlaySoundEvent event) {
        if (event.getSound() != null && silentAt != null && Util.getMillis() < silentUntil
                && silentAt.distanceToSqr(event.getSound().getX(), event.getSound().getY(), event.getSound().getZ()) < 48 * 48) {
            event.setSound(null);
        }
    }

    // ---- creatures stretching towards it ----------------------------------------------------

    @SubscribeEvent
    public static void onLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        final LivingEntity entity = event.getEntity();
        if (entity instanceof Player) {
            return;
        }
        final List<UndertowEntity> near = entity.level().getEntitiesOfClass(UndertowEntity.class, entity.getBoundingBox().inflate(UndertowEntity.PULL_RADIUS));
        if (near.isEmpty()) {
            return;
        }
        final UndertowEntity anomaly = near.get(0);
        final Vec3 towards = anomaly.position().subtract(entity.position());
        final double distance = towards.length();
        if (distance > UndertowEntity.PULL_RADIUS * anomaly.size() || distance < 0.2) {
            return;
        }
        final float pull = (float) (1.0 - distance / (UndertowEntity.PULL_RADIUS * anomaly.size()));
        final float angle = (float) Math.atan2(towards.x, towards.z);
        final PoseStack pose = event.getPoseStack();
        pose.pushPose();
        // stretched along the way to it (from the feet), thinner across
        pose.mulPose(Axis.YP.rotation(angle));
        pose.scale(1.0F - 0.2F * pull, 1.0F, 1.0F + 0.6F * pull);
        pose.mulPose(Axis.YP.rotation(-angle));
        STRETCHED.add(entity.getId());
    }

    @SubscribeEvent
    public static void onLivingPost(RenderLivingEvent.Post<?, ?> event) {
        if (STRETCHED.remove(event.getEntity().getId())) {
            event.getPoseStack().popPose();
        }
    }

    // ---- the burst ------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || BURSTS.isEmpty()) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final long now = Util.getMillis();
        BURSTS.removeIf(b -> now - b.start() > BURST_MILLIS * 2);
        final Camera camera = event.getCamera();
        final Vec3 cam = camera.getPosition();
        final Vector3f right = new Vector3f(camera.getLeftVector()).negate(), up = new Vector3f(camera.getUpVector());
        final PoseStack pose = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        for (Burst burst : BURSTS) {
            final float t = (now - burst.start()) / (float) BURST_MILLIS; // 0 .. 2
            pose.pushPose();
            pose.translate(burst.pos().x - cam.x, burst.pos().y - cam.y, burst.pos().z - cam.z);
            final Matrix4f m = pose.last().pose();
            final float s = burst.size();
            if (t < 0.45F) { // the flash: a ball of pixels swelling, breaking up as it goes
                flashBall(quads, m, burst, t / 0.45F, right, up);
            }
            if (t < 1.0F) { // the shockwave: a ring of grid cells racing out along the ground
                final float r = s * 9.0F * (1.0F - (1.0F - t) * (1.0F - t));
                shockwave(quads, m, burst, r, Math.max(CELL, 1.0F * (1.0F - t)), t);
            }
            debris(quads, m, burst, t * BURST_MILLIS / 1000.0F, right, up);
            pose.popPose();
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    /**
     * The shockwave: the ground cells (a grid of quarter blocks) between r - width and r, white at the
     * front, a step darker behind it; fewer and fewer as it runs out (dithered, not fading).
     */
    private static void shockwave(VertexConsumer quads, Matrix4f m, Burst burst, float r, float width, float t) {
        final int n = (int) Math.ceil(r / CELL);
        final float inner = r - width, frame = (int) (t * 20);
        for (int j = -n; j <= n; j++) {
            for (int i = -n; i <= n; i++) {
                final float x = (i + 0.5F) * CELL, z = (j + 0.5F) * CELL, d = Mth.sqrt(x * x + z * z);
                if (d > r || d < inner || UndertowRenderer.noise(i * 41 + burst.seed(), j * 23 + (int) frame) < t * 0.9F) {
                    continue;
                }
                final float depth = (r - d) / Math.max(CELL, width); // 0 at the front .. 1 behind
                final int rgb = UndertowRenderer.STEPS[depth < 0.34F ? 3 : depth < 0.67F ? 2 : 1];
                cell(quads, m, x, z, CELL / 2, 0xFF000000 | rgb);
            }
        }
    }

    /** A square lying on the ground. */
    private static void cell(VertexConsumer quads, Matrix4f m, float x, float z, float h, int argb) {
        quads.addVertex(m, x - h, 0.05F, z - h).setColor(argb);
        quads.addVertex(m, x - h, 0.05F, z + h).setColor(argb);
        quads.addVertex(m, x + h, 0.05F, z + h).setColor(argb);
        quads.addVertex(m, x + h, 0.05F, z - h).setColor(argb);
    }

    /**
     * The flash: a round grid of pixels facing the camera, swelling; white in the middle, a step darker
     * outwards, its pixels dropping out more and more as it goes (f 0 .. 1).
     */
    private static void flashBall(VertexConsumer quads, Matrix4f m, Burst burst, float f, Vector3f right, Vector3f up) {
        final float px = UndertowRenderer.PX * 1.5F * burst.size();
        final float radius = burst.size() * (0.5F + 3.5F * f);
        final int n = (int) Math.ceil(radius / px);
        final int frame = (int) (f * 12);
        for (int j = -n; j <= n; j++) {
            for (int i = -n; i <= n; i++) {
                final float a = (i + 0.5F) * px, b = (j + 0.5F) * px, d = Mth.sqrt(a * a + b * b) / radius;
                if (d > 1.0F || UndertowRenderer.noise(i * 19 + burst.seed(), j * 29 + frame) < f * (0.6F + 0.4F * d)) {
                    continue;
                }
                final int rgb = UndertowRenderer.STEPS[d < 0.45F ? 3 : d < 0.75F ? 2 : 1];
                final float x = right.x() * a + up.x() * b, y = 0.3F + right.y() * a + up.y() * b, z = right.z() * a + up.z() * b;
                billboard(quads, m, x, y, z, right, up, px / 2, 0xFF000000 | rgb);
            }
        }
    }

    /** Pixels of the dark flung out from the centre, falling, going out one by one. */
    private static void debris(VertexConsumer quads, Matrix4f m, Burst burst, float seconds, Vector3f right, Vector3f up) {
        final java.util.Random random = new java.util.Random(burst.seed() * 7919L);
        for (int i = 0; i < 110; i++) {
            final double yawAngle = random.nextDouble() * Math.PI * 2, pitch = (random.nextDouble() - 0.2) * Math.PI * 0.5;
            final float speed = (4.0F + random.nextFloat() * 8.0F) * burst.size();
            final float life = 0.6F + random.nextFloat() * 1.1F;
            final int rgb = DEBRIS[random.nextInt(DEBRIS.length)];
            final float size = 0.05F + random.nextFloat() * 0.07F;
            if (seconds > life) {
                continue;
            }
            final float x = (float) (Math.cos(yawAngle) * Math.cos(pitch)) * speed * seconds;
            final float z = (float) (Math.sin(yawAngle) * Math.cos(pitch)) * speed * seconds;
            final float y = 0.3F + (float) Math.sin(pitch) * speed * seconds - 6.0F * seconds * seconds;
            billboard(quads, m, x * (1.0F - 0.35F * seconds), y, z * (1.0F - 0.35F * seconds), right, up, UndertowRenderer.PX * (size > 0.09F ? 0.75F : 0.5F),
                    0xFF000000 | rgb);
        }
    }

    /** A round glow facing the camera at height y: full in the middle, fading out to its rim. */
    private static void glow(VertexConsumer quads, Matrix4f m, float y, Vector3f right, Vector3f up, float radius, int argb) {
        final int segments = 32, rim = argb & 0x00FFFFFF;
        for (int i = 0; i < segments; i++) {
            final float a0 = i * Mth.TWO_PI / segments, a1 = (i + 1) * Mth.TWO_PI / segments;
            final float c0 = Mth.cos(a0) * radius, s0 = Mth.sin(a0) * radius, c1 = Mth.cos(a1) * radius, s1 = Mth.sin(a1) * radius;
            quads.addVertex(m, 0, y, 0).setColor(argb);
            quads.addVertex(m, right.x() * c0 + up.x() * s0, y + right.y() * c0 + up.y() * s0, right.z() * c0 + up.z() * s0).setColor(rim);
            quads.addVertex(m, right.x() * c1 + up.x() * s1, y + right.y() * c1 + up.y() * s1, right.z() * c1 + up.z() * s1).setColor(rim);
            quads.addVertex(m, 0, y, 0).setColor(argb);
        }
    }

    private static void billboard(VertexConsumer quads, Matrix4f m, float x, float y, float z, Vector3f right, Vector3f up, float size, int argb) {
        final float rx = right.x() * size, ry = right.y() * size, rz = right.z() * size;
        final float ux = up.x() * size, uy = up.y() * size, uz = up.z() * size;
        quads.addVertex(m, x - rx - ux, y - ry - uy, z - rz - uz).setColor(argb);
        quads.addVertex(m, x + rx - ux, y + ry - uy, z + rz - uz).setColor(argb);
        quads.addVertex(m, x + rx + ux, y + ry + uy, z + rz + uz).setColor(argb);
        quads.addVertex(m, x - rx + ux, y - ry + uy, z - rz + uz).setColor(argb);
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layer {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            // the flash over the screen of those close to a burst
            // the flash over the screen of those close to a burst: blocks going out one by one
            event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "undertow_flash"), (g, delta) -> {
                final float t = (Util.getMillis() - flashStart) / 400.0F;
                if (flash <= 0.01F || t >= 1.0F) {
                    return;
                }
                final int alpha = (int) (Math.min(1.0F, flash) * 255) << 24;
                for (int y = 0; y < g.guiHeight(); y += FLASH_BLOCK) {
                    for (int x = 0; x < g.guiWidth(); x += FLASH_BLOCK) {
                        if (UndertowRenderer.noise(x * 3, y * 7) > t) {
                            g.fill(x, y, x + FLASH_BLOCK, y + FLASH_BLOCK, alpha | 0xFFF4FF);
                        }
                    }
                }
            });
        }
    }
}
