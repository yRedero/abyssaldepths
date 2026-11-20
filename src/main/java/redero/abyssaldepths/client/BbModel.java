package redero.abyssaldepths.client;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * A model made in Blockbench, drawn as Blockbench shows it: its bones (pivots, rotations in Blockbench's order, x
 * first), its cubes (each turned about its own pivot) with box-UV faces, and its animations (rotation, position and
 * scale keyframes, played linearly; loop, hold or once). Read from {@code assets/<ns>/models/entity/<name>.json},
 * written from the .bbmodel by the dev tools; coordinates are Blockbench pixels, y up.
 */
public final class BbModel {
    /** A bone: its pivot and rest rotation (degrees), its cubes and child bones. */
    public record Bone(String name, float[] origin, float[] rotation, List<Cube> cubes, List<Bone> bones) {
    }

    /** A cube: corners, pivot and rotation, and per face its uv rect (u1, v1, u2, v2) in texture pixels. */
    public record Cube(float[] from, float[] to, float[] origin, float[] rotation, Map<String, float[]> faces) {
    }

    /** An animation: length (seconds), how it ends, per bone per channel its keyframes (time, x, y, z). */
    public record Animation(float length, String loop, Map<String, Map<String, float[][]>> bones, float speed) {
    }

    /**
     * One animation played among others at once, at its own time, with a weight (0 to 1): the legs walking, the body
     * running, the face's expression, each moving only its own bones. Where two move the same bone, the later takes it
     * (a pose for holding something out over the cowering arms); a blending layer adds to it instead, and a weight below 1
     * blends one in or out (one expression easing into the next).
     */
    public record Layer(String animation, float time, float weight, boolean blend) {
        public Layer(String animation, float time, float weight) {
            this(animation, time, weight, false);
        }

        public static List<Layer> of(String animation, float time) {
            return animation == null ? List.of() : List.of(new Layer(animation, time, 1));
        }
    }

    /** What an animation does to a bone at a moment: rotation (degrees), position (pixels), scale. */
    public static final class Pose {
        public final float[] rotation = new float[3], position = new float[3], scale = {1, 1, 1};
    }

    public final List<Bone> bones;
    public final Map<String, Animation> animations;
    public final int texWidth, texHeight;

    private BbModel(List<Bone> bones, Map<String, Animation> animations, int texWidth, int texHeight) {
        this.bones = bones;
        this.animations = animations;
        this.texWidth = texWidth;
        this.texHeight = texHeight;
    }

    private static final Map<ResourceLocation, BbModel> CACHE = new HashMap<>();

    /** The model at that place, or null if there is none (an accessory not made yet). */
    public static BbModel find(ResourceLocation location) {
        if (!CACHE.containsKey(location) && Minecraft.getInstance().getResourceManager().getResource(location).isEmpty()) {
            return null;
        }
        return get(location);
    }

    /** Forgets the models read (the resources were reloaded: changed models show without a restart). */
    public static void clear() {
        CACHE.clear();
    }

    /**
     * Draws another model's parts on this one, each part where the bone of the same name is in this one as posed now
     * (an accessory: goggles on the head move with the head in every animation). Parts whose bone this model lacks are
     * drawn as they are.
     */
    public void wear(BbModel accessory, PoseStack pose, VertexConsumer out, int light, int overlay, List<Layer> layers,
                     Map<String, float[]> extra) {
        for (Bone b : accessory.bones) {
            wearBone(accessory, b, null, pose, out, light, overlay, layers, extra);
        }
    }

    /** A part of an accessory: on the bone of its name, else on the one its parent was on (a group of its own inside "head"). */
    private void wearBone(BbModel accessory, Bone b, String on, PoseStack pose, VertexConsumer out, int light, int overlay, List<Layer> layers,
                          Map<String, float[]> extra) {
        final String here = bone(b.name()) != null ? b.name() : on;
        pose.pushPose();
        if (here != null) {
            into(here, pose, layers, extra);
        }
        for (Cube c : b.cubes()) {
            accessory.cube(c, pose, out, light, overlay);
        }
        pose.popPose();
        for (Bone child : b.bones()) {
            wearBone(accessory, child, here, pose, out, light, overlay, layers, extra);
        }
    }

    /** The model at that place (read once, kept). */
    public static BbModel get(ResourceLocation location) {
        return CACHE.computeIfAbsent(location, BbModel::read);
    }

    private static BbModel read(ResourceLocation location) {
        try (Reader reader = new InputStreamReader(Minecraft.getInstance().getResourceManager().open(location), StandardCharsets.UTF_8)) {
            final JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            final List<Bone> bones = new ArrayList<>();
            for (JsonElement b : root.getAsJsonArray("bones")) {
                bones.add(bone(b.getAsJsonObject()));
            }
            final Map<String, Animation> animations = new HashMap<>();
            final JsonObject anims = root.getAsJsonObject("animations");
            for (String name : anims.keySet()) {
                final JsonObject a = anims.getAsJsonObject(name);
                final Map<String, Map<String, float[][]>> perBone = new HashMap<>();
                final JsonObject bs = a.getAsJsonObject("bones");
                for (String bone : bs.keySet()) {
                    final Map<String, float[][]> channels = new HashMap<>();
                    for (String channel : bs.getAsJsonObject(bone).keySet()) {
                        final JsonArray frames = bs.getAsJsonObject(bone).getAsJsonArray(channel);
                        final float[][] keys = new float[frames.size()][];
                        for (int i = 0; i < keys.length; i++) {
                            keys[i] = floats(frames.get(i).getAsJsonArray());
                        }
                        channels.put(channel, keys);
                    }
                    perBone.put(bone, channels);
                }
                animations.put(name, new Animation(a.get("length").getAsFloat(), a.get("loop").getAsString(), perBone,
                        a.has("speed") ? a.get("speed").getAsFloat() : 1));
            }
            final JsonArray tex = root.getAsJsonArray("texture");
            return new BbModel(bones, animations, tex.get(0).getAsInt(), tex.get(1).getAsInt());
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the model " + location, e);
        }
    }

    private static Bone bone(JsonObject o) {
        final List<Cube> cubes = new ArrayList<>();
        final List<Bone> bones = new ArrayList<>();
        for (JsonElement c : o.getAsJsonArray("children")) {
            final JsonObject child = c.getAsJsonObject();
            if (child.has("faces")) {
                final Map<String, float[]> faces = new HashMap<>();
                for (String side : child.getAsJsonObject("faces").keySet()) {
                    faces.put(side, floats(child.getAsJsonObject("faces").getAsJsonArray(side)));
                }
                cubes.add(new Cube(floats(child.getAsJsonArray("from")), floats(child.getAsJsonArray("to")), floats(child.getAsJsonArray("origin")),
                        floats(child.getAsJsonArray("rotation")), faces));
            } else {
                bones.add(bone(child));
            }
        }
        return new Bone(o.get("name").getAsString(), floats(o.getAsJsonArray("origin")), floats(o.getAsJsonArray("rotation")), cubes, bones);
    }

    private static float[] floats(JsonArray a) {
        final float[] f = new float[a.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = a.get(i).getAsFloat();
        }
        return f;
    }

    /** A bone of the model by name (searching the whole tree), or null. */
    public Bone bone(String name) {
        for (Bone b : bones) {
            final Bone found = find(b, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Bone find(Bone b, String name) {
        if (b.name().equals(name)) {
            return b;
        }
        for (Bone c : b.bones()) {
            final Bone found = find(c, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ---- animations ---------------------------------------------------------------------------------------------

    /** Whether an animation is over at that time (only one played once). */
    public boolean over(String name, float time) {
        final Animation a = animations.get(name);
        return a == null || "once".equals(a.loop()) && time > a.length();
    }

    /** What an animation does to a bone at a time (seconds), or null if nothing. */
    public Pose pose(String animation, String bone, float time) {
        final Animation a = animation == null ? null : animations.get(animation);
        if (a == null || !a.bones().containsKey(bone)) {
            return null;
        }
        float t = time * a.speed();
        if (a.length() <= 0) {
            t = 0; // a pose (no length): held as long as it is played
        } else if ("loop".equals(a.loop())) {
            t = t % a.length();
        } else if (t > a.length()) {
            if ("once".equals(a.loop())) {
                return null;
            }
            t = a.length(); // held at its end
        }
        final Pose pose = new Pose();
        final Map<String, float[][]> channels = a.bones().get(bone);
        sample(channels.get("rotation"), t, pose.rotation);
        sample(channels.get("position"), t, pose.position);
        if (channels.containsKey("scale")) {
            sample(channels.get("scale"), t, pose.scale);
        }
        return pose;
    }

    private static void sample(float[][] keys, float t, float[] out) {
        if (keys == null || keys.length == 0) {
            return;
        }
        if (t <= keys[0][0]) {
            System.arraycopy(keys[0], 1, out, 0, 3);
            return;
        }
        for (int i = 1; i < keys.length; i++) {
            if (t <= keys[i][0]) {
                final float k = (t - keys[i - 1][0]) / Math.max(1.0E-6F, keys[i][0] - keys[i - 1][0]);
                for (int c = 0; c < 3; c++) {
                    out[c] = keys[i - 1][c + 1] + (keys[i][c + 1] - keys[i - 1][c + 1]) * k;
                }
                return;
            }
        }
        System.arraycopy(keys[keys.length - 1], 1, out, 0, 3);
    }

    /**
     * What the layers together do to a bone, or null if none moves it: each layer that moves it blends from what the
     * layers before made of it towards its own pose by its weight (weight 1 takes the bone; one easing in at 0.3 is a
     * third of the way); a blending layer adds its moves instead.
     */
    private Pose layered(List<Layer> layers, String bone) {
        Pose sum = null;
        for (Layer l : layers) {
            final Pose p = pose(l.animation(), bone, l.time());
            if (p == null || l.weight() <= 0) {
                continue;
            }
            if (sum == null) {
                sum = new Pose(); // (the rest: no turn, no move, scale 1)
            }
            final float w = l.weight();
            for (int c = 0; c < 3; c++) {
                if (l.blend()) {
                    sum.rotation[c] += p.rotation[c] * w;
                    sum.position[c] += p.position[c] * w;
                    sum.scale[c] *= 1 + (p.scale[c] - 1) * w;
                } else {
                    sum.rotation[c] += (p.rotation[c] - sum.rotation[c]) * w;
                    sum.position[c] += (p.position[c] - sum.position[c]) * w;
                    sum.scale[c] += (p.scale[c] - sum.scale[c]) * w;
                }
            }
        }
        return sum;
    }

    /** A bone's own turn: its rest, the extra turn laid on it, the layers' moves; from its parent's space into its own. */
    private void transform(Bone bone, PoseStack pose, List<Layer> layers, Map<String, float[]> extra) {
        final float[] o = bone.origin();
        pose.translate(o[0], o[1], o[2]);
        final Pose anim = layered(layers, bone.name());
        float rx = bone.rotation()[0], ry = bone.rotation()[1], rz = bone.rotation()[2];
        final float[] more = extra == null ? null : extra.get(bone.name());
        if (more != null) {
            rx += more[0];
            ry += more[1];
            rz += more[2];
            if (more.length >= 6) { // and a move (pixels)
                pose.translate(more[3], more[4], more[5]);
            }
        }
        if (anim != null) {
            pose.translate(anim.position[0], anim.position[1], anim.position[2]);
            rx += anim.rotation[0];
            ry += anim.rotation[1];
            rz += anim.rotation[2];
        }
        rotate(pose, rx, ry, rz);
        if (anim != null) {
            pose.scale(anim.scale[0], anim.scale[1], anim.scale[2]);
        }
        pose.translate(-o[0], -o[1], -o[2]);
    }

    // ---- drawing ------------------------------------------------------------------------------------------------

    /** Draws a bone and all below it (pose in Blockbench pixels, y up), posed by an animation at a time (or none). */
    public void draw(Bone bone, PoseStack pose, VertexConsumer out, int light, int overlay, String animation, float time) {
        draw(bone, pose, out, light, overlay, Layer.of(animation, time), null);
    }

    /** Draws a bone and all below it, posed by an animation, with extra turns of named bones on top (see the other). */
    public void draw(Bone bone, PoseStack pose, VertexConsumer out, int light, int overlay, String animation, float time, Map<String, float[]> extra) {
        draw(bone, pose, out, light, overlay, Layer.of(animation, time), extra);
    }

    /**
     * Draws a bone and all below it, posed by animations played together ({@link Layer}) and, on top of them, extra
     * turns of named bones (degrees x, y, z, and optionally a move x, y, z in pixels: a creature's posture, where it looks).
     */
    public void draw(Bone bone, PoseStack pose, VertexConsumer out, int light, int overlay, List<Layer> layers, Map<String, float[]> extra) {
        pose.pushPose();
        transform(bone, pose, layers, extra);
        for (Cube c : bone.cubes()) {
            cube(c, pose, out, light, overlay);
        }
        for (Bone b : bone.bones()) {
            draw(b, pose, out, light, overlay, layers, extra);
        }
        pose.popPose();
    }

    /**
     * Moves a pose into a bone's space as drawn (the bones above it posed alike), for placing things on it (an item in a
     * hand). Whether the bone was found.
     */
    public boolean into(String name, PoseStack pose, String animation, float time, Map<String, float[]> extra) {
        return into(name, pose, Layer.of(animation, time), extra);
    }

    public boolean into(String name, PoseStack pose, List<Layer> layers, Map<String, float[]> extra) {
        for (Bone b : bones) {
            final List<Bone> path = new java.util.ArrayList<>();
            if (path(b, name, path)) {
                for (Bone step : path) {
                    transform(step, pose, layers, extra);
                }
                return true;
            }
        }
        return false;
    }

    private static boolean path(Bone b, String name, List<Bone> out) {
        out.add(b);
        if (b.name().equals(name)) {
            return true;
        }
        for (Bone c : b.bones()) {
            if (path(c, name, out)) {
                return true;
            }
        }
        out.remove(out.size() - 1);
        return false;
    }

    /** Blockbench's order: x first, then y, then z (as a matrix, Z * Y * X). */
    private static void rotate(PoseStack pose, float x, float y, float z) {
        if (z != 0) {
            pose.mulPose(Axis.ZP.rotationDegrees(z));
        }
        if (y != 0) {
            pose.mulPose(Axis.YP.rotationDegrees(y));
        }
        if (x != 0) {
            pose.mulPose(Axis.XP.rotationDegrees(x));
        }
    }

    private void cube(Cube c, PoseStack pose, VertexConsumer out, int light, int overlay) {
        pose.pushPose();
        final float[] o = c.origin();
        pose.translate(o[0], o[1], o[2]);
        rotate(pose, c.rotation()[0], c.rotation()[1], c.rotation()[2]);
        pose.translate(-o[0], -o[1], -o[2]);
        final float x0 = c.from()[0], y0 = c.from()[1], z0 = c.from()[2], x1 = c.to()[0], y1 = c.to()[1], z1 = c.to()[2];
        final PoseStack.Pose p = pose.last();
        // each face: its corners from its top left round (as seen from outside), its normal
        face(out, p, c.faces().get("north"), x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0, 0, 0, -1, light, overlay);
        face(out, p, c.faces().get("south"), x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1, 0, 0, 1, light, overlay);
        face(out, p, c.faces().get("east"), x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1, 1, 0, 0, light, overlay);
        face(out, p, c.faces().get("west"), x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0, -1, 0, 0, light, overlay);
        face(out, p, c.faces().get("up"), x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0, 1, 0, light, overlay);
        face(out, p, c.faces().get("down"), x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 0, -1, 0, light, overlay);
        pose.popPose();
    }

    private void face(VertexConsumer out, PoseStack.Pose p, float[] uv, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
                      float dx, float dy, float dz, float nx, float ny, float nz, int light, int overlay) {
        if (uv == null) {
            return;
        }
        final float u1 = uv[0] / texWidth, v1 = uv[1] / texHeight, u2 = uv[2] / texWidth, v2 = uv[3] / texHeight;
        vertex(out, p, ax, ay, az, u1, v1, nx, ny, nz, light, overlay);
        vertex(out, p, dx, dy, dz, u1, v2, nx, ny, nz, light, overlay);
        vertex(out, p, cx, cy, cz, u2, v2, nx, ny, nz, light, overlay);
        vertex(out, p, bx, by, bz, u2, v1, nx, ny, nz, light, overlay);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose p, float x, float y, float z, float u, float v, float nx, float ny, float nz, int light, int overlay) {
        out.addVertex(p, x, y, z).setColor(0xFFFFFFFF).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(p, nx, ny, nz);
    }

    /** (A point of the model at the current pose, in the pose's space: for placing things on it.) */
    public static Vector3f at(Matrix4f m, float x, float y, float z) {
        return m.transformPosition(new Vector3f(x, y, z));
    }
}
