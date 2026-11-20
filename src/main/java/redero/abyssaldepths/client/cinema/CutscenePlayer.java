package redero.abyssaldepths.client.cinema;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.cinema.CutsceneActor;

/**
 * The cutscene library, client side: loads a cutscene from {@code assets/<namespace>/cutscenes/<name>.json} and plays
 * it. Positions in it are relative to the anchor (x right, y up, z forward from the anchor's heading), times in ticks.
 *
 * <pre>
 * {
 *   "length": 160, "letterbox": true, "hide_hud": true, "skippable": true,
 *   "camera": [ {"t": 0, "pos": [0, 3, -8], "yaw": 0, "pitch": 10, "fov": 70}, {"t": 160, "pos": [4, 2, -4], "look": [0, 1, 0]} ],
 *   "fades":  [ {"t": 0, "from": 1, "to": 0, "over": 20, "color": "#000000"}, {"t": 140, "from": 0, "to": 1, "over": 20} ],
 *   "titles": [ {"t": 30, "key": "cutscene.abyssaldepths.x", "duration": 60, "style": "title"} ],
 *   "sounds": [ {"t": 0, "sound": "minecraft:ambient.cave", "volume": 1, "pitch": 0.6, "pos": [0, 1, 0]} ],
 *   "actors": [ {"entity": "abyssaldepths:shadeborn", "data": {"role": "seer"},
 *                "path": [ {"t": 0, "pos": [0, 0, 4], "yaw": 180}, {"t": 80, "pos": [0, 0, 2]} ],
 *                "animations": [ {"t": 60, "name": "zooooooosh"} ]} ]
 * }
 * </pre>
 * The camera either looks along its own yaw and pitch (relative to the anchor's heading) or at a point ("look").
 * Key frames are passed smoothly (a spline through them). Escape skips a skippable cutscene. While one plays, the
 * player's input does nothing and the game's own overlay is hidden (if hide_hud).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class CutscenePlayer {
    private record Key(float t, Vec3 pos, float yaw, float pitch, Vec3 look, float fov, String ease, float roll, float shake) {
    }

    private record Fade(float t, float from, float to, float over, int color) {
    }

    private record Title(float t, Component text, float duration, String style, int color) {
    }

    private record Sound(float t, ResourceLocation sound, float volume, float pitch, Vec3 pos) {
    }

    private record Act(float t, String name) {
    }

    private static final class Actor {
        Entity entity;
        final List<Key> path = new ArrayList<>();
        final List<Act> acts = new ArrayList<>();
        int nextAct;
    }

    private static final class Playing {
        ResourceLocation id;
        Vec3 anchor;
        float yaw, length;
        boolean letterbox, hideHud, skippable;
        final List<Key> camera = new ArrayList<>();
        final List<Fade> fades = new ArrayList<>();
        final List<Title> titles = new ArrayList<>();
        final List<Sound> sounds = new ArrayList<>();
        final List<Actor> actors = new ArrayList<>();
        int nextSound;
        long start;
        Entity eye;
        float fov = Float.NaN, roll;
    }

    private static Playing now;
    private static int nextActorId = -2_000_000;

    private CutscenePlayer() {
    }

    /** Whether a cutscene is playing. */
    public static boolean playing() {
        return now != null;
    }

    /** Plays a cutscene, anchored at a place and a heading (yaw, degrees). False if there is no such cutscene. */
    public static boolean play(ResourceLocation id, Vec3 anchor, float yaw) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }
        final JsonObject json = load(mc, id);
        if (json == null) {
            AbyssalDepths.LOGGER.warn("No cutscene {}", id);
            return false;
        }
        stop();
        final Playing p = new Playing();
        p.id = id;
        p.anchor = anchor;
        p.yaw = yaw;
        p.length = json.has("length") ? json.get("length").getAsFloat() : 100;
        p.letterbox = !json.has("letterbox") || json.get("letterbox").getAsBoolean();
        p.hideHud = !json.has("hide_hud") || json.get("hide_hud").getAsBoolean();
        p.skippable = !json.has("skippable") || json.get("skippable").getAsBoolean();
        for (JsonElement e : array(json, "camera")) {
            p.camera.add(key(e.getAsJsonObject()));
        }
        for (JsonElement e : array(json, "fades")) {
            final JsonObject o = e.getAsJsonObject();
            p.fades.add(new Fade(f(o, "t", 0), f(o, "from", 0), f(o, "to", 1), f(o, "over", 20), color(o, 0x000000)));
        }
        for (JsonElement e : array(json, "titles")) {
            final JsonObject o = e.getAsJsonObject();
            final Component text = o.has("key") && o.get("key").getAsString().equals("@crest") ? redero.abyssaldepths.client.CrestScreen.ownName() // the player's crest
                    : o.has("key") ? Component.translatable(o.get("key").getAsString()) : Component.literal(o.has("text") ? o.get("text").getAsString() : "");
            p.titles.add(new Title(f(o, "t", 0), text, f(o, "duration", 60), o.has("style") ? o.get("style").getAsString() : "title", color(o, 0xFFFFFF)));
        }
        for (JsonElement e : array(json, "sounds")) {
            final JsonObject o = e.getAsJsonObject();
            p.sounds.add(new Sound(f(o, "t", 0), ResourceLocation.parse(o.get("sound").getAsString()), f(o, "volume", 1), f(o, "pitch", 1),
                    o.has("pos") ? vec(o.getAsJsonArray("pos")) : null));
        }
        p.sounds.sort((a, b) -> Float.compare(a.t(), b.t()));
        for (JsonElement e : array(json, "actors")) {
            final JsonObject o = e.getAsJsonObject();
            final Optional<EntityType<?>> type = EntityType.byString(o.get("entity").getAsString());
            if (type.isEmpty()) {
                continue;
            }
            final Entity entity = type.get().create(mc.level);
            if (entity == null) {
                continue;
            }
            final Actor a = new Actor();
            a.entity = entity;
            for (JsonElement k : array(o, "path")) {
                a.path.add(key(k.getAsJsonObject()));
            }
            for (JsonElement k : array(o, "animations")) {
                a.acts.add(new Act(f(k.getAsJsonObject(), "t", 0), k.getAsJsonObject().get("name").getAsString()));
            }
            a.acts.sort((x, y) -> Float.compare(x.t(), y.t()));
            if (entity instanceof CutsceneActor actor && o.has("data")) {
                actor.setUpActor(o.getAsJsonObject("data"));
            }
            entity.setId(nextActorId--);
            final Key first = a.path.isEmpty() ? new Key(0, Vec3.ZERO, 0, 0, null, Float.NaN, "smooth", 0, 0) : a.path.get(0);
            place(p, entity, first.pos(), first.yaw(), true);
            mc.level.addEntity(entity);
            p.actors.add(a);
        }
        p.eye = EntityType.MARKER.create(mc.level);
        if (p.eye != null) {
            p.eye.setId(nextActorId--);
            mc.level.addEntity(p.eye);
            mc.setCameraEntity(p.eye);
        }
        p.start = mc.level.getGameTime();
        now = p;
        frame(0);
        return true;
    }

    /** Ends the cutscene playing (if any): the camera back on the player, its actors gone. */
    public static void stop() {
        final Minecraft mc = Minecraft.getInstance();
        if (now == null) {
            return;
        }
        if (mc.level != null) {
            for (Actor a : now.actors) {
                mc.level.removeEntity(a.entity.getId(), Entity.RemovalReason.DISCARDED);
            }
            if (now.eye != null) {
                mc.level.removeEntity(now.eye.getId(), Entity.RemovalReason.DISCARDED);
            }
        }
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        now = null;
    }

    // ------------------------------------------------------------------ loading

    private static JsonObject load(Minecraft mc, ResourceLocation id) {
        final ResourceLocation file = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "cutscenes/" + id.getPath() + ".json");
        final Optional<Resource> res = mc.getResourceManager().getResource(file);
        if (res.isEmpty()) {
            return null;
        }
        try (Reader r = res.get().openAsReader()) {
            return JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) {
            AbyssalDepths.LOGGER.warn("Cutscene {} could not be read", id, e);
            return null;
        }
    }

    private static JsonArray array(JsonObject o, String name) {
        return o.has(name) ? o.getAsJsonArray(name) : new JsonArray();
    }

    private static float f(JsonObject o, String name, float or) {
        return o.has(name) ? o.get(name).getAsFloat() : or;
    }

    private static int color(JsonObject o, int or) {
        return o.has("color") ? Integer.parseInt(o.get("color").getAsString().replace("#", ""), 16) : or;
    }

    private static Vec3 vec(JsonArray a) {
        return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    private static Key key(JsonObject o) {
        return new Key(f(o, "t", 0), o.has("pos") ? vec(o.getAsJsonArray("pos")) : Vec3.ZERO, f(o, "yaw", 0), f(o, "pitch", 0),
                o.has("look") ? vec(o.getAsJsonArray("look")) : null, f(o, "fov", Float.NaN), o.has("ease") ? o.get("ease").getAsString() : "smooth",
                f(o, "roll", 0), f(o, "shake", 0));
    }

    // ------------------------------------------------------------------ playing

    /** A position relative to the anchor, in the world. */
    private static Vec3 world(Playing p, Vec3 rel) {
        final double a = Math.toRadians(p.yaw);
        final double fx = -Math.sin(a), fz = Math.cos(a); // forward
        final double rx = -fz, rz = fx; // right
        return p.anchor.add(rx * rel.x + fx * rel.z, rel.y, rz * rel.x + fz * rel.z);
    }

    /** Where along key frames a time lies: a smooth curve through their positions, the rest eased between two. */
    private static Key at(List<Key> keys, float t) {
        if (keys.isEmpty()) {
            return new Key(t, Vec3.ZERO, 0, 0, null, Float.NaN, "smooth", 0, 0);
        }
        if (t <= keys.get(0).t() || keys.size() == 1) {
            return keys.get(0);
        }
        int i = 0;
        while (i < keys.size() - 2 && t > keys.get(i + 1).t()) {
            i++;
        }
        final Key a = keys.get(i), b = keys.get(i + 1);
        if (t >= b.t()) {
            return b;
        }
        final float u = (t - a.t()) / Math.max(0.001F, b.t() - a.t());
        final float s = ease(b.ease(), u);
        final Vec3 p0 = i > 0 ? keys.get(i - 1).pos() : a.pos(), p3 = i + 2 < keys.size() ? keys.get(i + 2).pos() : b.pos();
        final Vec3 pos = catmull(p0, a.pos(), b.pos(), p3, s);
        final Vec3 look = a.look() != null && b.look() != null ? a.look().lerp(b.look(), s) : b.look() != null ? b.look() : a.look();
        final float fov = Float.isNaN(a.fov()) ? b.fov() : Float.isNaN(b.fov()) ? a.fov() : Mth.lerp(s, a.fov(), b.fov());
        return new Key(t, pos, Mth.rotLerp(s, a.yaw(), b.yaw()), Mth.lerp(s, a.pitch(), b.pitch()), look, fov, b.ease(), Mth.lerp(s, a.roll(), b.roll()),
                Mth.lerp(s, a.shake(), b.shake()));
    }

    /**
     * The easing of a stretch towards its key: "linear", "smooth" (the default), "in", "out", "inOut", "back" (out with
     * a small overshoot, a landing).
     */
    static float ease(String kind, float u) {
        final float c = Mth.clamp(u, 0, 1);
        return switch (kind) {
            case "linear" -> c;
            case "in" -> c * c * c;
            case "out" -> 1 - (1 - c) * (1 - c) * (1 - c);
            case "inOut" -> c < 0.5F ? 4 * c * c * c : 1 - (float) Math.pow(-2 * c + 2, 3) / 2;
            case "back" -> {
                final float k = 2.04F;
                yield 1 + (k + 1) * (float) Math.pow(c - 1, 3) + k * (float) Math.pow(c - 1, 2);
            }
            default -> c * c * (3 - 2 * c);
        };
    }

    private static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t) {
        final double t2 = t * t, t3 = t2 * t;
        return p0.scale(-0.5 * t3 + t2 - 0.5 * t).add(p1.scale(1.5 * t3 - 2.5 * t2 + 1)).add(p2.scale(-1.5 * t3 + 2 * t2 + 0.5 * t))
                .add(p3.scale(0.5 * t3 - 0.5 * t2));
    }

    private static void place(Playing p, Entity e, Vec3 rel, float yaw, boolean snap) {
        final Vec3 at = world(p, rel);
        if (snap) {
            e.setPos(at);
            e.xo = e.xOld = at.x;
            e.yo = e.yOld = at.y;
            e.zo = e.zOld = at.z;
        } else {
            e.setPos(at);
        }
        e.setYRot(p.yaw + yaw);
        e.setYHeadRot(p.yaw + yaw);
        if (e instanceof net.minecraft.world.entity.LivingEntity l) {
            l.yBodyRot = p.yaw + yaw;
        }
    }

    /** Sets the camera for a moment of the cutscene (every frame, so it moves smoothly). */
    private static void frame(float partial) {
        final Minecraft mc = Minecraft.getInstance();
        final Playing p = now;
        if (p == null || p.eye == null || mc.level == null) {
            return;
        }
        final float t = mc.level.getGameTime() - p.start + partial;
        final Key k = at(p.camera, t);
        final Vec3 pos = world(p, k.pos());
        p.eye.setPos(pos);
        p.eye.xo = p.eye.xOld = pos.x;
        p.eye.yo = p.eye.yOld = pos.y;
        p.eye.zo = p.eye.zOld = pos.z;
        float yaw = p.yaw + k.yaw(), pitch = k.pitch();
        if (k.look() != null) {
            final Vec3 d = world(p, k.look()).subtract(pos);
            yaw = (float) (Mth.atan2(-d.x, d.z) * Mth.RAD_TO_DEG);
            pitch = (float) (-Mth.atan2(d.y, Math.hypot(d.x, d.z)) * Mth.RAD_TO_DEG);
        }
        if (k.shake() > 0) { // a shaking camera (a quake, a blow)
            final float st = t * 1.7F;
            yaw += Mth.sin(st * 2.3F) * k.shake();
            pitch += Mth.sin(st * 3.1F + 1) * k.shake() * 0.7F;
        }
        p.eye.setYRot(yaw);
        p.eye.setXRot(pitch);
        p.eye.yRotO = yaw;
        p.eye.xRotO = pitch;
        p.fov = k.fov();
        p.roll = k.roll();
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        if (now != null) {
            frame(event.getPartialTick().getGameTimeDeltaPartialTick(true));
        }
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final Playing p = now;
        if (p == null) {
            return;
        }
        if (mc.level == null || mc.player == null) {
            now = null;
            return;
        }
        final float t = mc.level.getGameTime() - p.start;
        if (t > p.length) {
            stop();
            return;
        }
        while (p.nextSound < p.sounds.size() && p.sounds.get(p.nextSound).t() <= t) {
            final Sound s = p.sounds.get(p.nextSound++);
            final SoundEvent event2 = BuiltInRegistries.SOUND_EVENT.get(s.sound());
            if (event2 != null) {
                final Vec3 at = s.pos() == null ? mc.gameRenderer.getMainCamera().getPosition() : world(p, s.pos());
                mc.level.playLocalSound(at.x, at.y, at.z, event2, SoundSource.MASTER, s.volume(), s.pitch(), false);
            }
        }
        for (Actor a : p.actors) {
            if (!a.path.isEmpty()) {
                final Key k = at(a.path, t + 1);
                a.entity.xo = a.entity.getX();
                a.entity.yo = a.entity.getY();
                a.entity.zo = a.entity.getZ();
                a.entity.yRotO = a.entity.getYRot();
                place(p, a.entity, k.pos(), k.yaw(), false);
            }
            while (a.nextAct < a.acts.size() && a.acts.get(a.nextAct).t() <= t) {
                final Act act = a.acts.get(a.nextAct++);
                if (a.entity instanceof CutsceneActor actor) {
                    actor.act(act.name(), mc.level.getGameTime());
                }
            }
            a.entity.tickCount++;
        }
    }

    // ------------------------------------------------------------------ while it plays: no input, its own overlay

    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent event) {
        if (now != null) {
            event.getInput().forwardImpulse = 0;
            event.getInput().leftImpulse = 0;
            event.getInput().jumping = false;
            event.getInput().shiftKeyDown = false;
            event.getInput().up = event.getInput().down = event.getInput().left = event.getInput().right = false;
        }
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Opening event) {
        if (now != null && event.getNewScreen() instanceof PauseScreen) {
            if (now.skippable) {
                stop(); // escape skips it
            }
            event.setCanceled(true);
        }
    }

    /**
     * The player in the scene: the game draws one's own player only when the camera is that player, and in a cutscene
     * the camera is the eye, so it is drawn here (as others see it).
     */
    @SubscribeEvent
    public static void onLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (now == null || event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES || mc.player == null
                || mc.player.isSpectator() || mc.player.isInvisible() || mc.getCameraEntity() == mc.player) {
            return;
        }
        final float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        final Vec3 cam = event.getCamera().getPosition();
        final double x = Mth.lerp(partial, mc.player.xOld, mc.player.getX()), y = Mth.lerp(partial, mc.player.yOld, mc.player.getY()),
                z = Mth.lerp(partial, mc.player.zOld, mc.player.getZ());
        final var buffers = mc.renderBuffers().bufferSource();
        mc.getEntityRenderDispatcher().render(mc.player, x - cam.x, y - cam.y, z - cam.z, Mth.lerp(partial, mc.player.yRotO, mc.player.getYRot()), partial,
                event.getPoseStack(), buffers, mc.getEntityRenderDispatcher().getPackedLightCoords(mc.player, partial));
        buffers.endBatch();
    }

    /** No attacking, using or picking in a cutscene (the camera is not the player: a blow would land on the camera itself). */
    @SubscribeEvent
    public static void onKey(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        if (now != null) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /** No name tag over one's own player in a cutscene (it is a scene, not a game view). */
    @SubscribeEvent
    public static void onNameTag(net.neoforged.neoforge.client.event.RenderNameTagEvent event) {
        if (now != null && event.getEntity() == Minecraft.getInstance().player) {
            event.setCanRender(net.neoforged.neoforge.common.util.TriState.FALSE);
        }
    }

    @SubscribeEvent
    public static void onHand(net.neoforged.neoforge.client.event.RenderHandEvent event) {
        if (now != null) {
            event.setCanceled(true); // the camera is not the player's eye: no hand
        }
    }

    @SubscribeEvent
    public static void onAngles(ViewportEvent.ComputeCameraAngles event) {
        if (now != null && now.roll != 0) {
            event.setRoll(now.roll);
        }
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (now != null && !Float.isNaN(now.fov)) {
            event.setFOV(now.fov);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLayer(RenderGuiLayerEvent.Pre event) {
        if (now != null && now.hideHud && !event.getName().getNamespace().equals(AbyssalDepths.MODID)) {
            event.setCanceled(true);
        }
    }

    /** The letterbox, the fades and the titles. */
    static void draw(GuiGraphics g, float partial) {
        final Minecraft mc = Minecraft.getInstance();
        final Playing p = now;
        if (p == null || mc.level == null) {
            return;
        }
        final float t = mc.level.getGameTime() - p.start + partial;
        final int w = g.guiWidth(), h = g.guiHeight();
        if (p.letterbox) {
            final float in = Mth.clamp(Math.min(t, p.length - t) / 15.0F, 0, 1);
            final int bar = (int) (h * 0.11F * in);
            g.fill(0, 0, w, bar, 0xFF000000);
            g.fill(0, h - bar, w, h, 0xFF000000);
        }
        for (Title title : p.titles) {
            final float u = t - title.t();
            if (u < 0 || u > title.duration()) {
                continue;
            }
            final float alpha = Mth.clamp(Math.min(u, title.duration() - u) / 12.0F, 0, 1);
            final int a = (int) (alpha * 255) << 24;
            if (a == 0) {
                continue;
            }
            if (title.style().equals("pop")) { // letter by letter, each landing with a small overshoot
                popTitle(g, mc, title, u, w, h, a);
                continue;
            }
            final boolean big = title.style().equals("title");
            g.pose().pushPose();
            final float scale = big ? 2.4F : 1.0F;
            g.pose().translate(w / 2.0F, big ? h * 0.42F : h * 0.8F, 0);
            g.pose().scale(scale, scale, 1);
            final int tw = mc.font.width(title.text());
            g.drawString(mc.font, title.text(), -tw / 2, -4, a | (title.color() & 0xFFFFFF), false);
            g.pose().popPose();
        }
        for (Fade f : p.fades) {
            final float u = t - f.t();
            if (u < 0) {
                continue;
            }
            final boolean last = p.fades.stream().noneMatch(o -> o.t() > f.t() && o.t() <= t);
            if (!last) {
                continue;
            }
            final float k = Mth.lerp(Mth.clamp(u / Math.max(1, f.over()), 0, 1), f.from(), f.to());
            if (k > 0.001F) {
                g.fill(0, 0, w, h, (int) (k * 255) << 24 | f.color() & 0xFFFFFF);
            }
        }
    }

    private static void popTitle(GuiGraphics g, Minecraft mc, Title title, float u, int w, int h, int alpha) {
        final String text = title.text().getString();
        final float scale = 2.6F, total = mc.font.width(text) * scale;
        float x = w / 2.0F - total / 2;
        for (int i = 0; i < text.length(); i++) {
            final String ch = text.substring(i, i + 1);
            final float cw = mc.font.width(ch) * scale, local = (u - i * 1.2F) / 10.0F;
            if (local > 0 && !ch.isBlank()) {
                final float k = ease("back", local);
                g.pose().pushPose();
                g.pose().translate(x + cw / 2, h * 0.42F + (1 - ease("out", local)) * 14, 0);
                g.pose().scale(scale * (0.6F + 0.4F * k), scale * (0.6F + 0.4F * k), 1);
                final int a = Math.min(alpha >>> 24, (int) (Mth.clamp(local * 2.5F, 0, 1) * 255));
                g.drawString(mc.font, ch, -mc.font.width(ch) / 2, -4, a << 24 | (title.color() & 0xFFFFFF), false);
                g.pose().popPose();
            }
            x += cw;
        }
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layer {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "cutscene"),
                    (graphics, delta) -> draw(graphics, delta.getGameTimeDeltaPartialTick(true)));
        }
    }
}
