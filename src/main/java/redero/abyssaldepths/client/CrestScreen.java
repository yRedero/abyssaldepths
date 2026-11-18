package redero.abyssaldepths.client;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.crest.Crests;

/**
 * Choosing a crest, on first joining a world, before the player is set into it: a screen of its own (opaque, the
 * world not shown), its background the black with motes of light and darkness drifting slowly through it (the
 * opening's two forces, at rest); the four crests large, side by side, the one under the mouse lifting, the chosen one
 * marked; then taken for good. It cannot be closed otherwise, and the game waits meanwhile.
 * Also keeps which crest each player has ({@link #KNOWN}), for the player list and cutscenes.
 */
public class CrestScreen extends Screen {
    /** Each player's crest, by entity id (sent by the server). */
    public static final Map<Integer, Integer> KNOWN = new HashMap<>();
    private static final int MOTES = 70;
    private final float[][] motes = new float[MOTES][5];
    private final long start = Util.getMillis();
    private int chosen = -1;
    private Button take;

    public CrestScreen() {
        super(Component.translatable("crest.abyssaldepths.choose"));
        final RandomSource r = RandomSource.create(11);
        for (float[] m : motes) { // x, y (shares), drift speed, phase, light (1) or dark (0)
            m[0] = r.nextFloat();
            m[1] = r.nextFloat();
            m[2] = 0.004F + r.nextFloat() * 0.01F;
            m[3] = r.nextFloat() * 6.28F;
            m[4] = r.nextBoolean() ? 1 : 0;
        }
    }

    /** The local player's crest name (for cutscenes), or nothing. */
    public static Component ownName() {
        final Minecraft mc = Minecraft.getInstance();
        final Integer c = mc.player == null ? null : KNOWN.get(mc.player.getId());
        return c == null ? Component.empty() : Crests.name(Math.min(c, Crests.IDS.length - 1));
    }

    @Override
    protected void init() {
        take = addRenderableWidget(Button.builder(Component.translatable("crest.abyssaldepths.take"), b -> {
            if (chosen >= 0) {
                PacketDistributor.sendToServer(new Crests.Choice(chosen));
                if (minecraft != null && minecraft.player != null) {
                    KNOWN.put(minecraft.player.getId(), chosen);
                    minecraft.player.playSound(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 0.6F);
                }
                if (minecraft != null) {
                    minecraft.setScreen(null);
                }
            }
        }).bounds(width / 2 - 70, height - 44, 140, 20).build());
        take.active = chosen >= 0;
    }

    /** Marks a crest as the one chosen (as a click on it does). */
    public void pick(int i) {
        chosen = Math.max(0, Math.min(Crests.IDS.length - 1, i));
        if (take != null) {
            take.active = true;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return true; // the world waits while one chooses
    }

    private int size() {
        return Math.min(96, (width - 60) / Crests.IDS.length - 16);
    }

    private int left(int i) {
        final int s = size(), gap = 16, all = Crests.IDS.length * (s + gap) - gap;
        return width / 2 - all / 2 + i * (s + gap);
    }

    private int top() {
        return height / 2 - size() / 2 - 6;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        final int s = size(), top = top();
        for (int i = 0; i < Crests.IDS.length; i++) {
            if (mx >= left(i) && mx < left(i) + s && my >= top - 6 && my < top + s) {
                pick(i);
                if (minecraft != null) {
                    minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 0.9F, 0.6F));
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    /** The screen's own background: the black, the two forces drifting slowly through it, darkening at the edges. */
    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
        final float t = (Util.getMillis() - start) / 1000.0F;
        g.fill(0, 0, width, height, 0xFF050407);
        for (float[] m : motes) {
            final float x = (m[0] + t * m[2] * (m[4] > 0 ? 1 : -1)) % 1.0F, xs = (x < 0 ? x + 1 : x) * width;
            final float y = m[1] * height + Mth.sin(t * 0.7F + m[3]) * 8;
            final float a = 0.35F + 0.35F * Mth.sin(t * 1.3F + m[3] * 2);
            final int px = (int) xs, py = (int) y;
            if (m[4] > 0) {
                g.fill(px - 1, py - 1, px + 3, py + 3, (int) (a * 35) << 24 | 0xF2E8D8);
                g.fill(px, py, px + 2, py + 2, (int) (a * 200) << 24 | 0xF7F2EA);
            } else {
                g.fill(px - 1, py - 1, px + 3, py + 3, (int) (a * 140) << 24 | 0x2A1838);
                g.fill(px, py, px + 2, py + 2, (int) (a * 255) << 24);
            }
        }
        g.fillGradient(0, 0, width, height / 4, 0xC0000000, 0x00000000);
        g.fillGradient(0, height * 3 / 4, width, height, 0x00000000, 0xC0000000);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        super.render(g, mx, my, partial);
        final int s = size(), top = top();
        g.pose().pushPose();
        g.pose().translate(width / 2.0F, top - 52, 0);
        g.pose().scale(1.6F, 1.6F, 1);
        g.drawCenteredString(font, title, 0, 0, 0xE6DED4);
        g.pose().popPose();
        g.drawCenteredString(font, Component.translatable("crest.abyssaldepths.hint"), width / 2, top - 30, 0x8A8480);
        for (int i = 0; i < Crests.IDS.length; i++) {
            final int x = left(i);
            final boolean over = mx >= x && mx < x + s && my >= top - 6 && my < top + s;
            final int lift = i == chosen ? -6 : over ? -3 : 0;
            if (i == chosen) {
                g.fill(x - 4, top - 4 + lift, x + s + 4, top + s + 4 + lift, 0x70000000 | Crests.COLORS[i]);
            }
            g.blit(Crests.sigil(i), x, top + lift, 0, 0, s, s, s, s);
            g.drawCenteredString(font, Crests.name(i), x + s / 2, top + s + 10, i == chosen || over ? 0xFF000000 | brighten(Crests.COLORS[i]) : 0xFF6A6460);
        }
    }

    private static int brighten(int c) {
        final int r = Math.min(255, ((c >> 16) & 0xFF) + 80), gg = Math.min(255, ((c >> 8) & 0xFF) + 80), b = Math.min(255, (c & 0xFF) + 80);
        return r << 16 | gg << 8 | b;
    }
}
