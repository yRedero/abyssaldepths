package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import redero.abyssaldepths.client.FadeDraw;

/** While {@link FadeDraw} draws a player: their colour in its alpha and tint, no layers, no name. */
@Mixin(LivingEntityRenderer.class)
public abstract class FadeDrawMixin {
    @ModifyArg(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
            index = 4)
    private int abyssaldepths$fadeColour(int colour) {
        return FadeDraw.active ? (int) (FadeDraw.alpha * 255) << 24 | FadeDraw.tint : colour;
    }

    @Redirect(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"))
    private void abyssaldepths$fadeNoLayers(RenderLayer<LivingEntity, ?> layer, PoseStack pose, MultiBufferSource buffers, int light, Entity entity,
            float a, float b, float c, float d, float e, float f) {
        if (!FadeDraw.active) {
            layer.render(pose, buffers, light, (LivingEntity) entity, a, b, c, d, e, f);
        }
    }

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$fadeNoName(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (FadeDraw.active) {
            cir.setReturnValue(false);
        }
    }
}
