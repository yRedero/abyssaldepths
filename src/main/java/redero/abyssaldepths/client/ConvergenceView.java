package redero.abyssaldepths.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderBlockScreenEffectEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.ConvergenceItem;

/**
 * What a player phasing with Convergence sees inside a wall: the blocks round them (one deep, two at full
 * mastery) are gone but for faint outlines, and beyond that hollow the wall shows again, dimly lit. (Inside
 * a block the game draws no faces round the eye, so without this the whole ground would be see-through.)
 * The block-in-the-face overlay is left out while phasing.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class ConvergenceView {
    /** The light of the hollow's walls: dim, but readable. */
    private static final int LIGHT = LightTexture.pack(8, 0);

    private ConvergenceView() {
    }

    @SubscribeEvent
    public static void onOverlay(RenderBlockScreenEffectEvent event) {
        if (event.getOverlayType() == RenderBlockScreenEffectEvent.OverlayType.BLOCK && ConvergenceItem.phasing(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    private static boolean solid(Minecraft mc, BlockPos pos) {
        return !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.player == null || mc.level == null) {
            return;
        }
        final int sight = ConvergenceItem.sight(mc.player);
        if (sight == 0) {
            return;
        }
        final Vec3 cam = event.getCamera().getPosition();
        final BlockPos feet = mc.player.blockPosition(), eye = BlockPos.containing(cam);
        if (!solid(mc, eye) && !solid(mc, feet) && !solid(mc, feet.above())) {
            return; // not in a wall: the world looks as it is
        }
        final int minX = Math.min(feet.getX(), eye.getX()) - sight, maxX = Math.max(feet.getX(), eye.getX()) + sight;
        final int minY = Math.min(feet.getY(), eye.getY()) - sight, maxY = Math.max(feet.getY(), eye.getY()) + sight;
        final int minZ = Math.min(feet.getZ(), eye.getZ()) - sight, maxZ = Math.max(feet.getZ(), eye.getZ()) + sight;
        final PoseStack pose = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final BlockRenderDispatcher blocks = mc.getBlockRenderer();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // the wall beyond the hollow
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 1; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                        continue;
                    }
                    final BlockState state = mc.level.getBlockState(pos.set(x, y, z));
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    pose.pushPose();
                    pose.translate(x - cam.x, y - cam.y, z - cam.z);
                    blocks.renderSingleBlock(state, pose, buffers, LIGHT, OverlayTexture.NO_OVERLAY);
                    pose.popPose();
                }
            }
        }
        buffers.endBatch();
        // the blocks of the hollow: faint outlines
        final VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (solid(mc, pos.set(x, y, z))) {
                        LevelRenderer.renderLineBox(pose, lines, new AABB(pos).move(-cam.x, -cam.y, -cam.z).deflate(0.002), 0.85F, 0.8F, 0.95F, 0.22F);
                    }
                }
            }
        }
        buffers.endBatch(RenderType.lines());
    }
}
