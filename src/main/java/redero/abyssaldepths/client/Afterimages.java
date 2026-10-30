package redero.abyssaldepths.client;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.artifact.InterludeField;

/**
 * Interlude's afterimages: every few milliseconds (client config) a still copy of a player with an active
 * Interlude is left behind where they were, translucent and tinted, and fades out right away. Everyone
 * sees them, the holder too (in first person only those a little away from the camera).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class Afterimages {
    /** How strong a fresh afterimage is, and its tint (a cold violet). */
    private static final float START_ALPHA = 0.5F;
    private static final int TINT = 0xB8A6FF;
    private static final int MAX_PER_PLAYER = 40;

    /** A captured pose. */
    private record Image(long at, Vec3 pos, float bodyYaw, float headYaw, float pitch, float walkPos, float walkSpeed, Pose pose, float swing) {
    }

    private static final Map<UUID, ArrayDeque<Image>> IMAGES = new HashMap<>();
    private static final Map<UUID, Long> LAST = new HashMap<>();
    private static final Map<UUID, RemotePlayer> GHOSTS = new HashMap<>();
    private static GhostRenderer renderer;
    private static ClientLevel ghostLevel;

    private Afterimages() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null || !ClientConfig.afterimages()) {
            IMAGES.clear();
            return;
        }
        if (level != ghostLevel) {
            GHOSTS.clear();
            IMAGES.clear();
            ghostLevel = level;
        }
        InterludeField.clientPrune(level.getGameTime());
        final long now = Util.getMillis(), fade = ClientConfig.afterimageFadeMillis();
        for (Iterator<ArrayDeque<Image>> it = IMAGES.values().iterator(); it.hasNext();) {
            final ArrayDeque<Image> images = it.next();
            images.removeIf(image -> now - image.at() > fade);
            if (images.isEmpty()) {
                it.remove();
            }
        }
    }

    /** Captures afterimages as often as the config says (checked every frame, so it can be finer than ticks). */
    private static void capture(ClientLevel level, long now) {
        final long interval = ClientConfig.afterimageIntervalMillis();
        for (Player player : level.players()) {
            if (!InterludeField.clientIsActive(player.getId(), level.getGameTime())) {
                continue;
            }
            final UUID id = player.getUUID();
            if (now - LAST.getOrDefault(id, 0L) < interval) {
                continue;
            }
            LAST.put(id, now);
            final ArrayDeque<Image> images = IMAGES.computeIfAbsent(id, k -> new ArrayDeque<>());
            if (images.size() >= MAX_PER_PLAYER) {
                images.removeFirst();
            }
            final float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
            images.add(new Image(now, player.getPosition(partial), player.yBodyRot, player.yHeadRot, player.getXRot(),
                    player.walkAnimation.position(partial), player.walkAnimation.speed(partial), player.getPose(), player.getAttackAnim(partial)));
        }
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || mc.level == null || renderer == null || !ClientConfig.afterimages()) {
            return;
        }
        final long now = Util.getMillis();
        capture(mc.level, now);
        if (IMAGES.isEmpty()) {
            return;
        }
        final Vec3 camera = event.getCamera().getPosition();
        final boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        final PoseStack pose = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final float fade = ClientConfig.afterimageFadeMillis();
        for (Map.Entry<UUID, ArrayDeque<Image>> entry : IMAGES.entrySet()) {
            final Player source = mc.level.getPlayerByUUID(entry.getKey());
            if (source == null) {
                continue;
            }
            final RemotePlayer ghost = GHOSTS.computeIfAbsent(entry.getKey(), k -> new RemotePlayer(mc.level, source.getGameProfile()));
            for (Image image : entry.getValue()) {
                if (firstPerson && source == mc.player && image.pos().add(0, 1.6, 0).distanceToSqr(camera) < 1.5) {
                    continue; // not inside your own head
                }
                final float alpha = START_ALPHA * (1.0F - (now - image.at()) / fade);
                if (alpha <= 0.01F) {
                    continue;
                }
                pose(ghost, image);
                FadingModel.alpha = alpha;
                pose.pushPose();
                pose.translate(image.pos().x - camera.x, image.pos().y - camera.y, image.pos().z - camera.z);
                renderer.render(ghost, image.bodyYaw(), 1.0F, pose, buffers, LightTexture.FULL_BRIGHT);
                pose.popPose();
            }
        }
        buffers.endBatch();
    }

    /** Puts the ghost in the captured pose (current and previous values alike: no interpolation). */
    private static void pose(RemotePlayer ghost, Image image) {
        ghost.setPos(image.pos());
        ghost.yBodyRot = ghost.yBodyRotO = image.bodyYaw();
        ghost.yHeadRot = ghost.yHeadRotO = image.headYaw();
        ghost.setXRot(image.pitch());
        ghost.xRotO = image.pitch();
        ghost.setPose(image.pose());
        // walking animation: position set exactly (update() adds the speed it is given), then the speed
        ghost.walkAnimation.update(image.walkPos() - ghost.walkAnimation.position(), 1.0F);
        ghost.walkAnimation.setSpeed(image.walkSpeed());
        ghost.attackAnim = ghost.oAttackAnim = image.swing();
    }

    // ---- drawing ----------------------------------------------------------------

    /** The player model drawn with the afterimage's alpha and tint. */
    private static final class FadingModel extends PlayerModel<AbstractClientPlayer> {
        static float alpha = 1.0F;

        FadingModel(net.minecraft.client.model.geom.ModelPart root, boolean slim) {
            super(root, slim);
        }

        @Override
        public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay, int colour) {
            super.renderToBuffer(pose, buffer, light, overlay, (int) (alpha * 255) << 24 | TINT);
        }
    }

    /** Draws a ghost: the owner's skin, translucent, no name, no armour or items (a silhouette of them). */
    private static final class GhostRenderer extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        private final PlayerModel<AbstractClientPlayer> wide, slim;

        GhostRenderer(EntityRendererProvider.Context context) {
            super(context, new FadingModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.0F);
            wide = model;
            slim = new FadingModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        }

        @Override
        public void render(AbstractClientPlayer ghost, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
            model = ghost.getSkin().model() == PlayerSkin.Model.SLIM ? slim : wide;
            model.crouching = ghost.isCrouching();
            super.render(ghost, yaw, partialTick, pose, buffers, light);
        }

        @Override
        public ResourceLocation getTextureLocation(AbstractClientPlayer ghost) {
            return ghost.getSkin().texture();
        }

        @Override
        protected RenderType getRenderType(AbstractClientPlayer ghost, boolean bodyVisible, boolean translucent, boolean glowing) {
            return RenderType.entityTranslucent(getTextureLocation(ghost));
        }

        @Override
        protected boolean shouldShowName(AbstractClientPlayer ghost) {
            return false;
        }
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent
        public static void onLayers(EntityRenderersEvent.AddLayers event) {
            renderer = new GhostRenderer(event.getContext());
        }
    }
}
