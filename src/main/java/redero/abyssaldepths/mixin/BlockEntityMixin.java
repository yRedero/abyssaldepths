package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.block.entity.BlockEntity;
import redero.abyssaldepths.artifact.reprise.RepriseRecorder;

/**
 * Reprise: a block entity tells it changed (a chest's items, a furnace's progress). Only marks it; what it
 * held before is known from the last time it was written down. Does nothing unless Reprise is recording.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    @Inject(method = "setChanged()V", at = @At("HEAD"))
    private void abyssaldepths$changed(CallbackInfo ci) {
        if (RepriseRecorder.recording) {
            RepriseRecorder.blockEntityChanged((BlockEntity) (Object) this);
        }
    }
}
