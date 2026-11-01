package redero.abyssaldepths.artifact;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Artifacts belong to their players: the mastery of an artifact is the player's, one per kind of artifact
 * and kept for good ({@link AbyssalDepths#MASTERIES}, kept through death), and every copy a player carries
 * shows and uses it (it is written onto the copies, which is what the rest reads); the mastery a found copy
 * carries counts for nothing. And a player carries only one of each kind: a second one is not picked up,
 * and one that gets in otherwise (a chest, a trade) is dropped at their feet. Players in creative are left
 * alone by the second rule.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class ArtifactOwnership {
    private ArtifactOwnership() {
    }

    private static String key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** The player's mastery of this kind of artifact (0 .. 100). */
    public static int mastery(Player player, Item item) {
        return player.getData(AbyssalDepths.MASTERIES).getOrDefault(key(item), 0);
    }

    /** Sets the player's mastery of the stack's kind of artifact, and the stack's to match. */
    public static void setMastery(Player player, ItemStack stack, int percent) {
        final Map<String, Integer> masteries = new HashMap<>(player.getData(AbyssalDepths.MASTERIES));
        masteries.put(key(stack.getItem()), Mth.clamp(percent, 0, 100));
        player.setData(AbyssalDepths.MASTERIES, masteries);
        ArtifactItem.setMastery(stack, percent);
    }

    /** Twice a second: the player's mastery onto each artifact they carry; a second of a kind out at their feet. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 10 != 0) {
            return;
        }
        final Inventory inventory = player.getInventory();
        final Set<Item> seen = new HashSet<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            final ItemStack stack = inventory.getItem(i);
            if (!(stack.getItem() instanceof ArtifactItem)) {
                continue;
            }
            if (!seen.add(stack.getItem()) && !player.isCreative()) {
                inventory.setItem(i, ItemStack.EMPTY);
                final ItemEntity dropped = new ItemEntity(player.level(), player.getX(), player.getY() + 0.5, player.getZ(), stack);
                dropped.setPickUpDelay(40);
                player.level().addFreshEntity(dropped);
                continue;
            }
            final int mastery = mastery(player, stack.getItem());
            if (ArtifactItem.mastery(stack) != mastery) {
                ArtifactItem.setMastery(stack, mastery);
            }
        }
    }

    /** A second artifact of a kind the player carries is not picked up. */
    @SubscribeEvent
    public static void onPickup(ItemEntityPickupEvent.Pre event) {
        final ItemStack stack = event.getItemEntity().getItem();
        final Player player = event.getPlayer();
        if (stack.getItem() instanceof ArtifactItem && !player.isCreative() && player.getInventory().hasAnyMatching(s -> s.getItem() == stack.getItem())) {
            event.setCanPickup(TriState.FALSE);
        }
    }
}
