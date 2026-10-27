package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import redero.abyssaldepths.worldgen.VanillaBootstrap;

/** Marks the build of the built-in registries, see {@link VanillaBootstrap}. */
@Mixin(VanillaRegistries.class)
public abstract class VanillaRegistriesMixin {
    @Inject(method = "createLookup", at = @At("HEAD"))
    private static void abyssaldepths$enter(CallbackInfoReturnable<HolderLookup.Provider> cir) {
        VanillaBootstrap.enter();
    }

    @Inject(method = "createLookup", at = @At("RETURN"))
    private static void abyssaldepths$exit(CallbackInfoReturnable<HolderLookup.Provider> cir) {
        VanillaBootstrap.exit();
    }
}
