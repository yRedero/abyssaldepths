package redero.abyssaldepths.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * The sky of the trials: black, all round; white static flickering in it (squares coming and going in steps),
 * and large fragments, white or grey, drifting slowly as if the world were still breaking far off. No sun,
 * no clouds.
 */
public final class FractureSky extends DimensionSpecialEffects {
    private static final float RADIUS = 100.0F;
    private static final int STATIC = 110, FRAGMENTS = 10;

    public FractureSky() {
        super(Float.NaN, false, SkyType.NONE, false, false);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
        return Vec3.ZERO;
    }

    @Override
    public boolean isFoggyAt(int x, int y) {
        return false;
    }

    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick, com.mojang.blaze3d.vertex.PoseStack pose, double camX, double camY, double camZ,
            Matrix4f modelView, Matrix4f projection) {
        return true;
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
        final java.util.Random random = new java.util.Random(0xF4AC7L);
        final int step = ticks / 12;
        for (int i = 0; i < STATIC; i++) { // static: each square on or off by turns
            final float e = (float) Math.asin(random.nextDouble() * 2 - 1), a = random.nextFloat() * Mth.TWO_PI;
            final boolean on = UndertowRenderer.noise(i, step + random.nextInt(7)) > 0.35F;
            final int shade = random.nextInt(3);
            if (on) {
                spot(b, m, e, a, 0.4F + random.nextFloat() * 0.5F, shade == 0 ? 0x55595F : shade == 1 ? 0x8C8B88 : 0xCDC6BA, 200);
            }
        }
        final float time = (ticks + partialTick) / 20.0F;
        for (int i = 0; i < FRAGMENTS; i++) { // large fragments drifting
            final float e = (float) Math.asin(random.nextDouble() * 1.6 - 0.8), a = random.nextFloat() * Mth.TWO_PI + time * 0.01F * (0.5F + random.nextFloat());
            spot(b, m, e, a, 3.0F + random.nextFloat() * 6.0F, random.nextBoolean() ? 0xA29C93 : 0x3C4049, 30 + random.nextInt(50));
        }
        BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        return true;
    }

    /** A square on the sky facing the eye, at elevation and angle. */
    private static void spot(BufferBuilder b, Matrix4f m, float elevation, float angle, float size, int rgb, int alpha) {
        final float r = RADIUS * 0.98F, c = Mth.cos(elevation);
        final float x = r * c * Mth.cos(angle), y = r * Mth.sin(elevation), z = r * c * Mth.sin(angle);
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
        private Registration() {
        }

        @SubscribeEvent
        public static void register(RegisterDimensionSpecialEffectsEvent event) {
            event.register(ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "fracture"), new FractureSky());
        }
    }
}
