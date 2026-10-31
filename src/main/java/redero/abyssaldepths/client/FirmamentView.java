package redero.abyssaldepths.client;

import java.util.Map;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.Firmament;

/**
 * Firmament's blocks as they are seen: everyone sees the top face's outline glowing yellow, a thin line
 * with a soft glow, slowly breathing; their user also sees the block itself, a faint transparent yellow. Blocks left behind, and all of them once let go, fade out.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class FirmamentView {
    /** The outline: a line this wide, its glow this far out to both sides (in blocks). */
    private static final float LINE_WIDTH = 0.035F, GLOW_WIDTH = 0.09F;
    private static final int LINE = 0xFFE36B, GLOW = 0xFFC93C;
    private static final int FILL = 0xFFD95A;
    private static final double RANGE = 96.0;

    private FirmamentView() {
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        final Map<Integer, Firmament.Platform> platforms = Firmament.clientPlatforms();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || platforms.isEmpty() || mc.level == null || mc.player == null) {
            return;
        }
        final Vec3 cam = event.getCamera().getPosition();
        final float time = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        final PoseStack pose = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        for (Map.Entry<Integer, Firmament.Platform> entry : platforms.entrySet()) {
            final Firmament.Platform platform = entry.getValue();
            final boolean own = entry.getKey() == mc.player.getId();
            for (Long2LongMap.Entry block : platform.blocks().long2LongEntrySet()) {
                final BlockPos pos = BlockPos.of(block.getLongKey());
                if (pos.distToCenterSqr(cam) > RANGE * RANGE) {
                    continue;
                }
                // fading over its last half second, or over what is left once let go
                final float left = block.getLongValue() - time;
                final float alpha = Mth.clamp(platform.held() ? left / 10.0F : left / Math.max(10.0F, platform.trail() * 0.5F), 0.0F, 1.0F);
                if (alpha <= 0.02F) {
                    continue;
                }
                pose.pushPose();
                pose.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
                final Matrix4f m = pose.last().pose();
                if (own) {
                    fill(quads, m, (int) (alpha * 50) << 24 | FILL);
                }
                outline(quads, m, time, pos, alpha);
                pose.popPose();
            }
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    /** The block's six faces, just inside it. */
    private static void fill(VertexConsumer q, Matrix4f m, int argb) {
        final float a = 0.002F, b = 1.0F - a;
        quad(q, m, a, a, a, b, a, a, b, a, b, a, a, b, argb); // bottom
        quad(q, m, a, b, a, a, b, b, b, b, b, b, b, a, argb); // top
        quad(q, m, a, a, a, a, b, a, b, b, a, b, a, a, argb);
        quad(q, m, a, a, b, b, a, b, b, b, b, a, b, b, argb);
        quad(q, m, a, a, a, a, a, b, a, b, b, a, b, a, argb);
        quad(q, m, b, a, a, b, b, a, b, b, b, b, a, b, argb);
    }

    /**
     * The top face's edge: a thin bright line, mitred at the corners, with a soft glow fading out to both
     * sides; breathing slowly, each block a little out of step.
     */
    private static void outline(VertexConsumer q, Matrix4f m, float time, BlockPos pos, float alpha) {
        final float breath = 0.85F + 0.15F * Mth.sin(time * 0.15F + (pos.getX() * 7 + pos.getZ() * 13) * 0.5F);
        final int line = (int) (alpha * breath * 255) << 24 | LINE, glow = (int) (alpha * breath * 110) << 24 | GLOW, none = GLOW;
        for (int side = 0; side < 4; side++) {
            // along the edge z = 0 (turned round the middle for the other sides): the line inwards from it,
            // the glow beyond it on both sides
            band(q, m, side, 0.0F, LINE_WIDTH, line, line);
            band(q, m, side, LINE_WIDTH, LINE_WIDTH + GLOW_WIDTH, glow, none);
            band(q, m, side, 0.0F, -GLOW_WIDTH, glow, none);
        }
    }

    /** A strip along one edge of the top face, from {@code from} to {@code to} in from the edge, mitred. */
    private static void band(VertexConsumer q, Matrix4f m, int side, float from, float to, int fromColour, int toColour) {
        final float y = 1.003F;
        final float[] a = turn(side, from, from), b = turn(side, 1.0F - from, from), c = turn(side, 1.0F - to, to), d = turn(side, to, to);
        q.addVertex(m, a[0], y, a[1]).setColor(fromColour);
        q.addVertex(m, b[0], y, b[1]).setColor(fromColour);
        q.addVertex(m, c[0], y, c[1]).setColor(toColour);
        q.addVertex(m, d[0], y, d[1]).setColor(toColour);
    }

    /** A point of the top face turned a quarter round its middle {@code side} times. */
    private static float[] turn(int side, float x, float z) {
        for (int i = 0; i < side; i++) {
            final float t = x;
            x = 1.0F - z;
            z = t;
        }
        return new float[] {x, z};
    }

    private static void quad(VertexConsumer q, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3, int argb) {
        q.addVertex(m, x0, y0, z0).setColor(argb);
        q.addVertex(m, x1, y1, z1).setColor(argb);
        q.addVertex(m, x2, y2, z2).setColor(argb);
        q.addVertex(m, x3, y3, z3).setColor(argb);
    }
}
