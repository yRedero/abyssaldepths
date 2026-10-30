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

    private static final ModConfigSpec.EnumValue<ShadeVanishStyle> SHADE_VANISH = BUILDER
            .comment("How a player stepping into the shadow (Parallax) vanishes for those who stay, and comes back:",
                    "PIXELS: falls apart into black and purple pixels; ASH: darkens and rises away as ash;",
                    "SLIDE: slides sideways out of the world, thinning to a line; SHADOW: fades, leaving a shadow",
                    "on the ground for a moment; NONE: just gone.")
            .translation("abyssaldepths.config.shade_vanish")
            .defineEnum("shadeVanish", ShadeVanishStyle.PIXELS);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** How a shade vanishes for those outside the shadow. */
    public enum ShadeVanishStyle { PIXELS, ASH, SLIDE, SHADOW, NONE }

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
        return SPEC.isLoaded() ? SHADE_VANISH.get() : ShadeVanishStyle.PIXELS;
    }

    public static int thermalScanChunks() {
        return SPEC.isLoaded() ? THERMAL_SCAN_CHUNKS.get() : 2;
    }
}
