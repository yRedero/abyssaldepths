package redero.abyssaldepths.artifact;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Parallax: right click switches it on and off. While it is on, its holder is a shade in the shadow of
 * this world ({@link Shades}): there, unseen and untouchable, unable to touch anything but able to walk,
 * and to meet everyone else in the shadow. It uses up one charge every six seconds (a minute from full);
 * when empty it switches off. While off, the charge comes back (one a minute).
 */
public class ParallaxItem extends ToggleArtifactItem {
    public ParallaxItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks, 120);
    }

    @Override
    protected void whileActive(ServerPlayer player) {
        Shades.mark(player);
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
