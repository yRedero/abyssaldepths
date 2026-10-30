package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import redero.abyssaldepths.artifact.reprise.RepriseRecorder;

/**
 * Reprise: every block change in a loaded chunk passes here before it happens, whoever makes it (players,
 * explosions, fluids, pistons, growth), so the old state can be written down. Does nothing unless Reprise
 * is recording.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void abyssaldepths$beforeChange(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> cir) {
        if (RepriseRecorder.recording) {
            RepriseRecorder.blockChanging((LevelChunk) (Object) this, pos, state);
        }
    }
}
