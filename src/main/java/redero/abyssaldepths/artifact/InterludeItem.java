package redero.abyssaldepths.artifact;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Interlude: right click switches it on and off. While it is on, everything around its holder runs at half
 * speed ({@link InterludeField}), the holder leaves afterimages, and it uses up one charge every two
 * seconds; when empty it switches off. While off, the charge comes back (one every 30 s).
 */
public class InterludeItem extends ToggleArtifactItem {
    public InterludeItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks, 40);
    }

    @Override
    protected void whileActive(ServerPlayer player) {
        InterludeField.mark(player);
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
