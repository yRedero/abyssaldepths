package redero.abyssaldepths.client;

import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.fracture.FractureTrials;

/**
 * The screen in a Fracturing trial: the usual HUD is gone (hotbar, hearts, food, experience, effects: none of it
 * counts here); the trial's name shows while the player looks round, and a small black and white bar at the top
 * says how it stands: the time left (the Collapse), the time and the strikes still to be borne (the Breakdown),
 * the core and the player's stability (the Core). A hit flashes the screen. Outside the trials: the
 * Corruption Scars, a small cracked mark each beside the hotbar, and now and then a flicker of black and white.
 * <p>
 * And the sound of the trials, ambient: two low drones under all of it, now and then a mood far off (a cave's
 * breath, the valleys of the Nether, all slowed and quiet), a soft low chime for each strike marked.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class TrialHud {
    private static final Set<ResourceLocation> HIDDEN = Set.of(VanillaGuiLayers.HOTBAR, VanillaGuiLayers.PLAYER_HEALTH, VanillaGuiLayers.ARMOR_LEVEL,
            VanillaGuiLayers.FOOD_LEVEL, VanillaGuiLayers.AIR_LEVEL, VanillaGuiLayers.EXPERIENCE_BAR, VanillaGuiLayers.EXPERIENCE_LEVEL,
            VanillaGuiLayers.SELECTED_ITEM_NAME, VanillaGuiLayers.EFFECTS, VanillaGuiLayers.VEHICLE_HEALTH, VanillaGuiLayers.JUMP_METER);

    private static String trial = "";
    private static int titleTicks, titleLength, hitTicks, outTicks, scars, flicker;
    private static boolean passed;
    private static float a, b, c;

    private static int nextSound = 160;
    private static Drone drone, under;

    private TrialHud() {
    }

    /** The drone under a trial: a slowed rumble, faded in, faded out once the trial is left. */
    private static final class Drone extends net.minecraft.client.resources.sounds.AbstractTickableSoundInstance {
        private final float most;

        Drone(net.minecraft.sounds.SoundEvent sound, float pitch, float most) {
            super(sound, net.minecraft.sounds.SoundSource.AMBIENT, net.minecraft.client.resources.sounds.SoundInstance.createUnseededRandom());
            this.most = most;
            looping = true;
            delay = 0;
            volume = 0.01F;
            this.pitch = pitch;
            relative = true;
            attenuation = Attenuation.NONE;
        }

        @Override
        public void tick() {
            if (inTrial()) {
                volume = Math.min(most, volume + 0.01F);
            } else {
                volume -= 0.04F;
                if (volume <= 0.0F) {
                    stop();
                }
            }
        }
    }

    private static void play(net.minecraft.sounds.SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    /** A strike marked: a soft chime, low. */
    public static void warned() {
        play(net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME, 0.5F, 0.45F);
    }

    /** The sounds of a trial, a tick of them. */
    private static void sounds(Minecraft mc) {
        if (drone == null || drone.isStopped()) {
            drone = new Drone(net.minecraft.sounds.SoundEvents.AMBIENT_BASALT_DELTAS_LOOP.value(), 0.5F, 0.4F);
            mc.getSoundManager().play(drone);
        }
        if (under == null || under.isStopped()) {
            under = new Drone(net.minecraft.sounds.SoundEvents.AMBIENT_SOUL_SAND_VALLEY_LOOP.value(), 0.5F, 0.3F);
            mc.getSoundManager().play(under);
        }
        final net.minecraft.util.RandomSource random = mc.level.random;
        if (--nextSound <= 0) { // a mood far off, slowed and quiet
            nextSound = 200 + random.nextInt(300);
            switch (random.nextInt(4)) {
                case 0 -> play(net.minecraft.sounds.SoundEvents.AMBIENT_CAVE.value(), 0.5F + random.nextFloat() * 0.15F, 0.3F);
                case 1 -> play(net.minecraft.sounds.SoundEvents.AMBIENT_SOUL_SAND_VALLEY_MOOD.value(), 0.6F, 0.35F);
                case 2 -> play(net.minecraft.sounds.SoundEvents.AMBIENT_BASALT_DELTAS_MOOD.value(), 0.55F, 0.35F);
                default -> play(net.minecraft.sounds.SoundEvents.AMBIENT_WARPED_FOREST_MOOD.value(), 0.5F, 0.3F);
            }
        }
    }

    private static boolean inTrial() {
        final Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == FractureTrials.DIMENSION;
    }

    /** In a trial (not yet seen: the screen is still covered): its bars, no name yet. */
    public static void enter(String type) {
        trial = type;
        titleTicks = 1000;
        titleLength = 0;
        a = 1;
        b = c = 0;
    }

    /** The trial is seen: its name shows for the looking round. */
    public static void start(String type, int ticks) {
        trial = type;
        titleTicks = 0;
        titleLength = ticks;
        a = 1;
        b = c = 0;
    }

    public static void values(String type, float a, float b, float c) {
        trial = type;
        TrialHud.a = a;
        TrialHud.b = b;
        TrialHud.c = c;
    }

    private static String voiceKey = "";
    private static int voiceTicks = 1000;

    /** The corruption speaks: a line, glitching in and out. */
    public static void voice(String key) {
        voiceKey = key;
        voiceTicks = 0;
    }

    private static final String GLYPHS = "#%&@$?!/|<>=+*";

    /** The voice's line: lower middle, letters glitching to signs as it comes and goes and now and then between. */
    private static void voiceLine(GuiGraphics g, Minecraft mc, int w, int h) {
        final int length = 80;
        if (voiceKey.isEmpty() || voiceTicks > length) {
            return;
        }
        final String line = Component.translatable("abyssaldepths.voice." + voiceKey).getString();
        final float settle = Math.min(1.0F, voiceTicks / 12.0F), leave = Math.min(1.0F, (length - voiceTicks) / 14.0F);
        final StringBuilder shown = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            final float n = UndertowRenderer.noise(i * 13 + voiceKey.hashCode(), voiceTicks / 2);
            final boolean glitch = c != ' ' && (n > Math.min(settle, leave) || n < 0.04F);
            shown.append(glitch ? GLYPHS.charAt((int) (UndertowRenderer.noise(i, voiceTicks) * GLYPHS.length()) % GLYPHS.length()) : c);
        }
        final float alpha = Mth.clamp(Math.min(voiceTicks / 6.0F, leave), 0.0F, 1.0F);
        centred(g, mc, Component.literal(shown.toString()), w / 2 + (voiceTicks % 7 == 0 ? 1 : 0), (int) (h * 0.66F), 1.6F, alpha);
    }

    public static void hit() {
        hitTicks = 6;
    }

    /** Back in the body: a word of how it went. */
    public static void out(boolean passed) {
        TrialHud.passed = passed;
        outTicks = 60;
    }

    /** How many scars the player has (as their screen was told). */
    public static int scarCount() {
        return scars;
    }

    /** The scars before the last change, and when it came (for their marks popping in and out). */
    private static int scarsBefore, clock, popAt = -100;
    private static final int POP_TICKS = 18;
    /** Ticks the screen has been clear of the fracturing (the scars' change shows a moment after). */
    private static int clearFor;

    /** The scars as last told (shown once the screen is clear of the fracturing). */
    private static int scarsTold;

    public static void scars(int scars) {
        scarsTold = scars;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (inTrial() && Minecraft.getInstance().player != null) {
            sounds(Minecraft.getInstance());
        }
        titleTicks++;
        clock++;
        clearFor = FractureScreen.clear() ? clearFor + 1 : 0;
        if (scarsTold != scars && clearFor > 20) { // the change, seen a moment after the screen has cleared: its marks pop
            scarsBefore = scars;
            scars = scarsTold;
            popAt = clock;
        }
        voiceTicks++;
        if (hitTicks > 0) {
            hitTicks--;
        }
        if (outTicks > 0) {
            outTicks--;
        }
        // the scarred: a flicker of black and white now and then (more often, the more scars)
        if (flicker > 0) {
            flicker--;
        } else if (scars > 0 && !inTrial() && Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.random.nextInt(Math.max(200, 2400 / scars)) == 0) {
            flicker = 3;
        }
    }

    @SubscribeEvent
    public static void onLayer(RenderGuiLayerEvent.Pre event) {
        if (inTrial() && HIDDEN.contains(event.getName())) {
            event.setCanceled(true);
        }
    }

    private static void draw(GuiGraphics g, float partial) {
        final Minecraft mc = Minecraft.getInstance();
        final int w = g.guiWidth(), h = g.guiHeight();
        voiceLine(g, mc, w, h);
        if (inTrial()) {
            title(g, mc, w, h);
            bars(g, w);
            if (hitTicks > 0) { // a hit: the screen flashes in black and white
                final int cell = 8;
                for (int y = 0; y < h; y += cell) {
                    for (int x = 0; x < w; x += cell) {
                        if (UndertowRenderer.noise(x * 7 + y, hitTicks) < 0.18F) {
                            final float n = UndertowRenderer.noise(x, y + hitTicks);
                            g.fill(x, y, x + cell, y + cell, n > 0.75F ? 0xB0F1EBDF : n > 0.5F ? 0xA09A958D : n > 0.25F ? 0xA03A3D46 : 0xB00C0D12);
                        }
                    }
                }
            }
            return;
        }
        if (outTicks > 0) { // back: how it went
            final float alpha = Mth.clamp(outTicks / 20.0F, 0.0F, 1.0F);
            centred(g, mc, Component.translatable(passed ? "abyssaldepths.trial.passed" : "abyssaldepths.trial.failed"), w / 2, h / 3, 2.0F, alpha);
        }
        final float pop = Math.min(1.0F, (clock - popAt + partial) / POP_TICKS);
        final int marks = pop < 1.0F ? Math.max(scars, scarsBefore) : scars;
        if (marks > 0 && !mc.options.hideGui) { // a mark for each scar, left of the hotbar (clear of the hearts)
            final long time = mc.level == null ? 0 : mc.level.getGameTime();
            for (int s = 0; s < marks; s++) {
                float scale = 1.0F;
                if (s >= scarsBefore && s < scars) { // new: big at first, shrinking into its size
                    final float e = 1.0F - (1.0F - pop) * (1.0F - pop);
                    scale = 1.0F + 0.8F * (1.0F - e);
                } else if (s >= scars) { // gone: a little swell, then shrinking to nothing
                    scale = pop < 0.35F ? 1.0F + 0.35F * pop / 0.35F : 1.35F * (1.0F - (pop - 0.35F) / 0.65F);
                }
                if (scale <= 0.02F) {
                    continue;
                }
                final int x = w / 2 - 91 - 10, y = h - 22 + s * 7 + 1;
                g.pose().pushPose();
                g.pose().translate(x + 3.5F, y + 3.5F, 0.0F);
                g.pose().scale(scale, scale, 1.0F);
                g.pose().translate(-x - 3.5F, -y - 3.5F, 0.0F);
                scarIcon(g, x, y, s, time);
                g.pose().popPose();
            }
        }
        if (flicker > 0) {
            final int cell = 10;
            for (int y = 0; y < h; y += cell) {
                for (int x = 0; x < w; x += cell) {
                    final float n = UndertowRenderer.noise(x * 31 + y, flicker + (int) (mc.level.getGameTime() % 97));
                    if (n < 0.3F) {
                        g.fill(x, y, x + cell, y + cell, n < 0.15F ? 0x90000000 : 0x90FFFFFF);
                    }
                }
            }
        }
    }

    /**
     * The scars' icons, 7 x 7 pixels: '#' cold black, 'o' dark grey, '+' warm grey, '.' warm white, ' ' nothing.
     * The eye has a second picture, open, shown now and then.
     */
    private static final java.util.Map<redero.abyssaldepths.ClientConfig.ScarIcon, String[]> ICONS = java.util.Map.of(
            redero.abyssaldepths.ClientConfig.ScarIcon.CRACK, new String[] {"#######", "#..#..#", "#..#+.#", "#.#...#", "#+#...#", "#.....#", "#######"},
            redero.abyssaldepths.ClientConfig.ScarIcon.HEART, new String[] {" ## ## ", "#..#+.#", "#.#+..#", "#..#.+#", " #.#.# ", "  #o#  ", "   #   "},
            redero.abyssaldepths.ClientConfig.ScarIcon.SHARD, new String[] {"   #   ", "  #.#  ", " #..+# ", "#.#..+#", " #+.o# ", "  #+#  ", "   #   "},
            redero.abyssaldepths.ClientConfig.ScarIcon.EYE, new String[] {"       ", "       ", " o###o ", "#######", " o###o ", "       ", "       "},
            redero.abyssaldepths.ClientConfig.ScarIcon.RUNE, new String[] {"#######", "#.o.o.#", "#..#..#", "#o###o#", "#..#..#", "#.o.o.#", "#######"},
            redero.abyssaldepths.ClientConfig.ScarIcon.VEIN, new String[] {"  o    ", " o#   o", "  ##+# ", "  ###  ", " #+##  ", "o   #o ", "     o "});
    private static final String[] EYE_OPEN = {"       ", " ##### ", "#..#..#", "#.###.#", "#..#..#", " ##### ", "       "};

    private static void scarIcon(GuiGraphics g, int x, int y, int index, long time) {
        final redero.abyssaldepths.ClientConfig.ScarIcon icon = redero.abyssaldepths.ClientConfig.scarIcon();
        String[] rows = ICONS.get(icon);
        if (icon == redero.abyssaldepths.ClientConfig.ScarIcon.EYE && (time / 4 + index * 13) % 40 < 6) {
            rows = EYE_OPEN; // it opens a moment now and then
        }
        final boolean pulse = (time / 3 + index * 7) % 50 < 2; // now and then the light parts dim
        for (int j = 0; j < 7; j++) {
            for (int i = 0; i < 7; i++) {
                final int colour = switch (rows[j].charAt(i)) {
                    case '#' -> 0xFF0C0D12;
                    case 'o' -> 0xFF4A4D55;
                    case '+' -> 0xFF9A958D;
                    case '.' -> pulse ? 0xFFC9C2B6 : 0xFFF1EBDF;
                    default -> 0;
                };
                if (colour != 0) {
                    g.fill(x + i, y + j, x + i + 1, y + j + 1, colour);
                }
            }
        }
    }

    private static void title(GuiGraphics g, Minecraft mc, int w, int h) {
        if (titleTicks > titleLength + 20 || trial.isEmpty()) {
            return;
        }
        final float alpha = Mth.clamp(Math.min(titleTicks / 10.0F, (titleLength + 20 - titleTicks) / 15.0F), 0.0F, 1.0F);
        centred(g, mc, Component.translatable("abyssaldepths.trial." + trial), w / 2, h / 4, 2.5F, alpha);
        centred(g, mc, Component.translatable("abyssaldepths.trial." + trial + ".hint"), w / 2, h / 4 + 26, 1.0F, alpha * 0.8F);
    }

    /** Black outlined white text, centred, scaled. */
    private static void centred(GuiGraphics g, Minecraft mc, Component text, int x, int y, float scale, float alpha) {
        if (alpha <= 0.02F) {
            return;
        }
        final int a8 = Math.max(4, (int) (alpha * 255)) << 24;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1.0F);
        final int tx = -mc.font.width(text) / 2;
        for (int k = 0; k < 4; k++) {
            g.drawString(mc.font, text, tx + (k == 0 ? -1 : k == 1 ? 1 : 0), (k == 2 ? -1 : k == 3 ? 1 : 0), a8, false);
        }
        g.drawString(mc.font, text, tx, 0, a8 | 0xFFFFFF, false);
        g.pose().popPose();
    }

    /**
     * A bar of big pixels: a cold black frame with its corners cut, a dark track, a warm white fill whose end eases
     * through the greys into the track.
     */
    private static void bar(GuiGraphics g, int x, int y, int width, float fill) {
        final int px = 2, height = 8;
        g.fill(x - px, y, x + width + px, y + height, 0xFF0C0D12);
        g.fill(x, y - px, x + width, y + height + px, 0xFF0C0D12);
        g.fill(x, y, x + width, y + height, 0xFF2B2E36);
        final int filled = Math.round(width * Mth.clamp(fill, 0.0F, 1.0F)) / px * px;
        if (filled <= 0) {
            return;
        }
        g.fill(x, y, x + filled, y + height, 0xFFF1EBDF);
        g.fill(x, y + height - px, x + filled, y + height, 0xFFC9C2B6); // its lower edge a step darker
        g.fill(x + filled - px, y, x + filled, y + height, 0xFFC9C2B6); // the end, easing into the track
        final int[] ease = {0xFF9A958D, 0xFF67666A};
        for (int k = 0; k < ease.length && filled + (k + 1) * px <= width; k++) {
            g.fill(x + filled + k * px, y, x + filled + (k + 1) * px, y + height, ease[k]);
        }
    }

    private static void bars(GuiGraphics g, int w) {
        final int width = 120, x = w / 2 - width / 2, y = 10;
        switch (trial) {
            case "COLLAPSE" -> bar(g, x, y, width, a);
            case "BREAKDOWN" -> {
                bar(g, x, y, width, a);
                for (int k = 0; k < (int) c; k++) { // the strikes still to be borne: warm white, the borne ones dark and cracked
                    final int dx = w / 2 - (int) c * 9 + k * 18 + 3, dy = y + 14;
                    final boolean left = k < (int) b;
                    g.fill(dx + 2, dy, dx + 10, dy + 12, 0xFF0C0D12);
                    g.fill(dx, dy + 2, dx + 12, dy + 10, 0xFF0C0D12);
                    g.fill(dx + 2, dy + 2, dx + 10, dy + 10, left ? 0xFFC9C2B6 : 0xFF2B2E36);
                    g.fill(dx + 2, dy + 2, dx + 8, dy + 8, left ? 0xFFF1EBDF : 0xFF2B2E36);
                    if (!left) {
                        g.fill(dx + 4, dy + 2, dx + 6, dy + 6, 0xFF67666A);
                        g.fill(dx + 6, dy + 6, dx + 8, dy + 10, 0xFF67666A);
                    }
                }
            }
            case "REACH", "DUEL", "HUSH", "FALL" -> { // (Hush: the bar is how loud they are; the Fall: how deep) // the control left (three marks, the lost ones dark and cracked); at the body, the heart's bar
                final int left = Math.round(a * 3);
                for (int k = 0; k < 3; k++) {
                    final int dx = w / 2 - 27 + k * 18 + 3, dy = y;
                    final boolean kept = k < left;
                    g.fill(dx + 2, dy, dx + 10, dy + 12, 0xFF0C0D12);
                    g.fill(dx, dy + 2, dx + 12, dy + 10, 0xFF0C0D12);
                    g.fill(dx + 2, dy + 2, dx + 10, dy + 10, kept ? 0xFFC9C2B6 : 0xFF2B2E36);
                    g.fill(dx + 2, dy + 2, dx + 8, dy + 8, kept ? 0xFFF1EBDF : 0xFF2B2E36);
                    if (!kept) {
                        g.fill(dx + 4, dy + 2, dx + 6, dy + 6, 0xFF67666A);
                        g.fill(dx + 6, dy + 6, dx + 8, dy + 10, 0xFF67666A);
                    }
                }
                if (b >= 0.0F) {
                    bar(g, x, y + 18, width, b);
                }
            }
            case "CORE" -> {
                final boolean open = c > 0.5F && (Minecraft.getInstance().level.getGameTime() / 4) % 2 == 0;
                bar(g, x, y, width, b); // the core
                if (open) {
                    g.fill(x - 8, y - 2, x - 4, y + 10, 0xFFF1EBDF);
                    g.fill(x + width + 4, y - 2, x + width + 8, y + 10, 0xFFF1EBDF);
                }
                bar(g, x + 20, y + 14, width - 40, a); // the player's stability
            }
            default -> {
            }
        }
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Layer {
        private Layer() {
        }

        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "trial_hud"),
                    (graphics, delta) -> draw(graphics, delta.getGameTimeDeltaPartialTick(true)));
        }
    }
}
