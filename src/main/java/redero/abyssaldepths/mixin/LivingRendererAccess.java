package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;

/** A living renderer's turning of the model (Convergence's layer places its copy of the model the same way). */
@Mixin(LivingEntityRenderer.class)
public interface LivingRendererAccess<T extends LivingEntity> {
    @Invoker("setupRotations")
    void abyssaldepths$setupRotations(T entity, PoseStack pose, float bob, float yBodyRot, float partialTick, float scale);
}
