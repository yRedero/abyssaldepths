package redero.abyssaldepths.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.artifact.AegisItem;

/**
 * Aegis's force field as everyone sees it, in the configured look ({@link ClientConfig.AegisLook}): a shell
 * of square pixels thick at its rim and flickering, rings of pixels stacked up it with a band of light
 * running up, glyphs circling over a ring on the ground, pixel shards drifting round on it, or a smooth
 * bubble with a fine grid. Pale blue in steps; its holder, inside in first person, sees it fainter.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class AegisView {
    /** The field's blues, dark to light. */
    private static final int[] BLUES = {0x2F6FA8, 0x5AA8E0, 0x9FD8FF, 0xE8F7FF};
    private static final float PX = 0.125F;
    private static final int RINGS = 18, SEGMENTS = 32;

    private AegisView() {
    }

    /** What a look needs to draw one field. */
    private record Field(VertexConsumer q, Matrix4f m, float radius, Vec3 fromEye, float time, float fade, Vector3f left, Vector3f up) {
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.level == null) {
            return;
        }
        final Vec3 cam = event.getCamera().getPosition();
        final float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        final float time = mc.level.getGameTime() + partial;
        final ClientConfig.AegisLook look = ClientConfig.aegisLook();
        MultiBufferSource.BufferSource buffers = null;
        for (Player player : mc.level.players()) {
            if (!AegisItem.clientHolds(player) || ShadeView.hidden(player)) {
                continue;
            }
            if (buffers == null) {
                buffers = mc.renderBuffers().bufferSource();
            }
            final Vec3 centre = player.getPosition(partial).add(0, player.getBbHeight() * 0.5, 0);
            final float fade = player == mc.player && mc.options.getCameraType().isFirstPerson() ? 0.45F : 1.0F;
            final PoseStack pose = event.getPoseStack();
            pose.pushPose();
            pose.translate(centre.x - cam.x, centre.y - cam.y, centre.z - cam.z);
            final Field field = new Field(buffers.getBuffer(RenderType.debugQuads()), pose.last().pose(), AegisItem.clientRadius(player), centre.subtract(cam),
                    time, fade, event.getCamera().getLeftVector(), event.getCamera().getUpVector());
            switch (look) {
                case PIXELS -> pixels(field);
                case RINGS -> rings(field);
                case RUNES -> runes(field, (float) (player.getY() - centre.y));
                case SHARDS -> shards(field);
                case BUBBLE -> {
                    bubble(field);
                    grid(buffers.getBuffer(RenderType.lines()), pose.last(), field);
                }
            }
            pose.popPose();
        }
        if (buffers != null) {
            buffers.endBatch(RenderType.debugQuads());
            buffers.endBatch(RenderType.lines());
        }
    }

    /** How sideways the eye looks at the sphere at this point (0 head-on, 1 grazing it at its rim). */
    private static float rim(Field f, float nx, float ny, float nz) {
        final double vx = f.fromEye().x + nx * f.radius(), vy = f.fromEye().y + ny * f.radius(), vz = f.fromEye().z + nz * f.radius();
        final double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        return len < 1.0E-3 ? 0.0F : 1.0F - (float) Math.abs((vx * nx + vy * ny + vz * nz) / len);
    }

    private static int colour(int step, float alpha, float fade) {
        return (int) (Mth.clamp(alpha, 0.0F, 1.0F) * fade * 255) << 24 | BLUES[Mth.clamp(step, 0, 3)];
    }

    /** A shell of pixels on the sphere: dense and bright at the rim, sparse head-on, some flickering a step. */
    private static void pixels(Field f) {
        final int rings = (int) (Mth.PI * f.radius() / (PX * 2.2F));
        final int frame = (int) (f.time() / 3);
        for (int i = 1; i < rings; i++) {
            final float lat = Mth.PI * i / rings - Mth.HALF_PI;
            final int count = Math.max(6, (int) (Mth.TWO_PI * f.radius() * Mth.cos(lat) / (PX * 2.2F)));
            for (int j = 0; j < count; j++) {
                final float lon = Mth.TWO_PI * j / count + i * 0.37F;
                final float nx = Mth.cos(lat) * Mth.cos(lon), ny = Mth.sin(lat), nz = Mth.cos(lat) * Mth.sin(lon);
                final float rim = rim(f, nx, ny, nz);
                final float n = UndertowRenderer.noise(i * 131 + j, frame);
                if (n > 0.25F + rim * 0.9F) {
                    continue; // thinning out where the eye looks straight through
                }
                final int step = (int) (rim * 3.2F) + (n < 0.04F ? 1 : 0);
                mote(f, nx * f.radius(), ny * f.radius(), nz * f.radius(), PX, colour(step, 0.35F + 0.6F * rim, f.fade()));
            }
        }
    }

    /** Rings of pixels stacked up the sphere, a bright band running up them and starting again at the bottom. */
    private static void rings(Field f) {
        final int count = Math.max(6, (int) (f.radius() * 3));
        final float band = (f.time() * 0.04F) % 1.4F - 0.2F; // 0 the bottom .. 1 the top
        for (int i = 1; i < count; i++) {
            final float h = (float) i / count, lat = Mth.PI * h - Mth.HALF_PI;
            final float glow = Math.max(0.0F, 1.0F - Math.abs(h - band) * 6.0F);
            final int around = Math.max(8, (int) (Mth.TWO_PI * f.radius() * Mth.cos(lat) / (PX * 1.6F)));
            for (int j = 0; j < around; j++) {
                final float lon = Mth.TWO_PI * j / around;
                final float nx = Mth.cos(lat) * Mth.cos(lon), ny = Mth.sin(lat), nz = Mth.cos(lat) * Mth.sin(lon);
                final float rim = rim(f, nx, ny, nz);
                mote(f, nx * f.radius(), ny * f.radius(), nz * f.radius(), PX, colour(1 + (int) (glow * 2.5F), 0.25F + 0.4F * rim + 0.5F * glow, f.fade()));
            }
        }
    }

    /** Glyphs of pixels circling at two heights, against each other, over a dotted ring on the ground. */
    private static void runes(Field f, float ground) {
        final int around = (int) (Mth.TWO_PI * f.radius() / (PX * 2));
        for (int j = 0; j < around; j++) { // the ring on the ground
            final float a = Mth.TWO_PI * j / around;
            if (j % 4 != 3) {
                flat(f, Mth.cos(a) * f.radius(), ground + 0.03F, Mth.sin(a) * f.radius(), colour(j % 4 == 0 ? 3 : 1, 0.8F, f.fade()));
            }
        }
        for (int level = 0; level < 2; level++) {
            final float y = level == 0 ? -0.2F : 0.9F, turn = f.time() * (level == 0 ? 0.02F : -0.015F);
            final float r = f.radius() * Mth.sqrt(Math.max(0.0F, 1.0F - y * y / (f.radius() * f.radius())));
            final int glyphs = Math.max(6, (int) (r * 2.5F));
            for (int g = 0; g < glyphs; g++) {
                final float a = turn + Mth.TWO_PI * g / glyphs;
                final int bits = (int) (UndertowRenderer.noise(g * 7 + level * 31, 3) * 512); // a 3 x 3 glyph
                final boolean lit = (g + (int) (f.time() / 8)) % glyphs == 0;
                for (int k = 0; k < 9; k++) {
                    if ((bits >> k & 1) == 0 && k != 4) {
                        continue;
                    }
                    final float along = (k % 3 - 1) * PX, high = (k / 3 - 1) * PX;
                    final float x = Mth.cos(a) * r - Mth.sin(a) * along, z = Mth.sin(a) * r + Mth.cos(a) * along;
                    mote(f, x, y + high, z, PX, colour(lit ? 3 : 2, lit ? 1.0F : 0.7F, f.fade()));
                }
            }
        }
    }

    /** Pixel shards drifting round on the sphere, each on its own slanted path, flickering now and then. */
    private static void shards(Field f) {
        final int count = (int) (f.radius() * 14);
        for (int s = 0; s < count; s++) {
            final float tilt = (UndertowRenderer.noise(s, 1) - 0.5F) * Mth.PI, speed = 0.004F + 0.01F * UndertowRenderer.noise(s, 2);
            final float a = UndertowRenderer.noise(s, 3) * Mth.TWO_PI + f.time() * speed;
            // a great circle tilted round the x axis
            final float px = Mth.cos(a), py = Mth.sin(a) * Mth.sin(tilt), pz = Mth.sin(a) * Mth.cos(tilt);
            final float rim = rim(f, px, py, pz);
            final boolean flash = UndertowRenderer.noise(s, (int) (f.time() / 4)) > 0.95F;
            final float x = px * f.radius(), y = py * f.radius(), z = pz * f.radius();
            final int c = colour(flash ? 3 : 1 + (int) (rim * 2), 0.5F + 0.5F * rim, f.fade());
            mote(f, x, y, z, PX, c); // a shard: three or four pixels in an L
            mote(f, x + f.left().x() * PX, y + f.left().y() * PX, z + f.left().z() * PX, PX, c);
            mote(f, x + f.up().x() * PX, y + f.up().y() * PX, z + f.up().z() * PX, PX, c);
            if (s % 2 == 0) {
                mote(f, x - f.up().x() * PX, y - f.up().y() * PX, z - f.up().z() * PX, PX, colour(0, 0.6F, f.fade()));
            }
        }
    }

    /** The smooth sphere: each corner's alpha by how sideways the eye looks at it there, faint bands drifting up. */
    private static void bubble(Field f) {
        for (int i = 0; i < RINGS; i++) {
            for (int j = 0; j < SEGMENTS; j++) {
                corner(f, i, j);
                corner(f, i + 1, j);
                corner(f, i + 1, j + 1);
                corner(f, i, j + 1);
            }
        }
    }

    private static void corner(Field f, int ring, int segment) {
        final float lat = Mth.PI * ring / RINGS - Mth.HALF_PI, lon = Mth.TWO_PI * segment / SEGMENTS;
        final float nx = Mth.cos(lat) * Mth.cos(lon), ny = Mth.sin(lat), nz = Mth.cos(lat) * Mth.sin(lon);
        final float rim = rim(f, nx, ny, nz);
        final float band = Math.max(0.0F, Mth.sin(lat * 5.0F - f.time() * 0.08F)) * 0.08F;
        f.q().addVertex(f.m(), nx * f.radius(), ny * f.radius(), nz * f.radius()).setColor(colour(1, 0.16F + 0.5F * rim * rim + band, f.fade()));
    }

    /** The bubble's fine grid (seen from inside too, where the rim is not), turning slowly. */
    private static void grid(VertexConsumer lines, PoseStack.Pose pose, Field f) {
        final int c = colour(3, 0.6F, f.fade());
        final float turn = f.time() * 0.004F;
        for (int k = 1; k < 6; k++) { // circles of latitude
            final float lat = Mth.PI * k / 6 - Mth.HALF_PI;
            for (int j = 0; j < SEGMENTS; j++) {
                line(lines, pose, f.radius(), lat, Mth.TWO_PI * j / SEGMENTS + turn, lat, Mth.TWO_PI * (j + 1) / SEGMENTS + turn, c);
            }
        }
        for (int k = 0; k < 12; k++) { // meridians
            final float lon = Mth.TWO_PI * k / 12 + turn;
            for (int i = 0; i < RINGS; i++) {
                line(lines, pose, f.radius(), Mth.PI * i / RINGS - Mth.HALF_PI, lon, Mth.PI * (i + 1) / RINGS - Mth.HALF_PI, lon, c);
            }
        }
    }

    private static void line(VertexConsumer lines, PoseStack.Pose pose, float radius, float lat0, float lon0, float lat1, float lon1, int colour) {
        final float x0 = Mth.cos(lat0) * Mth.cos(lon0), y0 = Mth.sin(lat0), z0 = Mth.cos(lat0) * Mth.sin(lon0);
        final float x1 = Mth.cos(lat1) * Mth.cos(lon1), y1 = Mth.sin(lat1), z1 = Mth.cos(lat1) * Mth.sin(lon1);
        float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
        final float len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= len;
        ny /= len;
        nz /= len;
        lines.addVertex(pose, x0 * radius, y0 * radius, z0 * radius).setColor(colour).setNormal(pose, nx, ny, nz);
        lines.addVertex(pose, x1 * radius, y1 * radius, z1 * radius).setColor(colour).setNormal(pose, nx, ny, nz);
    }

    /** A square pixel facing the eye. */
    private static void mote(Field f, float x, float y, float z, float size, int argb) {
        final float h = size * 0.5F;
        final Vector3f l = f.left(), u = f.up();
        final float lx = l.x() * h, ly = l.y() * h, lz = l.z() * h, ux = u.x() * h, uy = u.y() * h, uz = u.z() * h;
        f.q().addVertex(f.m(), x - lx - ux, y - ly - uy, z - lz - uz).setColor(argb);
        f.q().addVertex(f.m(), x + lx - ux, y + ly - uy, z + lz - uz).setColor(argb);
        f.q().addVertex(f.m(), x + lx + ux, y + ly + uy, z + lz + uz).setColor(argb);
        f.q().addVertex(f.m(), x - lx + ux, y - ly + uy, z - lz + uz).setColor(argb);
    }

    /** A square pixel lying flat. */
    private static void flat(Field f, float x, float y, float z, int argb) {
        final float h = PX * 0.5F;
        f.q().addVertex(f.m(), x - h, y, z - h).setColor(argb);
        f.q().addVertex(f.m(), x - h, y, z + h).setColor(argb);
        f.q().addVertex(f.m(), x + h, y, z + h).setColor(argb);
        f.q().addVertex(f.m(), x + h, y, z - h).setColor(argb);
    }
}
