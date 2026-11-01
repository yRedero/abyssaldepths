package redero.abyssaldepths.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.fracture.FractureTrials;

/**
 * The corruption reaching for the body, before it takes it: from half way on, now and then the player's control
 * slips, more often the further it has come. The view twitches, a step stutters; from two thirds the held slot
 * shifts by itself; from four fifths the body takes a few steps of its own, turning. The scarred feel a twitch now
 * and then even with no corruption in them. In a trial the same, by the control lost there.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class FractureControl {
    private static int stutter, walk;
    private static float drift;

    private FractureControl() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc.player;
        if (stutter > 0) {
            stutter--;
        }
        if (walk > 0) {
            walk--;
            if (player != null) {
                player.setYRot(player.getYRot() + drift); // turning as it walks
            }
        }
        if (player == null || mc.level == null || mc.isPaused() || !player.isAlive() || !FractureScreen.clear()) {
            return;
        }
        final float value = FractureScreen.corruptionLevel();
        final RandomSource random = mc.level.random;
        final float strength = value < 0.5F ? 0.0F : (value - 0.5F) / 0.5F;
        final float chance = (strength > 0 ? 0.004F + 0.03F * strength : 0.0F) + 0.0006F * TrialHud.scarCount();
        if (random.nextFloat() >= chance || walk > 0) {
            return;
        }
        final int roll = random.nextInt(strength <= 0 ? 1 : value >= 0.8F ? 4 : value >= 0.65F ? 3 : 2);
        switch (roll) {
            case 0 -> { // the view twitches
                player.setYRot(player.getYRot() + (random.nextFloat() - 0.5F) * 14.0F);
                player.setXRot(net.minecraft.util.Mth.clamp(player.getXRot() + (random.nextFloat() - 0.5F) * 8.0F, -90.0F, 90.0F));
            }
            case 1 -> stutter = 4 + random.nextInt(4); // a step stutters
            case 2 -> player.getInventory().selected = random.nextInt(9); // the hand wanders to another slot
            default -> { // it walks a few steps of its own
                walk = 14 + random.nextInt(12);
                drift = (random.nextFloat() - 0.5F) * 4.0F;
            }
        }
    }

    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent event) {
        final Input input = event.getInput();
        if (walk > 0) {
            input.forwardImpulse = 1.0F;
            input.leftImpulse = 0.0F;
            input.up = true;
            input.down = false;
        } else if (stutter > 0) {
            input.forwardImpulse = 0.0F;
            input.leftImpulse = 0.0F;
            input.up = input.down = input.left = input.right = false;
            input.jumping = false;
        }
    }
}
