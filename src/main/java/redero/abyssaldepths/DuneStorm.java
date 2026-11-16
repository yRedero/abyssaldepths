package redero.abyssaldepths;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Storms of the silt dunes: now and then (on average every 40 minutes) a storm rises for 2 to 4
 * minutes. It is one storm for the whole world, kept by the server and sent to every player (with its wind's
 * direction); only players in the dunes see it (dust everywhere, the fog closes in). Not saved: a restart ends it.
 * <p>
 * Out in it, it is dangerous: gusts shove players along the wind every few seconds (crouching braces them), and the
 * grinding silt wears down the armour of those it reaches unsheltered (nothing solid close upwind of them). The storm
 * crystals charge while it rages and draw its lightning ({@link StormCrystals}).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class DuneStorm {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int AVERAGE_GAP = 20 * 60 * 40;
    private static int remaining;
    /** The wind's direction (radians), the same for every player; a new one with every storm. */
    private static double wind;
    /** Per player: when their next gust comes. */
    private static final java.util.Map<java.util.UUID, Long> NEXT_GUST = new java.util.HashMap<>();

    private DuneStorm() {
    }

    /** Starts a storm of the given length, or ends the running one with 0. */
    public static void set(MinecraftServer server, int ticks) {
        if (remaining <= 0 && ticks > 0) {
            wind = RANDOM.nextDouble() * Math.PI * 2;
        }
        remaining = Math.max(0, ticks);
        PacketDistributor.sendToAllPlayers(new EffectPayload(message(), remaining));
    }

    public static int remaining() {
        return remaining;
    }

    private static String message() {
        return String.format(java.util.Locale.ROOT, "storm/%.4f", wind);
    }

    /** Whether a player stands out in the storm: in the dunes, down in the layer. */
    private static boolean outIn(ServerPlayer player) {
        return player.level() == player.server.overworld() && player.getY() < redero.abyssaldepths.worldgen.TunnelLayer.MAX_AIR_Y + 4
                && !player.isSpectator() && player.level().getBiome(player.blockPosition()).is(AbyssalDepths.SILT_DUNES);
    }

    /** Whether something solid stands close upwind of them (the silt does not reach them there). */
    private static boolean sheltered(ServerPlayer player) {
        final net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        final double ux = -Math.cos(wind), uz = -Math.sin(wind);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (double d = 0.8; d <= 4.0; d += 0.5) {
            for (double h : new double[] {0.0, -1.0}) {
                pos.set(eye.x + ux * d, eye.y + h, eye.z + uz * d);
                if (player.level().getBlockState(pos).isCollisionShapeFullBlock(player.level(), pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The storm's dangers for those out in it: gusts, and the silt grinding at their armour. */
    private static void dangers(net.minecraft.server.MinecraftServer server) {
        final long now = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!outIn(player)) {
                continue;
            }
            final long next = NEXT_GUST.computeIfAbsent(player.getUUID(), k -> now + 60 + RANDOM.nextInt(100));
            if (now >= next) { // a gust: shoved along the wind (less if crouching, braced)
                NEXT_GUST.put(player.getUUID(), now + 100 + RANDOM.nextInt(140));
                final double force = (player.isCrouching() ? 0.25 : 0.85) * (sheltered(player) ? 0.4 : 1.0);
                player.push(Math.cos(wind) * force, player.onGround() ? 0.18 : 0.05, Math.sin(wind) * force);
                player.hurtMarked = true;
                player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.WEATHER, 0.7F, 0.5F);
                player.serverLevel().sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, player.getX() - Math.cos(wind) * 1.5, player.getY() + 1.0,
                        player.getZ() - Math.sin(wind) * 1.5, 12, 0.6, 0.6, 0.6, 0.05);
            }
            if (now % 200 == 0 && !sheltered(player)) { // the silt grinding at their armour
                for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {
                        net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                        net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                    final net.minecraft.world.item.ItemStack armour = player.getItemBySlot(slot);
                    if (!armour.isEmpty() && armour.isDamageableItem()) {
                        armour.hurtAndBreak(1, player, slot);
                    }
                }
                player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.SAND_BREAK, SoundSource.WEATHER, 0.4F, 0.6F);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (remaining > 0) {
            remaining--;
            dangers(event.getServer());
        } else if (RANDOM.nextInt(AVERAGE_GAP) == 0) {
            set(event.getServer(), 2400 + RANDOM.nextInt(2400));
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (remaining > 0 && event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, new EffectPayload(message(), remaining));
        }
    }
}
