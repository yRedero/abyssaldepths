package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import redero.abyssaldepths.client.FadeDraw;
import redero.abyssaldepths.client.ShadeView;

/** In the shadow, shades wear their skin in the shadow's look ({@link ShadeView#shadeSkin}): body and hand. */
@Mixin(PlayerRenderer.class)
public abstract class ShadeSkinMixin {
    @Inject(method = "getTextureLocation(Lnet/minecraft/client/player/AbstractClientPlayer;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$shadeSkin(AbstractClientPlayer player, CallbackInfoReturnable<ResourceLocation> cir) {
        if (FadeDraw.active && FadeDraw.texture != null) {
            cir.setReturnValue(FadeDraw.texture); // a fade's texture (the look, a crumbling copy)
            return;
        }
        final ResourceLocation skin = ShadeView.shadeSkin(player);
        if (skin != null) {
            cir.setReturnValue(skin);
        }
    }

    @Redirect(method = "renderHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/PlayerSkin;texture()Lnet/minecraft/resources/ResourceLocation;"))
    private ResourceLocation abyssaldepths$shadeHand(PlayerSkin skin, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
            int light, AbstractClientPlayer player, net.minecraft.client.model.geom.ModelPart arm, net.minecraft.client.model.geom.ModelPart sleeve) {
        final ResourceLocation look = ShadeView.shadeSkin(player);
        return look != null ? look : skin.texture();
    }
}
