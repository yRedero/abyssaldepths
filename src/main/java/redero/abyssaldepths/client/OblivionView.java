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
 * <li>MARK: a dark tint creeps over the ground out from the holder, its edge a border of pale violet pixels following
 * the ground, until all it will reach (its radius) is marked; cast, the whole mark holds a moment and fades.</li>
 * <li>INK: rings of black pixels rushing in over the ground to the holder, one after another, darker as it
 * fills.</li>
 * <li>ECLIPSE: a black sun swelling above the holder with a flickering rim, streams of dark pixels rising into
 * it from round about; the screens of those near darken.</li>
 * <li>GRASP: fingers of shadow rising from the ground round the holder and closing over them.</li>
 * <li>TORN: black cracks running over the screens of the holder and those near; they shatter when it is cast
 * (and just fade if it is let go of before).</li>
 * </ul>
 * Let go (or cast), it fades out over {@link #FADE_TICKS}. Whoever Oblivion sent away sees the edge of the circle
 * they are held in as a wall of dark pixels when they come near it ({@link #bound}).
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
        /** MARK: every open face round where they stood when it began (see {@link #gather}), gathered a few planes a tick. */
        final java.util.List<float[]> faces = new java.util.ArrayList<>();
        int planes = -1, ox, oy, oz;
        double fx, fy, fz;
    }

    /** The circle the local player is held in (sent away by Oblivion): centre, radius, until which client tick. */
    private static double boundX, boundY, boundZ, boundR;
    private static long boundUntil = -1;

    /** From the server: "x/y/z/r" (held for {@code ticks}), or "clear". */
    public static void bound(String data, int ticks) {
        final Minecraft mc = Minecraft.getInstance();
        if (data.equals("clear") || mc.level == null) {
            boundUntil = -1;
            return;
        }
        final String[] p = data.split("/");
        boundX = Double.parseDouble(p[0]);
        boundY = Double.parseDouble(p[1]);
        boundZ = Double.parseDouble(p[2]);
        boundR = Double.parseDouble(p[3]);
        boundUntil = mc.level.getGameTime() + ticks;
    }

    /** MARK reaches this far from the holder (Oblivion's radius); faces gathered per tick while it charges, at most. */
    private static final int REACH = (int) Math.ceil(OblivionItem.RADIUS) + 1;
    private static final int PLANES_PER_TICK = 10, MAX_FACES = 40000;

    /**
     * Gathers the next x planes of the sphere round where the holder stood (from the middle outwards, so what shows
     * first is there first): every face of a block that is open to the air, by the block's real outline (a snow layer,
     * a slab, a path are marked where their top really is), with its distance from the middle.
     */
    private static void gather(net.minecraft.world.level.Level level, Charge c) {
        final net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        final net.minecraft.core.BlockPos.MutableBlockPos near = new net.minecraft.core.BlockPos.MutableBlockPos();
        final double cx = c.fx, cy = c.fy, cz = c.fz;
        for (int k = 0; k < PLANES_PER_TICK && c.planes < 2 * REACH + 1; k++, c.planes++) {
            final int dx = c.planes % 2 == 0 ? c.planes / 2 : -(c.planes + 1) / 2; // 0, -1, 1, -2, 2 ...
            for (int dy = -REACH; dy <= REACH; dy++) {
                for (int dz = -REACH; dz <= REACH; dz++) {
                    if (dx * dx + dy * dy + dz * dz > REACH * REACH || c.faces.size() >= MAX_FACES) {
                        continue;
                    }
                    pos.set(c.ox + dx, c.oy + dy, c.oz + dz);
                    final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                    if (state.isAir() || state.getCollisionShape(level, pos).isEmpty() && !(state.getBlock() instanceof net.minecraft.world.level.block.SnowLayerBlock)) {
                        continue; // only what one stands on (and snow): not grass, flowers, water
                    }
                    final net.minecraft.world.phys.shapes.VoxelShape shape = state.getShape(level, pos);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    final net.minecraft.world.phys.AABB box = shape.bounds().move(pos);
                    for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                        final boolean onEdge = switch (dir) { // the face lies on the block's edge: then the neighbour may hide it
                            case UP -> box.maxY >= pos.getY() + 1 - 1.0E-4;
                            case DOWN -> box.minY <= pos.getY() + 1.0E-4;
                            case EAST -> box.maxX >= pos.getX() + 1 - 1.0E-4;
                            case WEST -> box.minX <= pos.getX() + 1.0E-4;
                            case SOUTH -> box.maxZ >= pos.getZ() + 1 - 1.0E-4;
                            case NORTH -> box.minZ <= pos.getZ() + 1.0E-4;
                        };
                        if (onEdge) {
                            near.setWithOffset(pos, dir);
                            if (level.getBlockState(near).isSolidRender(level, near)) {
                                continue; // hidden behind a whole block
                            }
                        }
                        final float[] f = face(box, dir);
                        final double mx = (f[0] + f[3] + f[6] + f[9]) / 4, my = (f[1] + f[4] + f[7] + f[10]) / 4, mz = (f[2] + f[5] + f[8] + f[11]) / 4;
                        f[12] = (float) Math.sqrt((mx - cx) * (mx - cx) + (my - cy) * (my - cy) + (mz - cz) * (mz - cz));
                        c.faces.add(f);
                    }
                }
            }
        }
    }

    /** A face of a box, nudged out a little: its four corners (x, y, z each, in order round it), then room for its distance. */
    private static float[] face(net.minecraft.world.phys.AABB b, net.minecraft.core.Direction dir) {
        final float e = 0.003F;
        final float x0 = (float) b.minX - e, y0 = (float) b.minY - e, z0 = (float) b.minZ - e;
        final float x1 = (float) b.maxX + e, y1 = (float) b.maxY + e, z1 = (float) b.maxZ + e;
        return switch (dir) {
            case UP -> new float[] {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0};
            case DOWN -> new float[] {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0};
            case NORTH -> new float[] {x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0};
            case SOUTH -> new float[] {x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0};
            case WEST -> new float[] {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, 0};
            case EAST -> new float[] {x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 0};
        };
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
                if (c.planes < 0) {
                    c.ox = player.getBlockX();
                    c.oy = player.getBlockY();
                    c.oz = player.getBlockZ();
                    c.fx = player.getX();
                    c.fy = player.getY();
                    c.fz = player.getZ();
                    c.planes = 0;
                }
                if (ClientConfig.oblivionLook() == ClientConfig.OblivionLook.MARK) {
                    gather(mc.level, c);
                }
                c.progress = Mth.clamp(player.getTicksUsingItem() / (float) OblivionItem.useTicks(), 0.0F, 1.0F);
                CHARGES.put(player.getId(), c);
            } else if (charge != null && Float.isNaN(charge.stopped)) {
                charge.stopped = now;
            }
        }
        CHARGES.entrySet().removeIf(e -> !Float.isNaN(e.getValue().stopped) && now - e.getValue().stopped > fadeTicks(e.getValue())
                || mc.level.getEntity(e.getKey()) == null);
        if (boundUntil >= 0 && now > boundUntil) {
            boundUntil = -1;
        }
    }

    /** How long it fades once let go: a cast MARK holds the whole marked ground a moment longer. */
    private static int fadeTicks(Charge charge) {
        return ClientConfig.oblivionLook() == ClientConfig.OblivionLook.MARK && charge.progress >= 0.95F ? 36 : FADE_TICKS;
    }

    /** How far the charge is now, and how much of it still shows (1 while charging, fading after). */
    private static float[] state(Charge charge, Player player, float now, float partial) {
        if (Float.isNaN(charge.stopped)) {
            return new float[] {Mth.clamp((player.getTicksUsingItem() + partial) / OblivionItem.useTicks(), 0.0F, 1.0F), 1.0F};
        }
        final float t = (now - charge.stopped) / fadeTicks(charge);
        return new float[] {charge.progress, Mth.clamp(t < 0.35F && fadeTicks(charge) > FADE_TICKS ? 1.0F : 1.0F - t, 0.0F, 1.0F)};
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
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.level == null || CHARGES.isEmpty() && boundUntil < 0) {
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
                case MARK -> {
                    pose.popPose();
                    pose.pushPose();
                    pose.translate(-cam.x, -cam.y, -cam.z); // in world coordinates: it lies on the ground, not on the holder
                    mark(new Draw(d.q(), pose.last().pose(), d.left(), d.up()), entry.getValue(), s[0], s[1]);
                }
                case INK -> ink(d, s[0], s[1], now);
                case ECLIPSE -> eclipse(d, s[0], s[1], now);
                case GRASP -> grasp(d, s[0], s[1], now);
                case TORN -> {
                }
            }
            pose.popPose();
        }
        if (boundUntil >= 0 && mc.player != null) {
            pose.pushPose();
            pose.translate(-cam.x, -cam.y, -cam.z);
            wall(new Draw(buffers.getBuffer(RenderType.debugQuads()), pose.last().pose(), event.getCamera().getLeftVector(),
                    event.getCamera().getUpVector()), mc.player.getPosition(partial), now);
            pose.popPose();
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    /**
     * The mark spreading out from where the holder stood over everything round them (tops, sides, undersides, as far as
     * Oblivion reaches): one even dark tint, its edge a border of violet pixels (pale at the very edge) on whatever it
     * crosses; at full charge all it will reach is marked.
     */
    private static void mark(Draw d, Charge c, float p, float fade) {
        final float eased = 1.0F - (1.0F - p) * (1.0F - p);
        final float r = 0.8F + ((float) OblivionItem.RADIUS - 0.8F) * eased;
        final float band = 1.1F;
        final int tint = colour(1, 0.42F * fade);
        for (float[] f : c.faces) {
            final float dist = f[12];
            if (dist > r + 1.0F) {
                continue;
            }
            if (dist < r - band) {
                quad(d, f, tint);
                continue;
            }
            // near the edge: the face cut into pixels, each by its own distance (a crisp line over the ground and walls)
            final int n = 4;
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    final float u0 = i / (float) n, u1 = (i + 1) / (float) n, v0 = j / (float) n, v1 = (j + 1) / (float) n;
                    final float mu = (u0 + u1) / 2, mv = (v0 + v1) / 2;
                    final float px = lerp2(f, 0, mu, mv), py = lerp2(f, 1, mu, mv), pz = lerp2(f, 2, mu, mv);
                    final float pd = Mth.sqrt((float) ((px - c.fx) * (px - c.fx) + (py - c.fy) * (py - c.fy) + (pz - c.fz) * (pz - c.fz)));
                    if (pd > r) {
                        continue;
                    }
                    final int argb = pd > r - 0.3F ? colour(3, 0.9F * fade) : pd > r - 0.7F ? colour(2, 0.75F * fade) : tint;
                    sub(d, f, u0, v0, u1, v1, argb);
                }
            }
        }
    }

    /** A point of a face at (u, v) across it (u from corner 0 to 3, v from corner 0 to 1), one axis of it. */
    private static float lerp2(float[] f, int axis, float u, float v) {
        final float a = f[axis], b = f[3 + axis], c = f[6 + axis], dd = f[9 + axis];
        final float bottom = a + (dd - a) * u, top = b + (c - b) * u;
        return bottom + (top - bottom) * v;
    }

    private static void quad(Draw d, float[] f, int argb) {
        d.q().addVertex(d.m(), f[0], f[1], f[2]).setColor(argb);
        d.q().addVertex(d.m(), f[3], f[4], f[5]).setColor(argb);
        d.q().addVertex(d.m(), f[6], f[7], f[8]).setColor(argb);
        d.q().addVertex(d.m(), f[9], f[10], f[11]).setColor(argb);
    }

    /** The part (u0, v0) .. (u1, v1) of a face. */
    private static void sub(Draw d, float[] f, float u0, float v0, float u1, float v1, int argb) {
        d.q().addVertex(d.m(), lerp2(f, 0, u0, v0), lerp2(f, 1, u0, v0), lerp2(f, 2, u0, v0)).setColor(argb);
        d.q().addVertex(d.m(), lerp2(f, 0, u0, v1), lerp2(f, 1, u0, v1), lerp2(f, 2, u0, v1)).setColor(argb);
        d.q().addVertex(d.m(), lerp2(f, 0, u1, v1), lerp2(f, 1, u1, v1), lerp2(f, 2, u1, v1)).setColor(argb);
        d.q().addVertex(d.m(), lerp2(f, 0, u1, v0), lerp2(f, 1, u1, v0), lerp2(f, 2, u1, v0)).setColor(argb);
    }

    /** The edge of the circle the local player is held in: a curtain of dark pixels, showing as they come near it. */
    private static void wall(Draw d, Vec3 at, float now) {
        final double dx = at.x - boundX, dz = at.z - boundZ;
        final double away = boundR - Math.sqrt(dx * dx + dz * dz); // how far from the edge
        if (away > 9.0) {
            return;
        }
        final float near = (float) Mth.clamp(1.0 - away / 9.0, 0.0, 1.0);
        final double facing = Math.atan2(dz, dx);
        final float cell = 0.25F;
        final int across = (int) (14.0 / cell); // the stretch of it near the player, along the circle
        for (int i = -across / 2; i < across / 2; i++) {
            final double a = facing + i * cell / boundR;
            final float x = (float) (boundX + Math.cos(a) * boundR), z = (float) (boundZ + Math.sin(a) * boundR);
            final float side = Math.abs(i) / (across / 2.0F); // fainter towards its ends
            for (float h = -2.0F; h < 6.0F; h += cell) {
                final float y = (float) at.y + h;
                final float n = UndertowRenderer.noise(i * 17 + (int) (h * 4), (int) (now / 2));
                if (n > 0.55F + 0.4F * near) {
                    continue; // gaps, fewer the nearer
                }
                final float alpha = near * (1.0F - side * side) * (0.35F + 0.5F * n) * (1.0F - Math.abs(h - 2.0F) / 4.5F);
                if (alpha <= 0.02F) {
                    continue;
                }
                wallPixel(d, x, y, z, (float) a, cell, colour(n > 0.45F ? 2 : n > 0.2F ? 1 : 0, alpha));
            }
        }
    }

    /** A square of the wall, upright, along the circle (tangent at angle {@code a}). */
    private static void wallPixel(Draw d, float x, float y, float z, float a, float size, int argb) {
        final float tx = -Mth.sin(a) * size * 0.5F, tz = Mth.cos(a) * size * 0.5F, h = size * 0.5F;
        d.q().addVertex(d.m(), x - tx, y - h, z - tz).setColor(argb);
        d.q().addVertex(d.m(), x + tx, y - h, z + tz).setColor(argb);
        d.q().addVertex(d.m(), x + tx, y + h, z + tz).setColor(argb);
        d.q().addVertex(d.m(), x - tx, y + h, z - tz).setColor(argb);
    }

    /** A flat quad on the ground (x0, z0) .. (x1, z1) at height y, facing up. */
    private static void quad(Draw d, float x0, float z0, float x1, float z1, float y, int argb) {
        d.q().addVertex(d.m(), x0, y, z0).setColor(argb);
        d.q().addVertex(d.m(), x0, y, z1).setColor(argb);
        d.q().addVertex(d.m(), x1, y, z1).setColor(argb);
        d.q().addVertex(d.m(), x1, y, z0).setColor(argb);
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
