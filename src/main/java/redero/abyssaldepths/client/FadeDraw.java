package redero.abyssaldepths.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a real player (or creature) through the game's own renderer, in a fade's way: another texture,
 * alpha and tint, without armour, items, cape or name. Their pose and animation are then exactly the usual ones
 * (a renderer of our own animated them wrongly: limbs flung, sitting). The changes are picked up by
 * {@code FadeDrawMixin} and {@code ShadeSkinMixin} while {@link #active}.
 */
public final class FadeDraw {
    /** Set while a player is drawn this way. */
    public static boolean active;
    public static float alpha = 1.0F;
    public static int tint = 0xFFFFFF;
    /** The texture in place of the skin (null: the skin). */
    public static ResourceLocation texture;

    private FadeDraw() {
    }

    /** Draws the player at the origin of the pose (their feet), as the game would at partialTick. */
    static void draw(net.minecraft.world.entity.LivingEntity player, float partialTick, PoseStack pose, MultiBufferSource buffers, float alpha, int tint, ResourceLocation texture) {
        final Minecraft mc = Minecraft.getInstance();
        final EntityRenderer<? super net.minecraft.world.entity.LivingEntity> renderer = mc.getEntityRenderDispatcher().getRenderer(player);
        final int light = mc.getEntityRenderDispatcher().getPackedLightCoords(player, partialTick);
        final float yaw = net.minecraft.util.Mth.lerp(partialTick, player.yRotO, player.getYRot());
        FadeDraw.active = true;
        FadeDraw.alpha = alpha;
        FadeDraw.tint = tint;
        FadeDraw.texture = texture;
        try {
            final Vec3 offset = renderer.getRenderOffset(player, partialTick);
            pose.pushPose();
            pose.translate(offset.x, offset.y, offset.z);
            renderer.render(player, yaw, partialTick, pose, buffers, light);
            pose.popPose();
        } finally {
            FadeDraw.active = false;
            FadeDraw.texture = null;
        }
    }
}
