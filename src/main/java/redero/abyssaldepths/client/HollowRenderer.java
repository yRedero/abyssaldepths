package redero.abyssaldepths.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.resources.PlayerSkin;
import redero.abyssaldepths.fracture.Hollow;

import com.mojang.blaze3d.vertex.PoseStack;

/** A Hollow drawn as the player it was: their model and skin (the corruption's look laid over by {@link FractureView}). */
public class HollowRenderer extends HumanoidMobRenderer<Hollow, PlayerModel<Hollow>> {
    private final PlayerModel<Hollow> wide, slim;

    public HollowRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        wide = model;
        slim = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
    }

    private static PlayerSkin skin(Hollow hollow) {
        final var connection = Minecraft.getInstance().getConnection();
        final PlayerInfo info = connection == null ? null : hollow.owner().map(connection::getPlayerInfo).orElse(null);
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(hollow.owner().orElse(hollow.getUUID()));
    }

    @Override
    public void render(Hollow hollow, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        model = skin(hollow).model() == PlayerSkin.Model.SLIM ? slim : wide;
        super.render(hollow, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(Hollow hollow) {
        return skin(hollow).texture();
    }
}
