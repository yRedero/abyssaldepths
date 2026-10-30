package redero.abyssaldepths.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * The inner self's sky: a pastel dream. A dome running from lavender below through a pink glow at the
 * horizon to a soft violet blue above, faint stars twinkling even by day, and soft glowing clouds drifting
 * slowly far below the islands. No sun, no vanilla clouds.
 */
public final class InnerSelfSky extends DimensionSpecialEffects {
    private static final float RADIUS = 100.0F;
    /** Colours of the dome from the bottom (-90 degrees) to the top (+90), every 15 degrees. */
    private static final int[] DOME = {0xB99AD8, 0xC6A6E2, 0xD4B3E8, 0xE6C2E8, 0xF6CFE0, 0xF9D8E4, 0xF2D2EC, 0xE2CBF2, 0xD0C4F4, 0xC2BCF2, 0xB6B4EE, 0xACAEEA, 0xA6AAE8};
    private static final int STARS = 220, CLOUDS = 18;

    public InnerSelfSky() {
        super(Float.NaN, false, SkyType.NONE, false, true);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
        return fogColor;
    }

    @Override
    public boolean isFoggyAt(int x, int y) {
        return false;
    }

    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick, com.mojang.blaze3d.vertex.PoseStack pose, double camX, double camY, double camZ,
            Matrix4f modelView, Matrix4f projection) {
        return true; // none of vanilla's
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelView, Camera camera, Matrix4f projection, boolean foggy, Runnable setupFog) {
        final Matrix4f m = new Matrix4f(modelView);
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        final BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // the dome
        final int segments = 36;
        for (int band = 0; band < DOME.length - 1; band++) {
            final float e0 = (band * 15 - 90) * Mth.DEG_TO_RAD, e1 = ((band + 1) * 15 - 90) * Mth.DEG_TO_RAD;
            for (int s = 0; s < segments; s++) {
                final float a0 = s * Mth.TWO_PI / segments, a1 = (s + 1) * Mth.TWO_PI / segments;
                vertex(b, m, e0, a0, DOME[band], 255);
                vertex(b, m, e0, a1, DOME[band], 255);
                vertex(b, m, e1, a1, DOME[band + 1], 255);
                vertex(b, m, e1, a0, DOME[band + 1], 255);
            }
        }
        final float time = (ticks + partialTick) / 20.0F;
        // faint stars, twinkling, on the upper half
        final java.util.Random random = new java.util.Random(0x5EED1E55L);
        for (int i = 0; i < STARS; i++) {
            final float e = (float) Math.asin(0.15 + 0.85 * random.nextDouble()), a = random.nextFloat() * Mth.TWO_PI;
            final float twinkle = 0.35F + 0.35F * Mth.sin(time * (0.6F + random.nextFloat()) + random.nextFloat() * 10.0F);
            final float size = 0.25F + random.nextFloat() * 0.35F;
            spot(b, m, e, a, size, 0xFFFFFF, (int) (twinkle * 255));
        }
        // soft glowing clouds far below, drifting round
        for (int i = 0; i < CLOUDS; i++) {
            final float e = (-12.0F - random.nextFloat() * 25.0F) * Mth.DEG_TO_RAD;
            final float a = random.nextFloat() * Mth.TWO_PI + time * 0.004F * (0.5F + random.nextFloat());
            final float size = 8.0F + random.nextFloat() * 14.0F;
            for (int layer = 0; layer < 3; layer++) { // a soft blob: three discs, larger and fainter outwards
                spot(b, m, e, a, size * (1.0F + layer * 0.45F), 0xFFF2FA, 70 - layer * 22);
            }
        }
        BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        return true;
    }

    private static void vertex(BufferBuilder b, Matrix4f m, float elevation, float angle, int rgb, int alpha) {
        final float c = Mth.cos(elevation);
        b.addVertex(m, RADIUS * c * Mth.cos(angle), RADIUS * Mth.sin(elevation), RADIUS * c * Mth.sin(angle)).setColor(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, alpha);
    }

    /** A small square on the sky facing the eye, at elevation and angle. */
    private static void spot(BufferBuilder b, Matrix4f m, float elevation, float angle, float size, int rgb, int alpha) {
        final float r = RADIUS * 0.98F, c = Mth.cos(elevation);
        final float x = r * c * Mth.cos(angle), y = r * Mth.sin(elevation), z = r * c * Mth.sin(angle);
        // two axes across the direction to it
        final float ux = -Mth.sin(angle), uz = Mth.cos(angle);
        final float vx = -Mth.sin(elevation) * Mth.cos(angle), vy = c, vz = -Mth.sin(elevation) * Mth.sin(angle);
        final int red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
        b.addVertex(m, x - ux * size - vx * size, y - vy * size, z - uz * size - vz * size).setColor(red, green, blue, alpha);
        b.addVertex(m, x + ux * size - vx * size, y - vy * size, z + uz * size - vz * size).setColor(red, green, blue, alpha);
        b.addVertex(m, x + ux * size + vx * size, y + vy * size, z + uz * size + vz * size).setColor(red, green, blue, alpha);
        b.addVertex(m, x - ux * size + vx * size, y + vy * size, z - uz * size + vz * size).setColor(red, green, blue, alpha);
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Registration {
        @SubscribeEvent
        public static void register(RegisterDimensionSpecialEffectsEvent event) {
            event.register(ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "inner_self"), new InnerSelfSky());
        }
    }
}
