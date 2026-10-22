package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.world.level.levelgen.structure.structures.MineshaftStructure;
import redero.abyssaldepths.worldgen.TunnelLayer;

/**
 * Normal mineshafts are moved to a random height between the world bottom and sea level. With the
 * world reaching down to y=-128 they could end up in the thalan layer, so they get y=-64 as bottom.
 */
@Mixin(MineshaftStructure.class)
public abstract class MineshaftStructureMixin {
    @ModifyArg(method = "generatePiecesAndAdjust", index = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/pieces/StructurePiecesBuilder;moveBelowSeaLevel(IILnet/minecraft/util/RandomSource;I)I"))
    private int abyssaldepths$keepAboveThalan(int minY) {
        return Math.max(minY, TunnelLayer.LAYER_TOP_Y + 1);
    }
}
