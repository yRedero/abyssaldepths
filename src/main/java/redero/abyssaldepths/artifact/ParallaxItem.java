package redero.abyssaldepths.artifact;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Parallax: right click switches it on and off. While it is on, its holder is a shade in the shadow of
 * this world ({@link Shades}): there, unseen and untouchable, unable to touch anything but able to walk,
 * and to meet everyone else in the shadow. It uses up its charge (a full one lasts 60 s, up to 120 s with
 * the mastery milestones, {@link #SECONDS}); when empty it switches off. While off, the charge comes back
 * (one a minute). A shade sees nothing of the world's creatures and players (only other shades) until its
 * Parallax is at full mastery.
 */
public class ParallaxItem extends ToggleArtifactItem {
    public ParallaxItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks, 120);
    }

    /** Mastery from which a shade sees the creatures and players of the world. */
    public static final int FULL_SIGHT_MASTERY = 100;

    /** Whether the player carries an active Parallax at full mastery (so sees the world from the shadow). */
    public static boolean seesWorld(Player player) {
        final var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            final ItemStack stack = inventory.getItem(i);
            if (stack.getItem() instanceof ParallaxItem && active(stack) && mastery(stack) >= FULL_SIGHT_MASTERY) {
                return true;
            }
        }
        return false;
    }

    /** Seconds a full charge lasts, per milestone (0 .. 4). */
    static final int[] SECONDS = {60, 75, 90, 105, 120};

    @Override
    protected void whileActive(ServerPlayer player, ItemStack stack) {
        Shades.mark(player);
    }

    @Override
    protected int drainTicks(ItemStack stack) {
        return SECONDS[milestone(stack)] * 20 / Math.max(1, maxCharge());
    }

    @Override
    public net.minecraft.network.chat.Component powerLine(ItemStack stack) {
        return net.minecraft.network.chat.Component.translatable("item.abyssaldepths.parallax.power", SECONDS[milestone(stack)]);
    }

    @Override
    protected String noChargeKey() {
        return "abyssaldepths.parallax.no_charge";
    }

    @Override
    protected void playSwitch(Level level, Player player, boolean on) {
        playSwitch(level, player, SoundEvents.SOUL_ESCAPE.value(), on ? 0.5F : 1.2F); // a breath out going in, in coming back
    }
}
