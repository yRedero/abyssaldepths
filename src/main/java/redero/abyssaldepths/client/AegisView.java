package redero.abyssaldepths.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.AegisItem;

/**
 * Aegis's force field as everyone sees it: a sphere of pale light round its holder, nearly clear where you
 * look straight through it and brighter towards its rim, a fine grid of lines on it turning slowly, faint
 * bands drifting up it. Its holder, inside
 * in first person, sees it fainter.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class AegisView {
    private static final int COLOUR = 0x7FCFFF, RINGS = 18, SEGMENTS = 32;

    private AegisView() {
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.level == null) {
            return;
        }
        final Vec3 cam = event.getCamera().getPosition();
        final float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        final float time = mc.level.getGameTime() + partial;
        MultiBufferSource.BufferSource buffers = null;
        for (Player player : mc.level.players()) {
            if (!AegisItem.clientHolds(player) || ShadeView.hidden(player)) {
                continue;
            }
            if (buffers == null) {
                buffers = mc.renderBuffers().bufferSource();
            }
            final Vec3 centre = player.getPosition(partial).add(0, player.getBbHeight() * 0.5, 0);
            final float fade = player == mc.player && mc.options.getCameraType().isFirstPerson() ? 0.45F : 1.0F;
            final PoseStack pose = event.getPoseStack();
            pose.pushPose();
            pose.translate(centre.x - cam.x, centre.y - cam.y, centre.z - cam.z);
            sphere(buffers.getBuffer(RenderType.debugQuads()), pose.last().pose(), (float) AegisItem.RADIUS, centre.subtract(cam), time, fade);
            grid(buffers.getBuffer(RenderType.lines()), pose.last(), (float) AegisItem.RADIUS, time, fade);
            pose.popPose();
        }
        if (buffers != null) {
            buffers.endBatch(RenderType.debugQuads());
            buffers.endBatch(RenderType.lines());
        }
    }

    /** The sphere, each corner's alpha by how sideways the eye looks at it there. */
    private static void sphere(VertexConsumer q, Matrix4f m, float radius, Vec3 fromEye, float time, float fade) {
        for (int i = 0; i < RINGS; i++) {
            for (int j = 0; j < SEGMENTS; j++) {
                corner(q, m, radius, i, j, fromEye, time, fade);
                corner(q, m, radius, i + 1, j, fromEye, time, fade);
                corner(q, m, radius, i + 1, j + 1, fromEye, time, fade);
                corner(q, m, radius, i, j + 1, fromEye, time, fade);
            }
        }
    }

    /** A fine grid on the sphere (seen from inside too, where the rim is not), turning slowly. */
    private static void grid(VertexConsumer lines, PoseStack.Pose pose, float radius, float time, float fade) {
        final int colour = (int) (0.6F * fade * 255) << 24 | 0xD8F1FF;
        final float turn = time * 0.004F;
        for (int k = 1; k < 6; k++) { // circles of latitude
            final float lat = Mth.PI * k / 6 - Mth.HALF_PI;
            for (int j = 0; j < SEGMENTS; j++) {
                line(lines, pose, radius, lat, Mth.TWO_PI * j / SEGMENTS + turn, lat, Mth.TWO_PI * (j + 1) / SEGMENTS + turn, colour);
            }
        }
        for (int k = 0; k < 12; k++) { // meridians
            final float lon = Mth.TWO_PI * k / 12 + turn;
            for (int i = 0; i < RINGS; i++) {
                line(lines, pose, radius, Mth.PI * i / RINGS - Mth.HALF_PI, lon, Mth.PI * (i + 1) / RINGS - Mth.HALF_PI, lon, colour);
            }
        }
    }

    private static void line(VertexConsumer lines, PoseStack.Pose pose, float radius, float lat0, float lon0, float lat1, float lon1, int colour) {
        final float x0 = Mth.cos(lat0) * Mth.cos(lon0), y0 = Mth.sin(lat0), z0 = Mth.cos(lat0) * Mth.sin(lon0);
        final float x1 = Mth.cos(lat1) * Mth.cos(lon1), y1 = Mth.sin(lat1), z1 = Mth.cos(lat1) * Mth.sin(lon1);
        float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
        final float len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= len;
        ny /= len;
        nz /= len;
        lines.addVertex(pose, x0 * radius, y0 * radius, z0 * radius).setColor(colour).setNormal(pose, nx, ny, nz);
        lines.addVertex(pose, x1 * radius, y1 * radius, z1 * radius).setColor(colour).setNormal(pose, nx, ny, nz);
    }

    private static void corner(VertexConsumer q, Matrix4f m, float radius, int ring, int segment, Vec3 fromEye, float time, float fade) {
        final float lat = Mth.PI * ring / RINGS - Mth.HALF_PI, lon = Mth.TWO_PI * segment / SEGMENTS;
        final float nx = Mth.cos(lat) * Mth.cos(lon), ny = Mth.sin(lat), nz = Mth.cos(lat) * Mth.sin(lon);
        // the eye's view onto this point: the rim is where it grazes the sphere
        final double vx = fromEye.x + nx * radius, vy = fromEye.y + ny * radius, vz = fromEye.z + nz * radius;
        final double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        final float facing = len < 1.0E-3 ? 1.0F : (float) Math.abs((vx * nx + vy * ny + vz * nz) / len);
        final float rim = (1.0F - facing) * (1.0F - facing);
        final float band = Math.max(0.0F, Mth.sin(lat * 5.0F - time * 0.08F)) * 0.08F;
        final int alpha = (int) (Mth.clamp(0.16F + 0.5F * rim + band, 0.0F, 1.0F) * fade * 255);
        q.addVertex(m, nx * radius, ny * radius, nz * radius).setColor(alpha << 24 | COLOUR);
    }
}
