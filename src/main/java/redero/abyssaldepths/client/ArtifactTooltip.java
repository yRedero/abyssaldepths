package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.datafixers.util.Either;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.ArtifactFrame;
import redero.abyssaldepths.artifact.ArtifactItem;
import redero.abyssaldepths.artifact.ArtifactRank;

/**
 * Tooltips of artifacts, drawn by the mod instead of vanilla (same layout, own frame and position):
 * <ul>
 * <li>the name as an ornamented title with a shimmer running through it ({@link ArtifactTitle}), the
 * epithet small under it ({@link ArtifactEpithet}), then rank, charge (as pips), mastery; with Shift the
 * description, otherwise a hint; lines other mods add (like the mod name) at the bottom;</li>
 * <li>a pixelated frame in the colours of the artifact's {@link ArtifactFrame}, drawn in 2 x 2 pixel
 * blocks: a dark outer line with stepped corners, a broken accent line, an arrow in every corner and a
 * spike in the middle of every side pointing at the centre. Kept fully on screen.</li>
 * </ul>
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class ArtifactTooltip {
    /** Behind the text (vanilla draws it at 400) and in front of it, for the frame. */
    private static final int BEHIND = 399, FRONT = 401;
    /** Size of one frame "pixel", and how far the frame reaches out from the text, in GUI pixels. */
    private static final int UNIT = 2, MARGIN = 7 * UNIT;
    /** Width the description is wrapped to, in GUI pixels. */
    private static final int DESCRIPTION_WIDTH = 190;
    /** Development: show the details as if Shift were held (tooltip preview screen). */
    public static boolean forceDetails;

    private ArtifactTooltip() {
    }

    // ---- content ----------------------------------------------------------------

    /**
     * The lines under the name: rank, charge (if the artifact has one), mastery; with Shift held the
     * description, otherwise a hint. Built here on the client: whether Shift is held is client-only.
     */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        final ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof ArtifactItem artifact)) {
            return;
        }
        final List<Component> lines = new ArrayList<>();
        final ArtifactRank rank = artifact.rank();
        lines.add(Component.translatable("abyssaldepths.artifact.rank_line", rank.title()).withColor(rank.colour()));
        if (artifact.maxCharge() > 0) {
            lines.add(chargePips(artifact.charge(stack), artifact.maxCharge(), artifact.frame()));
            final long next = Minecraft.getInstance().level == null ? -1 : artifact.ticksToNextCharge(stack, Minecraft.getInstance().level.getGameTime());
            if (next >= 0) {
                final long seconds = (next + 19) / 20;
                lines.add(Component.translatable("abyssaldepths.artifact.next_charge", seconds / 60, String.format("%02d", seconds % 60))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        lines.add(Component.translatable("abyssaldepths.artifact.mastery", ArtifactItem.mastery(stack)).withStyle(ChatFormatting.GRAY));
        if (forceDetails || Screen.hasShiftDown()) {
            lines.add(Component.empty());
            final Font font = Minecraft.getInstance().font;
            for (FormattedText part : font.getSplitter().splitLines(Component.translatable(artifact.descriptionKey()), DESCRIPTION_WIDTH, Style.EMPTY)) {
                lines.add(Component.literal(part.getString()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
        } else {
            lines.add(Component.translatable("abyssaldepths.artifact.more").withStyle(ChatFormatting.DARK_GRAY));
        }
        event.getToolTip().addAll(Math.min(1, event.getToolTip().size()), lines); // right under the name
    }

    /** Charge as a row of pips: filled ones in the frame's highlight, empty ones dark, then the numbers. */
    private static Component chargePips(int charge, int max, ArtifactFrame frame) {
        final MutableComponent line = Component.translatable("abyssaldepths.artifact.charge_label").withStyle(ChatFormatting.GRAY);
        line.append(" ");
        for (int i = 0; i < max; i++) {
            line.append(Component.literal(i < charge ? "◆" : "◇").withColor((i < charge ? frame.highlight : frame.accent) & 0xFFFFFF));
        }
        return line.append(Component.literal("  " + charge + "/" + max).withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * Last, after every other mod: the name becomes the title with the small epithet under it, and lines
     * of other mods that name this mod (e.g. a "mod name" line) move to the bottom.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGather(RenderTooltipEvent.GatherComponents event) {
        if (!(event.getItemStack().getItem() instanceof ArtifactItem artifact) || event.getTooltipElements().isEmpty()) {
            return;
        }
        final List<Either<FormattedText, TooltipComponent>> elements = event.getTooltipElements();
        final String modName = ModList.get().getModContainerById(AbyssalDepths.MODID)
                .map(c -> c.getModInfo().getDisplayName()).orElse("Abyssal Depths");
        final List<Either<FormattedText, TooltipComponent>> moved = new ArrayList<>();
        elements.removeIf(e -> e.left().map(text -> text.getString().strip().equals(modName)).orElse(false) && moved.add(e));
        elements.addAll(moved);
        elements.set(0, Either.right(new ArtifactTitle(event.getItemStack().getHoverName().getString(),
                artifact.rank().colour(), artifact.frame().highlight & 0xFFFFFF)));
        if (I18n.exists(artifact.epithetKey())) {
            elements.add(1, Either.right(new ArtifactEpithet(I18n.get(artifact.epithetKey()))));
        }
    }

    // ---- drawing ----------------------------------------------------------------

    /**
     * Draws the whole tooltip in place of vanilla: placed like vanilla's (beside the mouse, flipped and
     * pushed back on screen at the edges) but with room for the bigger frame, the text laid out like
     * vanilla's.
     */
    @SubscribeEvent
    public static void onRenderTooltip(RenderTooltipEvent.Pre event) {
        if (!(event.getItemStack().getItem() instanceof ArtifactItem artifact)) {
            return;
        }
        event.setCanceled(true);
        final GuiGraphics g = event.getGraphics();
        final Font font = event.getFont();
        final List<ClientTooltipComponent> components = event.getComponents();
        int width = 0, height = 0;
        for (int i = 0; i < components.size(); i++) {
            width = Math.max(width, components.get(i).getWidth(font));
            height += components.get(i).getHeight() + (i < components.size() - 1 ? gapAfter(components, i) : 0);
        }
        final int screenW = event.getScreenWidth(), screenH = event.getScreenHeight();
        int x = event.getX() + 12 + MARGIN - 4, y = event.getY() - 12;
        if (x + width + MARGIN > screenW) {
            x = Math.max(event.getX() - 12 - MARGIN - width, MARGIN);
        }
        y = Math.max(MARGIN, Math.min(y, screenH - height - MARGIN));

        g.pose().pushPose();
        draw(g, artifact.frame(), x, y, width, height);
        g.pose().translate(0.0F, 0.0F, 400.0F);
        int line = y;
        for (int i = 0; i < components.size(); i++) {
            final ClientTooltipComponent component = components.get(i);
            final int left = component instanceof ArtifactEpithet.Line ? x + (width - component.getWidth(font)) / 2 : x;
            component.renderText(font, left, line, g.pose().last().pose(), g.bufferSource());
            line += component.getHeight() + gapAfter(components, i);
        }
        line = y;
        for (int i = 0; i < components.size(); i++) {
            components.get(i).renderImage(font, x, line, g);
            line += components.get(i).getHeight() + gapAfter(components, i);
        }
        g.pose().popPose();
        g.flush();
    }

    /** Vanilla leaves 2 pixels after the first line; not between the title and its epithet. */
    private static int gapAfter(List<ClientTooltipComponent> components, int i) {
        return i == 0 && !(components.size() > 1 && components.get(1) instanceof ArtifactEpithet.Line) ? 2 : 0;
    }

    /**
     * The frame around the text area at x, y (width, height), in blocks of {@link #UNIT} pixels, reaching
     * {@link #MARGIN} out. From the outside in: a dark outer line with corners stepped twice, a broken
     * accent line, then the ornaments: an arrow in every corner and a spike in the middle of every side.
     */
    public static void draw(GuiGraphics g, ArtifactFrame frame, int x, int y, int width, int height) {
        final Blocks b = new Blocks(g, x - MARGIN, y - MARGIN);
        final int right = (width + 2 * MARGIN + UNIT - 1) / UNIT - 1, bottom = (height + 2 * MARGIN + UNIT - 1) / UNIT - 1;

        // background inside the stepped outline
        b.rect(1, 3, right - 1, bottom - 3, BEHIND, frame.background);
        b.rect(3, 1, right - 3, 2, BEHIND, frame.background);
        b.rect(3, bottom - 2, right - 3, bottom - 1, BEHIND, frame.background);
        for (int[] c : corners(right, bottom)) {
            b.dot(c[0] + 2 * c[2], c[1] + 2 * c[3], BEHIND, frame.background);
        }

        // outer line, two steps at every corner
        b.rect(3, 0, right - 3, 0, FRONT, frame.edge);
        b.rect(3, bottom, right - 3, bottom, FRONT, frame.edge);
        b.rect(0, 3, 0, bottom - 3, FRONT, frame.edge);
        b.rect(right, 3, right, bottom - 3, FRONT, frame.edge);
        for (int[] c : corners(right, bottom)) {
            b.dot(c[0] + 2 * c[2], c[1] + c[3], FRONT, frame.edge);
            b.dot(c[0] + c[2], c[1] + 2 * c[3], FRONT, frame.edge);
        }

        // accent line just inside, broken every few blocks
        for (int i = 4; i <= right - 4; i++) {
            if (i % 5 != 0) {
                b.dot(i, 1, FRONT, frame.accent);
                b.dot(i, bottom - 1, FRONT, frame.accent);
            }
        }
        for (int j = 4; j <= bottom - 4; j++) {
            if (j % 5 != 0) {
                b.dot(1, j, FRONT, frame.accent);
                b.dot(right - 1, j, FRONT, frame.accent);
            }
        }

        // corner arrows pointing inwards
        for (int[] c : corners(right, bottom)) {
            final int dx = c[2], dy = c[3];
            b.dot(c[0] + 2 * dx, c[1] + 2 * dy, FRONT, frame.highlight);
            b.dot(c[0] + 3 * dx, c[1] + 2 * dy, FRONT, frame.accent);
            b.dot(c[0] + 2 * dx, c[1] + 3 * dy, FRONT, frame.accent);
            b.dot(c[0] + 3 * dx, c[1] + 3 * dy, FRONT, frame.highlight);
            b.dot(c[0] + 4 * dx, c[1] + 4 * dy, FRONT, frame.accent);
        }

        // spikes in the middle of every side, with a small nub on the outside
        final int midX = right / 2, midY = bottom / 2;
        for (int side = -1; side <= 1; side += 2) {
            final int edgeY = side < 0 ? 0 : bottom, edgeX = side < 0 ? 0 : right;
            b.dot(midX, edgeY + side, FRONT, frame.edge);
            for (int k = -2; k <= 2; k++) {
                b.dot(midX + k, edgeY - side, FRONT, Math.abs(k) == 2 ? frame.accent : frame.highlight);
            }
            b.dot(midX - 1, edgeY - 2 * side, FRONT, frame.accent);
            b.dot(midX, edgeY - 2 * side, FRONT, frame.highlight);
            b.dot(midX + 1, edgeY - 2 * side, FRONT, frame.accent);
            b.dot(midX, edgeY - 3 * side, FRONT, frame.highlight);
            if (bottom >= 14) { // the sides only get one when the tooltip is tall enough
                b.dot(edgeX + side, midY, FRONT, frame.edge);
                for (int k = -1; k <= 1; k++) {
                    b.dot(edgeX - side, midY + k, FRONT, k == 0 ? frame.highlight : frame.accent);
                }
                b.dot(edgeX - 2 * side, midY, FRONT, frame.highlight);
            }
        }
    }

    /** The four corners (in blocks) with the direction pointing inwards: x, y, dx, dy. */
    private static int[][] corners(int right, int bottom) {
        return new int[][] {{0, 0, 1, 1}, {right, 0, -1, 1}, {0, bottom, 1, -1}, {right, bottom, -1, -1}};
    }

    /** Drawing in blocks of {@link #UNIT} pixels from an origin. */
    private record Blocks(GuiGraphics g, int x0, int y0) {
        void dot(int i, int j, int z, int colour) {
            rect(i, j, i, j, z, colour);
        }

        /** Blocks i0..i1, j0..j1 (inclusive). */
        void rect(int i0, int j0, int i1, int j1, int z, int colour) {
            g.fill(x0 + i0 * UNIT, y0 + j0 * UNIT, x0 + (i1 + 1) * UNIT, y0 + (j1 + 1) * UNIT, z, colour);
        }
    }
}
