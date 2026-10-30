package redero.abyssaldepths.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.network.WaypointChoicePayload;

/**
 * Where to come back to from the inner self (Inner Haven at full mastery): where the body waits, or one of
 * the last places left from. The choice goes to the server; closing it chooses nothing.
 */
public final class WaypointScreen extends Screen {
    private final String[] places;

    /** {@code places}: "dimension|x|y|z|seconds ago" each, joined by ';' (as the server sends them). */
    public WaypointScreen(String places) {
        super(Component.translatable("abyssaldepths.waypoints.title"));
        this.places = places.isEmpty() ? new String[0] : places.split(";");
    }

    @Override
    protected void init() {
        final int w = 240, h = 20, x = (width - w) / 2;
        int y = height / 2 - (places.length + 1) * (h + 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable("abyssaldepths.waypoints.body"), b -> choose(-1)).bounds(x, y, w, h).build());
        for (int i = 0; i < places.length; i++) {
            final String[] p = places[i].split("\\|");
            if (p.length < 5) {
                continue;
            }
            y += h + 4;
            final int index = i;
            final Component label = Component.translatable("abyssaldepths.waypoints.place", dimension(p[0]), p[1], p[2], p[3], ago(Long.parseLong(p[4])));
            addRenderableWidget(Button.builder(label, b -> choose(index)).bounds(x, y, w, h).build());
        }
    }

    private void choose(int index) {
        PacketDistributor.sendToServer(new WaypointChoicePayload(index));
        onClose();
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, height / 2 - (places.length + 1) * 12 - 24, 0xFFE8E0F0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
