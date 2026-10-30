package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.serialization.Lifecycle;

import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.WorldData;

/**
 * No "experimental settings" warning. The mod adds a dimension of its own (the Inner Self), and any
 * dimension beyond the vanilla ones makes Minecraft call the world experimental and ask before creating
 * or loading it. Applies to every world.
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
    /** Creating a world: skip the warning. */
    @ModifyVariable(method = "confirmWorldCreation", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static boolean abyssaldepths$skipWarning(boolean skipWarnings) {
        return true;
    }

    /** Loading a world: its settings count as stable, so no backup warning. */
    @Redirect(method = "openWorldCheckWorldStemCompatibility",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/WorldData;worldGenSettingsLifecycle()Lcom/mojang/serialization/Lifecycle;"))
    private Lifecycle abyssaldepths$stable(WorldData data) {
        return Lifecycle.stable();
    }
}
