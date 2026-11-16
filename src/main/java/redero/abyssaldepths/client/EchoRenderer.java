package redero.abyssaldepths.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.entity.AbsentBody;

/**
 * An echo of the player on the threshold of their trial: their shape (slim or wide like them) in a pale, see-through
 * white, no skin of their own, so it reads as a memory of them acting, not as them, nor as their dark double.
 */
public class EchoRenderer extends AbsentBodyRenderer {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "textures/entity/trial_echo.png");

    public EchoRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(AbsentBody body) {
        return TEXTURE;
    }

    @Override
    protected RenderType getRenderType(AbsentBody body, boolean bodyVisible, boolean translucent, boolean glowing) {
        return RenderType.entityTranslucent(TEXTURE);
    }
}
