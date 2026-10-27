package redero.abyssaldepths.client;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Matrix4f;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Dark fog in the thalan layer, so the huge halls fade into black instead of ending at the edge of
 * the rendered chunks with the sky showing through. Fades in between y=-60 and y=-70; the sky is
 * fully fogged on the way and not drawn at all once the fog is complete.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class ThalanFog {
    private static final double FADE_TOP = -60.0, FADE_BOTTOM = -70.0;
    /** Fog starts / is opaque at this share of the render distance. */
    private static final float NEAR = 0.35F, FAR = 0.9F;
    private static final float RED = 0.035F, GREEN = 0.035F, BLUE = 0.045F;
    private static final float WARM_RED = 0.11F, WARM_GREEN = 0.05F, WARM_BLUE = 0.032F;

    private ThalanFog() {
    }

    /** 0 above the layer, 1 inside it. */
    static float amount(Camera camera) {
        final double t = (FADE_TOP - camera.getPosition().y) / (FADE_TOP - FADE_BOTTOM);
        return (float) Math.max(0.0, Math.min(1.0, t));
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        final float t = amount(event.getCamera());
        if (t <= 0.0F || event.getType() != FogType.NONE) {
            return; // water, lava and powder snow keep their own fog
        }
        final boolean sky = event.getMode() == FogRenderer.FogMode.FOG_SKY;
        final float distance = event.getRenderer().getRenderDistance();
        final float near = sky ? 0.0F : NEAR * distance, far = sky ? 0.0F : FAR * distance;
        // never thinner than vanilla (blindness, darkness)
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), lerp(t, event.getNearPlaneDistance(), near)));
        event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), lerp(t, event.getFarPlaneDistance(), far)));
        event.setFogShape(FogShape.SPHERE);
        event.setCanceled(true); // required for the changes to apply
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        final float t = amount(event.getCamera());
        if (t > 0.0F) {
            // the thermal reaches and the air around heat sources fade into a warm reddish brown
            final float warm = t * ThermalClient.warmth();
            event.setRed(lerp(warm, lerp(t, event.getRed(), RED), WARM_RED));
            event.setGreen(lerp(warm, lerp(t, event.getGreen(), GREEN), WARM_GREEN));
            event.setBlue(lerp(warm, lerp(t, event.getBlue(), BLUE), WARM_BLUE));
        }
    }

    private static float lerp(float t, float a, float b) {
        return a + (b - a) * t;
    }

    /** Overworld sky and clouds, skipped while the camera is fully inside the fog. */
    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Sky extends DimensionSpecialEffects.OverworldEffects {
        @SubscribeEvent
        public static void register(RegisterDimensionSpecialEffectsEvent event) {
            event.register(ResourceLocation.withDefaultNamespace("overworld"), new Sky());
        }

        @Override
        public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix, Camera camera,
                Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
            return amount(camera) >= 1.0F; // true = handled, vanilla draws nothing
        }

        @Override
        public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, double camX,
                double camY, double camZ, Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
            return amount(Minecraft.getInstance().gameRenderer.getMainCamera()) >= 1.0F;
        }
    }
}
