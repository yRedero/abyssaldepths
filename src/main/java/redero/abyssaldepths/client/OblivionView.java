package redero.abyssaldepths.client;

import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.artifact.OblivionItem;

/**
 * Oblivion charging up, while its holder holds right click, as every client sees it (the use is synced), in
 * the configured style ({@link ClientConfig.OblivionLook}), in square pixels of black and deep purple:
 * <ul>
 * <li>INK: rings of black pixels rushing in over the ground to the holder, one after another, darker as it
 * fills.</li>
 * <li>ECLIPSE: a black sun swelling above the holder with a flickering rim, streams of dark pixels rising into
 * it from round about; the screens of those near darken.</li>
 * <li>GRASP: fingers of shadow rising from the ground round the holder and closing over them.</li>
 * <li>TORN: black cracks running over the screens of the holder and those near; they shatter when it is cast
 * (and just fade if it is let go of before).</li>
 * </ul>
 * Let go (or cast), it fades out over {@link #FADE_TICKS}.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class OblivionView {
    /** Black to deep purple to a pale violet edge. */
    private static final int[] SHADES = {0x050307, 0x1A0B26, 0x3A1A66, 0x8E52D6};
    private static final float PX = 0.125F;
    private static final int FADE_TICKS = 12;
    /** How near (blocks) the screens darken or crack. */
    private static final double SCREEN_RANGE = 12.0;

    /** A player charging (or just done): how far (0 .. 1), and when they stopped (NaN while charging). */
    private static final class Charge {
        float progress, stopped = Float.NaN;
    }

    private static final Map<Integer, Charge> CHARGES = new HashMap<>();

    private OblivionView() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            CHARGES.clear();
            return;
        }
        final float now = mc.level.getGameTime();
        for (Player player : mc.level.players()) {
            final boolean charging = player.isUsingItem() && player.getUseItem().getItem() instanceof OblivionItem;
            final Charge charge = CHARGES.get(player.getId());
            if (charging) {
                final Charge c = charge == null || !Float.isNaN(charge.stopped) ? new Charge() : charge;
                c.progress = Mth.clamp(player.getTicksUsingItem() / (float) OblivionItem.useTicks(), 0.0F, 1.0F);
                CHARGES.put(player.getId(), c);
            } else if (charge != null && Float.isNaN(charge.stopped)) {
                charge.stopped = now;
            }
        }
        CHARGES.entrySet().removeIf(e -> !Float.isNaN(e.getValue().stopped) && now - e.getValue().stopped > FADE_TICKS
                || mc.level.getEntity(e.getKey()) == null);
    }

    /** How far the charge is now, and how much of it still shows (1 while charging, fading after). */
    private static float[] state(Charge charge, Player player, float now, float partial) {
        if (Float.isNaN(charge.stopped)) {
            return new float[] {Mth.clamp((player.getTicksUsingItem() + partial) / OblivionItem.useTicks(), 0.0F, 1.0F), 1.0F};
        }
        return new float[] {charge.progress, Mth.clamp(1.0F - (now - charge.stopped) / FADE_TICKS, 0.0F, 1.0F)};
    }

    private static int colour(int step, float alpha) {
        return (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255) << 24 | SHADES[Mth.clamp(step, 0, 3)];
    }

    private record Draw(VertexConsumer q, Matrix4f m, Vector3f left, Vector3f up) {
    }

    // ---- in the world ---------------------------------------------------------------------

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || CHARGES.isEmpty() || mc.level == null) {
            return;
        }
        final float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        final float now = mc.level.getGameTime() + partial;
        final Vec3 cam = event.getCamera().getPosition();
        final PoseStack pose = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final ClientConfig.OblivionLook look = ClientConfig.oblivionLook();
        for (Map.Entry<Integer, Charge> entry : CHARGES.entrySet()) {
            if (!(mc.level.getEntity(entry.getKey()) instanceof Player player) || ShadeView.hidden(player)) {
                continue;
            }
            final float[] s = state(entry.getValue(), player, now, partial);
            final Vec3 at = player.getPosition(partial);
            pose.pushPose();
            pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
            final Draw d = new Draw(buffers.getBuffer(RenderType.debugQuads()), pose.last().pose(), event.getCamera().getLeftVector(),
                    event.getCamera().getUpVector());
            switch (look) {
                case INK -> ink(d, s[0], s[1], now);
                case ECLIPSE -> eclipse(d, s[0], s[1], now);
                case GRASP -> grasp(d, s[0], s[1], now);
                case TORN -> {
                }
            }
            pose.popPose();
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    /** Three rings rushing in over the ground, one after another, darker and thicker as it fills. */
    private static void ink(Draw d, float p, float fade, float now) {
        for (int k = 0; k < 3; k++) {
            final float phase = (now * 0.035F + k / 3.0F) % 1.0F;
            final float r = 0.6F + 9.0F * (1.0F - phase);
            final int rows = 1 + (int) (p * 2.5F);
            final float alpha = fade * (0.35F + 0.65F * p) * (0.4F + 0.6F * phase); // stronger as it closes in
            for (int row = 0; row < rows; row++) {
                final float rr = r + row * PX;
                final int count = (int) (Mth.TWO_PI * rr / PX);
                for (int i = 0; i < count; i++) {
                    if (UndertowRenderer.noise(i * 7 + row + k * 1000, (int) (now / 2)) > 0.55F + 0.45F * p) {
                        continue; // gaps, closing as it fills
                    }
                    final float a = Mth.TWO_PI * i / count;
                    flat(d, Mth.cos(a) * rr, 0.06F, Mth.sin(a) * rr, colour(row == rows - 1 && rows > 1 ? 2 : 0, alpha));
                }
            }
        }
    }

    /** A black sun above the holder, swelling as it fills, its rim flickering; dark streams rising into it. */
    private static void eclipse(Draw d, float p, float fade, float now) {
        final float r = 0.3F + 1.5F * p, height = 3.6F;
        final int n = (int) Math.ceil(r / PX) + 1;
        for (int j = -n; j <= n; j++) {
            for (int i = -n; i <= n; i++) {
                final float x = i * PX, y = j * PX, dist = Mth.sqrt(x * x + y * y);
                if (dist <= r - PX) {
                    mote(d, x, y, height, colour(0, 0.97F * fade));
                } else if (dist <= r + PX && UndertowRenderer.noise(i * 13 + j, (int) (now / 2)) > 0.3F) {
                    mote(d, x, y, height, colour(UndertowRenderer.noise(i, j + (int) now) > 0.6F ? 3 : 2, 0.9F * fade));
                }
            }
        }
        for (int k = 0; k < 12; k++) { // the streams, from the ground round about
            final float a = k * Mth.TWO_PI / 12 + 0.3F;
            for (int m = 0; m < 6; m++) {
                final float s = (now * 0.05F + m / 6.0F + k * 0.13F) % 1.0F;
                final float x = Mth.cos(a) * 5.0F * (1 - s), z = Mth.sin(a) * 5.0F * (1 - s), y = height * s * s;
                moteAt(d, x, y, z, colour(s > 0.7F ? 0 : 1, (0.3F + 0.6F * p) * fade));
            }
        }
    }

    /** Fingers of shadow rising round the holder, higher as it fills, closing over them. */
    private static void grasp(Draw d, float p, float fade, float now) {
        final float tall = 2.4F, top = tall * p;
        for (int f = 0; f < 6; f++) {
            final float a = f * Mth.TWO_PI / 6 + Mth.sin(now * 0.05F + f) * 0.08F;
            for (float h = 0; h < top; h += PX) {
                final float bend = h / tall; // closing in, higher up
                final float r = 1.3F * (1.0F - 0.75F * bend * bend);
                final int step = h > top - PX * 2 ? 2 : (int) (UndertowRenderer.noise(f, (int) (h * 8)) * 2);
                moteAt(d, Mth.cos(a) * r, h, Mth.sin(a) * r, colour(step, 0.92F * fade));
            }
        }
    }

    /** A square pixel facing the eye, at (x, y) in the plane facing it through a point {@code height} above the holder. */
    private static void mote(Draw d, float x, float y, float height, int argb) {
        final Vector3f l = d.left(), u = d.up();
        moteAt(d, -l.x() * x + u.x() * y, height - l.y() * x + u.y() * y, -l.z() * x + u.z() * y, argb);
    }

    private static void moteAt(Draw d, float x, float y, float z, int argb) {
        final float h = PX * 0.5F;
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

    // ---- on the screen ----------------------------------------------------------------------

    private static void screen(GuiGraphics g, float partial) {
        final Minecraft mc = Minecraft.getInstance();
        if (CHARGES.isEmpty() || mc.level == null || mc.player == null) {
            return;
        }
        final float now = mc.level.getGameTime() + partial;
        for (Map.Entry<Integer, Charge> entry : CHARGES.entrySet()) {
            if (!(mc.level.getEntity(entry.getKey()) instanceof Player player) || ShadeView.hidden(player)
                    || player.distanceTo(mc.player) > SCREEN_RANGE) {
                continue;
            }
            final float[] s = state(entry.getValue(), player, now, partial);
            switch (ClientConfig.oblivionLook()) {
                case ECLIPSE -> g.fill(0, 0, g.guiWidth(), g.guiHeight(), (int) (s[0] * s[1] * 0x70) << 24 | 0x05030A);
                case TORN -> {
                    final Charge charge = entry.getValue();
                    final boolean cast = !Float.isNaN(charge.stopped) && charge.progress >= 0.95F;
                    cracks(g, entry.getKey(), s[0], cast ? 1.0F - s[1] : 0.0F, cast ? 1.0F : s[1]);
                }
                default -> {
                }
            }
        }
    }

    /** Black cracks from near the middle of the screen, as long as the charge is far; shattering outwards when cast. */
    private static void cracks(GuiGraphics g, int seed, float grow, float shatter, float fade) {
        final int cell = 6, w = g.guiWidth(), h = g.guiHeight();
        for (int c = 0; c < 6; c++) {
            float x = w * (0.35F + 0.3F * UndertowRenderer.noise(seed + c, 1)), y = h * (0.35F + 0.3F * UndertowRenderer.noise(seed + c, 2));
            float a = UndertowRenderer.noise(seed + c, 3) * Mth.TWO_PI;
            for (int k = 0; k < 30 * grow; k++) {
                a += (UndertowRenderer.noise(seed + c * 97, k) - 0.5F) * 1.1F; // wandering
                x += Mth.cos(a) * cell;
                y += Mth.sin(a) * cell;
                final float ox = (x - w / 2.0F) * shatter * 0.6F, oy = (y - h / 2.0F) * shatter * 0.6F + shatter * shatter * 40;
                final int px = (int) (x + ox) / cell * cell, py = (int) (y + oy) / cell * cell;
                final float alpha = 0.95F * fade * (1.0F - shatter);
                g.fill(px, py, px + cell, py + cell, colour(k % 7 == 0 ? 2 : 0, alpha));
                if (k % 5 == 2 && shatter == 0.0F) { // splinters off the crack
                    final int sx = px + (int) (Mth.sin(a) * cell), sy = py - (int) (Mth.cos(a) * cell);
                    g.fill(sx, sy, sx + cell, sy + cell, colour(1, alpha * 0.8F));
                }
            }
        }
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layer {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "oblivion"),
                    (graphics, delta) -> screen(graphics, delta.getGameTimeDeltaPartialTick(true)));
        }
    }
}
