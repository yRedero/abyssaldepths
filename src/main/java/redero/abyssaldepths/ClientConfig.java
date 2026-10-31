package redero.abyssaldepths;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client settings (config/abyssaldepths-client.toml, or the mod's config screen): mostly the look of the
 * thalan layer and the cost of its effects, for anyone whose game struggles with them.
 */
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.DoubleValue FOG_DISTANCE = BUILDER
            .comment("How far you can see through the fog of the thalan layer, relative to the default (1.0).")
            .translation("abyssaldepths.config.fog_distance")
            .defineInRange("fogDistance", 1.0, 0.5, 3.0);
    private static final ModConfigSpec.DoubleValue PARTICLES = BUILDER
            .comment("Amount of ambient particles in the layer: dust, drips, gusts, steam, glowing motes and dune storms.",
                    "0 turns them off, 1 is the default, 2 doubles them.")
            .translation("abyssaldepths.config.particles")
            .defineInRange("particles", 1.0, 0.0, 2.0);
    private static final ModConfigSpec.BooleanValue DUNE_STORMS = BUILDER
            .comment("Show the storms of the silt dunes (dust and close fog). Off: the storm still happens, you just don't see it.")
            .translation("abyssaldepths.config.dune_storms")
            .define("duneStorms", true);
    private static final ModConfigSpec.BooleanValue HEAT_VIGNETTE = BUILDER
            .comment("Amber glow at the screen edges near heat sources in the thermal reaches.")
            .translation("abyssaldepths.config.heat_vignette")
            .define("heatVignette", true);
    private static final ModConfigSpec.BooleanValue REVERB = BUILDER
            .comment("Echo of sounds in the halls of the layer (OpenAL EFX). Turn off if sounds misbehave.")
            .translation("abyssaldepths.config.reverb")
            .define("reverb", true);
    private static final ModConfigSpec.BooleanValue AMBIENT_SOUNDS = BUILDER
            .comment("Sounds from somewhere in the halls: cracking shale, rockfall, deep groans, distant creatures.")
            .translation("abyssaldepths.config.ambient_sounds")
            .define("ambientSounds", true);
    private static final ModConfigSpec.BooleanValue SILENCE_MUSIC = BUILDER
            .comment("No game music while in the thalan layer.")
            .translation("abyssaldepths.config.silence_music")
            .define("silenceMusic", true);
    private static final ModConfigSpec.IntValue THERMAL_SCAN_CHUNKS = BUILDER
            .comment("Radius in chunks searched for vents, smouldering rock and warm pools (steam and heat). Lower is cheaper.")
            .translation("abyssaldepths.config.thermal_scan_chunks")
            .defineInRange("thermalScanChunks", 2, 1, 4);

    private static final ModConfigSpec.DoubleValue ARTIFACT_TITLE_SCALE = BUILDER
            .comment("Size of an artifact's name at the top of its tooltip, relative to normal text.")
            .translation("abyssaldepths.config.artifact_title_scale")
            .defineInRange("artifactTitleScale", 1.25, 1.0, 1.5);
    private static final ModConfigSpec.BooleanValue ARTIFACT_TITLE_SHIMMER = BUILDER
            .comment("A shimmer sweeping through the name of an artifact in its tooltip.")
            .translation("abyssaldepths.config.artifact_title_shimmer")
            .define("artifactTitleShimmer", true);

    private static final ModConfigSpec.BooleanValue AFTERIMAGES = BUILDER
            .comment("Afterimages of players using Interlude.")
            .translation("abyssaldepths.config.afterimages")
            .define("afterimages", true);
    private static final ModConfigSpec.EnumValue<InterludeOverlay> INTERLUDE_OVERLAY = BUILDER
            .comment("What its holder sees while Interlude runs: BOTH (a violet tint and a ragged pixel frame), TINT, BORDER, NONE.")
            .translation("abyssaldepths.config.interlude_overlay")
            .defineEnum("interludeOverlay", InterludeOverlay.BOTH);
    private static final ModConfigSpec.BooleanValue INTERLUDE_PARTICLES = BUILDER
            .comment("Particles in an Interlude field slow down too.")
            .translation("abyssaldepths.config.interlude_particles")
            .define("interludeParticles", true);
    private static final ModConfigSpec.IntValue AFTERIMAGE_INTERVAL = BUILDER
            .comment("How often an afterimage is left behind, in milliseconds.")
            .translation("abyssaldepths.config.afterimage_interval")
            .defineInRange("afterimageInterval", 70, 20, 1000);
    private static final ModConfigSpec.IntValue AFTERIMAGE_FADE = BUILDER
            .comment("How long an afterimage takes to fade out, in milliseconds.")
            .translation("abyssaldepths.config.afterimage_fade")
            .defineInRange("afterimageFade", 250, 50, 2000);

    private static final ModConfigSpec.IntValue PARALLAX_TRANSITION = BUILDER
            .comment("How long Parallax's crossing into the shadow (and back) takes, in milliseconds.")
            .translation("abyssaldepths.config.parallax_transition")
            .defineInRange("parallaxTransition", 1600, 200, 6000);

    private static final ModConfigSpec.EnumValue<RepriseWait> REPRISE_WAIT = BUILDER
            .comment("What turns in the middle while Reprise is still rewinding: CIRCLE, SQUARE (a snake running",
                    "round a square), CLOCK (sweeping backwards), CLOCK_TICKING (jumping back mark by mark).")
            .translation("abyssaldepths.config.reprise_wait")
            .defineEnum("repriseWait", RepriseWait.SQUARE);
    private static final ModConfigSpec.IntValue REPRISE_SNAKE = BUILDER
            .comment("How many pixels long the snake of Reprise's SQUARE waiting sign is (the square has 24 round).")
            .translation("abyssaldepths.config.reprise_snake")
            .defineInRange("repriseSnakeLength", 8, 2, 23);
    private static final ModConfigSpec.EnumValue<WaypointMark> WAYPOINT_MARK = BUILDER
            .comment("How Inner Haven's remembered places are marked on the ground: SIGIL (turning rings), BEACON (a column of",
                    "rising pixels), RUNES (a ring of glyphs lighting up in turn), RIFT (a dark tear with pixels spiralling in), HALO (a",
                    "ring floating above the ground, bobbing), SPIRAL (arms of pixels turning out from the middle), EMBERS (a small",
                    "glow with embers drifting up), CROWN (a ring of pixel spikes rising and falling in a wave).")
            .translation("abyssaldepths.config.waypoint_mark")
            .defineEnum("waypointMark", WaypointMark.SIGIL);
    private static final ModConfigSpec.EnumValue<UndertowBurst> UNDERTOW_BURST = BUILDER
            .comment("Undertow's burst: VOID (a black ball swelling and falling back), PIXELS (a pixel ball, a shockwave, debris),",
                    "SHATTER (big shards tumbling out), RINGS (shockwaves one after another), STARBURST (rays of pixels).")
            .translation("abyssaldepths.config.undertow_burst")
            .defineEnum("undertowBurst", UndertowBurst.VOID);
    private static final ModConfigSpec.EnumValue<AegisLook> AEGIS_LOOK = BUILDER
            .comment("Aegis's force field: PIXELS (a shell of square pixels, thick at the rim, flickering), RINGS (rings of pixels",
                    "stacked up the sphere, a bright band running up them), RUNES (glyphs circling at two heights over a ring on the",
                    "ground), SHARDS (pixel shards drifting round on the sphere), BUBBLE (a smooth sphere with a fine grid).")
            .translation("abyssaldepths.config.aegis_look")
            .defineEnum("aegisLook", AegisLook.PIXELS);
    private static final ModConfigSpec.EnumValue<AegisOverlay> AEGIS_OVERLAY = BUILDER
            .comment("Aegis's screen effect for its holder: FRAME (a thin ragged frame of blue pixels like Interlude's, leaping far in",
                    "by a hit's speed and by how close and hard anything in the field presses), HEX (a honeycomb of pixel hexagons along the screen's edges, a light running",
                    "round them, faster under pressure, flashing on the side of a hit), RIPPLES (nothing while quiet, pixel ripples running",
                    "in from the side of each hit), NONE.")
            .translation("abyssaldepths.config.aegis_overlay")
            .defineEnum("aegisOverlay", AegisOverlay.FRAME);
    private static final ModConfigSpec.EnumValue<ConvergenceLook> CONVERGENCE_LOOK = BUILDER
            .comment("How the blocks round a player phasing with Convergence show inside a wall: PIXEL_EDGES (their edges dotted",
                    "with pixels), OUTLINE (thin lines), DITHER (a faint checker of pixels in their colour), CORNERS (pixel brackets",
                    "at their corners), NONE (not at all).")
            .translation("abyssaldepths.config.convergence_look")
            .defineEnum("convergenceLook", ConvergenceLook.PIXEL_EDGES);
    private static final ModConfigSpec.EnumValue<ConvergenceLayerFit> CONVERGENCE_LAYER = BUILDER
            .comment("How a player phasing with Convergence is tinted: SKIN (their skin's own pixels tinted, the band running down",
                    "them), OVER (a see-through layer a little out from the skin, like a second skin over it).")
            .translation("abyssaldepths.config.convergence_layer")
            .defineEnum("convergenceLayer", ConvergenceLayerFit.SKIN);
    private static final ModConfigSpec.EnumValue<ConvergenceApply> CONVERGENCE_APPLY = BUILDER
            .comment("How the layer on a player phasing with Convergence comes on and goes: DISSOLVE (pixel by pixel at random, like",
                    "Parallax's dissolve, each flaring light as it comes), SWEEP (down from the head), RISE (up from the feet), GLITCH",
                    "(in flickering blocks), NONE (at once).")
            .translation("abyssaldepths.config.convergence_apply")
            .defineEnum("convergenceApply", ConvergenceApply.DISSOLVE);
    private static final ModConfigSpec.EnumValue<RepulsionWave> REPULSION_WAVE = BUILDER
            .comment("Repulsion's pressure wave: SHOCK (three thin rings one after another), RING (a ring of pixels running out over",
                    "the ground), DOME (a dome of pixels swelling), BURST (rays of pixels shooting out), GUST (the game's",
                    "own gusts and clouds).")
            .translation("abyssaldepths.config.repulsion_wave")
            .defineEnum("repulsionWave", RepulsionWave.SHOCK);
    private static final ModConfigSpec.IntValue PARALLAX_SPOTS = BUILDER
            .comment("How many black spots open on the screen when crossing into the shadow (Parallax).")
            .translation("abyssaldepths.config.parallax_spots")
            .defineInRange("parallaxSpots", 33, 1, 300);
    private static final ModConfigSpec.EnumValue<ShadeLook> SHADE_LOOK = BUILDER
            .comment("How shades (players in Parallax's shadow) look to each other:",
                    "CONTRAST: harsh contrast, the shading leaning purple; PRINT: three purples, like a print;",
                    "SILHOUETTE: near black with glowing eyes; INVERTED: the colours turned, purple; NONE: their skin.")
            .translation("abyssaldepths.config.shade_look")
            .defineEnum("shadeLook", ShadeLook.SILHOUETTE);
    private static final ModConfigSpec.BooleanValue SHADE_FULL_COLOUR = BUILDER
            .comment("Shades keep the full colours of their look in the shadow (drawn over its drained picture);",
                    "off: they are drained and darkened with the rest of the shadow.")
            .translation("abyssaldepths.config.shade_full_colour")
            .define("shadeFullColour", false);
    private static final ModConfigSpec.EnumValue<ShadeVanishStyle> SHADE_VANISH = BUILDER
            .comment("How a player stepping into the shadow (Parallax) vanishes for those who stay, and comes back:",
                    "PIXELS: falls apart into black and purple pixels; ASH: darkens and rises away as ash;",
                    "SLIDE: slides sideways out of the world, thinning to a line; SHADOW: fades, leaving a shadow",
                    "on the ground for a moment; DISINTEGRATE: crumbles away, pixel by pixel of their skin;",
                    "DISSOLVE: the pixels of their skin go out one by one;",
                    "NONE: just gone. Also: /abyssaldepths vanish style <style>, preview: /abyssaldepths vanish play.")
            .translation("abyssaldepths.config.shade_vanish")
            .defineEnum("shadeVanish", ShadeVanishStyle.DISSOLVE);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Interlude's screen effect. */
    public enum InterludeOverlay { BOTH, TINT, BORDER, NONE }

    /** The marks of Inner Haven's remembered places. */
    public enum WaypointMark { SIGIL, BEACON, RUNES, RIFT, HALO, SPIRAL, EMBERS, CROWN }

    /** Undertow's burst. */
    public enum UndertowBurst { PIXELS, SHATTER, RINGS, STARBURST, VOID }

    public enum AegisLook { PIXELS, RINGS, RUNES, SHARDS, BUBBLE }

    public enum AegisOverlay { FRAME, HEX, RIPPLES, NONE }

    public enum ConvergenceLook { PIXEL_EDGES, OUTLINE, DITHER, CORNERS, NONE }

    public enum RepulsionWave { SHOCK, RING, DOME, BURST, GUST }

    public enum ConvergenceApply { DISSOLVE, SWEEP, RISE, GLITCH, NONE }

    public enum ConvergenceLayerFit { SKIN, OVER }

    /** The waiting sign of Reprise. */
    public enum RepriseWait { CIRCLE, SQUARE, CLOCK, CLOCK_TICKING }

    /** How shades look in the shadow. */
    public enum ShadeLook { CONTRAST, PRINT, SILHOUETTE, INVERTED, NONE }

    /** How a shade vanishes for those outside the shadow. */
    public enum ShadeVanishStyle { PIXELS, ASH, SLIDE, SHADOW, DISINTEGRATE, DISSOLVE, NONE }

    private ClientConfig() {
    }

    public static float fogDistance() {
        return SPEC.isLoaded() ? FOG_DISTANCE.get().floatValue() : 1.0F;
    }

    public static float particles() {
        return SPEC.isLoaded() ? PARTICLES.get().floatValue() : 1.0F;
    }

    public static boolean duneStorms() {
        return !SPEC.isLoaded() || DUNE_STORMS.get();
    }

    public static boolean heatVignette() {
        return !SPEC.isLoaded() || HEAT_VIGNETTE.get();
    }

    public static boolean reverb() {
        return !SPEC.isLoaded() || REVERB.get();
    }

    public static boolean ambientSounds() {
        return !SPEC.isLoaded() || AMBIENT_SOUNDS.get();
    }

    public static boolean silenceMusic() {
        return !SPEC.isLoaded() || SILENCE_MUSIC.get();
    }

    public static float artifactTitleScale() {
        return SPEC.isLoaded() ? ARTIFACT_TITLE_SCALE.get().floatValue() : 1.25F;
    }

    public static boolean artifactTitleShimmer() {
        return !SPEC.isLoaded() || ARTIFACT_TITLE_SHIMMER.get();
    }

    public static boolean afterimages() {
        return !SPEC.isLoaded() || AFTERIMAGES.get();
    }

    public static InterludeOverlay interludeOverlay() {
        return SPEC.isLoaded() ? INTERLUDE_OVERLAY.get() : InterludeOverlay.BOTH;
    }

    public static boolean interludeParticles() {
        return !SPEC.isLoaded() || INTERLUDE_PARTICLES.get();
    }

    public static long afterimageIntervalMillis() {
        return SPEC.isLoaded() ? AFTERIMAGE_INTERVAL.get() : 70;
    }

    public static long afterimageFadeMillis() {
        return SPEC.isLoaded() ? AFTERIMAGE_FADE.get() : 250;
    }

    public static int parallaxTransitionMillis() {
        return SPEC.isLoaded() ? PARALLAX_TRANSITION.get() : 1600;
    }

    public static ShadeVanishStyle shadeVanish() {
        return SPEC.isLoaded() ? SHADE_VANISH.get() : ShadeVanishStyle.DISSOLVE;
    }

    /** Sets the vanishing style and saves it (the command). */
    public static void setShadeVanish(ShadeVanishStyle style) {
        if (SPEC.isLoaded()) {
            SHADE_VANISH.set(style);
            SHADE_VANISH.save();
        }
    }

    public static ShadeLook shadeLook() {
        return SPEC.isLoaded() ? SHADE_LOOK.get() : ShadeLook.SILHOUETTE;
    }

    /** Sets the look of shades and saves it (the command). */
    public static void setShadeLook(ShadeLook look) {
        if (SPEC.isLoaded()) {
            SHADE_LOOK.set(look);
            SHADE_LOOK.save();
        }
    }

    public static RepriseWait repriseWait() {
        return SPEC.isLoaded() ? REPRISE_WAIT.get() : RepriseWait.SQUARE;
    }

    public static int repriseSnakeLength() {
        return SPEC.isLoaded() ? REPRISE_SNAKE.get() : 8;
    }

    public static boolean shadeFullColour() {
        return SPEC.isLoaded() && SHADE_FULL_COLOUR.get();
    }

    public static WaypointMark waypointMark() {
        return SPEC.isLoaded() ? WAYPOINT_MARK.get() : WaypointMark.SIGIL;
    }

    public static UndertowBurst undertowBurst() {
        return SPEC.isLoaded() ? UNDERTOW_BURST.get() : UndertowBurst.VOID;
    }

    public static AegisLook aegisLook() {
        return SPEC.isLoaded() ? AEGIS_LOOK.get() : AegisLook.PIXELS;
    }

    public static AegisOverlay aegisOverlay() {
        return SPEC.isLoaded() ? AEGIS_OVERLAY.get() : AegisOverlay.FRAME;
    }

    public static ConvergenceLook convergenceLook() {
        return SPEC.isLoaded() ? CONVERGENCE_LOOK.get() : ConvergenceLook.PIXEL_EDGES;
    }

    public static ConvergenceLayerFit convergenceLayer() {
        return SPEC.isLoaded() ? CONVERGENCE_LAYER.get() : ConvergenceLayerFit.SKIN;
    }

    public static ConvergenceApply convergenceApply() {
        return SPEC.isLoaded() ? CONVERGENCE_APPLY.get() : ConvergenceApply.DISSOLVE;
    }

    public static RepulsionWave repulsionWave() {
        return SPEC.isLoaded() ? REPULSION_WAVE.get() : RepulsionWave.SHOCK;
    }

    /** Sets a style option by its config name (the style command) and saves it; false if unknown. */
    public static boolean setStyle(String option, String value) {
        if (!SPEC.isLoaded()) {
            return false;
        }
        try {
            switch (option) {
                case "mark" -> WAYPOINT_MARK.set(WaypointMark.valueOf(value));
                case "burst" -> UNDERTOW_BURST.set(UndertowBurst.valueOf(value));
                case "aegis" -> AEGIS_LOOK.set(AegisLook.valueOf(value));
                case "aegis_overlay" -> AEGIS_OVERLAY.set(AegisOverlay.valueOf(value));
                case "convergence" -> CONVERGENCE_LOOK.set(ConvergenceLook.valueOf(value));
                case "repulsion" -> REPULSION_WAVE.set(RepulsionWave.valueOf(value));
                case "convergence_apply" -> CONVERGENCE_APPLY.set(ConvergenceApply.valueOf(value));
                case "convergence_layer" -> CONVERGENCE_LAYER.set(ConvergenceLayerFit.valueOf(value));
                default -> {
                    return false;
                }
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            SPEC.save();
        } catch (RuntimeException e) { // the file busy (another game on the same folder): the choice holds until restart
            redero.abyssaldepths.AbyssalDepths.LOGGER.warn("Could not save the client config: {}", e.toString());
        }
        return true;
    }

    public static int parallaxSpots() {
        return SPEC.isLoaded() ? PARALLAX_SPOTS.get() : 33;
    }

    public static int thermalScanChunks() {
        return SPEC.isLoaded() ? THERMAL_SCAN_CHUNKS.get() : 2;
    }
}
