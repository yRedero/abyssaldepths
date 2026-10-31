package redero.abyssaldepths.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.event.RenderBlockScreenEffectEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.artifact.ConvergenceItem;

/**
 * What a player phasing with Convergence sees with their eye inside a wall: the blocks round them (one deep,
 * two at full mastery; not below their feet, which stay a floor) are a hollow, shown only by marks in the
 * configured look ({@link ClientConfig.ConvergenceLook}); beyond it the wall shows again, dimly lit. Inside a
 * block the game draws no faces round the eye (they face each other and are left out), so the faces of the
 * hollow's walls are drawn here: only those the game leaves out, so nothing is drawn twice. The
 * block-in-the-face overlay is left out while phasing.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class ConvergenceView {
    /** The light of the hollow's walls: dim, but readable. */
    private static final int LIGHT = LightTexture.pack(8, 0);
    private static final int MARK = 0xD9CCF2;
    private static final float PX = 1.0F / 16;

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

    /** The hollow round the player: its corners. */
    private record Hollow(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.player == null || mc.level == null) {
            return;
        }
        final int sight = ConvergenceItem.sight(mc.player);
        final Vec3 cam = event.getCamera().getPosition();
        final BlockPos eye = BlockPos.containing(cam);
        if (sight == 0 || !solid(mc, eye)) {
            return; // the eye is not in a wall: the world looks as it is
        }
        final BlockPos feet = mc.player.blockPosition();
        final Hollow hollow = new Hollow(Math.min(feet.getX(), eye.getX()) - sight, Math.min(feet.getY(), eye.getY()),
                Math.min(feet.getZ(), eye.getZ()) - sight, Math.max(feet.getX(), eye.getX()) + sight, Math.max(feet.getY(), eye.getY()) + sight,
                Math.max(feet.getZ(), eye.getZ()) + sight);
        final PoseStack pose = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        walls(mc, pose, buffers, cam, hollow);
        buffers.endBatch();
        marks(mc, pose, buffers, cam, hollow, event.getCamera());
    }

    /**
     * The hollow's walls: of each solid block round it, the faces towards solid blocks inside it (the faces
     * the game leaves out, as the block beside them hides them).
     */
    private static void walls(Minecraft mc, PoseStack pose, MultiBufferSource buffers, Vec3 cam, Hollow hollow) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final RandomSource random = RandomSource.create();
        for (int x = hollow.minX() - 1; x <= hollow.maxX() + 1; x++) {
            for (int y = hollow.minY() - 1; y <= hollow.maxY() + 1; y++) {
                for (int z = hollow.minZ() - 1; z <= hollow.maxZ() + 1; z++) {
                    if (hollow.contains(x, y, z)) {
                        continue;
                    }
                    final BlockState state = mc.level.getBlockState(pos.set(x, y, z));
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    BakedModel model = null;
                    for (Direction face : Direction.values()) {
                        final int nx = x + face.getStepX(), ny = y + face.getStepY(), nz = z + face.getStepZ();
                        if (!hollow.contains(nx, ny, nz) || !solid(mc, new BlockPos(nx, ny, nz))) {
                            continue; // not towards the hollow, or the game draws that face itself
                        }
                        if (model == null) {
                            model = mc.getBlockRenderer().getBlockModel(state);
                            pose.pushPose();
                            pose.translate(x - cam.x, y - cam.y, z - cam.z);
                        }
                        face(mc, pose.last(), buffers, model, state, pos, face, random);
                    }
                    if (model != null) {
                        pose.popPose();
                    }
                }
            }
        }
    }

    private static void face(Minecraft mc, PoseStack.Pose pose, MultiBufferSource buffers, BakedModel model, BlockState state, BlockPos pos, Direction face,
            RandomSource random) {
        random.setSeed(42L);
        for (RenderType type : model.getRenderTypes(state, random, ModelData.EMPTY)) {
            final VertexConsumer buffer = buffers.getBuffer(RenderTypeHelper.getEntityRenderType(type, false));
            random.setSeed(42L);
            for (BakedQuad quad : model.getQuads(state, face, random, ModelData.EMPTY, type)) {
                final int tint = quad.isTinted() ? mc.getBlockColors().getColor(state, mc.level, pos, quad.getTintIndex()) : 0xFFFFFF;
                buffer.putBulkData(pose, quad, (tint >> 16 & 255) / 255.0F, (tint >> 8 & 255) / 255.0F, (tint & 255) / 255.0F, 1.0F, LIGHT,
                        OverlayTexture.NO_OVERLAY);
            }
        }
    }

    /** The marks of the hollow's solid blocks, in the configured look. */
    private static void marks(Minecraft mc, PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 cam, Hollow hollow, Camera camera) {
        final ClientConfig.ConvergenceLook look = ClientConfig.convergenceLook();
        if (look == ClientConfig.ConvergenceLook.NONE) {
            return;
        }
        final RenderType type = look == ClientConfig.ConvergenceLook.OUTLINE ? RenderType.lines() : RenderType.debugQuads();
        final VertexConsumer buffer = buffers.getBuffer(type);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final Vector3f left = camera.getLeftVector(), up = camera.getUpVector();
        for (int x = hollow.minX(); x <= hollow.maxX(); x++) {
            for (int y = hollow.minY(); y <= hollow.maxY(); y++) {
                for (int z = hollow.minZ(); z <= hollow.maxZ(); z++) {
                    if (!solid(mc, pos.set(x, y, z))) {
                        continue;
                    }
                    if (look == ClientConfig.ConvergenceLook.OUTLINE) {
                        LevelRenderer.renderLineBox(pose, buffer, new AABB(pos).move(-cam.x, -cam.y, -cam.z).deflate(0.002), 0.85F, 0.8F, 0.95F, 0.22F);
                        continue;
                    }
                    pose.pushPose();
                    pose.translate(x - cam.x, y - cam.y, z - cam.z);
                    final Matrix4f m = pose.last().pose();
                    switch (look) {
                        case PIXEL_EDGES -> edges(buffer, m, left, up);
                        case CORNERS -> corners(buffer, m, left, up);
                        case DITHER -> dither(buffer, m, 0x55 << 24 | mc.level.getBlockState(pos).getMapColor(mc.level, pos).col);
                        default -> {
                        }
                    }
                    pose.popPose();
                }
            }
        }
        buffers.endBatch(type);
    }

    /** The twelve edges, dotted: a pixel every eighth of a block. */
    private static void edges(VertexConsumer q, Matrix4f m, Vector3f left, Vector3f up) {
        for (int axis = 0; axis < 3; axis++) {
            for (int a = 0; a <= 1; a++) {
                for (int b = 0; b <= 1; b++) {
                    for (int k = 1; k < 8; k += 2) {
                        final float t = k / 8.0F;
                        final float x = axis == 0 ? t : a, y = axis == 1 ? t : axis == 0 ? a : b, z = axis == 2 ? t : b;
                        mote(q, m, x, y, z, left, up, 0x99 << 24 | MARK);
                    }
                }
            }
        }
    }

    /** At each corner a bracket of three pixels along each of its edges. */
    private static void corners(VertexConsumer q, Matrix4f m, Vector3f left, Vector3f up) {
        for (int c = 0; c < 8; c++) {
            final float cx = c & 1, cy = c >> 1 & 1, cz = c >> 2 & 1;
            for (int k = 0; k < 3; k++) {
                final float d = k * PX * 1.5F;
                mote(q, m, cx == 0 ? d : 1 - d, cy, cz, left, up, 0xCC << 24 | MARK);
                mote(q, m, cx, cy == 0 ? d : 1 - d, cz, left, up, 0xCC << 24 | MARK);
                mote(q, m, cx, cy, cz == 0 ? d : 1 - d, left, up, 0xCC << 24 | MARK);
            }
        }
    }

    /** On each face a faint checker of pixels in the block's colour, a quarter of a block apart. */
    private static void dither(VertexConsumer q, Matrix4f m, int argb) {
        for (Direction face : Direction.values()) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    if ((i + j & 1) == 1) {
                        continue;
                    }
                    final float u = (i + 0.5F) / 4, v = (j + 0.5F) / 4, h = PX;
                    final float[] c = switch (face.getAxis()) {
                        case X -> new float[] {face == Direction.EAST ? 0.998F : 0.002F, u, v};
                        case Y -> new float[] {u, face == Direction.UP ? 0.998F : 0.002F, v};
                        case Z -> new float[] {u, v, face == Direction.SOUTH ? 0.998F : 0.002F};
                    };
                    final float x = c[0], y = c[1], z = c[2];
                    switch (face.getAxis()) {
                        case X -> quad(q, m, x, y - h, z - h, x, y + h, z - h, x, y + h, z + h, x, y - h, z + h, argb);
                        case Y -> quad(q, m, x - h, y, z - h, x + h, y, z - h, x + h, y, z + h, x - h, y, z + h, argb);
                        case Z -> quad(q, m, x - h, y - h, z, x + h, y - h, z, x + h, y + h, z, x - h, y + h, z, argb);
                    }
                }
            }
        }
    }

    /** A small square pixel facing the eye. */
    private static void mote(VertexConsumer q, Matrix4f m, float x, float y, float z, Vector3f left, Vector3f up, int argb) {
        final float h = PX * 0.25F; // a 32nd of a block: small enough not to crowd the eye up close
        final float lx = left.x() * h, ly = left.y() * h, lz = left.z() * h, ux = up.x() * h, uy = up.y() * h, uz = up.z() * h;
        quad(q, m, x - lx - ux, y - ly - uy, z - lz - uz, x + lx - ux, y + ly - uy, z + lz - uz, x + lx + ux, y + ly + uy, z + lz + uz,
                x - lx + ux, y - ly + uy, z - lz + uz, argb);
    }

    private static void quad(VertexConsumer q, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3, int argb) {
        q.addVertex(m, x0, y0, z0).setColor(argb);
        q.addVertex(m, x1, y1, z1).setColor(argb);
        q.addVertex(m, x2, y2, z2).setColor(argb);
        q.addVertex(m, x3, y3, z3).setColor(argb);
    }
}
