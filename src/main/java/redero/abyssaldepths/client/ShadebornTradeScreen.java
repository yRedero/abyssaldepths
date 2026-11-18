package redero.abyssaldepths.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Trading with a Shadeborn: a screen of the mod's own ({@code textures/gui/shadeborn_trade.png}), in the layer's colours: near-black
 * panels with soft bevels (light from the top left), the gloam's violet only where something is chosen or under the
 * mouse, a little red for what cannot be had. On the left what they offer, a row each (what they want, then what they
 * give); on the right where the goods are laid (their slots where the game's trading keeps them), the inventory below.
 * They do not speak: no words but the title. A first version to be judged; the vanilla screen is replaced only for the
 * Shadeborn.
 */
public class ShadebornTradeScreen extends AbstractContainerScreen<MerchantMenu> {
    // the palette: panel, its light and dark edges, slot wells, the gloam's violet, the red of what cannot be had
    private static final int VIOLET_DIM = 0x60725276, RED = 0xFF96253E, TEXT = 0xFFD0C8C0, TEXT_DIM = 0xFF8A8480;
    private static final int ROWS = 7, ROW_H = 20, LIST_X = 6, LIST_Y = 18, LIST_W = 92;
    /** The screen's texture (512x256): the screen at 0,0; below it the lit arrow (0,170), the chosen row (0,190), the row under the mouse (0,212). */
    private static final net.minecraft.resources.ResourceLocation TEXTURE = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID,
            "textures/gui/shadeborn_trade.png");
    private static final net.minecraft.resources.ResourceLocation VANILLA = net.minecraft.resources.ResourceLocation.withDefaultNamespace(
            "textures/gui/container/villager.png");
    private int selected = -1, scroll;

    public ShadebornTradeScreen(MerchantMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 276;
        imageHeight = 166;
        inventoryLabelX = 107;
        titleLabelX = 107;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
        // the inventory as the game's own trading screen has it (only the trade part is the Shadeborn's)
        g.blit(VANILLA, leftPos + 100, topPos + 70, 100, 70, imageWidth - 100, imageHeight - 70, 512, 256);
        g.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 512, 256);
        if (!menu.getSlot(2).getItem().isEmpty()) { // a trade ready: the arrow lit
            g.blit(TEXTURE, leftPos + 188, topPos + 41, 0, 170, 16, 8, 512, 256);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        super.render(g, mx, my, partial);
        final MerchantOffers offers = menu.getOffers();
        final int x = leftPos + LIST_X, y = topPos + LIST_Y;
        scroll = Mth.clamp(scroll, 0, Math.max(0, offers.size() - ROWS));
        for (int i = 0; i < ROWS && i + scroll < offers.size(); i++) {
            final int index = i + scroll;
            final MerchantOffer offer = offers.get(index);
            final int ry = y + i * ROW_H;
            final boolean over = mx >= x && mx < x + LIST_W && my >= ry && my < ry + ROW_H;
            if (index == selected) {
                g.blit(TEXTURE, x, ry, 0, 190, LIST_W, ROW_H, 512, 256);
            } else if (over) {
                g.blit(TEXTURE, x, ry, 0, 212, LIST_W, ROW_H, 512, 256);
            }
            final boolean out = offer.isOutOfStock();
            g.renderFakeItem(offer.getCostA(), x + 3, ry + 2);
            g.renderItemDecorations(font, offer.getCostA(), x + 3, ry + 2);
            if (!offer.getCostB().isEmpty()) {
                g.renderFakeItem(offer.getCostB(), x + 22, ry + 2);
                g.renderItemDecorations(font, offer.getCostB(), x + 22, ry + 2);
            }
            // a small arrow, then what they give
            final int ax = x + 44, ay = ry + 9;
            g.fill(ax, ay, ax + 10, ay + 1, out ? RED : TEXT_DIM);
            g.fill(ax + 8, ay - 2, ax + 9, ay + 3, out ? RED : TEXT_DIM);
            g.renderFakeItem(offer.getResult(), x + 60, ry + 2);
            g.renderItemDecorations(font, offer.getResult(), x + 60, ry + 2);
            if (out) { // none left for now: crossed out in the layer's red
                g.fill(x + 2, ry + ROW_H / 2, x + LIST_W - 2, ry + ROW_H / 2 + 1, RED);
            }
        }
        if (offers.size() > ROWS) { // the scroll bar, a thin violet bead
            final int track = ROWS * ROW_H - 4, bead = Math.max(8, track * ROWS / offers.size());
            final int by = y + 2 + (track - bead) * scroll / Math.max(1, offers.size() - ROWS);
            g.fill(x + LIST_W - 3, by, x + LIST_W - 1, by + bead, VIOLET_DIM);
        }
        // the tooltip of what is under the mouse in the list
        for (int i = 0; i < ROWS && i + scroll < offers.size(); i++) {
            final MerchantOffer offer = offers.get(i + scroll);
            final int ry = y + i * ROW_H;
            final ItemStack hovered = hit(mx, my, x + 3, ry + 2) ? offer.getCostA() : hit(mx, my, x + 22, ry + 2) ? offer.getCostB()
                    : hit(mx, my, x + 60, ry + 2) ? offer.getResult() : ItemStack.EMPTY;
            if (!hovered.isEmpty()) {
                g.renderTooltip(font, hovered, mx, my);
            }
        }
        renderTooltip(g, mx, my);
    }

    private static boolean hit(int mx, int my, int x, int y) {
        return mx >= x && mx < x + 16 && my >= y && my < y + 16;
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, title, titleLabelX, 6, TEXT, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, imageHeight - 94, 0x404040, false);
    }

    // ------------------------------------------------------------------ choosing a trade

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        final int x = leftPos + LIST_X, y = topPos + LIST_Y;
        if (mx >= x && mx < x + LIST_W && my >= y && my < y + ROWS * ROW_H) {
            final int index = (int) ((my - y) / ROW_H) + scroll;
            if (index < menu.getOffers().size()) {
                selected = index;
                menu.setSelectionHint(index);
                menu.tryMoveItems(index);
                if (minecraft != null && minecraft.getConnection() != null) {
                    minecraft.getConnection().send(new ServerboundSelectTradePacket(index));
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll = Mth.clamp(scroll - (int) Math.signum(dy), 0, Math.max(0, menu.getOffers().size() - ROWS));
        return true;
    }

    /** The Shadeborn's trades open in this screen instead of the game's own. */
    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Hook {
        @SubscribeEvent
        public static void onOpen(ScreenEvent.Opening event) {
            if (event.getNewScreen() instanceof MerchantScreen vanilla && vanilla.getTitle().getContents() instanceof TranslatableContents t
                    && t.getKey().startsWith("entity.abyssaldepths.shadeborn") && net.minecraft.client.Minecraft.getInstance().player != null) {
                event.setNewScreen(new ShadebornTradeScreen(vanilla.getMenu(), net.minecraft.client.Minecraft.getInstance().player.getInventory(), vanilla.getTitle()));
            }
        }
    }
}
