package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.ClientConfig.ShadeVanishStyle;

/**
 * How shades vanish and come back for those who see it ({@link ClientConfig#shadeVanish}): when a player
 * near this client turns hidden (stepping into the shadow, while this client stays out; or leaving it, seen
 * from inside), the vanishing plays on them, and when one turns visible, it plays backwards; either way
 * they keep moving while it does (if they are gone meanwhile, a still copy finishes it where they were).
 * Changes are caught the moment the server's word arrives ({@link #check}), not only every tick: a frame
 * of the player drawn plainly in between would flicker.
 * <ul>
 * <li>PIXELS: the body darkens and fades while it falls apart into black and purple pixels flying out, which
 * go out one by one;</li>
 * <li>ASH: the body darkens to a black silhouette, then fades while ash rises off it, from the feet up;</li>
 * <li>SLIDE: the body slides sideways out of the world, thinning to a violet line;</li>
 * <li>SHADOW: the body fades into the dark and leaves a pixelated shadow on the ground, which shrinks away;</li>
 * <li>DISINTEGRATE: the skin crumbles away pixel by pixel, the top first: every pixel of it breaks off the
 * body (leaving a hole) and falls, darkening to purple, until nothing is left;</li>
 * <li>DISSOLVE: the same, but the pixels just go out.</li>
 * </ul>
 * {@link #preview} plays one on a copy of the player in front of them (the command).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class ShadeVanish {
    /** Only players this close play it. */
    private static final double RANGE = 64.0;
    private static final int BLACK = 0x050307, DEEP = 0x2A0F45, VIOLET = 0x5C2A93, BRIGHT = 0x8E52D6;
    private static final int ASH = 0x1A181C, ASH_LIGHT = 0x34303A, EMBER = 0x4A2A6A, LINE = 0xB8A6FF;
    /** The dark purple a disintegrating body turns. */
    private static final int DUSK = 0x3A2450;

    /** What a fade does: vanish, appear (the vanishing backwards), or just stand (in a preview). */
    private enum Mode { VANISH, APPEAR, HOLD }

    /**
     * One fade. {@code entityId} is the player it is about (-1 for a preview); a real player appearing is
     * drawn where they are, everything else where it was ({@code pos}).
     */
    private record Fade(int entityId, Mode mode, long start, long length, ShadeVanishStyle style, AbstractClientPlayer ghost, Vec3 pos,
            float width, float height, int seed) {
        boolean appear() {
            return mode == Mode.APPEAR;
        }

        long duration() {
            return length;
        }

        /** How long a style takes. */
        static long duration(ShadeVanishStyle style) {
            return switch (style) {
                case PIXELS -> 1400;
                case ASH -> 2000;
                case SLIDE -> 900;
                case SHADOW -> 2600;
                case DISINTEGRATE -> 2200;
                case DISSOLVE -> 1600;
                case NONE -> 0;
            };
        }

        /** How far it has vanished: 0 .. 1 (backwards when appearing, 0 while standing). */
        float progress(long now) {
            final float t = Mth.clamp((now - start) / (float) length, 0.0F, 1.0F);
            return switch (mode) {
                case VANISH -> t;
                case APPEAR -> 1.0F - t;
                case HOLD -> 0.0F;
            };
        }

        boolean done(long now) {
            return now - start >= duration();
        }
    }

    private static final List<Fade> FADES = new ArrayList<>();
    /** The crumbling bodies of the disintegrating fades. */
    private static final Map<Fade, Crumble> CRUMBLES = new HashMap<>();
    /** Whether each player was hidden last tick. */
    private static final Map<Integer, Boolean> HIDDEN = new HashMap<>();
    private static boolean wasInShadow;
    private static ClientLevel lastLevel;
    private static int seeds;
    /** The rest of a preview: fades waiting for their turn. */
    private static final List<Fade> PREVIEW = new ArrayList<>();

    private ShadeVanish() {
    }

    /** Whether the entity is still appearing (and only its fade is drawn). */
    static boolean appearing(Entity entity) {
        for (Fade fade : FADES) {
            if (fade.appear() && fade.entityId() == entity.getId()) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null || mc.player == null || level != lastLevel) {
            FADES.clear();
            PREVIEW.clear();
            CRUMBLES.values().forEach(Crumble::close);
            CRUMBLES.clear();
            HIDDEN.clear();
            lastLevel = level;
            return;
        }
        final long now = Util.getMillis();
        FADES.removeIf(fade -> fade.done(now));
        CRUMBLES.entrySet().removeIf(entry -> {
            if (FADES.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
        while (!PREVIEW.isEmpty() && PREVIEW.get(0).start() <= now) { // the next part of a preview
            FADES.add(PREVIEW.remove(0));
        }
        check();
    }

    /** Starts a fade for every player near this client whose visibility changed since the last look. */
    public static void check() {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null || mc.player == null || level != lastLevel) {
            return;
        }
        final long now = Util.getMillis();
        // the client's own crossing changes who it sees all at once: that is the crossing's business
        final boolean viewerChanged = ShadeView.inShadow() != wasInShadow;
        wasInShadow = ShadeView.inShadow();
        final ShadeVanishStyle style = ClientConfig.shadeVanish();
        final Set<Integer> present = new HashSet<>();
        for (Player player : level.players()) {
            if (player == mc.player || !(player instanceof AbstractClientPlayer other)) {
                continue;
            }
            present.add(player.getId());
            final boolean hidden = ShadeView.seenHidden(player);
            final Boolean before = HIDDEN.put(player.getId(), hidden);
            if (before != null && before != hidden && !viewerChanged && style != ShadeVanishStyle.NONE
                    && player.distanceToSqr(mc.player) < RANGE * RANGE) {
                FADES.removeIf(fade -> fade.entityId() == player.getId());
                FADES.add(new Fade(player.getId(), hidden ? Mode.VANISH : Mode.APPEAR, now, Fade.duration(style), style,
                        hidden ? copy(level, other) : other, player.position(), player.getBbWidth(), player.getBbHeight(), seeds++));
            }
        }
        HIDDEN.keySet().retainAll(present);
    }

    /**
     * Plays a vanishing on a copy of this client's player, three blocks in front of them and facing them;
     * after a moment it comes back, stands for a second and vanishes again (so it ends gone, as it began).
     */
    public static void preview(ShadeVanishStyle style) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || style == ShadeVanishStyle.NONE) {
            return;
        }
        final Vec3 look = Vec3.directionFromRotation(0.0F, mc.player.getYRot());
        final AbstractClientPlayer ghost = copy(mc.level, mc.player);
        final Vec3 pos = mc.player.position().add(look.scale(3.0));
        ghost.setPos(pos);
        ghost.setOldPosAndRot();
        ghost.yBodyRot = ghost.yBodyRotO = ghost.yHeadRot = ghost.yHeadRotO = mc.player.getYRot() + 180.0F;
        ghost.setXRot(0.0F);
        ghost.xRotO = 0.0F;
        final long now = Util.getMillis(), length = Fade.duration(style);
        final float w = ghost.getBbWidth(), h = ghost.getBbHeight();
        PREVIEW.clear();
        FADES.removeIf(fade -> fade.entityId() == -1);
        FADES.add(new Fade(-1, Mode.VANISH, now, length, style, ghost, pos, w, h, seeds++));
        final long back = now + length + 700;
        PREVIEW.add(new Fade(-1, Mode.APPEAR, back, length, style, ghost, pos, w, h, seeds++));
        PREVIEW.add(new Fade(-1, Mode.HOLD, back + length, 1000, style, ghost, pos, w, h, seeds));
        PREVIEW.add(new Fade(-1, Mode.VANISH, back + length + 1000, length, style, ghost, pos, w, h, seeds++));
    }

    /** A still copy of the player as they are now, to vanish in their place. */
    private static AbstractClientPlayer copy(ClientLevel level, AbstractClientPlayer player) {
        final RemotePlayer ghost = new RemotePlayer(level, player.getGameProfile());
        ghost.setPos(player.position());
        ghost.setOldPosAndRot(); // or it is drawn between the world's origin and here
        ghost.yBodyRot = ghost.yBodyRotO = player.yBodyRot;
        ghost.yHeadRot = ghost.yHeadRotO = player.yHeadRot;
        ghost.setXRot(player.getXRot());
        ghost.xRotO = player.getXRot();
        ghost.setPose(player.getPose());
        return ghost;
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || FADES.isEmpty() || mc.level == null) {
            return;
        }
        final long now = Util.getMillis();
        final float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        final Camera camera = event.getCamera();
        final Vec3 cam = camera.getPosition();
        final Vector3f right = new Vector3f(camera.getLeftVector()).negate(), up = new Vector3f(camera.getUpVector());
        final PoseStack pose = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (Fade fade : FADES) {
            // the player themselves while they are there (moving on), else the still copy where they were
            final AbstractClientPlayer player = fade.entityId() >= 0 && mc.level.getEntity(fade.entityId()) instanceof AbstractClientPlayer found
                    && !found.isRemoved() ? found : null;
            if (player == null && fade.appear()) {
                continue;
            }
            final boolean live = player != null;
            final AbstractClientPlayer subject = live ? player : fade.ghost();
            final float p = fade.progress(now);
            final Vec3 at = live ? subject.getPosition(partial) : fade.pos();
            final float subjectPartial = live ? partial : 1.0F;
            pose.pushPose();
            pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
            final Matrix4f matrix = pose.last().pose();
            switch (fade.style()) {
                case PIXELS -> {
                    final float alpha = 1.0F - smooth(p / 0.55F);
                    body(subject, subjectPartial, pose, buffers, alpha, mix(0xFFFFFF, 0x6A4A8A, smooth(p / 0.3F)));
                    pixels(quads(buffers), matrix, fade, p, right, up);
                }
                case ASH -> {
                    final float alpha = 1.0F - smooth((p - 0.35F) / 0.45F);
                    body(subject, subjectPartial, pose, buffers, alpha, mix(0xFFFFFF, 0x000000, smooth(p / 0.35F)));
                    ash(quads(buffers), matrix, fade, p, right, up);
                }
                case SLIDE -> {
                    final float e = smooth(p);
                    final float angle = (float) Math.atan2(-right.z(), right.x());
                    pose.pushPose();
                    pose.translate(right.x() * 0.9F * e, 0.0F, right.z() * 0.9F * e);
                    pose.mulPose(Axis.YP.rotation(angle));
                    pose.scale(Math.max(0.02F, 1.0F - e), 1.0F, 1.0F); // thinning across the view
                    pose.mulPose(Axis.YP.rotation(-angle));
                    body(subject, subjectPartial, pose, buffers, 1.0F - smooth((p - 0.75F) / 0.25F), mix(0xFFFFFF, LINE, e));
                    pose.popPose();
                }
                case SHADOW -> {
                    body(subject, subjectPartial, pose, buffers, 1.0F - smooth(p / 0.3F), mix(0xFFFFFF, 0x000000, smooth(p / 0.25F)));
                    shadow(quads(buffers), matrix, fade, p);
                }
                case DISINTEGRATE, DISSOLVE -> {
                    final Crumble crumble = p > 0.0F ? CRUMBLES.computeIfAbsent(fade, f -> Crumble.of(f, subject)) : null;
                    if (crumble == null) {
                        body(subject, subjectPartial, pose, buffers, 1.0F, 0xFFFFFF);
                    } else {
                        final float yaw = Mth.rotLerp(subjectPartial, subject.yBodyRotO, subject.yBodyRot);
                        crumble.show(p, at, yaw);
                        GhostRenderer.draw(subject, subject.yBodyRot, subjectPartial, pose, buffers, 1.0F, mix(0xFFFFFF, 0x9A88B0, smooth(p / 0.6F)), crumble.id);
                        if (fade.style() == ShadeVanishStyle.DISINTEGRATE) {
                            crumble.fall(quads(buffers), matrix, p, fade.duration() / 1000.0F, at, yaw, !fade.appear(), right, up);
                        }
                    }
                }
                case NONE -> {
                }
            }
            pose.popPose();
        }
        buffers.endBatch();
    }

    /** The buffer for the pixels: fetched after the body, as fetching another one closes it. */
    private static VertexConsumer quads(MultiBufferSource buffers) {
        return buffers.getBuffer(RenderType.debugQuads());
    }

    private static void body(AbstractClientPlayer subject, float partial, PoseStack pose, MultiBufferSource buffers, float alpha, int tint) {
        if (alpha > 0.01F) {
            GhostRenderer.draw(subject, subject.yBodyRot, partial, pose, buffers, alpha, tint);
        }
    }

    /** Pixels flying out of the body, each let go at its own moment and going out at its own. */
    private static void pixels(VertexConsumer quads, Matrix4f matrix, Fade fade, float p, Vector3f right, Vector3f up) {
        for (int i = 0; i < 170; i++) {
            final int n = fade.seed() * 1000 + i;
            final float release = noise(n, 1) * 0.55F;
            final float life = (p - release) / 0.45F;
            if (life < 0.0F || life > 0.6F + 0.4F * noise(n, 2)) {
                continue;
            }
            final float sx = (noise(n, 3) - 0.5F) * fade.width(), sy = noise(n, 4) * fade.height(), sz = (noise(n, 5) - 0.5F) * fade.width();
            final Vector3f dir = new Vector3f(sx, 0.25F + 0.5F * noise(n, 6), sz).normalize();
            final float distance = (0.6F + 1.0F * noise(n, 7)) * (1.0F - (1.0F - life) * (1.0F - life));
            final float size = 0.06F * (1.0F - 0.5F * life) * (0.7F + 0.6F * noise(n, 8));
            final float r = noise(n, 9);
            final int rgb = r < 0.45F ? BLACK : r < 0.75F ? DEEP : r < 0.93F ? VIOLET : BRIGHT;
            billboard(quads, matrix, sx + dir.x() * distance, sy + dir.y() * distance, sz + dir.z() * distance, right, up, size, 0xFF000000 | rgb);
        }
    }

    /** Ash rising off the body, from the feet up, drifting and shrinking. */
    private static void ash(VertexConsumer quads, Matrix4f matrix, Fade fade, float p, Vector3f right, Vector3f up) {
        for (int i = 0; i < 220; i++) {
            final int n = fade.seed() * 1000 + i;
            final float height = noise(n, 4);
            final float release = 0.2F + height * 0.45F + noise(n, 1) * 0.1F; // the feet first
            final float life = (p - release) / 0.4F;
            if (life < 0.0F || life > 1.0F) {
                continue;
            }
            final float sx = (noise(n, 3) - 0.5F) * fade.width(), sz = (noise(n, 5) - 0.5F) * fade.width();
            final float rise = (0.8F + 0.8F * noise(n, 6)) * life * life + 0.2F * life;
            final float sway = 0.15F * Mth.sin(life * 5.0F + noise(n, 7) * Mth.TWO_PI);
            final float size = 0.045F * (1.0F - life) * (0.6F + 0.8F * noise(n, 8));
            final float r = noise(n, 9);
            final int rgb = r < 0.6F ? ASH : r < 0.95F ? ASH_LIGHT : EMBER;
            final int alpha = (int) ((1.0F - life * life) * 255);
            billboard(quads, matrix, sx + sway, height * fade.height() + rise, sz + sway * 0.5F, right, up, size, alpha << 24 | rgb);
        }
    }

    /**
     * A body crumbling away: a copy of its skin from which the pixels are taken one by one (each at its own
     * moment, the top first), and every taken pixel falling from where it sat on the body.
     */
    private static final class Crumble {
        /** How long a broken-off pixel falls, in seconds. */
        private static final float FALL = 0.6F;

        final ResourceLocation id;
        private final DynamicTexture texture;
        private final NativeImage skin;
        private final List<SkinPixels.Texel> texels;
        /** When each pixel breaks off (as part of the vanishing). */
        private final float[] when;
        private int shown = -1;
        /** Where each pixel broke off, in the world (a moving body leaves them behind), and which way it faced. */
        private final double[] origin;
        private final float[] normal;
        private final boolean[] broken;

        private Crumble(ResourceLocation id, DynamicTexture texture, NativeImage skin, List<SkinPixels.Texel> texels, float[] when) {
            this.id = id;
            this.texture = texture;
            this.skin = skin;
            this.texels = texels;
            this.when = when;
            this.origin = new double[texels.size() * 3];
            this.normal = new float[texels.size() * 3];
            this.broken = new boolean[texels.size()];
        }

        static Crumble of(Fade fade, AbstractClientPlayer subject) {
            final NativeImage skin = SkinPixels.read(subject.getSkin().texture());
            final NativeImage copy = new NativeImage(64, 64, false);
            if (skin != null) {
                copy.copyFrom(skin);
            }
            final List<SkinPixels.Texel> texels = skin == null ? List.of() : SkinPixels.texels(skin, subject.getSkin().model() == PlayerSkin.Model.SLIM);
            final float[] when = new float[texels.size()];
            for (int i = 0; i < when.length; i++) {
                final float top = 1.0F - Mth.clamp(texels.get(i).up() / 2.0F, 0.0F, 1.0F); // 0 at the top of the head
                // falling pixels need time left to fall; going out, they can take the whole time
                final float spread = fade.style() == ShadeVanishStyle.DISSOLVE ? 0.9F : 0.6F;
                when[i] = 0.04F + spread * (0.55F * noise(fade.seed() * 7 + i, 3) + 0.45F * top);
            }
            final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "crumble/" + fade.seed());
            final DynamicTexture texture = new DynamicTexture(copy);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            return new Crumble(id, texture, skin, texels, when);
        }

        /**
         * Takes out of the copy every pixel broken off by p (puts back the others: appearing runs backwards),
         * and notes where in the world the newly broken ones were, on the body at {@code at} turned {@code yaw}.
         */
        void show(float p, Vec3 at, float yaw) {
            final float y = yaw * Mth.DEG_TO_RAD;
            final float rx = -Mth.cos(y), rz = -Mth.sin(y), fx = -Mth.sin(y), fz = Mth.cos(y); // the body's right and forward
            int count = 0;
            for (int i = 0; i < when.length; i++) {
                final boolean now = when[i] < p;
                count += now ? 1 : 0;
                if (now && !broken[i]) {
                    final SkinPixels.Texel t = texels.get(i);
                    origin[i * 3] = at.x + t.right() * rx + t.forward() * fx;
                    origin[i * 3 + 1] = at.y + t.up();
                    origin[i * 3 + 2] = at.z + t.right() * rz + t.forward() * fz;
                    normal[i * 3] = t.nr() * rx + t.nf() * fx;
                    normal[i * 3 + 1] = t.nu();
                    normal[i * 3 + 2] = t.nr() * rz + t.nf() * fz;
                }
                broken[i] = now;
            }
            if (count == shown || skin == null) {
                return;
            }
            shown = count;
            final NativeImage pixels = texture.getPixels();
            for (int i = 0; i < texels.size(); i++) {
                final SkinPixels.Texel t = texels.get(i);
                pixels.setPixelRGBA(t.x(), t.y(), when[i] < p ? 0 : skin.getPixelRGBA(t.x(), t.y()));
            }
            texture.upload();
        }

        /**
         * The broken-off pixels falling: a little push outwards, then down, darkening to purple. Vanishing,
         * they fall from where they broke off; appearing, they rise to where the body is now. The matrix is
         * at {@code at}.
         */
        void fall(VertexConsumer quads, Matrix4f matrix, float p, float seconds, Vec3 at, float yaw, boolean leftBehind, Vector3f right, Vector3f up) {
            final float y0 = yaw * Mth.DEG_TO_RAD;
            final float rx = -Mth.cos(y0), rz = -Mth.sin(y0), fx = -Mth.sin(y0), fz = Mth.cos(y0);
            for (int i = 0; i < texels.size(); i++) {
                final float age = (p - when[i]) * seconds;
                if (age < 0.0F || age > FALL) {
                    continue;
                }
                final SkinPixels.Texel t = texels.get(i);
                final float ox, oy, oz, nx, ny, nz;
                if (leftBehind && broken[i]) {
                    ox = (float) (origin[i * 3] - at.x);
                    oy = (float) (origin[i * 3 + 1] - at.y);
                    oz = (float) (origin[i * 3 + 2] - at.z);
                    nx = normal[i * 3];
                    ny = normal[i * 3 + 1];
                    nz = normal[i * 3 + 2];
                } else {
                    ox = t.right() * rx + t.forward() * fx;
                    oy = t.up();
                    oz = t.right() * rz + t.forward() * fz;
                    nx = t.nr() * rx + t.nf() * fx;
                    ny = t.nu();
                    nz = t.nr() * rz + t.nf() * fz;
                }
                final float push = 0.3F * age;
                final float y = oy + ny * push + 0.2F * age - 4.5F * age * age;
                final int rgb = mix(SkinPixels.fromAbgr(skin.getPixelRGBA(t.x(), t.y())), DUSK, age / FALL);
                billboard(quads, matrix, ox + nx * push, y, oz + nz * push, right, up, 0.028F, 0xFF000000 | rgb);
            }
        }

        void close() {
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    /** A pixelated shadow on the ground: grows under the fading body, then shrinks away. */
    private static void shadow(VertexConsumer quads, Matrix4f matrix, Fade fade, float p) {
        final float radius = 0.75F * (p < 0.15F ? smooth(p / 0.15F) : 1.0F - smooth((p - 0.15F) / 0.85F));
        final float cell = 1.0F / 8.0F;
        for (int i = -6; i < 6; i++) {
            for (int j = -6; j < 6; j++) {
                final float x = (i + 0.5F) * cell, z = (j + 0.5F) * cell;
                final float d = (float) Math.sqrt(x * x + z * z);
                if (d > radius * (0.75F + 0.25F * noise(fade.seed() * 31 + i, j))) {
                    continue;
                }
                final int alpha = (int) (200 * (1.0F - 0.5F * d / 0.75F));
                final float y = 0.02F;
                final int colour = alpha << 24 | BLACK;
                quads.addVertex(matrix, x - cell / 2, y, z - cell / 2).setColor(colour);
                quads.addVertex(matrix, x - cell / 2, y, z + cell / 2).setColor(colour);
                quads.addVertex(matrix, x + cell / 2, y, z + cell / 2).setColor(colour);
                quads.addVertex(matrix, x + cell / 2, y, z - cell / 2).setColor(colour);
            }
        }
    }

    /** A square facing the camera. */
    private static void billboard(VertexConsumer quads, Matrix4f matrix, float x, float y, float z, Vector3f right, Vector3f up, float size, int argb) {
        final float rx = right.x() * size, ry = right.y() * size, rz = right.z() * size;
        final float ux = up.x() * size, uy = up.y() * size, uz = up.z() * size;
        quads.addVertex(matrix, x - rx - ux, y - ry - uy, z - rz - uz).setColor(argb);
        quads.addVertex(matrix, x + rx - ux, y + ry - uy, z + rz - uz).setColor(argb);
        quads.addVertex(matrix, x + rx + ux, y + ry + uy, z + rz + uz).setColor(argb);
        quads.addVertex(matrix, x - rx + ux, y - ry + uy, z - rz + uz).setColor(argb);
    }

    private static float smooth(float t) {
        final float c = Mth.clamp(t, 0.0F, 1.0F);
        return c * c * (3 - 2 * c);
    }

    private static int mix(int a, int b, float t) {
        final int r = (int) Mth.lerp(t, (a >> 16) & 255, (b >> 16) & 255);
        final int g = (int) Mth.lerp(t, (a >> 8) & 255, (b >> 8) & 255);
        final int bl = (int) Mth.lerp(t, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    /** A fixed random value 0 .. 1. */
    private static float noise(int a, int b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }
}
