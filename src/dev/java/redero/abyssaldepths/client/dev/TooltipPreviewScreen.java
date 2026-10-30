package redero.abyssaldepths.client.dev;


import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.ArtifactFrame;
import redero.abyssaldepths.artifact.ArtifactItem;
import redero.abyssaldepths.artifact.ArtifactRank;
import redero.abyssaldepths.client.ArtifactTooltip;

/**
 * Development only (autoshot "artifacts"): the real Inner Haven tooltip as the game draws it, short and
 * with Shift, so tooltip work can be judged from one screenshot. Add mock-ups here to compare ideas.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
final class TooltipPreviewScreen extends Screen {
    private static boolean open;

    /** Like a "mod name" mod would: the mod's name right under the item name (it must end up at the bottom). */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (open && event.getItemStack().getItem() instanceof ArtifactItem) {
            event.getToolTip().add(1, Component.literal("Abyssal Depths").withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
        }
    }


    TooltipPreviewScreen() {
        super(Component.literal("Tooltip preview"));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        open = true;
        g.fill(0, 0, width, height, 0xFF3A3F47); // a neutral backdrop, like a stone wall
        final ItemStack haven = new ItemStack(AbyssalDepths.INNER_HAVEN.get());
        ArtifactItem.setMastery(haven, 73);
        ((ArtifactItem) haven.getItem()).setCharge(haven, 3);
        label(g, "In game (no Shift)", 8, 4);
        g.renderTooltip(font, haven, -4, 44);
        label(g, "In game (Shift held)", 212, 4);
        ArtifactTooltip.forceDetails = true;
        g.renderTooltip(font, haven, 200, 44);
        ArtifactTooltip.forceDetails = false;
        // the other ranks, for their palette and title size
        g.renderTooltip(font, new ItemStack(AbyssalDepths.INTERLUDE.get()), -4, 170);
        g.renderTooltip(font, new ItemStack(AbyssalDepths.PARALLAX.get()), 420, 44);
        // Reprise's waiting signs, every style
        final float time = (net.minecraft.Util.getMillis() % 1_000_000L) / 50.0F;
        int x = 340;
        for (redero.abyssaldepths.ClientConfig.RepriseWait style : redero.abyssaldepths.ClientConfig.RepriseWait.values()) {
            g.fill(x - 30, 200, x + 30, 260, 0xFF050203);
            redero.abyssaldepths.client.RepriseCover.waiting(g, x, 230, time, style);
            label(g, style.name().toLowerCase(java.util.Locale.ROOT), x - 28, 264);
            x += 70;
        }
    }

    private void label(GuiGraphics g, String text, int x, int y) {
        g.drawString(font, text, x, y, 0xFFE0E0E0);
    }

    @Override
    public void removed() {
        open = false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
