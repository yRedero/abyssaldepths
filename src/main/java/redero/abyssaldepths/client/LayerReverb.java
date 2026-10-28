package redero.abyssaldepths.client;

import java.lang.reflect.Field;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;

import com.mojang.blaze3d.audio.Channel;

import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import net.neoforged.neoforge.client.event.sound.SoundEvent.SoundSourceEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.ClientConfig;

/**
 * Echo in the halls of the thalan layer: every sound that starts while the camera is in the layer is
 * also sent through an OpenAL reverb, longer and stronger the bigger the hall around the camera
 * ({@link LayerAmbience#openness()}), muffled in a dune storm. Outside the layer sounds stay dry. Needs the OpenAL EFX extension
 * (OpenAL Soft, which Minecraft ships, has it); without it, or on any error, it switches itself off.
 * Runs on the sound thread, where the channel events are fired.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class LayerReverb {
    private static boolean failed;
    /** OpenAL names of the reverb; they belong to the sound engine that made them (0 = not made yet). */
    private static volatile int effect, slot;
    private static int errors;
    private static float appliedDecay = -1.0F, appliedStorm;
    private static Field sourceField;

    private LayerReverb() {
    }

    /**
     * The sound engine was (re)started (resource reload, F3+T, changed device): the old OpenAL context is
     * gone with the reverb in it. Using its names again would make OpenAL raise errors that Minecraft then
     * blames on its own calls, and sounds fail to play.
     */
    @SubscribeEvent
    public static void onEngineLoad(SoundEngineLoadEvent event) {
        effect = 0;
        slot = 0;
        appliedDecay = -1.0F;
    }

    @SubscribeEvent
    public static void onPlay(PlaySoundSourceEvent event) {
        attach(event);
    }

    @SubscribeEvent
    public static void onPlayStreaming(PlayStreamingSourceEvent event) {
        attach(event);
    }

    private static void attach(SoundSourceEvent event) {
        final float depth = LayerAmbience.depth();
        if (failed || depth <= 0.0F || !ClientConfig.reverb() || event.getSound().getSource() == SoundSource.MUSIC || event.getSound().getSource() == SoundSource.MASTER
                || event.getSound().isRelative()) {
            return; // dry: outside the layer, music, menus and sounds glued to the camera
        }
        try {
            AL10.alGetError(); // start clean: an error raised now is ours
            if ((slot == 0 || !EXTEfx.alIsAuxiliaryEffectSlot(slot) || !EXTEfx.alIsEffect(effect)) && !init()) {
                return;
            }
            update(depth, LayerAmbience.openness());
            final int source = sourceField.getInt(event.getChannel());
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, slot, 0, EXTEfx.AL_FILTER_NULL);
            // never leave an error behind for Minecraft's own checks; give up after a few
            if (AL10.alGetError() != AL10.AL_NO_ERROR && ++errors >= 5) {
                failed = true;
                AbyssalDepths.LOGGER.warn("cave reverb switched off after repeated OpenAL errors");
            }
        } catch (Exception | LinkageError e) {
            failed = true;
            AL10.alGetError();
            AbyssalDepths.LOGGER.warn("cave reverb switched off", e);
        }
    }

    private static boolean init() throws ReflectiveOperationException {
        final long device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext());
        if (device == 0L || !ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
            failed = true;
            AbyssalDepths.LOGGER.info("no OpenAL EFX: cave reverb off");
            return false;
        }
        if (sourceField == null) {
            sourceField = Channel.class.getDeclaredField("source");
            sourceField.setAccessible(true);
        }
        effect = EXTEfx.alGenEffects();
        EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
        slot = EXTEfx.alGenAuxiliaryEffectSlots();
        appliedDecay = -1.0F; // a new effect: set all of its parameters
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            failed = true;
            AbyssalDepths.LOGGER.warn("cave reverb could not be created: off");
            return false;
        }
        AbyssalDepths.LOGGER.info("cave reverb on");
        return true;
    }

    /**
     * Hall size 0 (narrow passage) .. 1 (huge hall): short and quiet .. long and strong echo. In a dune
     * storm the dust in the air swallows most of it: short, dull and quiet.
     */
    private static void update(float depth, float openness) {
        final float storm = DuneStormClient.strength();
        final float decay = (0.8F + 4.2F * openness) * (1.0F - 0.75F * storm);
        if (Math.abs(decay - appliedDecay) < 0.15F && Math.abs(storm - appliedStorm) < 0.1F) {
            return;
        }
        appliedDecay = decay;
        appliedStorm = storm;
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME, decay);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DENSITY, 1.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DIFFUSION, 0.8F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAIN, depth * (0.18F + 0.22F * openness) * (1.0F - 0.85F * storm));
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAINHF, 0.6F * (1.0F - 0.8F * storm));
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_HFRATIO, 0.7F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_REFLECTIONS_DELAY, 0.01F + 0.05F * openness);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_LATE_REVERB_DELAY, 0.02F + 0.06F * openness);
        EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect); // the slot copies the effect: reload it
    }
}
