package redero.abyssaldepths.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Pictures of the places left into the inner self, for the waypoint screen: taken by the client when it
 * crosses (a small copy of the last frame), filed per world (or server) and player under the place's key,
 * in {@code abyssaldepths/waypoints} in the game folder.
 */
public final class WaypointShots {
    private static final int WIDTH = 192, HEIGHT = 108;
    private static final Map<Long, ResourceLocation> LOADED = new HashMap<>();

    private WaypointShots() {
    }

    private static Path folder() {
        final Minecraft mc = Minecraft.getInstance();
        final String world = mc.getSingleplayerServer() != null ? "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName()
                : mc.getCurrentServer() != null ? "mp_" + mc.getCurrentServer().ip : "unknown";
        final String player = mc.player != null ? mc.player.getUUID().toString() : "nobody";
        return mc.gameDirectory.toPath().resolve("abyssaldepths").resolve("waypoints").resolve(world.replaceAll("[^A-Za-z0-9._-]", "_")).resolve(player);
    }

    /** Takes the picture of the place being left (render thread), filed under its key. */
    public static void capture(long key) {
        final Minecraft mc = Minecraft.getInstance();
        try (NativeImage full = Screenshot.takeScreenshot(mc.getMainRenderTarget()); NativeImage small = new NativeImage(WIDTH, HEIGHT, false)) {
            // the middle 16:9 of the frame, scaled down
            final int w = full.getWidth(), h = full.getHeight();
            final int cw = Math.min(w, h * 16 / 9), ch = Math.min(h, w * 9 / 16);
            full.resizeSubRectTo((w - cw) / 2, (h - ch) / 2, cw, ch, small);
            final Path file = folder().resolve(key + ".png");
            Files.createDirectories(file.getParent());
            small.writeToFile(file);
        } catch (IOException | RuntimeException e) {
            AbyssalDepths.LOGGER.warn("could not keep the picture of a waypoint", e);
        }
    }

    /** The picture of a place as a texture, or null if there is none. */
    static ResourceLocation texture(long key) {
        if (LOADED.containsKey(key)) {
            return LOADED.get(key);
        }
        ResourceLocation id = null;
        final Path file = folder().resolve(key + ".png");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                id = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "waypoint/" + Long.toUnsignedString(key));
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(NativeImage.read(in)));
            } catch (IOException e) {
                id = null;
            }
        }
        LOADED.put(key, id);
        return id;
    }

    /** Lets go of the loaded pictures (the screen closed). */
    static void release() {
        LOADED.values().stream().filter(java.util.Objects::nonNull).forEach(id -> Minecraft.getInstance().getTextureManager().release(id));
        LOADED.clear();
    }
}
