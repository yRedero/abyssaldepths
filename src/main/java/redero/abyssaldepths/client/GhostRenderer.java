package redero.abyssaldepths.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Draws a player as a ghost: their skin, translucent and tinted, fully lit, without name, armour or items
 * (a silhouette of them). Used by Interlude's afterimages and by shades vanishing ({@link ShadeVanish}).
 */
final class GhostRenderer extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private static GhostRenderer instance;
    /** Alpha and tint of what is drawn now (read while the model is put into the buffer). */
    private static float alpha = 1.0F;
    private static int tint = 0xFFFFFF;

    private final PlayerModel<AbstractClientPlayer> wide, slim;

    private GhostRenderer(EntityRendererProvider.Context context) {
        super(context, new FadingModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.0F);
        wide = model;
        slim = new FadingModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
    }

    static boolean ready() {
        return instance != null;
    }

    /** Draws the player at the origin of the pose, with this alpha (0 .. 1) and tint (RGB). */
    static void draw(AbstractClientPlayer player, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, float alpha, int tint) {
        if (instance == null) {
            return;
        }
        GhostRenderer.alpha = alpha;
        GhostRenderer.tint = tint;
        instance.render(player, yaw, partialTick, pose, buffers, LightTexture.FULL_BRIGHT);
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

    /** The player model drawn with the current alpha and tint. */
    private static final class FadingModel extends PlayerModel<AbstractClientPlayer> {
        FadingModel(ModelPart root, boolean slim) {
            super(root, slim);
        }

        @Override
        public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay, int colour) {
            super.renderToBuffer(pose, buffer, light, overlay, (int) (alpha * 255) << 24 | tint);
        }
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent
        public static void onLayers(EntityRenderersEvent.AddLayers event) {
            instance = new GhostRenderer(event.getContext());
        }
    }
}
