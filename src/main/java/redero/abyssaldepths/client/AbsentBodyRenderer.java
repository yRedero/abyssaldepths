package redero.abyssaldepths.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.entity.AbsentBody;

/**
 * Draws an {@link AbsentBody} as its owner: the player model with their skin (slim or wide arms like
 * theirs), their armour and held items, bobbing very slowly; no name tag.
 */
public class AbsentBodyRenderer extends LivingEntityRenderer<AbsentBody, PlayerModel<AbsentBody>> {
    private final PlayerModel<AbsentBody> wide, slim;

    public AbsentBodyRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.4F);
        wide = model;
        slim = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        addLayer(new HumanoidArmorLayer<>(this, new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    private static PlayerSkin skin(AbsentBody body) {
        final var connection = Minecraft.getInstance().getConnection();
        final PlayerInfo info = connection == null ? null : body.owner().map(connection::getPlayerInfo).orElse(null);
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(body.owner().orElse(body.getUUID()));
    }

    @Override
    public void render(AbsentBody body, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        model = skin(body).model() == PlayerSkin.Model.SLIM ? slim : wide;
        pose.pushPose();
        pose.translate(0.0, 0.04 * Mth.sin((body.tickCount + partialTick) * 0.05F), 0.0); // a slow, faint bob
        super.render(body, yaw, partialTick, pose, buffers, light);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(AbsentBody body) {
        return skin(body).texture();
    }

    @Override
    protected boolean shouldShowName(AbsentBody body) {
        return false;
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Registration {
        @SubscribeEvent
        public static void register(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(AbyssalDepths.ABSENT_BODY.get(), AbsentBodyRenderer::new);
            event.registerEntityRenderer(AbyssalDepths.SEAT.get(), net.minecraft.client.renderer.entity.NoopRenderer::new); // the mat's seat: nothing to see
        }
    }
}
