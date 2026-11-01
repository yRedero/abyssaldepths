package redero.abyssaldepths.fracture;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Cleansing Shard: used, it takes one Corruption Scar ({@link Scars}) away and is gone; without a scar to take
 * it does nothing. Not made: only found (in the chests of ancient cities and strongholds).
 */
public class CleansingShardItem extends Item {
    public CleansingShardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (Scars.of(player) <= 0) {
            player.displayClientMessage(Component.translatable("item.abyssaldepths.cleansing_shard.clean"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (player instanceof ServerPlayer server) {
            Scars.set(server, Scars.of(server) - 1);
            stack.consume(1, player);
            final ServerLevel world = server.serverLevel();
            world.sendParticles(new DustParticleOptions(new org.joml.Vector3f(1, 1, 1), 1.2F), player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.4, 0.7, 0.4, 0.0);
            world.sendParticles(new DustParticleOptions(new org.joml.Vector3f(0, 0, 0), 1.2F), player.getX(), player.getY() + 1.0, player.getZ(), 20, 0.4, 0.7, 0.4, 0.0);
            world.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 1.6F);
            player.displayClientMessage(Component.translatable("item.abyssaldepths.cleansing_shard.used", Scars.of(server)), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.abyssaldepths.cleansing_shard.description").withStyle(ChatFormatting.GRAY));
    }
}
