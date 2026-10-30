package redero.abyssaldepths.client;

import java.util.List;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;
import redero.abyssaldepths.artifact.ArtifactItem;

/**
 * A showcase of the artifacts ({@code /abyssaldepths showcase}): one at a time (◀ ▶), its tooltip as it is
 * and with its details, its icon large, at a mastery chosen with the buttons below; and Reprise's waiting
 * signs, every style.
 */
public final class ArtifactShowcaseScreen extends Screen {
    private static final int[] MASTERY = {0, 25, 50, 75, 100};
    private final List<ArtifactItem> artifacts;
    private int index, mastery;

    public ArtifactShowcaseScreen() {
        super(Component.translatable("abyssaldepths.showcase.title"));
        artifacts = BuiltInRegistries.ITEM.stream().filter(item -> item instanceof ArtifactItem
                && BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(AbyssalDepths.MODID)).map(item -> (ArtifactItem) item).toList();
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> index = (index + artifacts.size() - 1) % artifacts.size()).bounds(8, 8, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> index = (index + 1) % artifacts.size()).bounds(32, 8, 20, 20).build());
        for (int i = 0; i < MASTERY.length; i++) {
            final int m = MASTERY[i];
            addRenderableWidget(Button.builder(Component.literal(m + " %"), b -> mastery = m).bounds(8 + i * 44, height - 28, 40, 20).build());
        }
    }

    /** Shows the artifact with this item id at this mastery (also for the tests). */
    public ArtifactShowcaseScreen show(String id, int mastery) {
        for (int i = 0; i < artifacts.size(); i++) {
            if (BuiltInRegistries.ITEM.getKey(artifacts.get(i)).getPath().equals(id)) {
                index = i;
            }
        }
        this.mastery = mastery;
        return this;
    }

    private ItemStack shown() {
        final ArtifactItem artifact = artifacts.get(index);
        final ItemStack stack = new ItemStack(artifact);
        ArtifactItem.setMastery(stack, mastery);
        if (artifact.maxCharge() > 0) {
            artifact.setCharge(stack, artifact.maxCharge());
        }
        return stack;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (artifacts.isEmpty()) {
            return;
        }
        final ItemStack stack = shown();
        g.drawString(font, (index + 1) + " / " + artifacts.size(), 58, 14, 0xFFC0C0C8);
        // the icon, large
        g.pose().pushPose();
        g.pose().translate(width - 80, 16, 0);
        g.pose().scale(4.0F, 4.0F, 1.0F);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
        // as it is, and with its details
        g.renderTooltip(font, stack, 0, 70);
        ArtifactTooltip.forceDetails = true;
        g.renderTooltip(font, stack, width / 2 - 40, 70);
        ArtifactTooltip.forceDetails = false;
        // Reprise's waiting signs
        final float time = (Util.getMillis() % 1_000_000L) / 50.0F;
        int x = width - 70 * ClientConfig.RepriseWait.values().length + 10;
        for (ClientConfig.RepriseWait style : ClientConfig.RepriseWait.values()) {
            g.fill(x - 30, height - 100, x + 30, height - 40, 0xFF050203);
            RepriseCover.waiting(g, x, height - 70, time, style);
            g.drawCenteredString(font, style.name().toLowerCase(java.util.Locale.ROOT), x, height - 36, 0xFFC0C0C8);
            x += 70;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
