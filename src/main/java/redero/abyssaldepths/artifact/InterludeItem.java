package redero.abyssaldepths.artifact;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Interlude: right click switches it on and off. While it is on, everything around its holder runs slower
 * ({@link InterludeField}), the holder leaves afterimages, and it uses up its charge; when empty it switches
 * off. While off, the charge comes back (one every 30 s). Its mastery milestones slow the field more and
 * make a full charge last longer ({@link #PACE}, {@link #SECONDS}).
 */
public class InterludeItem extends ToggleArtifactItem {
    public InterludeItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks, 40);
    }

    /** Pace of the field and seconds a full charge lasts, per milestone (0 .. 4). */
    static final float[] PACE = {0.7F, 0.58F, 0.45F, 0.33F, 0.2F};
    static final int[] SECONDS = {10, 18, 25, 32, 40};

    @Override
    protected void whileActive(ServerPlayer player, ItemStack stack) {
        InterludeField.mark(player, 1.0F - PACE[milestone(stack)]);
    }

    @Override
    protected int drainTicks(ItemStack stack) {
        return SECONDS[milestone(stack)] * 20 / Math.max(1, maxCharge());
    }

    @Override
    public net.minecraft.network.chat.Component powerLine(ItemStack stack) {
        final int m = milestone(stack);
        return net.minecraft.network.chat.Component.translatable("item.abyssaldepths.interlude.power", Math.round((1.0F - PACE[m]) * 100), SECONDS[m]);
    }

    @Override
    protected String noChargeKey() {
        return "abyssaldepths.interlude.no_charge";
    }

    @Override
    protected void playSwitch(Level level, Player player, boolean on) {
        playSwitch(level, player, on ? SoundEvents.AMETHYST_BLOCK_RESONATE : SoundEvents.AMETHYST_BLOCK_CHIME, on ? 0.5F : 0.7F);
    }
}
