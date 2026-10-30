package redero.abyssaldepths.client;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.network.WaypointChoicePayload;

/**
 * Where to come back to from the inner self (Inner Haven at full mastery). The screen fades to the haven's
 * white, and every remembered place shows as a red-framed picture of it (taken when it was left), its
 * place below; the first one is where the body waits. Clicking one goes there (the fade stays white into
 * the crossing); Escape stays.
 */
public final class WaypointScreen extends Screen {
    private static final int WHITE = 0xF4F2EC, RED = 0xFFB0102A, RED_LIT = 0xFFE0304A, INK = 0xFF3A2A2A, FAINT = 0xFF8A7A7A;
    private static final int TILE_W = 128, TILE_H = 72, FRAME = 3, GAP = 18;
    private final String[][] places;
    private final long opened = Util.getMillis();
    private boolean chosen;

    /** {@code places}: "dimension|x|y|z|seconds ago|key" each, joined by ';' (as the server sends them). */
    public WaypointScreen(String places) {
        super(Component.translatable("abyssaldepths.waypoints.title"));
        this.places = java.util.Arrays.stream(places.isEmpty() ? new String[0] : places.split(";"))
                .map(p -> p.split("\\|")).filter(p -> p.length >= 6).toArray(String[][]::new);
    }

    /** Where tile i lies: in rows of up to three, centred. */
    private int[] tile(int i) {
        final int perRow = Math.min(3, Math.max(1, places.length));
        final int rows = (places.length + perRow - 1) / perRow;
        final int row = i / perRow, inRow = Math.min(perRow, places.length - row * perRow);
        final int rowWidth = inRow * (TILE_W + 2 * FRAME) + (inRow - 1) * GAP;
        final int totalHeight = rows * (TILE_H + 2 * FRAME + 30) - 30;
        final int x = (width - rowWidth) / 2 + (i % perRow) * (TILE_W + 2 * FRAME + GAP);
        final int y = (height - totalHeight) / 2 + 10 + row * (TILE_H + 2 * FRAME + 30);
        return new int[] {x, y};
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // the haven's white, fading in
        final float fade = Mth.clamp((Util.getMillis() - opened) / 600.0F, 0.0F, 1.0F);
        g.fill(0, 0, width, height, (int) (fade * 255) << 24 | WHITE);
        if (fade < 0.6F) {
            return;
        }
        final int alpha = (int) (Mth.clamp((fade - 0.6F) / 0.4F, 0.0F, 1.0F) * 255);
        g.drawCenteredString(font, title, width / 2, tile(0)[1] - 26, alpha << 24 | (INK & 0xFFFFFF));
        for (int i = 0; i < places.length; i++) {
            final int[] at = tile(i);
            final boolean hover = mouseX >= at[0] && mouseX < at[0] + TILE_W + 2 * FRAME && mouseY >= at[1] && mouseY < at[1] + TILE_H + 2 * FRAME;
            g.fill(at[0], at[1], at[0] + TILE_W + 2 * FRAME, at[1] + TILE_H + 2 * FRAME, hover ? RED_LIT : RED);
            final ResourceLocation picture = WaypointShots.texture(Long.parseLong(places[i][5]));
            if (picture != null) {
                g.blit(picture, at[0] + FRAME, at[1] + FRAME, 0, 0, TILE_W, TILE_H, TILE_W, TILE_H);
            } else {
                g.fill(at[0] + FRAME, at[1] + FRAME, at[0] + FRAME + TILE_W, at[1] + FRAME + TILE_H, 0xFF201418);
                g.drawCenteredString(font, "?", at[0] + FRAME + TILE_W / 2, at[1] + FRAME + TILE_H / 2 - 4, 0xFF6A5050);
            }
            final int cx = at[0] + FRAME + TILE_W / 2, below = at[1] + TILE_H + 2 * FRAME + 4;
            g.drawCenteredString(font, Component.translatable("abyssaldepths.waypoints.coords", places[i][1], places[i][2], places[i][3]), cx, below, INK);
            final Component second = i == 0 ? Component.translatable("abyssaldepths.waypoints.body")
                    : Component.translatable("abyssaldepths.waypoints.where", dimension(places[i][0]), ago(Long.parseLong(places[i][4])));
            g.drawCenteredString(font, second, cx, below + 11, FAINT);
        }
        if (places.length == 0) {
            g.drawCenteredString(font, Component.translatable("abyssaldepths.waypoints.none"), width / 2, height / 2, INK);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // the white is drawn in render
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = 0; i < places.length && !chosen; i++) {
            final int[] at = tile(i);
            if (mouseX >= at[0] && mouseX < at[0] + TILE_W + 2 * FRAME && mouseY >= at[1] && mouseY < at[1] + TILE_H + 2 * FRAME) {
                chosen = true;
                PacketDistributor.sendToServer(new WaypointChoicePayload(i == 0 ? -1 : i));
                HavenFade.white(); // stays white into the crossing
                onClose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void removed() {
        WaypointShots.release();
    }

    /** "Overworld", "The Nether", "The End", else the dimension's own name. */
    private static Component dimension(String id) {
        final ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return Component.literal(id);
        }
        return switch (key.toString()) {
            case "minecraft:overworld" -> Component.translatable("abyssaldepths.waypoints.overworld");
            case "minecraft:the_nether" -> Component.translatable("abyssaldepths.waypoints.nether");
            case "minecraft:the_end" -> Component.translatable("abyssaldepths.waypoints.end");
            default -> Component.literal(key.getPath());
        };
    }

    private static Component ago(long seconds) {
        return seconds < 60 ? Component.translatable("abyssaldepths.waypoints.seconds", seconds)
                : seconds < 3600 ? Component.translatable("abyssaldepths.waypoints.minutes", seconds / 60)
                : Component.translatable("abyssaldepths.waypoints.hours", seconds / 3600);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
