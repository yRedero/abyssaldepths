package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;

/**
 * The opening, "The Awakening" (DESIGN.md 10), played once when the game has finished loading, before the title
 * screen: about six seconds on a fixed timeline, in a cavern of its own (built here from the mod's blocks, the same
 * every time; no world needed).
 * <ol>
 * <li><b>The descent</b> (0 to 1.5 s): black, a low rumble; the camera drifts forward and down through a vast cavern
 * that shows only as silhouettes, dust in the air, far ahead a tiny point of light.</li>
 * <li><b>The discovery</b> (1.5 to 3 s): faster towards the light, which begins to pulse like something alive, each
 * pulse lighting a little more: rock, pillars, a colonnade of old columns, a ring of standing stones round it.</li>
 * <li><b>The awakening</b> (3 to 4.5 s): it grows unstable, cracks of light round it, the pulses quicker; the camera
 * checks itself; then it bursts, and a wave of white light rushes at the camera and fills the screen.</li>
 * <li><b>The reveal</b> (4.5 to 6 s): out of the white the logo, settling; the white draws back to black; a last pulse
 * of light runs through the logo; a deep resonant tone. Then black, and the title screen.</li>
 * </ol>
 * The cave is drawn from the blocks' own textures but lit by hand: only by that light (its distance, which way each
 * face looks, the pulses), fading into black with distance. Any key or click skips it; the config ({@code opening})
 * turns it off.
 */
public class OpeningScreen extends Screen {
    private static final float DISCOVERY = 1.5F, AWAKENING = 3.0F, BURST = 3.75F, WHITE = 4.5F, PULSE = 5.45F, HOLD = 6.6F, END = 7.1F;
    private static final ResourceLocation LOGO = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "textures/gui/lumenfall_title.png");
    private static final int LOGO_W = 1024, LOGO_H = 168;
    private static final ResourceLocation GLOW = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "opening_glow");
    private static final ResourceLocation RING = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "opening_ring");
    /** Where the light is (it hangs over a mound inside a ring of standing stones). */
    private static final Vector3f LIGHT = new Vector3f(0, 7, 124);
    /** The light's colour: a cold white. */
    private static final float LR = 0.86F, LG = 0.93F, LB = 1.0F;
    /** When the light pulses (seconds): slow at first, faster and faster. */
    private static final float[] PULSES = {1.55F, 2.15F, 2.65F, 3.0F, 3.25F, 3.43F, 3.56F, 3.65F, 3.71F};

    private static boolean shown;
    private final Screen next;
    /** When it began: once the loading screen is gone (black under it till then); -1 before. */
    private long start = -1;
    private Cave cave;
    private final List<float[]> sounds = new ArrayList<>();
    private int soundsPlayed;
    private final float[][] dust = new float[320][4];
    private final float[][] cracks = new float[11][12];

    public OpeningScreen(Screen next) {
        super(Component.empty());
        this.next = next;
        final RandomSource r = RandomSource.create(41);
        for (float[] d : dust) { // drifting motes along the way: x, y, z, phase
            d[0] = (r.nextFloat() - 0.5F) * 40;
            d[1] = -2 + r.nextFloat() * 22;
            d[2] = r.nextFloat() * 130;
            d[3] = r.nextFloat() * Mth.TWO_PI;
        }
        for (float[] c : cracks) { // jagged rays round the light: their angle and the bends of each
            c[0] = r.nextFloat() * Mth.TWO_PI;
            for (int k = 1; k < c.length; k++) {
                c[k] = (r.nextFloat() - 0.5F) * 0.9F;
            }
        }
        // the sound, on the same timeline: time, which, pitch, volume
        sound(0.0F, 0, 0.55F, 1.0F);   // a deep groan of the earth
        sound(0.35F, 1, 0.5F, 0.6F);   // the cave breathing
        sound(1.45F, 2, 0.5F, 0.5F);   // a tone rising under it
        sound(2.3F, 2, 0.62F, 0.6F);
        sound(2.95F, 2, 0.8F, 0.7F);
        for (float p : PULSES) {
            sound(p, 3, 0.55F + (p - 1.5F) * 0.22F, 0.5F + (p - 1.5F) * 0.25F); // its heart, each pulse
        }
        sound(3.3F, 4, 1.6F, 0.6F);    // the cracks singing
        sound(BURST, 5, 0.5F, 1.0F);   // the burst
        sound(BURST, 6, 0.6F, 0.9F);
        sound(BURST + 0.02F, 7, 0.55F, 1.0F);
        sound(4.6F, 8, 0.5F, 1.0F);    // the logo: a deep resonant tone
        sound(4.6F, 9, 0.5F, 0.8F);
        sound(PULSE, 10, 0.7F, 0.6F);  // the last pulse through it
    }

    private void sound(float time, int which, float pitch, float volume) {
        sounds.add(new float[] {time, which, pitch, volume});
    }

    private static SoundEvent soundOf(int which) {
        return switch (which) {
            case 0 -> AbyssalDepths.DEEP_GROAN.get();
            case 1 -> SoundEvents.AMBIENT_CAVE.value();
            case 2 -> SoundEvents.BEACON_AMBIENT;
            case 3 -> SoundEvents.WARDEN_HEARTBEAT;
            case 4 -> SoundEvents.AMETHYST_BLOCK_RESONATE;
            case 5 -> SoundEvents.GENERIC_EXPLODE.value();
            case 6 -> SoundEvents.LIGHTNING_BOLT_THUNDER;
            case 7 -> SoundEvents.BEACON_ACTIVATE;
            case 8 -> SoundEvents.BELL_RESONATE;
            case 9 -> SoundEvents.AMETHYST_BLOCK_RESONATE;
            default -> SoundEvents.BEACON_POWER_SELECT;
        };
    }

    private float time() {
        return start < 0 ? 0 : (Util.getMillis() - start) / 1000.0F;
    }

    static float smooth(float x) {
        final float c = Mth.clamp(x, 0, 1);
        return c * c * (3 - 2 * c);
    }

    static float out(float x) {
        final float c = Mth.clamp(x, 0, 1);
        return 1 - (1 - c) * (1 - c) * (1 - c);
    }

    @Override
    protected void init() {
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        if (minecraft == null) {
            return;
        }
        g.fill(0, 0, width, height, 0xFF000000);
        if (start < 0) { // under the loading screen still: black, and the cavern built meanwhile
            if (cave == null) {
                cave = new Cave();
                textures();
            }
            if (minecraft.getOverlay() == null) {
                start = Util.getMillis();
            }
            return;
        }
        final float t = time();
        if (t >= END) {
            onClose();
            return;
        }
        if (minecraft == null) {
            return;
        }
        minecraft.getMusicManager().stopPlaying(); // (the menu's music waits for the menu)
        while (soundsPlayed < sounds.size() && sounds.get(soundsPlayed)[0] <= t) {
            final float[] s = sounds.get(soundsPlayed++);
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(soundOf((int) s[1]), s[2], s[3]));
        }
        g.fill(0, 0, width, height, 0xFF000000);
        if (t < WHITE + 0.1F) {
            g.flush();
            scene(t);
        }
        // the wave reaching the camera: the screen fills with white, which then draws back
        final float white = t < BURST ? 0 : t < WHITE ? whiteness(t) : 1 - smooth((t - WHITE - 0.1F) / 0.85F);
        if (t >= WHITE) {
            logo(g, t);
        }
        if (white > 0.002F) {
            g.fill(0, 0, width, height, (int) (Mth.clamp(white, 0, 1) * 255) << 24 | 0xF4F8FF);
        }
        final float black = t < 0.6F ? 1 - smooth(t / 0.6F) : t > HOLD ? smooth((t - HOLD) / (END - HOLD)) : 0;
        if (black > 0.002F) {
            g.fill(0, 0, width, height, (int) (black * 255) << 24);
        }
    }

    /** How white the screen is as the wave nears the camera (0 to 1). */
    private float whiteness(float t) {
        final Vector3f cam = camera(t);
        final float d = cam.distance(LIGHT), r = wave(t);
        return smooth((r - (d - 30)) / 30);
    }

    /** How far the wave has come from the light (blocks). */
    private static float wave(float t) {
        return t < BURST ? 0 : (t - BURST) * 70 + (t - BURST) * (t - BURST) * 60;
    }

    // ------------------------------------------------------------------ the timeline

    /** Where the camera is: forward and slowly down, faster in the discovery, checking itself before the burst. */
    private static Vector3f camera(float t) {
        final float z;
        if (t < DISCOVERY) {
            z = 2 + 16 * (t / DISCOVERY) * (0.6F + 0.4F * t / DISCOVERY);
        } else if (t < AWAKENING) {
            final float u = (t - DISCOVERY) / (AWAKENING - DISCOVERY);
            z = 18 + 50 * (u * u * 0.55F + u * 0.45F);
        } else {
            z = 68 + 22 * out((t - AWAKENING) / (BURST - AWAKENING + 0.2F));
        }
        final float y = 16 - 7 * smooth(t / BURST) + Mth.sin(t * 0.9F) * 0.25F;
        final float x = Mth.sin(t * 0.55F) * 1.2F;
        return new Vector3f(x, y, z);
    }

    /** How bright the light is: faint, then pulsing, unstable, bursting. */
    private static float intensity(float t) {
        float pulse = 0;
        for (float p : PULSES) {
            final float d = (t - p) / 0.11F;
            pulse = Math.max(pulse, (float) Math.exp(-d * d));
        }
        final float base = 0.35F + 0.4F * smooth((t - 0.8F) / 1.2F);
        float i = base + pulse * (0.6F + 0.9F * smooth((t - DISCOVERY) / 2.2F));
        if (t > AWAKENING) { // unstable: rising, flickering
            final float u = smooth((t - AWAKENING) / (BURST - AWAKENING));
            i += u * 1.6F + u * 0.5F * Mth.sin(t * 61) * Mth.sin(t * 23);
        }
        if (t > BURST) {
            i += 9 * (float) Math.exp(-(t - BURST) * 3);
        }
        return i;
    }

    /** How far its light reaches (blocks), with its strength. */
    private static float reach(float i) {
        return 10 + 11 * i;
    }

    // ------------------------------------------------------------------ the scene

    private void scene(float t) {
        final Vector3f cam = camera(t);
        final float shake = t > BURST - 0.1F ? 0.5F * (float) Math.exp(-(t - BURST) * 2.5F) : t > AWAKENING ? 0.06F * (t - AWAKENING) : 0;
        final float jx = shake * Mth.sin(t * 71), jy = shake * Mth.sin(t * 53 + 1);
        final Vector3f look = new Vector3f(LIGHT).sub(cam).add(jx, 2.5F + jy, 0).normalize();
        final float fov = 72 + 10 * smooth((t - BURST) / 0.4F) - 3 * smooth((t - AWAKENING) / 0.7F);
        final Matrix4f projection = new Matrix4f().perspective(fov * Mth.DEG_TO_RAD, (float) minecraft.getWindow().getWidth() / minecraft.getWindow().getHeight(),
                0.05F, 400.0F);
        final Matrix4f view = new Matrix4f().lookAt(cam.x, cam.y, cam.z, cam.x + look.x, cam.y + look.y, cam.z + look.z, 0, 1, 0);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        final Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        final float i = intensity(t);
        cave.draw(view, cam, t, i);
        // the light and what it does, added on top
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE); // (added light: its strength in the colour)
        RenderSystem.depthMask(false);
        final Vector3f right = new Vector3f(look).cross(0, 1, 0).normalize(), up = new Vector3f(right).cross(look).normalize();
        motes(view, cam, t, i, right, up);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, GLOW);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        billboard(b, view, LIGHT, right, up, 0.6F + 0.35F * i, 1, 1, 1, Math.min(1, 0.6F + 0.4F * i)); // its heart
        billboard(b, view, LIGHT, right, up, 3.5F + 3.5F * i, LR, LG, LB, Math.min(0.9F, 0.18F * i)); // its glow
        billboard(b, view, LIGHT, right, up, 14 + 9 * i, LR, LG, LB, Math.min(0.6F, 0.05F * i)); // the haze round it
        draw(b);
        if (t > AWAKENING - 0.2F) {
            cracks(view, t, right, up);
        }
        if (t > BURST) { // the wave: a ring rushing out
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, RING);
            b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            final float r = wave(t);
            billboard(b, view, LIGHT, right, up, r, 1, 1, 1, 0.9F);
            billboard(b, view, LIGHT, right, up, r * 0.8F, LR, LG, LB, 0.5F);
            draw(b);
        }
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.disableDepthTest();
        stack.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
    }

    private static void draw(BufferBuilder b) {
        final MeshData mesh = b.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
    }

    private static void billboard(BufferBuilder b, Matrix4f view, Vector3f at, Vector3f right, Vector3f up, float size, float r, float g, float bl, float a) {
        final float rx = right.x * size, ry = right.y * size, rz = right.z * size, ux = up.x * size, uy = up.y * size, uz = up.z * size;
        // (the strength in the colour, the alpha whole: the game's shader drops pixels under 10 % alpha, which would cut a
        // soft glow off in a hard ring)
        b.addVertex(view, at.x - rx - ux, at.y - ry - uy, at.z - rz - uz).setUv(0, 1).setColor(r * a, g * a, bl * a, 1);
        b.addVertex(view, at.x + rx - ux, at.y + ry - uy, at.z + rz - uz).setUv(1, 1).setColor(r * a, g * a, bl * a, 1);
        b.addVertex(view, at.x + rx + ux, at.y + ry + uy, at.z + rz + uz).setUv(1, 0).setColor(r * a, g * a, bl * a, 1);
        b.addVertex(view, at.x - rx + ux, at.y - ry + uy, at.z - rz + uz).setUv(0, 0).setColor(r * a, g * a, bl * a, 1);
    }

    /** Dust in the air, seen only where the light reaches it; after the burst, blown outwards. */
    private void motes(Matrix4f view, Vector3f cam, float t, float i, Vector3f right, Vector3f up) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, GLOW);
        final BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        final float reach = reach(i), push = wave(t);
        final Vector3f p = new Vector3f();
        for (float[] d : dust) {
            p.set(d[0] + Mth.sin(t * 0.4F + d[3]) * 0.6F, d[1] + Mth.sin(t * 0.3F + d[3] * 2) * 0.4F - t * 0.15F, d[2]);
            if (push > 0) {
                final Vector3f away = new Vector3f(p).sub(LIGHT);
                final float dist = away.length();
                if (dist < push) {
                    p.add(away.normalize().mul(Math.min(12, (push - dist) * 0.3F)));
                }
            }
            final float dl = p.distance(LIGHT), dc = p.distance(cam);
            if (dc < 0.5F || dc > 70) {
                continue;
            }
            final float lit = 0.05F + 1.4F * i / (1 + (dl / reach) * (dl / reach) * 4), fog = (float) Math.exp(-dc / 40);
            final float a = Math.min(0.85F, lit * fog);
            if (a > 0.01F) {
                billboard(b, view, p, right, up, 0.07F, LR, LG, LB, a);
            }
        }
        draw(b);
    }

    /** Cracks of light round it as it grows unstable: jagged rays, longer and brighter, flickering. */
    private void cracks(Matrix4f view, float t, Vector3f right, Vector3f up) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        final BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        final float u = smooth((t - AWAKENING + 0.2F) / (BURST - AWAKENING + 0.2F)), fade = t > BURST ? (float) Math.exp(-(t - BURST) * 6) : 1;
        final int frame = (int) (t * 30);
        for (int n = 0; n < cracks.length; n++) {
            final float[] c = cracks[n];
            final float flicker = ((frame * 31 + n * 17) % 7) / 7.0F;
            final float alpha = (0.35F + 0.65F * flicker) * u * fade;
            if (alpha < 0.02F) {
                continue;
            }
            float angle = c[0], x = 0, y = 0;
            final float step = 0.35F + 1.1F * u;
            for (int k = 1; k < c.length; k++) {
                angle += c[k];
                final float nx = x + Mth.cos(angle) * step, ny = y + Mth.sin(angle) * step;
                final float w = 0.05F * (1 - k / (float) c.length) + 0.015F;
                segment(b, view, right, up, x, y, nx, ny, w, alpha * (1 - k / (float) c.length));
                x = nx;
                y = ny;
            }
        }
        draw(b);
    }

    private static void segment(BufferBuilder b, Matrix4f view, Vector3f right, Vector3f up, float x0, float y0, float x1, float y1, float w, float a) {
        final float dx = x1 - x0, dy = y1 - y0, len = Mth.sqrt(dx * dx + dy * dy);
        final float px = -dy / len * w, py = dx / len * w;
        final float[][] corners = {{x0 + px, y0 + py}, {x1 + px, y1 + py}, {x1 - px, y1 - py}, {x0 - px, y0 - py}};
        for (float[] c : corners) {
            b.addVertex(view, LIGHT.x + right.x * c[0] + up.x * c[1], LIGHT.y + right.y * c[0] + up.y * c[1], LIGHT.z + right.z * c[0] + up.z * c[1])
                    .setColor(LR * a, LG * a, LB * a, 1);
        }
    }

    // ------------------------------------------------------------------ the logo

    /**
     * The logo out of the white: a little larger at first and glowing, settling to its size as the white draws back;
     * then a band of light runs through it once.
     */
    private void logo(GuiGraphics g, float t) {
        final float k = out((t - WHITE) / 0.9F);
        final float w = Math.min(width * 0.62F, 560), h = w * LOGO_H / LOGO_W;
        final float scale = 1.08F - 0.08F * k;
        final float cx = width / 2.0F, cy = height / 2.0F;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1, 1, 1, Mth.clamp((t - WHITE) / 0.5F, 0, 1));
        blitLogo(g, cx, cy, w * scale, h * scale, 0, 1);
        // the light it came out of, round it, fading
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        final float glow = (1 - k) * 0.6F;
        if (glow > 0.01F) {
            for (int n = 1; n <= 3; n++) {
                g.setColor(LR * glow / n, LG * glow / n, LB * glow / n, 1);
                blitLogo(g, cx, cy, w * scale * (1 + 0.03F * n), h * scale * (1 + 0.08F * n), 0, 1);
            }
        }
        // the last pulse through it: a band of light from left to right
        final float band = (t - PULSE) / 0.55F;
        if (band > -0.2F && band < 1.3F) {
            final int slices = 48;
            for (int s = 0; s < slices; s++) {
                final float u0 = s / (float) slices, u1 = (s + 1) / (float) slices, mid = (u0 + u1) / 2 * 1.2F - 0.1F;
                final float a = 0.75F * (float) Math.exp(-Math.pow((mid - band) / 0.07F, 2));
                if (a > 0.01F) {
                    g.setColor(a, a, a, 1);
                    blitLogo(g, cx, cy, w, h, u0, u1);
                }
            }
        }
        g.setColor(1, 1, 1, 1);
        RenderSystem.defaultBlendFunc();
    }

    /** A part of the logo (from u0 to u1 across it) drawn centred at (cx, cy) at that size. */
    private static void blitLogo(GuiGraphics g, float cx, float cy, float w, float h, float u0, float u1) {
        g.pose().pushPose();
        g.pose().translate(cx - w / 2 + w * u0, cy - h / 2, 0);
        g.pose().scale(w / LOGO_W, h / LOGO_H, 1);
        final int x0 = (int) (LOGO_W * u0), x1 = (int) Math.ceil(LOGO_W * u1);
        g.blit(LOGO, 0, 0, x0, 0, x1 - x0, LOGO_H, LOGO_W, LOGO_H);
        g.pose().popPose();
    }

    // ------------------------------------------------------------------ textures made here

    private void textures() {
        if (minecraft == null) {
            return;
        }
        final NativeImage glow = new NativeImage(64, 64, false), ring = new NativeImage(128, 128, false);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                final float d = Mth.sqrt((x - 31.5F) * (x - 31.5F) + (y - 31.5F) * (y - 31.5F)) / 32;
                final float a = (float) Math.exp(-d * d * 5) * Math.max(0, 1 - d * d); // soft, tailing off to nothing
                final int v = (int) (a * 255);
                glow.setPixelRGBA(x, y, 0xFF000000 | v << 16 | v << 8 | v); // (brightness in the colour, see billboard)
            }
        }
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                final float d = Mth.sqrt((x - 63.5F) * (x - 63.5F) + (y - 63.5F) * (y - 63.5F)) / 64;
                final float a = (float) Math.exp(-Math.pow((d - 0.86F) / 0.06F, 2)) + 0.25F * (float) Math.exp(-Math.pow((d - 0.7F) / 0.18F, 2));
                final int v = (int) (Mth.clamp(a, 0, 1) * 255);
                ring.setPixelRGBA(x, y, 0xFF000000 | v << 16 | v << 8 | v);
            }
        }
        final DynamicTexture g = new DynamicTexture(glow), r = new DynamicTexture(ring);
        g.setFilter(true, false);
        r.setFilter(true, false);
        minecraft.getTextureManager().register(GLOW, g);
        minecraft.getTextureManager().register(RING, r);
    }

    // ------------------------------------------------------------------ the cavern

    /**
     * The cavern: a long hall of thalan, wide and tall, bending gently, its floor of shale, with spires rising from
     * the floor and hanging from the roof, a colonnade of old brick columns along the way (some broken), and at its end
     * a mound inside a ring of standing stones under the light. Built once (the same every time) as the faces of
     * its blocks open to the air; drawn each frame lit by the light alone.
     */
    private static final class Cave {
        private static final int X0 = -58, X1 = 58, Y0 = -14, Y1 = 50, Z0 = -10, Z1 = 150;
        private final int sx = X1 - X0, sy = Y1 - Y0, sz = Z1 - Z0;
        private final byte[] cells = new byte[sx * sy * sz]; // 0 air, else the block (1 thalan, 2 shale, 3 bricks, 4 polished)
        /** The faces: per face its 4 corners (x, y, z), normal, and texture (u0, v0, u1, v1). */
        private float[] faces;
        private int count;

        Cave() {
            final ImprovedNoise noise = new ImprovedNoise(RandomSource.create(1337));
            final RandomSource r = RandomSource.create(7);
            for (int z = Z0; z < Z1; z++) {
                final float cx = 7 * Mth.sin(z * 0.022F) * Mth.clamp((110 - z) / 40.0F, 0, 1);
                for (int x = X0; x < X1; x++) {
                    for (int y = Y0; y < Y1; y++) {
                        final double n = noise.noise(x * 0.06, y * 0.06, z * 0.06) + 0.5 * noise.noise(x * 0.15, y * 0.15, z * 0.15);
                        final float rx = 46 + (z > 105 ? (z - 105) * 0.5F : 0), ry = 29;
                        final float ex = (x - cx) / rx, ey = (y - 14) / ry;
                        final boolean air = ex * ex + ey * ey < 1 + n * 0.35 && y > -4 + n * 2.5 + 0.004 * (x - cx) * (x - cx);
                        if (!air) {
                            set(x, y, z, y < 1 && ey < -0.2F ? (byte) 2 : (byte) 1);
                        }
                    }
                }
            }
            for (int k = 0; k < 70; k++) { // spires: up from the floor, down from the roof
                final int px = -40 + r.nextInt(80), pz = r.nextInt(140);
                if (Math.abs(px) < 7 && pz < 115) {
                    continue; // (the way stays clear)
                }
                final boolean down = r.nextBoolean();
                final float rad = 1.5F + r.nextFloat() * 2.5F, len = 8 + r.nextFloat() * 18;
                final int from = down ? roof(px, pz) : floor(px, pz);
                for (int d = 0; d < len; d++) {
                    final float rr = rad * (1 - d / len);
                    for (int ox = -3; ox <= 3; ox++) {
                        for (int oz = -3; oz <= 3; oz++) {
                            if (ox * ox + oz * oz <= rr * rr) {
                                set(px + ox, from + (down ? -d : d), pz + oz, (byte) 1);
                            }
                        }
                    }
                }
            }
            for (int c = 0; c < 8; c++) { // the colonnade: old columns either side of the way, some broken
                for (int side = -1; side <= 1; side += 2) {
                    final int px = side * 17, pz = 34 + c * 11;
                    final int base = floor(px, pz), top = roof(px, pz);
                    final boolean broken = r.nextFloat() < 0.4F;
                    final int height = broken ? 5 + r.nextInt(9) : top - base;
                    for (int d = 0; d < height; d++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            for (int oz = -1; oz <= 1; oz++) {
                                set(px + ox, base + d, pz + oz, (byte) 3);
                            }
                        }
                    }
                    for (int ox = -2; ox <= 2; ox++) { // its foot (and, whole, its head)
                        for (int oz = -2; oz <= 2; oz++) {
                            set(px + ox, base, pz + oz, (byte) 4);
                            if (!broken) {
                                set(px + ox, top - 1, pz + oz, (byte) 4);
                            }
                        }
                    }
                    if (broken) { // its fallen pieces
                        for (int f = 0; f < 4; f++) {
                            set(px + side * (2 + f), floor(px + side * (2 + f), pz + f % 2), pz + f % 2, (byte) 3);
                        }
                    }
                }
            }
            final int lx = (int) LIGHT.x, lz = (int) LIGHT.z;
            for (int ox = -6; ox <= 6; ox++) { // the mound under the light
                for (int oz = -6; oz <= 6; oz++) {
                    final float d = Mth.sqrt(ox * ox + oz * oz);
                    for (int h = 0; h < 3.5F - d * 0.55F; h++) {
                        set(lx + ox, floor(lx + ox, lz + oz) + h, lz + oz, (byte) 2);
                    }
                }
            }
            for (int s = 0; s < 9; s++) { // the ring of standing stones round it
                final float a = s * Mth.TWO_PI / 9 + 0.3F;
                final int px = lx + Math.round(Mth.cos(a) * 11), pz = lz + Math.round(Mth.sin(a) * 11);
                final int base = floor(px, pz), height = s % 3 == 1 ? 4 : 7 + (s % 2) * 2;
                for (int d = 0; d < height; d++) {
                    for (int o = 0; o < 4; o++) {
                        set(px + (o & 1), base + d, pz + (o >> 1), (byte) 4);
                    }
                }
            }
            build();
        }

        private int index(int x, int y, int z) {
            return ((x - X0) * sy + (y - Y0)) * sz + (z - Z0);
        }

        private boolean inside(int x, int y, int z) {
            return x >= X0 && x < X1 && y >= Y0 && y < Y1 && z >= Z0 && z < Z1;
        }

        private byte get(int x, int y, int z) {
            return inside(x, y, z) ? cells[index(x, y, z)] : 1;
        }

        private void set(int x, int y, int z, byte b) {
            if (inside(x, y, z)) {
                cells[index(x, y, z)] = b;
            }
        }

        /** The first air above the floor at a column (near the middle of the hall). */
        private int floor(int x, int z) {
            for (int y = Y0; y < Y1 - 1; y++) {
                if (get(x, y, z) != 0 && get(x, y + 1, z) == 0 && y > -8) {
                    return y + 1;
                }
            }
            return 0;
        }

        /** The lowest solid of the roof over a column. */
        private int roof(int x, int z) {
            for (int y = 16; y < Y1; y++) {
                if (get(x, y, z) != 0) {
                    return y;
                }
            }
            return Y1;
        }

        private void build() {
            final Minecraft mc = Minecraft.getInstance();
            final BlockState[] states = {null, AbyssalDepths.THALAN.get().defaultBlockState(), AbyssalDepths.SHALE.get().defaultBlockState(),
                    AbyssalDepths.THALAN_BRICKS.get().defaultBlockState(), AbyssalDepths.POLISHED_THALAN.get().defaultBlockState()};
            final TextureAtlasSprite[][] sprites = new TextureAtlasSprite[states.length][6];
            for (int b = 1; b < states.length; b++) {
                final BakedModel model = mc.getBlockRenderer().getBlockModel(states[b]);
                for (Direction d : Direction.values()) {
                    final var quads = model.getQuads(states[b], d, RandomSource.create(0));
                    sprites[b][d.ordinal()] = quads.isEmpty() ? model.getParticleIcon() : quads.get(0).getSprite();
                }
            }
            final List<float[]> out = new ArrayList<>();
            for (int x = X0; x < X1; x++) {
                for (int y = Y0; y < Y1; y++) {
                    for (int z = Z0; z < Z1; z++) {
                        final byte b = get(x, y, z);
                        if (b == 0) {
                            continue;
                        }
                        for (Direction d : Direction.values()) {
                            if (inside(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()) && get(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()) == 0) {
                                out.add(face(x, y, z, d, sprites[b][d.ordinal()]));
                            }
                        }
                    }
                }
            }
            count = out.size();
            faces = new float[count * 19];
            for (int i = 0; i < count; i++) {
                System.arraycopy(out.get(i), 0, faces, i * 19, 19);
            }
        }

        /** A face of a block: its corners (round the face), its normal, its texture's corners on the atlas. */
        private static float[] face(int x, int y, int z, Direction d, TextureAtlasSprite s) {
            final float[] f = new float[19];
            final int nx = d.getStepX(), ny = d.getStepY(), nz = d.getStepZ();
            // two axes across the face
            final float[] a = nx != 0 ? new float[] {0, 0, 1} : new float[] {1, 0, 0}; // across (u)
            final float[] c = ny != 0 ? new float[] {0, 0, 1} : new float[] {0, 1, 0}; // up the face (v)
            final float ox = x + (nx > 0 ? 1 : 0), oy = y + (ny > 0 ? 1 : 0), oz = z + (nz > 0 ? 1 : 0);
            final float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int k = 0; k < 4; k++) {
                f[k * 3] = ox + a[0] * corners[k][0] + c[0] * corners[k][1];
                f[k * 3 + 1] = oy + a[1] * corners[k][0] + c[1] * corners[k][1];
                f[k * 3 + 2] = oz + a[2] * corners[k][0] + c[2] * corners[k][1];
            }
            f[12] = nx;
            f[13] = ny;
            f[14] = nz;
            f[15] = s.getU0();
            f[16] = s.getV0();
            f[17] = s.getU1();
            f[18] = s.getV1();
            return f;
        }

        /**
         * Draws the faces near enough to matter, each corner lit by the light alone: brighter the nearer and the more
         * the face looks towards it, nothing beyond its reach but a breath of grey (the silhouettes), into black with
         * distance from the camera.
         */
        void draw(Matrix4f view, Vector3f cam, float t, float i) {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            final BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            final float reach = reach(i), ambient = 0.1F * smooth(t / 1.4F) + 0.025F; // the dark giving up the cavern's shapes
            for (int n = 0; n < count; n++) {
                final int o = n * 19;
                final float mz = (faces[o + 2] + faces[o + 8]) / 2;
                final float dz = mz - cam.z;
                if (dz < -6 || dz > 150) {
                    continue; // behind the camera, or past the end
                }
                final float u0 = faces[o + 15], v0 = faces[o + 16], u1 = faces[o + 17], v1 = faces[o + 18];
                final float[][] uv = {{u0, v1}, {u1, v1}, {u1, v0}, {u0, v0}};
                for (int k = 0; k < 4; k++) {
                    final float px = faces[o + k * 3], py = faces[o + k * 3 + 1], pz = faces[o + k * 3 + 2];
                    final float lx = LIGHT.x - px, ly = LIGHT.y - py, lz = LIGHT.z - pz;
                    final float dl = Mth.sqrt(lx * lx + ly * ly + lz * lz);
                    final float facing = Math.max(0, (lx * faces[o + 12] + ly * faces[o + 13] + lz * faces[o + 14]) / Math.max(0.001F, dl)) * 0.8F + 0.2F;
                    final float q = dl / reach;
                    final float light = i * facing / (1 + q * q * 3);
                    final float dx2 = px - cam.x, dy2 = py - cam.y, dz2 = pz - cam.z;
                    final float fog = (float) Math.exp(-Mth.sqrt(dx2 * dx2 + dy2 * dy2 + dz2 * dz2) / 55);
                    final float lum = light * fog, base = ambient * fog;
                    // softly saturating (no hard edge where the light is strongest)
                    b.addVertex(view, px, py, pz).setUv(uv[k][0], uv[k][1])
                            .setColor(tone(base + lum * LR), tone(base + lum * LG), tone(base * 1.1F + lum * LB), 1);
                }
            }
            OpeningScreen.draw(b);
        }
    }

    /** Brightness to colour, saturating softly towards white. */
    private static float tone(float v) {
        return 1 - (float) Math.exp(-v * 1.25F);
    }

    // ------------------------------------------------------------------ the rest

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key >= org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT && key <= org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SUPER) {
            return true; // (shift, ctrl, alt, the windows key: alt-tabbing away does not skip it)
        }
        onClose();
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        onClose();
        return true;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.getSoundManager().stop();
            minecraft.setScreen(next);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Once, as the first title screen opens (still under the loading screen): the opening is put in its place, so the
     * menu is never seen before it; it comes after, fading in from black as the game's own title screen does.
     */
    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Hook {
        @SubscribeEvent
        public static void onOpen(net.neoforged.neoforge.client.event.ScreenEvent.Opening event) {
            if (!shown && event.getNewScreen() instanceof TitleScreen title) {
                shown = true;
                if (ClientConfig.opening()) {
                    event.setNewScreen(new OpeningScreen(title));
                }
            }
        }
    }

}
