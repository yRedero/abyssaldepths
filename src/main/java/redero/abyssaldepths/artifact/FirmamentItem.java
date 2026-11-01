package redero.abyssaldepths.artifact;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Firmament: right click switches it on and off; it works from anywhere in the inventory. While on, a block
 * of air under its holder's feet bears them ({@link Firmament}): they walk on it, jump to climb, sneak (or
 * switch it off) to fall. A charge lasts {@link #SECONDS}, longer with the mastery, and the blocks left
 * behind stay for {@link #TRAIL}; from {@link #SOFT_LANDING} on the fall after letting go does no harm, and
 * mastered it gives Speed II while on. Switching it on takes a charge at once, then one each {@link #SECONDS}
 * ({@link #chargesOnSwitch}); switching it off needs no rest. Five charges, one back a minute (not while on). The clients learn who has it on from {@code firmament/<entity id>/<milestone>} effects.
 */
public class FirmamentItem extends ToggleArtifactItem {
    /** Seconds a charge lasts and ticks a block left behind stays, per milestone (0 .. 4). */
    static final int[] SECONDS = {5, 10, 15, 20, 30}, TRAIL = {20, 30, 40, 50, 60};
    /** The milestone from which the fall after letting go is soft. */
    static final int SOFT_LANDING = 2;
    /** Client: other players with it on (until which tick), and its milestone. */
    private static final Int2LongOpenHashMap CLIENT = new Int2LongOpenHashMap();
    private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap CLIENT_MILESTONE = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();

    public FirmamentItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks, 100);
    }

    @Override
    public Component powerLine(ItemStack stack) {
        final int m = milestone(stack);
        return Component.translatable(m >= 4 ? "item.abyssaldepths.firmament.power_speed" : m >= SOFT_LANDING ? "item.abyssaldepths.firmament.power_soft"
                : "item.abyssaldepths.firmament.power", SECONDS[m], TRAIL[m] / 20.0F);
    }

    @Override
    protected int drainTicks(ItemStack stack) {
        return SECONDS[milestone(stack)] * 20;
    }

    @Override
    protected boolean chargesOnSwitch() {
        return true;
    }

    /** Switching off is free to do again at once; only switching on rests. */
    @Override
    protected boolean restsAfter(boolean on) {
        return on;
    }

    @Override
    protected String noChargeKey() {
        return "abyssaldepths.firmament.no_charge";
    }

    @Override
    protected void playSwitch(Level level, Player player, boolean on) {
        playSwitch(level, player, on ? SoundEvents.BEACON_ACTIVATE : SoundEvents.BEACON_DEACTIVATE, 1.8F);
        if (player instanceof ServerPlayer server) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(server, new EffectPayload("firmament/" + player.getId() + "/0", on ? 15 : 0));
        }
    }

    @Override
    protected void whileActive(ServerPlayer player, ItemStack stack) {
        final int m = milestone(stack);
        if (player.serverLevel().getGameTime() % 10 == 0) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new EffectPayload("firmament/" + player.getId() + "/" + m, 15));
        }
        if (m >= 4) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 30, 1, true, false, true));
        }
    }

    /** The milestone of the Firmament the player has on (0 .. 4), or -1 if none is on. */
    static int onMilestone(Player player) {
        if (player.level().isClientSide && !player.isLocalPlayer()) { // others' inventories are not known here
            return !CLIENT.isEmpty() && CLIENT.get(player.getId()) >= player.level().getGameTime() ? CLIENT_MILESTONE.get(player.getId()) : -1;
        }
        final Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            final ItemStack stack = inventory.getItem(i);
            if (stack.getItem() instanceof FirmamentItem && active(stack)) {
                return milestone(stack);
            }
        }
        return -1;
    }

    /** Client: another player has it on, at this milestone, for the next {@code ticks} ticks (0: no longer). */
    public static void clientOn(int entityId, int milestone, int ticks, long clientTick) {
        CLIENT.put(entityId, ticks > 0 ? clientTick + ticks : clientTick - 1);
        if (ticks > 0) {
            CLIENT_MILESTONE.put(entityId, milestone);
        }
    }
}
