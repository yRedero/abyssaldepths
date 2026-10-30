package redero.abyssaldepths.artifact;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
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
 * An artifact that right click switches on and off (Interlude, Parallax). While on it uses up one charge
 * every {@link #drainTicks} and calls {@link #whileActive} every tick; when empty, or thrown away, it
 * switches off. While off, the charge comes back. It glows like an enchanted item while on.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public abstract class ToggleArtifactItem extends ArtifactItem {
    private final int drainTicks;

    protected ToggleArtifactItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks, int drainTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
        this.drainTicks = drainTicks;
    }

    /** Every tick while on, on the server, for the player carrying it. */
    protected abstract void whileActive(ServerPlayer player);

    /** The message when it is used without charge. */
    protected abstract String noChargeKey();

    protected abstract void playSwitch(Level level, Player player, boolean on);

    protected static void playSwitch(Level level, Player player, SoundEvent sound, float pitch) {
        level.playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 1.0F, pitch);
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
            player.displayClientMessage(Component.translatable(noChargeKey()), true);
            return InteractionResultHolder.fail(stack);
        }
        if (!level.isClientSide) {
            final boolean on = !active(stack);
            setActive(stack, on);
            playSwitch(level, player, on);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** While on: drains the charge and does its work; while off: the charge comes back. */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!active(stack)) {
            super.inventoryTick(stack, level, entity, slot, selected);
            return;
        }
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) {
            return;
        }
        if (level.getGameTime() % drainTicks == 0) {
            setCharge(stack, charge(stack) - 1);
        }
        if (charge(stack) <= 0) {
            setActive(stack, false);
            playSwitch(level, player, false);
            return;
        }
        stack.remove(AbyssalDepths.CHARGE_TIME.get()); // no regeneration while it runs
        whileActive(player);
    }

    /** Thrown away: it stops. */
    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        final ItemStack stack = event.getEntity().getItem();
        if (stack.getItem() instanceof ToggleArtifactItem && active(stack)) {
            setActive(stack, false);
        }
    }
}
