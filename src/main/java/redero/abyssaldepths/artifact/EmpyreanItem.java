package redero.abyssaldepths.artifact;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.entity.EmpyreanBeam;

/**
 * Empyrean: right click calls a column of light down from the sky where its holder looks (up to
 * {@link #REACH} blocks): a warning beam first, then it burns ({@link EmpyreanBeam}). Its damage grows with
 * the mastery ({@link #DAMAGE}, 40 to 200 in all); mastered, right clicking a creature itself locks the beam
 * onto it, and it follows them. Two charges, each back after 15 minutes, sooner with the mastery
 * ({@link #REGEN_MINUTES}).
 */
public class EmpyreanItem extends ArtifactItem {
    public static final double REACH = 64.0;
    /** Its whole damage, and minutes for a charge to come back, per milestone (0 .. 4). */
    static final float[] DAMAGE = {40, 80, 120, 160, 200};
    static final int[] REGEN_MINUTES = {15, 13, 11, 9, 7};
    /** The milestone from which it locks onto the creature right clicked. */
    static final int LOCK = 4;

    public EmpyreanItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
    }

    @Override
    public int regenTicks(ItemStack stack) {
        return REGEN_MINUTES[milestone(stack)] * 60 * 20;
    }

    @Override
    public Component powerLine(ItemStack stack) {
        final int m = milestone(stack);
        return Component.translatable(m >= LOCK ? "item.abyssaldepths.empyrean.power_lock" : "item.abyssaldepths.empyrean.power", Math.round(DAMAGE[m]),
                REGEN_MINUTES[m]);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (charge(stack) < 1) {
            player.displayClientMessage(Component.translatable("abyssaldepths.empyrean.no_charge"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (level instanceof ServerLevel server) {
            final int m = milestone(stack);
            final Vec3 eye = player.getEyePosition(), end = eye.add(player.getLookAngle().scale(REACH));
            final BlockHitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
            Entity locked = null;
            if (m >= LOCK) { // a creature right clicked, before any block: locked onto it
                final Vec3 reach = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
                final EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, player, eye, reach, new AABB(eye, reach).inflate(1.0),
                        e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && !Shades.apart(player, e), 0.3F);
                if (hit != null) {
                    locked = hit.getEntity();
                }
            }
            final Vec3 at;
            if (locked != null) {
                at = locked.position();
            } else if (block.getType() != HitResult.Type.MISS) {
                at = block.getLocation();
            } else { // into the air: where the look ends, down to the ground under it
                final BlockHitResult ground = level.clip(new ClipContext(end, end.subtract(0, REACH, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
                at = ground.getType() == HitResult.Type.MISS ? end : ground.getLocation();
            }
            final EmpyreanBeam beam = new EmpyreanBeam(AbyssalDepths.EMPYREAN_BEAM.get(), server);
            beam.moveTo(at.x, at.y, at.z);
            beam.start(player, DAMAGE[m], locked);
            server.addFreshEntity(beam);
            setCharge(stack, charge(stack) - 1);
            if (locked != null) {
                player.displayClientMessage(Component.translatable("abyssaldepths.empyrean.locked", locked.getDisplayName()), true);
            }
            rest(player);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
