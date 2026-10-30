package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig.ShadeLook;

/**
 * The pixels of player skins: read back from the texture the game made of them (downloaded skins are not
 * kept anywhere else), recoloured into the shadow's looks ({@link #shadeSkin}), and placed on the body
 * ({@link #texels}): where on a standing player every pixel of the skin sits.
 */
public final class SkinPixels {
    private static final Map<ResourceLocation, NativeImage> READ = new HashMap<>();
    private static final Map<String, ResourceLocation> LOOKS = new HashMap<>();
    /** One pixel of the skin on the body, in blocks. */
    private static final float PIXEL = 0.9375F / 16.0F;

    private SkinPixels() {
    }

    /** A texture's pixels (ABGR), or null if it is not there (yet). Render thread only. */
    static NativeImage read(ResourceLocation skin) {
        final NativeImage known = READ.get(skin);
        if (known != null) {
            return known;
        }
        final AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(skin, null);
        if (texture == null) {
            return null;
        }
        RenderSystem.bindTexture(texture.getId());
        final int w = GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        final int h = GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (w <= 0 || h <= 0 || w > 1024 || h > 1024) {
            return null;
        }
        final NativeImage image = new NativeImage(w, h, false);
        image.downloadTexture(0, false);
        READ.put(skin, image);
        return image;
    }

    // ---- the shadow's looks -------------------------------------------------------

    /** The skin in the look, made once per skin and look; the plain skin if it cannot be read. */
    static ResourceLocation shadeSkin(ResourceLocation skin, ShadeLook look) {
        final String key = skin + "|" + look;
        final ResourceLocation known = LOOKS.get(key);
        if (known != null) {
            return known;
        }
        final NativeImage source = read(skin);
        if (source == null || source.getWidth() != 64 || source.getHeight() != 64) {
            return skin; // not a skin as we know them
        }
        final NativeImage image = new NativeImage(64, 64, false);
        image.copyFrom(source);
        final int[] face = look == ShadeLook.SILHOUETTE ? faceMedian(source) : null;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                final int abgr = source.getPixelRGBA(x, y);
                if ((abgr >>> 24) != 0) {
                    image.setPixelRGBA(x, y, (abgr & 0xFF000000) | toAbgr(recolour(fromAbgr(abgr), look, x, y, face)));
                }
            }
        }
        final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID,
                "shade_skin/" + Integer.toHexString(key.hashCode()) + "_" + look.name().toLowerCase(java.util.Locale.ROOT));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        LOOKS.put(key, id);
        return id;
    }

    /** A pixel (RGB) in the look. */
    private static int recolour(int rgb, ShadeLook look, int x, int y, int[] face) {
        final float r = ((rgb >> 16) & 255) / 255.0F, g = ((rgb >> 8) & 255) / 255.0F, b = (rgb & 255) / 255.0F;
        final float l = 0.299F * r + 0.587F * g + 0.114F * b;
        return switch (look) {
            case CONTRAST -> {
                // steep contrast, half the colour, and the darker it is the more it leans purple
                final float lc = Mth.clamp((l - 0.5F) * 1.9F + 0.5F, 0.0F, 1.0F);
                final float k = lc / Math.max(l, 0.02F);
                final float cr = Mth.clamp(Mth.lerp(0.5F, lc, r * k), 0, 1), cg = Mth.clamp(Mth.lerp(0.5F, lc, g * k), 0, 1),
                        cb = Mth.clamp(Mth.lerp(0.5F, lc, b * k), 0, 1);
                final float purple = 0.55F * (1.0F - lc);
                yield rgb(Mth.lerp(purple, cr, 0.30F), Mth.lerp(purple, cg, 0.08F), Mth.lerp(purple, cb, 0.48F));
            }
            case PRINT -> l < 0.3F ? 0x382941 : l < 0.58F ? 0x725276 : 0xD0B5D2; // three of Exalted's purples
            case SILHOUETTE -> {
                if (face != null && isEye(rgb, x, y, face)) {
                    yield 0xC08CFF;
                }
                final float d = 0.03F + 0.06F * l; // near black, a trace of the shape left
                yield rgb(d * 1.1F, d * 0.7F, d * 1.5F);
            }
            case INVERTED -> rgb((1 - r) * 0.85F, (1 - g) * 0.65F, (1 - b));
            case NONE -> rgb;
        };
    }

    /** The middle brightness of the face (front of the head), to find the eyes by: one value in an array. */
    private static int[] faceMedian(NativeImage skin) {
        final List<Integer> values = new ArrayList<>();
        for (int y = 8; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                values.add(luma(fromAbgr(skin.getPixelRGBA(x, y))));
            }
        }
        values.sort(null);
        return new int[] {values.get(values.size() / 2)};
    }

    /** An eye: in the eye rows of the face, clearly brighter or darker than the face around it. */
    private static boolean isEye(int rgb, int x, int y, int[] face) {
        return x >= 9 && x <= 14 && y >= 11 && y <= 13 && Math.abs(luma(rgb) - face[0]) > 60;
    }

    private static int luma(int rgb) {
        return (int) (0.299F * ((rgb >> 16) & 255) + 0.587F * ((rgb >> 8) & 255) + 0.114F * (rgb & 255));
    }

    static int fromAbgr(int abgr) {
        return (abgr & 0xFF) << 16 | (abgr & 0xFF00) | (abgr >> 16) & 0xFF;
    }

    static int toAbgr(int rgb) {
        return (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16) & 0xFF;
    }

    private static int rgb(float r, float g, float b) {
        return (int) (Mth.clamp(r, 0, 1) * 255) << 16 | (int) (Mth.clamp(g, 0, 1) * 255) << 8 | (int) (Mth.clamp(b, 0, 1) * 255);
    }

    // ---- where the pixels sit -------------------------------------------------------

    /**
     * A pixel of the skin on the body of a standing player: x, y in the skin; where it sits relative to
     * the feet, in blocks along the player's right, up and forward; and which way its face looks
     * (outwards, same axes).
     */
    record Texel(int x, int y, float right, float up, float forward, float nr, float nu, float nf) {
    }

    /** A box of the player model: its place on the skin, its size and its centre (in skin pixels). */
    private record Box(int u, int v, int w, int h, int d, float cr, float cu, float inflate) {
    }

    /** Every visible pixel of the skin with its place on the body (wide or slim arms). */
    static List<Texel> texels(NativeImage skin, boolean slim) {
        final int arm = slim ? 3 : 4;
        final float armRight = slim ? 5.5F : 6.0F;
        final Box[] boxes = {
                new Box(0, 0, 8, 8, 8, 0, 28, 0), new Box(32, 0, 8, 8, 8, 0, 28, 0.5F),                   // head, hat
                new Box(16, 16, 8, 12, 4, 0, 18, 0), new Box(16, 32, 8, 12, 4, 0, 18, 0.25F),            // body, jacket
                new Box(40, 16, arm, 12, 4, armRight, 18, 0), new Box(40, 32, arm, 12, 4, armRight, 18, 0.25F),     // right arm
                new Box(32, 48, arm, 12, 4, -armRight, 18, 0), new Box(48, 48, arm, 12, 4, -armRight, 18, 0.25F),  // left arm
                new Box(0, 16, 4, 12, 4, 1.9F, 6, 0), new Box(0, 32, 4, 12, 4, 1.9F, 6, 0.25F),          // right leg
                new Box(16, 48, 4, 12, 4, -1.9F, 6, 0), new Box(0, 48, 4, 12, 4, -1.9F, 6, 0.25F)};     // left leg
        final List<Texel> out = new ArrayList<>();
        for (Box box : boxes) {
            final float hw = box.w() / 2.0F + box.inflate(), hh = box.h() / 2.0F + box.inflate(), hd = box.d() / 2.0F + box.inflate();
            final float sw = (box.w() + 2 * box.inflate()) / box.w(), sh = (box.h() + 2 * box.inflate()) / box.h(), sd = (box.d() + 2 * box.inflate()) / box.d();
            final int u = box.u(), v = box.v(), w = box.w(), h = box.h(), d = box.d();
            for (int j = 0; j < d; j++) {
                for (int i = 0; i < w; i++) {
                    add(out, skin, box, u + d + i, v + j, hw - (i + 0.5F) * sw, hh, -hd + (j + 0.5F) * sd, 0, 1, 0);       // top
                    add(out, skin, box, u + d + w + i, v + j, hw - (i + 0.5F) * sw, -hh, hd - (j + 0.5F) * sd, 0, -1, 0); // bottom
                }
            }
            for (int j = 0; j < h; j++) {
                final float up = hh - (j + 0.5F) * sh;
                for (int i = 0; i < d; i++) {
                    add(out, skin, box, u + i, v + d + j, hw, up, -hd + (i + 0.5F) * sd, 1, 0, 0);            // right side
                    add(out, skin, box, u + d + w + i, v + d + j, -hw, up, hd - (i + 0.5F) * sd, -1, 0, 0);    // left side
                }
                for (int i = 0; i < w; i++) {
                    add(out, skin, box, u + d + i, v + d + j, hw - (i + 0.5F) * sw, up, hd, 0, 0, 1);              // front
                    add(out, skin, box, u + 2 * d + w + i, v + d + j, -hw + (i + 0.5F) * sw, up, -hd, 0, 0, -1);   // back
                }
            }
        }
        return out;
    }

    private static void add(List<Texel> out, NativeImage skin, Box box, int x, int y, float r, float u, float f, float nr, float nu, float nf) {
        if (x < 64 && y < 64 && (skin.getPixelRGBA(x, y) >>> 24) != 0) {
            out.add(new Texel(x, y, (box.cr() + r) * PIXEL, (box.cu() + u) * PIXEL, f * PIXEL, nr, nu, nf));
        }
    }
}
