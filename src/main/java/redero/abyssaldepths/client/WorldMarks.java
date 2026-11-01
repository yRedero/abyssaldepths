package redero.abyssaldepths.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.Shades;

/**
 * Seen from the shadow with a mastered Parallax, the creatures and players of the world (not shades) carry a
 * mark over their heads, so a shade can tell who is out there in the world and who is with them in the
 * shadow: a small diamond of pixels, crimson round a warm white middle, bobbing a little. Seen through walls
 * no more than the creature itself is.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class WorldMarks {
    private static final float PX = 1.0F / 12;
    private static final int CRIMSON = 0xC3122B, WARM = 0xFFF4E0;
    private static final double RANGE = 48.0;
    /** The diamond: its pixels, row by row (X crimson, O warm). */
    private static final String[] DIAMOND = {"..X..", ".XOX.", "XOOOX", ".XOX.", "..X.."};

    private WorldMarks() {
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.level == null || mc.player == null || !Shades.clientViewInShadow) {
            return;
        }
        final Vec3 cam = event.getCamera().getPosition();
        final float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        final float time = mc.level.getGameTime() + partial;
        final Vector3f left = event.getCamera().getLeftVector(), up = event.getCamera().getUpVector();
        final PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || entity == mc.player || entity instanceof ArmorStand || !living.isAlive() || Shades.isShade(entity)
                    || ShadeView.hidden(entity) || entity.position().distanceToSqr(cam) > RANGE * RANGE) {
                continue;
            }
            if (buffers == null) {
                buffers = mc.renderBuffers().bufferSource();
            }
            final Vec3 at = entity.getPosition(partial);
            final float bob = 0.06F * Mth.sin(time * 0.15F + entity.getId());
            pose.pushPose();
            pose.translate(at.x - cam.x, at.y - cam.y + entity.getBbHeight() + 0.55 + bob, at.z - cam.z);
            diamond(buffers.getBuffer(RenderType.debugQuads()), pose.last().pose(), left, up);
            pose.popPose();
        }
        if (buffers != null) {
            buffers.endBatch(RenderType.debugQuads());
        }
    }

    private static void diamond(VertexConsumer q, Matrix4f m, Vector3f left, Vector3f up) {
        for (int row = 0; row < DIAMOND.length; row++) {
            for (int col = 0; col < DIAMOND[row].length(); col++) {
                final char c = DIAMOND[row].charAt(col);
                if (c == '.') {
                    continue;
                }
                final float x = (col - 2) * PX, y = (2 - row) * PX, h = PX / 2;
                final int argb = 0xE6000000 | (c == 'X' ? CRIMSON : WARM);
                for (int k = 0; k < 4; k++) {
                    final float sx = k == 0 || k == 3 ? -h : h, sy = k < 2 ? -h : h;
                    final float lx = (x + sx), ly = (y + sy);
                    q.addVertex(m, left.x() * -lx + up.x() * ly, left.y() * -lx + up.y() * ly, left.z() * -lx + up.z() * ly).setColor(argb);
                }
            }
        }
    }
}
