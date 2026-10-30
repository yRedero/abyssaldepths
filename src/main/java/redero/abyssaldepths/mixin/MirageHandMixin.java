package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
import redero.abyssaldepths.client.MirageClient;

/** Under a Mirage, the first person arm is translucent and purple too, as the body is for everyone. */
@Mixin(PlayerRenderer.class)
public abstract class MirageHandMixin {
    @Redirect(method = "renderHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderType;entitySolid(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"))
    private RenderType abyssaldepths$mirageArmType(ResourceLocation texture) {
        return MirageClient.ownActive() ? RenderType.entityTranslucent(texture) : RenderType.entitySolid(texture);
    }

    @Redirect(method = "renderHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"))
    private void abyssaldepths$mirageArm(ModelPart part, PoseStack pose, VertexConsumer buffer, int light, int overlay) {
        if (MirageClient.ownActive()) {
            part.render(pose, buffer, light, overlay, MirageClient.colour());
        } else {
            part.render(pose, buffer, light, overlay);
        }
    }
}
