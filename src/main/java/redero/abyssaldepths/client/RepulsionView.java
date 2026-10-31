package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;

/**
 * Repulsion's pressure wave as this client sees it, in the configured look ({@link ClientConfig.RepulsionWave}):
 * a ring of pixels running out over the ground, a dome of pixels swelling, three thin rings one after
 * another, rays of pixels shooting out, or the game's own gusts. Pale teal in steps, out to the wave's
 * reach in {@link #TICKS}, fading as it goes. The server sends where and how far ({@code repulsion/...}).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class RepulsionView {
    /** The wave's colours, dark to light. */
    private static final int[] TEALS = {0x426C78, 0x789AA1, 0xB4CED0, 0xF0FAFA};
    private static final float PX = 0.125F;
    private static final int TICKS = 12;

    private record Wave(Vec3 at, float radius, long start) {
    }

    private static final List<Wave> WAVES = new ArrayList<>();

    private RepulsionView() {
    }

    /** A wave starts at the feet {@code at}, out to {@code radius} (from the server). */
    public static void start(Vec3 at, float radius) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (ClientConfig.repulsionWave() == ClientConfig.RepulsionWave.GUST) {
            mc.level.addParticle(ParticleTypes.GUST_EMITTER_SMALL, at.x, at.y + 0.5, at.z, 0, 0, 0);
            for (int i = 0; i < 48; i++) {
                final float a = i * Mth.TWO_PI / 48;
                mc.level.addParticle(ParticleTypes.CLOUD, at.x + Mth.cos(a) * 0.8, at.y + 0.4, at.z + Mth.sin(a) * 0.8, Mth.cos(a) * radius * 0.09, 0.002,
                        Mth.sin(a) * radius * 0.09);
            }
            return;
        }
        WAVES.add(new Wave(at, radius, mc.level.getGameTime()));
    }

    private record Draw(VertexConsumer q, Matrix4f m, Vector3f left, Vector3f up, float t, float radius) {
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || WAVES.isEmpty() || mc.level == null) {
            return;
        }
        final float now = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        WAVES.removeIf(wave -> now - wave.start() > TICKS + 8 || now < wave.start() - 1);
        final Vec3 cam = event.getCamera().getPosition();
        final PoseStack pose = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        final ClientConfig.RepulsionWave look = ClientConfig.repulsionWave();
        for (Wave wave : WAVES) {
            pose.pushPose();
            pose.translate(wave.at().x - cam.x, wave.at().y - cam.y, wave.at().z - cam.z);
            final Draw d = new Draw(quads, pose.last().pose(), event.getCamera().getLeftVector(), event.getCamera().getUpVector(), now - wave.start(), wave.radius());
            switch (look) {
                case RING -> ring(d, d.t(), 3);
                case SHOCK -> {
                    for (int k = 0; k < 3; k++) {
                        ring(d, d.t() - k * 4, 1);
                    }
                }
                case DOME -> dome(d);
                case BURST -> burst(d);
                case GUST -> {
                }
            }
            pose.popPose();
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    /** How far out the wave is at this tick: fast at first, slowing (0 .. 1). */
    private static float reach(float t) {
        final float x = Mth.clamp(t / TICKS, 0.0F, 1.0F);
        return 1.0F - (1.0F - x) * (1.0F - x);
    }

    private static int colour(int step, float alpha) {
        return (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255) << 24 | TEALS[Mth.clamp(step, 0, 3)];
    }

    /** A ring of flat pixels on the ground, {@code rows} deep, its leading row brightest, breaking up as it fades. */
    private static void ring(Draw d, float t, int rows) {
        if (t < 0 || t > TICKS) {
            return;
        }
        final float r = 0.6F + (d.radius() - 0.6F) * reach(t), fade = 1.0F - t / TICKS;
        for (int row = 0; row < rows; row++) {
            final float rr = r - row * PX;
            if (rr <= 0.1F) {
                continue;
            }
            final int count = (int) (Mth.TWO_PI * rr / PX);
            for (int i = 0; i < count; i++) {
                if (UndertowRenderer.noise(i * 13 + row, (int) t) > fade + 0.25F) {
                    continue;
                }
                final float a = Mth.TWO_PI * i / count;
                flat(d, Mth.cos(a) * rr, 0.05F, Mth.sin(a) * rr, colour(3 - row, 0.3F + 0.7F * fade));
            }
        }
    }

    /** A dome of pixels swelling out to the reach, thinning as it goes. */
    private static void dome(Draw d) {
        if (d.t() > TICKS) {
            return;
        }
        final float r = 0.6F + (d.radius() - 0.6F) * reach(d.t()), fade = 1.0F - d.t() / TICKS;
        final int rings = Math.max(3, (int) (Mth.HALF_PI * r / (PX * 2.5F)));
        for (int i = 0; i < rings; i++) {
            final float lat = Mth.HALF_PI * i / rings;
            final int count = Math.max(4, (int) (Mth.TWO_PI * r * Mth.cos(lat) / (PX * 2.5F)));
            for (int j = 0; j < count; j++) {
                if (UndertowRenderer.noise(i * 97 + j, (int) d.t()) > fade * 0.8F + 0.1F) {
                    continue;
                }
                final float lon = Mth.TWO_PI * j / count + i * 0.5F;
                mote(d, Mth.cos(lat) * Mth.cos(lon) * r, Mth.sin(lat) * r + 0.05F, Mth.cos(lat) * Mth.sin(lon) * r,
                        colour(i < rings / 3 ? 3 : 2, 0.25F + 0.6F * fade));
            }
        }
    }

    /** Rays of pixels shooting out level with the waist, each a streak brightest at its head. */
    private static void burst(Draw d) {
        if (d.t() > TICKS) {
            return;
        }
        final float fade = 1.0F - d.t() / TICKS;
        for (int ray = 0; ray < 28; ray++) {
            final float a = Mth.TWO_PI * ray / 28 + UndertowRenderer.noise(ray, 5) * 0.2F;
            final float head = (0.6F + (d.radius() - 0.6F) * reach(d.t())) * (0.75F + 0.25F * UndertowRenderer.noise(ray, 6));
            final float y = 0.3F + UndertowRenderer.noise(ray, 7) * 1.2F;
            for (int k = 0; k < 5; k++) {
                final float r = head - k * PX * 1.5F;
                if (r > 0.3F) {
                    mote(d, Mth.cos(a) * r, y, Mth.sin(a) * r, colour(3 - k, (0.4F + 0.6F * fade) * (1.0F - k * 0.15F)));
                }
            }
        }
    }

    private static void mote(Draw d, float x, float y, float z, int argb) {
        final float h = PX * 0.35F; // a little under a pixel: they pass close by the eye
        final Vector3f l = d.left(), u = d.up();
        final float lx = l.x() * h, ly = l.y() * h, lz = l.z() * h, ux = u.x() * h, uy = u.y() * h, uz = u.z() * h;
        d.q().addVertex(d.m(), x - lx - ux, y - ly - uy, z - lz - uz).setColor(argb);
        d.q().addVertex(d.m(), x + lx - ux, y + ly - uy, z + lz - uz).setColor(argb);
        d.q().addVertex(d.m(), x + lx + ux, y + ly + uy, z + lz + uz).setColor(argb);
        d.q().addVertex(d.m(), x - lx + ux, y - ly + uy, z - lz + uz).setColor(argb);
    }

    private static void flat(Draw d, float x, float y, float z, int argb) {
        final float h = PX * 0.5F;
        d.q().addVertex(d.m(), x - h, y, z - h).setColor(argb);
        d.q().addVertex(d.m(), x - h, y, z + h).setColor(argb);
        d.q().addVertex(d.m(), x + h, y, z + h).setColor(argb);
        d.q().addVertex(d.m(), x + h, y, z - h).setColor(argb);
    }
}
