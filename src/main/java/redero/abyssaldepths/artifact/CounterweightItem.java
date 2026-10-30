package redero.abyssaldepths.artifact;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Counterweight: right click switches it on and off. While on, the world pulls less at its holder: lower
 * gravity (higher, floatier jumps, slower falls) and less fall damage, both more with the mastery
 * ({@link #GRAVITY}, {@link #FALL_DAMAGE}), and a full charge lasts longer ({@link #SECONDS}).
 */
public class CounterweightItem extends ToggleArtifactItem {
    /** Share of gravity and of fall damage left, and seconds a full charge lasts, per milestone (0 .. 4). */
    static final float[] GRAVITY = {0.6F, 0.5F, 0.4F, 0.33F, 0.25F}, FALL_DAMAGE = {0.5F, 0.4F, 0.3F, 0.2F, 0.1F};
    static final int[] SECONDS = {20, 30, 40, 50, 60};
    private static final ResourceLocation LIGHT = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "counterweight");
    /** Server: players it lightens, with the tick last seen and the milestone. */
    private static final Map<UUID, long[]> ACTIVE = new HashMap<>();

    public CounterweightItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks, 40);
    }

    @Override
    protected void whileActive(ServerPlayer player, ItemStack stack) {
        final int m = milestone(stack);
        final long[] before = ACTIVE.put(player.getUUID(), new long[] {player.serverLevel().getGameTime(), m});
        if (before == null || before[1] != m) {
            set(player, Attributes.GRAVITY, GRAVITY[m] - 1.0);
            set(player, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_DAMAGE[m] - 1.0);
        }
    }

    private static void set(Player player, Holder<Attribute> attribute, double amount) {
        final AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(new AttributeModifier(LIGHT, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /** The event listener (not on the item class: its parent has listeners of its own). */
    @EventBusSubscriber(modid = AbyssalDepths.MODID)
    public static final class Events {
        private Events() {
        }

        /** Switched off, empty, or no longer carried: the weight comes back. */
        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post event) {
            CounterweightItem.onServerTick(event);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        final MinecraftServer server = event.getServer();
        final long now = server.overworld().getGameTime();
        ACTIVE.entrySet().removeIf(entry -> {
            if (entry.getValue()[0] >= now - 2) {
                return false;
            }
            final ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                for (Holder<Attribute> attribute : java.util.List.of(Attributes.GRAVITY, Attributes.FALL_DAMAGE_MULTIPLIER)) {
                    final AttributeInstance instance = player.getAttribute(attribute);
                    if (instance != null) {
                        instance.removeModifier(LIGHT);
                    }
                }
            }
            return true;
        });
    }

    @Override
    protected int drainTicks(ItemStack stack) {
        return SECONDS[milestone(stack)] * 20 / Math.max(1, maxCharge());
    }

    @Override
    public Component powerLine(ItemStack stack) {
        final int m = milestone(stack);
        return Component.translatable("item.abyssaldepths.counterweight.power", Math.round(GRAVITY[m] * 100), Math.round(FALL_DAMAGE[m] * 100), SECONDS[m]);
    }

    @Override
    protected String noChargeKey() {
        return "abyssaldepths.counterweight.no_charge";
    }

    @Override
    protected void playSwitch(Level level, Player player, boolean on) {
        playSwitch(level, player, on ? SoundEvents.BEACON_ACTIVATE : SoundEvents.BEACON_DEACTIVATE, on ? 1.6F : 1.2F);
    }
}
