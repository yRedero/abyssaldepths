package redero.abyssaldepths.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.artifact.ConvergenceItem;
import redero.abyssaldepths.mixin.LivingRendererAccess;

/**
 * How everyone sees a player phasing with Convergence ({@link ClientConfig.ConvergenceLayerFit}): their skin's own
 * pixels tinted, or an extra layer over them, a little out from their skin,
 * a see-through tint going from purple at the head to grey-black at the feet; a faint, soft lighter band
 * glides down it from head to feet again and again. When Convergence is switched on the layer comes on pixel
 * by pixel, and goes the same way when it is switched off, in the configured style
 * ({@link ClientConfig.ConvergenceApply}): pixels lighting up at random, like Parallax's dissolve (each
 * flaring light as it comes), sweeping down from the head, rising from the feet, or in flickering blocks.
 * The layer is the player's own model drawn once more with a texture made here every frame, one for each
 * player showing it: each pixel of the skin's layout knows how high on the body it lies ({@link #HEIGHT}),
 * so the band runs down the body whatever the pose.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class ConvergenceLayer {
    /** Ticks the layer takes to come on or go. */
    private static final float APPLY_TICKS = 16.0F;
    /** The tint at the head and at the feet, and the band's lighter colour (RGB); their alphas. */
    private static final int TOP = 0x3D2659, BOTTOM = 0x1E1A24, LIGHT = 0x9C86C4;
    private static final float ALPHA = 0.5F, BAND_ALPHA = 0.18F;
    /** The band: half its height in model pixels, and model pixels it glides a tick (32 down, then a pause). */
    private static final float BAND_HALF = 3.0F, SPEED = 0.45F;
    /** Per pixel of the 64 x 64 skin layout: how many model pixels below the top of the head it lies (-1: unused). */
    private static final int[] HEIGHT = heights();

    /** A player showing the layer (or with it coming or going): their texture, and when it came on or began to go. */
    private static final class Shown {
        final ResourceLocation id;
        final DynamicTexture texture = new DynamicTexture(64, 64, true);
        float on, off = -1.0F, painted = Float.NaN;

        Shown(int entityId) {
            id = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "convergence_layer/" + entityId);
            Minecraft.getInstance().getTextureManager().register(id, texture);
        }

        /** How far the layer is on: 0 .. 1. */
        float progress(float time) {
            if (ClientConfig.convergenceApply() == ClientConfig.ConvergenceApply.NONE) {
                return off < 0 ? 1.0F : 0.0F;
            }
            return Mth.clamp(off < 0 ? (time - on) / APPLY_TICKS : 1.0F - (time - off) / APPLY_TICKS, 0.0F, 1.0F);
        }
    }

    private static final java.util.Map<Integer, Shown> SHOWN = new java.util.HashMap<>();

    private ConvergenceLayer() {
    }

    /** Marks the pixels of one box of the skin layout (at u, v; w x h x d) whose top is {@code top} pixels down. */
    private static void box(int[] heights, int u, int v, int w, int h, int d, int top) {
        for (int x = u + d; x < u + d + 2 * w && x < 64; x++) { // its top and bottom faces
            for (int y = v; y < v + d && y < 64; y++) {
                heights[y * 64 + x] = x < u + d + w ? top : top + h - 1;
            }
        }
        for (int x = u; x < u + 2 * (w + d) && x < 64; x++) { // its sides, row by row
            for (int y = v + d; y < v + d + h && y < 64; y++) {
                heights[y * 64 + x] = top + y - (v + d);
            }
        }
    }

    private static int[] heights() {
        final int[] heights = new int[64 * 64];
        java.util.Arrays.fill(heights, -1);
        for (int layer = 0; layer <= 16; layer += 16) { // the skin, then its outer layer
            box(heights, layer * 2, 0, 8, 8, 8, 0); // head, hat
            box(heights, 16, 16 + layer, 8, 12, 4, 8); // body, jacket
            box(heights, 40, 16 + layer, 4, 12, 4, 8); // right arm, sleeve
            box(heights, 0, 16 + layer, 4, 12, 4, 20); // right leg, trousers
        }
        box(heights, 32, 48, 4, 12, 4, 8); // left arm
        box(heights, 48, 48, 4, 12, 4, 8); // its sleeve
        box(heights, 16, 48, 4, 12, 4, 20); // left leg
        box(heights, 0, 48, 4, 12, 4, 20); // its trousers
        return heights;
    }

    /** Starts and ends the players' layers as they switch Convergence on and off; forgets them when gone. */
    @SubscribeEvent
    public static void onTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            SHOWN.values().forEach(shown -> mc.getTextureManager().release(shown.id));
            SHOWN.clear();
            return;
        }
        final float now = mc.level.getGameTime();
        for (net.minecraft.world.entity.player.Player player : mc.level.players()) {
            final boolean shows = ConvergenceItem.clientShows(player);
            Shown shown = SHOWN.get(player.getId());
            if (shows && (shown == null || shown.off >= 0)) { // coming on (from where it was, if it was going)
                final float from = shown == null ? 0.0F : shown.progress(now);
                if (shown == null) {
                    shown = new Shown(player.getId());
                    SHOWN.put(player.getId(), shown);
                }
                shown.on = now - from * APPLY_TICKS;
                shown.off = -1.0F;
            } else if (!shows && shown != null && shown.off < 0) { // going
                shown.off = now - (1.0F - shown.progress(now)) * APPLY_TICKS;
            }
        }
        SHOWN.entrySet().removeIf(entry -> {
            final Shown shown = entry.getValue();
            if (mc.level.getEntity(entry.getKey()) == null || shown.off >= 0 && now - shown.off > APPLY_TICKS + 1) {
                mc.getTextureManager().release(shown.id);
                return true;
            }
            return false;
        });
    }

    /** When, from 0 to 1 of the layer coming on, this pixel comes (it goes again in the same order, backwards). */
    private static float when(int i, int h, ClientConfig.ConvergenceApply style) {
        final float down = (h + 0.5F) / 32.0F, n = UndertowRenderer.noise(i, 19);
        return 0.04F + 0.86F * switch (style) {
            case DISSOLVE, NONE -> 0.6F * n + 0.4F * down;
            case SWEEP -> 0.88F * down + 0.12F * n;
            case RISE -> 0.88F * (1.0F - down) + 0.12F * n;
            case GLITCH -> UndertowRenderer.noise((i % 64) / 4 * 16 + i / 64 / 4, 23); // in blocks of 4 x 4
        };
    }

    /** The player's texture for this moment: the tint, the band at its place, as much of it as has come on. */
    private static ResourceLocation paint(Shown shown, float time, ResourceLocation skinTexture) {
        if (time == shown.painted) {
            return shown.id;
        }
        shown.painted = time;
        // tinting the skin itself: its pixels, if they can be read (else the layer over it)
        final NativeImage skin = ClientConfig.convergenceLayer() == ClientConfig.ConvergenceLayerFit.SKIN ? SkinPixels.read(skinTexture) : null;
        final boolean onSkin = skin != null && skin.getWidth() == 64 && skin.getHeight() == 64;
        final float progress = shown.progress(time);
        final ClientConfig.ConvergenceApply style = ClientConfig.convergenceApply();
        final float band = (time * SPEED) % 48.0F - 8.0F; // where its middle is, in model pixels down
        final NativeImage image = shown.texture.getPixels();
        for (int i = 0; i < HEIGHT.length; i++) {
            final int h = HEIGHT[i];
            int argb = 0;
            final float lead = h >= 0 ? progress - when(i, h, style) : -1.0F; // how long since this pixel came on (in shares)
            final boolean flicker = style == ClientConfig.ConvergenceApply.GLITCH && progress < 1.0F && Math.abs(lead) < 0.15F
                    && UndertowRenderer.noise(i / 4, (int) time) > 0.5F;
            if (h >= 0 && (progress >= 1.0F || lead >= 0.0F != flicker)) {
                if (progress < 1.0F && lead >= 0.0F && lead < 0.07F) {
                    argb = 0xD8000000 | LIGHT; // just come on (or about to go): flaring light
                    image.setPixelRGBA(i % 64, i / 64, abgr(argb));
                    continue;
                }
                final float down = (h + 0.5F) / 32.0F, d = (h + 0.5F - band) / BAND_HALF;
                final float glow = d * d < 1.0F ? (1.0F - d * d) * (1.0F - d * d) : 0.0F; // soft at both ends
                final int tint = lerp(TOP, BOTTOM, down);
                if (onSkin) { // the skin's pixel, its own alpha, coloured over and lit by the band
                    final int own = SkinPixels.fromAbgr(skin.getPixelRGBA(i % 64, i / 64));
                    final int alpha = skin.getPixelRGBA(i % 64, i / 64) >>> 24;
                    argb = alpha == 0 ? 0 : alpha << 24 | lerp(lerp(own & 0xFFFFFF, tint, 0.62F), LIGHT, glow * 0.4F);
                } else {
                    argb = (int) ((ALPHA + BAND_ALPHA * glow) * 255) << 24 | lerp(tint, LIGHT, glow * 0.55F);
                }
            }
            image.setPixelRGBA(i % 64, i / 64, abgr(argb));
        }
        shown.texture.upload();
        return shown.id;
    }

    private static int lerp(int a, int b, float t) {
        final int r = (int) Mth.lerp(t, a >> 16 & 255, b >> 16 & 255), g = (int) Mth.lerp(t, a >> 8 & 255, b >> 8 & 255), bl = (int) Mth.lerp(t, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    private static int abgr(int argb) {
        return argb & 0xFF00FF00 | (argb & 0xFF) << 16 | argb >> 16 & 0xFF;
    }

    @SubscribeEvent
    public static void onRender(RenderLivingEvent.Post<?, ?> event) {
        final Shown shown = SHOWN.get(event.getEntity().getId());
        if (shown == null || !(event.getEntity() instanceof AbstractClientPlayer player) || !(event.getRenderer().getModel() instanceof PlayerModel<?> model)
                || ShadeView.hidden(player)) {
            return;
        }
        final float partial = event.getPartialTick();
        if (shown.progress(player.level().getGameTime() + partial) <= 0.0F) {
            return;
        }
        final ResourceLocation layer = paint(shown, player.level().getGameTime() + partial, player.getSkin().texture());
        final PoseStack pose = event.getPoseStack();
        pose.pushPose();
        // the renderer's own placing of the model (it is still posed as it was just drawn)
        final float scale = player.getScale();
        pose.scale(scale, scale, scale);
        @SuppressWarnings("unchecked")
        final LivingRendererAccess<AbstractClientPlayer> renderer = (LivingRendererAccess<AbstractClientPlayer>) (LivingEntityRenderer<?, ?>) event.getRenderer();
        renderer.abyssaldepths$setupRotations(player, pose, player.tickCount + partial, Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot), partial, scale);
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.scale(0.9375F, 0.9375F, 0.9375F); // a player's size
        pose.translate(0.0F, -1.501F, 0.0F);
        // a little out from the body, round its middle; the tinted skin only a hair out (over the skin, not flickering with it)
        final boolean over = ClientConfig.convergenceLayer() == ClientConfig.ConvergenceLayerFit.OVER;
        pose.translate(0.0F, 0.75F, 0.0F);
        pose.scale(over ? 1.05F : 1.004F, over ? 1.03F : 1.003F, over ? 1.05F : 1.004F);
        pose.translate(0.0F, -0.75F, 0.0F);
        model.renderToBuffer(pose, event.getMultiBufferSource().getBuffer(RenderType.entityTranslucent(layer)), event.getPackedLight(), OverlayTexture.NO_OVERLAY,
                -1);
        pose.popPose();
    }
}
