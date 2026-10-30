package redero.abyssaldepths.artifact;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Parallax: right click switches it on and off. While it is on, everything around its holder runs at half
 * speed ({@link ParallaxField}), the holder leaves afterimages, and it uses up one charge every
 * {@link #DRAIN_TICKS}; when empty it switches off. While off, the charge comes back (one every 30 s).
 * It glows like an enchanted item while on.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public class ParallaxItem extends ArtifactItem {
    /** One charge every two seconds while on: 20 seconds from full. */
    private static final int DRAIN_TICKS = 40;

    public ParallaxItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
    }

    public static boolean active(ItemStack stack) {
        return stack.getOrDefault(AbyssalDepths.ARTIFACT_ACTIVE.get(), false);
    }

    private static void setActive(ItemStack stack, boolean on) {
        if (on) {
            stack.set(AbyssalDepths.ARTIFACT_ACTIVE.get(), true);
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        } else {
            stack.remove(AbyssalDepths.ARTIFACT_ACTIVE.get());
            stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (!active(stack) && charge(stack) < 1) {
            player.displayClientMessage(Component.translatable("abyssaldepths.parallax.no_charge"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (!level.isClientSide) {
            final boolean on = !active(stack);
            setActive(stack, on);
            level.playSound(null, player.blockPosition(), on ? SoundEvents.AMETHYST_BLOCK_RESONATE : SoundEvents.AMETHYST_BLOCK_CHIME,
                    SoundSource.PLAYERS, 1.0F, on ? 0.5F : 0.7F);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** While on: keeps the field alive and drains the charge; while off: the charge comes back. */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!active(stack)) {
            super.inventoryTick(stack, level, entity, slot, selected);
            return;
        }
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) {
            return;
        }
        if (level.getGameTime() % DRAIN_TICKS == 0) {
            setCharge(stack, charge(stack) - 1);
        }
        if (charge(stack) <= 0) {
            setActive(stack, false);
            level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 0.7F);
            return;
        }
        stack.remove(AbyssalDepths.CHARGE_TIME.get()); // no regeneration while it runs
        ParallaxField.mark(player);
    }

    /** Thrown away: it stops. */
    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        final ItemStack stack = event.getEntity().getItem();
        if (stack.getItem() instanceof ParallaxItem && active(stack)) {
            setActive(stack, false);
        }
    }
}
