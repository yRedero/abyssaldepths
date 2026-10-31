package redero.abyssaldepths.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Where a player would respawn (Recall), without using up a respawn anchor's charge (keepInventory true). */
@Mixin(ServerPlayer.class)
public interface ServerPlayerAccess {
    @Invoker("findRespawnAndUseSpawnBlock")
    static Optional<ServerPlayer.RespawnPosAngle> abyssaldepths$findRespawn(ServerLevel level, BlockPos pos, float angle, boolean forced, boolean keepInventory) {
        throw new AssertionError();
    }
}
