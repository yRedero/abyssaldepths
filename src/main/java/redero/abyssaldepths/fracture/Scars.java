package redero.abyssaldepths.fracture;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Corruption Scars: what a failed trial leaves on a player, kept through death ({@link AbyssalDepths#SCARS}).
 * Each takes {@link #HEARTS} hearts of their most health, up to {@link #MOST} scars; the scarred see black and
 * white flickers now and then (their screen is told how many they have), and the corruption has an easier time
 * with them in its trials. A Cleansing Shard takes one away ({@link CleansingShardItem}).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class Scars {
    public static final int HEARTS = 2, MOST = 3;
    private static final ResourceLocation SCAR = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "corruption_scars");

    private Scars() {
    }

    public static int of(Player player) {
        return player.getData(AbyssalDepths.SCARS);
    }

    /** Sets the player's scars (0 .. {@link #MOST}); their most health and their screen follow. */
    public static void set(ServerPlayer player, int scars) {
        player.setData(AbyssalDepths.SCARS, Math.max(0, Math.min(MOST, scars)));
        apply(player);
    }

    /** Their most health as their scars have it, and their screen told. */
    static void apply(ServerPlayer player) {
        final AttributeInstance health = player.getAttribute(Attributes.MAX_HEALTH);
        final int scars = of(player);
        if (health != null) {
            if (scars == 0) {
                health.removeModifier(SCAR);
            } else {
                health.addOrUpdateTransientModifier(new AttributeModifier(SCAR, -2.0 * HEARTS * scars, AttributeModifier.Operation.ADD_VALUE));
            }
            if (player.getHealth() > player.getMaxHealth()) {
                player.setHealth(player.getMaxHealth());
            }
        }
        PacketDistributor.sendToPlayer(player, new EffectPayload("scars/" + scars, 0));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new EffectPayload("scarred/" + player.getId() + "/" + scars, 0)); // the marks on them
    }

    /** Someone comes into sight of a scarred player: they see the marks. */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer target && event.getEntity() instanceof ServerPlayer viewer) {
            PacketDistributor.sendToPlayer(viewer, new EffectPayload("scarred/" + target.getId() + "/" + of(target), 0));
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            apply(player);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            apply(player);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            apply(player);
        }
    }

    /** Now and then, the modifier checked (other mods may reset attributes). */
    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % 100 == 0 && of(player) > 0) {
            final AttributeInstance health = player.getAttribute(Attributes.MAX_HEALTH);
            if (health != null && health.getModifier(SCAR) == null) {
                apply(player);
            }
        }
    }
}
