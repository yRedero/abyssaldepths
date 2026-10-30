package redero.abyssaldepths.artifact;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import redero.abyssaldepths.AbyssalDepths;

/**
 * An artifact: one of a kind, ranked by power ({@link ArtifactRank}), with a mastery from 0 to 100 % and,
 * for most of them, a charge (0 .. {@link #maxCharge()}), both kept on the item stack
 * ({@link AbyssalDepths#MASTERY}, {@link AbyssalDepths#CHARGE}). The tooltip (name in the rank's colour,
 * rank, charge, mastery; with Shift also the description) is built and framed on the client by
 * {@code client.ArtifactTooltip}. What an artifact does comes later; the base class only carries the data.
 */
public class ArtifactItem extends Item {
    private final ArtifactRank rank;
    private final ArtifactFrame frame;
    private final int maxCharge, regenTicks;

    /**
     * {@code maxCharge} 0: the artifact has no charge. {@code regenTicks}: one charge comes back every so
     * many ticks while the artifact is in an inventory (0: it never comes back by itself).
     */
    /**
     * Ticks an artifact rests after being used, so it cannot be spammed (the game shows it on the item and
     * refuses the use meanwhile); artifacts can ask for another rest.
     */
    protected int cooldownTicks() {
        return 100;
    }

    /** Starts the rest after a use. */
    protected void rest(net.minecraft.world.entity.player.Player player) {
        player.getCooldowns().addCooldown(this, cooldownTicks());
    }

    public ArtifactItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties.stacksTo(1));
        this.rank = rank;
        this.frame = frame;
        this.maxCharge = maxCharge;
        this.regenTicks = regenTicks;
    }

    public ArtifactRank rank() {
        return rank;
    }

    public ArtifactFrame frame() {
        return frame;
    }

    public int maxCharge() {
        return maxCharge;
    }

    public int regenTicks() {
        return regenTicks;
    }

    /**
     * Ticks until the next charge comes back, measured on the world's game time (so time in a chest counts
     * too: it is caught up the next time the artifact is in an inventory); -1 if full or the artifact does
     * not regenerate.
     */
    public long ticksToNextCharge(ItemStack stack, long gameTime) {
        final Long since = stack.get(AbyssalDepths.CHARGE_TIME.get());
        if (regenTicks <= 0 || charge(stack) >= maxCharge || since == null) {
            return -1;
        }
        return Math.max(0, regenTicks - (gameTime - since));
    }

    /** Regenerates the charge: one per {@link #regenTicks()} since the charge was last below its maximum. */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || regenTicks <= 0 || maxCharge <= 0) {
            return;
        }
        final long now = level.getGameTime();
        final int charge = charge(stack);
        final Long since = stack.get(AbyssalDepths.CHARGE_TIME.get());
        if (charge >= maxCharge) {
            if (since != null) {
                stack.remove(AbyssalDepths.CHARGE_TIME.get());
            }
        } else if (since == null) {
            stack.set(AbyssalDepths.CHARGE_TIME.get(), now);
        } else if (now - since >= regenTicks) {
            final long gained = (now - since) / regenTicks;
            setCharge(stack, (int) Math.min(maxCharge, charge + gained));
            stack.set(AbyssalDepths.CHARGE_TIME.get(), since + gained * regenTicks);
        }
    }

    /** 0 .. 100. */
    public static int mastery(ItemStack stack) {
        return Math.max(0, Math.min(100, stack.getOrDefault(AbyssalDepths.MASTERY.get(), 0)));
    }

    public static void setMastery(ItemStack stack, int percent) {
        stack.set(AbyssalDepths.MASTERY.get(), Math.max(0, Math.min(100, percent)));
    }

    /** 0 .. max charge; a new artifact starts full. */
    public int charge(ItemStack stack) {
        return Math.max(0, Math.min(maxCharge, stack.getOrDefault(AbyssalDepths.CHARGE.get(), maxCharge)));
    }

    public void setCharge(ItemStack stack, int charge) {
        stack.set(AbyssalDepths.CHARGE.get(), Math.max(0, Math.min(maxCharge, charge)));
    }

    /** The translation key of the epithet under the name, e.g. "the Stillness Below" (optional). */
    public String epithetKey() {
        return getDescriptionId() + ".epithet";
    }

    /** The translation key of the description shown with Shift: {@code item.<mod>.<name>.description}. */
    public String descriptionKey() {
        return getDescriptionId() + ".description";
    }

    @Override
    public Component getName(ItemStack stack) {
        return super.getName(stack).copy().withColor(rank.colour());
    }
}
