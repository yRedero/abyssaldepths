package redero.abyssaldepths.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.artifact.ParallaxItem;
import redero.abyssaldepths.artifact.Shades;

/**
 * How a shade sees the world, and the crossing into it.
 * <p>
 * The crossing runs from 0 (the world) to 1 (the shadow) in the time the client config gives: from spots
 * all over the screen black spots open and grow into each other until it is black; then from the same
 * spots wave after wave runs outwards, each one taking the black a step further towards transparent, until
 * only translucent purple pixels are left, which stay while in the shadow
 * (denser at the edges, some of them lighting up now and then). Leaving runs the same backwards. How many
 * spots there are is in the client config. In the shadow only the shadow's sounds are heard ({@link #onSound}).
 * <p>
 * Behind the black the view changes: the shadow's own look, a post effect ({@code shaders/program/shade.fsh})
 * with purple outlines, drained and darkened colours and more (the shades are drawn again over it, in their
 * full colours, if the client config wants it: {@link #afterPost}); and other entities are hidden (but other
 * shades, and everything for a Parallax at full mastery, see {@link ParallaxItem#seesWorld}).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class ShadeView {
    private static final ResourceLocation EFFECT = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "shaders/post/shade.json");
    /** A player this young (in ticks) has just joined: who is in the shadow then was in it already. */
    static final int JOINED_TICKS = 60;
    /** The game mode seen last tick: changing it can leave the post effect broken, so it is loaded anew. */
    private static net.minecraft.world.level.GameType lastMode;

    /** The level's matrices and partial tick of this frame, for drawing the shades again (afterPost). */
    private static Matrix4f levelProjection, levelModelView;
    private static float levelPartial;
    /** The effect's target holding a copy of the world's depth. */
    private static final String DEPTH_TARGET = "world_depth";
    /** One pixel of the crossing (GUI pixels, as in Reprise's cover). */
    private static final int CELL = 10;
    /** The black and the rim of the growing spots. */
    private static final int BLACK = 0x050307, RIM = 0x1C0B2B;
    /**
     * The clearing waves (second half of the crossing): how many, how far apart and how soft their edge
     * (in reach, 0 .. 1). Each one takes away an equal share of the black.
     */
    private static final int WAVES = 10;
    private static final float WAVE_GAP = 0.07F, WAVE_EDGE = 0.025F;
    /** The pixels that stay. */
    private static final int PURPLE = 0x3A1E50, VIOLET = 0x8E52D6;

    private static float crossing, previous;
    private static boolean seesWorld;
    /** Per pixel: when the black reaches it (0 .. 1), for the current screen and crossing. */
    private static float[] reach = new float[0];
    private static int reachCols, reachRows, reachSeed = -1, reachSpots, seed;
    /** Set while a sound that may play in the shadow is started (the server's and the player's own). */
    public static boolean allowSounds;

    private ShadeView() {
    }

    /** Whether this client looks at the shadow (from the black moment of the crossing on). */
    public static boolean inShadow() {
        return crossing >= 0.5F;
    }

    /**
     * Whether the entity is hidden from this client: shades from those outside the shadow; in the shadow,
     * everything but other shades (unless the artifact lets it see the world); a shade still appearing.
     */
    public static boolean hidden(Entity entity) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || entity == mc.player) {
            return false;
        }
        if (ShadeVanish.appearing(entity)) {
            return true;
        }
        return seenHidden(entity);
    }

    /** The skin a shade wears in this client's view of the shadow (in the configured look), or null for its own. */
    public static ResourceLocation shadeSkin(AbstractClientPlayer player) {
        final ClientConfig.ShadeLook look = ClientConfig.shadeLook();
        if (look == ClientConfig.ShadeLook.NONE || !inShadow() || !Shades.isShade(player)) {
            return null;
        }
        return SkinPixels.shadeSkin(player.getSkin().texture(), look);
    }

    /**
     * The skin a player coming into the shadow or leaving it wears in this client's view while it plays:
     * the look, if this client is in the shadow (null otherwise, or without a look).
     */
    static ResourceLocation lookSkin(AbstractClientPlayer player) {
        final ClientConfig.ShadeLook look = ClientConfig.shadeLook();
        return look == ClientConfig.ShadeLook.NONE || !inShadow() ? null : SkinPixels.shadeSkin(player.getSkin().texture(), look);
    }

    /** Hidden by the rules alone (without the appearing ones). */
    static boolean seenHidden(Entity entity) {
        return inShadow() ? !Shades.isShade(entity) && !seesWorld : Shades.isShade(entity);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final boolean shade = mc.player != null && Shades.isShade(mc.player);
        previous = crossing;
        if (mc.player == null) {
            crossing = previous = 0.0F;
        } else {
            if (shade && crossing == 0.0F) {
                seed++; // new spots every time
                if (mc.player.tickCount < JOINED_TICKS) {
                    crossing = previous = 1.0F; // joined the game in the shadow: already there, no crossing
                }
            }
            final float step = 50.0F / ClientConfig.parallaxTransitionMillis();
            crossing = Mth.clamp(crossing + (shade ? step : -step), 0.0F, 1.0F);
        }
        seesWorld = mc.player != null && ParallaxItem.seesWorld(mc.player);
        if (inShadow() && previous < 0.5F) {
            mc.getSoundManager().stop(); // what plays in the world stops at the crossing
        }

        final net.minecraft.world.level.GameType mode = mc.gameMode == null ? null : mc.gameMode.getPlayerMode();
        if (mode != lastMode && ours() != null) {
            mc.gameRenderer.shutdownEffect(); // reloaded right below
        }
        lastMode = mode;
        final boolean wanted = crossing > 0.0F;
        if (wanted && ours() == null) {
            mc.gameRenderer.loadEffect(EFFECT);
        } else if (!wanted && ours() != null) {
            mc.gameRenderer.shutdownEffect();
        }
    }

    /**
     * In the shadow only the sounds of the shadow are heard: those the server sends (it keeps them to their
     * side: other shades), the player's own (also what is in their head, {@link #ownSound}), and the menus'.
     * No ambience, music, weather or blocks.
     */
    @SubscribeEvent
    public static void onSound(PlaySoundEvent event) {
        if (inShadow() && !allowSounds && event.getSound() != null && event.getSound().getSource() != SoundSource.MASTER) {
            event.setSound(null);
        }
    }

    /** Plays a sound that is in the player's own head (the deep pressure): heard in the shadow too. */
    public static void ownSound(net.minecraft.client.resources.sounds.SoundInstance sound) {
        allowSounds = true;
        try {
            Minecraft.getInstance().getSoundManager().play(sound);
        } finally {
            allowSounds = false;
        }
    }

    /** The shadow's post effect, if it is the one running. */
    private static PostChain ours() {
        final PostChain current = Minecraft.getInstance().gameRenderer.currentEffect();
        return current != null && current.getName().equals(EFFECT.toString()) ? current : null;
    }

    /** Every frame: how far the shadow's look is in (it changes while the screen is black). */
    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        final PostChain effect = ours();
        if (effect != null) {
            final float v = Mth.lerp(event.getPartialTick().getGameTimeDeltaPartialTick(true), previous, crossing);
            final float t = Mth.clamp((v - 0.44F) / 0.12F, 0.0F, 1.0F);
            effect.setUniform("Amount", t * t * (3 - 2 * t));
        }
    }

    private static void draw(GuiGraphics g, float partialTick) {
        final float v = Mth.lerp(partialTick, previous, crossing);
        if (v <= 0.0F) {
            return;
        }
        final int cols = (g.guiWidth() + CELL - 1) / CELL, rows = (g.guiHeight() + CELL - 1) / CELL;
        if (cols != reachCols || rows != reachRows || seed != reachSeed || ClientConfig.parallaxSpots() != reachSpots) {
            spots(cols, rows);
        }
        final float time = Util.getMillis() / 1000.0F;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                final int colour = colour(col, row, cols, rows, v, time);
                if (colour >>> 24 != 0) {
                    g.fill(col * CELL, row * CELL, col * CELL + CELL, row * CELL + CELL, colour);
                }
            }
        }
    }

    /**
     * At the end of the world's drawing: its depth copied into the effect's own target, which the outlines
     * are found in. The game clears the depth before it draws the hand, and the effect runs after that.
     */
    @SubscribeEvent
    public static void onLevelDrawn(RenderLevelStageEvent event) {
        final PostChain effect = event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL ? ours() : null;
        final RenderTarget depth = effect == null ? null : effect.getTempTarget(DEPTH_TARGET);
        if (depth != null) {
            final RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            depth.copyDepthFrom(main);
            main.bindWrite(false);
            // kept for drawing the shades again after the effect (afterPost)
            levelProjection = new Matrix4f(event.getProjectionMatrix());
            levelModelView = new Matrix4f(event.getModelViewMatrix());
            levelPartial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        }
    }

    /**
     * After the post effect, the shades are drawn once more over the drained picture, so they keep the full
     * colour of their look (client config, off by default: then they are drained with the rest). The world's depth is put back first
     * (the copy the effect keeps), so the world still hides them where it is in front.
     */
    public static void afterPost() {
        final Minecraft mc = Minecraft.getInstance();
        final PostChain effect = ours();
        final RenderTarget depth = effect == null ? null : effect.getTempTarget(DEPTH_TARGET);
        if (depth == null || !ClientConfig.shadeFullColour() || !inShadow() || mc.level == null || mc.player == null || levelProjection == null) {
            return;
        }
        final java.util.List<AbstractClientPlayer> shades = new java.util.ArrayList<>();
        for (AbstractClientPlayer player : mc.level.players()) {
            final boolean own = player == mc.player;
            if (Shades.isShade(player) && !hidden(player) && (!own || !mc.options.getCameraType().isFirstPerson()) && !player.isInvisible()) {
                shades.add(player);
            }
        }
        if (shades.isEmpty()) {
            return;
        }
        final RenderTarget main = mc.getMainRenderTarget();
        main.copyDepthFrom(depth);
        main.bindWrite(false);
        final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        final com.mojang.blaze3d.vertex.VertexSorting sorting = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(levelProjection, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        final org.joml.Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(levelModelView);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableDepthTest();
        final net.minecraft.world.phys.Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        final com.mojang.blaze3d.vertex.PoseStack pose = new com.mojang.blaze3d.vertex.PoseStack();
        final net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        for (AbstractClientPlayer player : shades) {
            final double x = Mth.lerp(levelPartial, player.xOld, player.getX()) - camera.x;
            final double y = Mth.lerp(levelPartial, player.yOld, player.getY()) - camera.y;
            final double z = Mth.lerp(levelPartial, player.zOld, player.getZ()) - camera.z;
            final float yaw = Mth.lerp(levelPartial, player.yRotO, player.getYRot());
            dispatcher.render(player, x, y, z, yaw, levelPartial, pose, buffers, dispatcher.getPackedLightCoords(player, levelPartial));
        }
        buffers.endBatch();
        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(projection, sorting);
    }

    /** Scatters the spots and works out, for every pixel, how soon the black reaches it. */
    private static void spots(int cols, int rows) {
        reachCols = cols;
        reachRows = rows;
        reachSeed = seed;
        reachSpots = ClientConfig.parallaxSpots();
        final int count = reachSpots;
        final float[] sx = new float[count], sy = new float[count];
        for (int i = 0; i < count; i++) {
            sx[i] = noise(seed * 7919 + i, 1) * cols;
            sy[i] = noise(seed * 7919 + i, 2) * rows;
        }
        reach = new float[cols * rows];
        float max = 0.0F;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                float nearest = Float.MAX_VALUE;
                for (int i = 0; i < count; i++) {
                    final float dx = col + 0.5F - sx[i], dy = row + 0.5F - sy[i];
                    nearest = Math.min(nearest, dx * dx + dy * dy);
                }
                final float d = (float) Math.sqrt(nearest) + 0.9F * noise(col * 31 + seed, row); // ragged edges
                reach[row * cols + col] = d;
                max = Math.max(max, d);
            }
        }
        for (int i = 0; i < reach.length; i++) {
            reach[i] /= max * 1.001F; // all below 1: black everywhere at the middle of the crossing
        }
    }

    /** The colour of a pixel at crossing v. */
    private static int colour(int col, int row, int cols, int rows, float v, float time) {
        if (v < 0.5F) { // black spreading from the spots
            final float front = v * 2.0F, r = reach[row * cols + col];
            return r > front ? 0 : 0xFF000000 | (front - r < 0.05F ? RIM : BLACK);
        }
        // waves from the spots, each one passing a pixel takes a share of its black; the last has passed
        // the furthest pixel (reach 1) just as the crossing ends
        final float r = reach[row * cols + col];
        final float lead = (v - 0.5F) * 2.0F * (1.0F + (WAVES - 1) * WAVE_GAP + WAVE_EDGE);
        float passed = 0.0F;
        for (int k = 0; k < WAVES; k++) {
            passed += smooth((lead - k * WAVE_GAP - r) / WAVE_EDGE);
        }
        final float fade = passed / WAVES;
        final int stays = stays(col, row, cols, rows, time);
        final float alpha = Mth.lerp(fade, 1.0F, (stays >>> 24) / 255.0F);
        return (int) (alpha * 255) << 24 | mix(BLACK, stays & 0xFFFFFF, fade);
    }

    private static float smooth(float t) {
        final float c = Mth.clamp(t, 0.0F, 1.0F);
        return c * c * (3 - 2 * c);
    }

    /** What stays in the shadow at a pixel. */
    private static int stays(int col, int row, int cols, int rows, float time) {
        // translucent purple, denser towards the edges, some pixels lighting up for a while
        final float ex = Math.abs((col + 0.5F) / cols * 2.0F - 1.0F), ey = Math.abs((row + 0.5F) / rows * 2.0F - 1.0F);
        final float edge = Mth.clamp((Math.max(ex, ey) - 0.55F) / 0.45F, 0.0F, 1.0F);
        float alpha = 0.06F + 0.12F * noise(col, row * 3 + seed) + 0.45F * edge * edge;
        int rgb = PURPLE;
        final int beat = (int) Math.floor(time * 0.8F + noise(col, row)); // every pixel on its own beat of 1.25 s
        if (noise(col * 977 + beat, row * 613) > 0.985F) {
            final float glow = Mth.sin((time * 0.8F + noise(col, row)) % 1.0F * Mth.PI);
            rgb = mix(PURPLE, VIOLET, glow);
            alpha += 0.35F * glow;
        }
        return (int) (Math.min(1.0F, alpha) * 255) << 24 | rgb;
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

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layer {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "shade_crossing"),
                    (graphics, delta) -> draw(graphics, delta.getGameTimeDeltaPartialTick(true)));
        }
    }
}
