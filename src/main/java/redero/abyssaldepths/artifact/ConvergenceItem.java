package redero.abyssaldepths.artifact;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Convergence: right click switches it on and off. While on, its holder walks through blocks: they still
 * stand on the ground (the tops of blocks below their feet), sneak to sink through it, jump to climb
 * inside; nothing unbreakable lets them through (bedrock). No suffocation, and inside they see a block
 * round them ({@code client.ConvergenceView}), two at full mastery. Uses up its charge while on; switched
 * off (or empty) inside a wall, they are put in the nearest open space. The movement is in
 * {@code ConvergenceMixin} (both sides work it out alike, from the switched-on item in the inventory).
 */
public class ConvergenceItem extends ToggleArtifactItem {
    /** Seconds a full charge lasts, per milestone (0 .. 4). */
    static final int[] SECONDS = {20, 30, 40, 50, 60};
    /** The milestone from which they see two blocks deep. */
    static final int FAR_SIGHT = 4;
    /** Server: players phasing last tick (to put in the open when it ends inside a wall). */
    private static final Set<UUID> PHASING = new HashSet<>();

    public ConvergenceItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks, 40);
    }

    @Override
    protected int drainTicks(ItemStack stack) {
        return SECONDS[milestone(stack)] * 20 / Math.max(1, maxCharge());
    }

    @Override
    public Component powerLine(ItemStack stack) {
        final int m = milestone(stack);
        return Component.translatable("item.abyssaldepths.convergence.power", SECONDS[m], m >= FAR_SIGHT ? 2 : 1);
    }

    @Override
    protected String noChargeKey() {
        return "abyssaldepths.convergence.no_charge";
    }

    @Override
    protected void playSwitch(Level level, Player player, boolean on) {
        playSwitch(level, player, on ? SoundEvents.RESPAWN_ANCHOR_CHARGE : SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), on ? 1.5F : 1.3F);
    }

    @Override
    protected void whileActive(ServerPlayer player, ItemStack stack) {
    }

    /** The switched-on Convergence the player carries, or null. */
    private static ItemStack active(Player player) {
        final Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            final ItemStack stack = inventory.getItem(i);
            if (stack.getItem() instanceof ConvergenceItem && ToggleArtifactItem.active(stack)) {
                return stack;
            }
        }
        return null;
    }

    /** Whether the entity walks through blocks now. */
    public static boolean phasing(Entity entity) {
        return entity instanceof Player player && !player.isSpectator() && active(player) != null;
    }

    /** How many blocks deep the player sees inside (0: not phasing). */
    public static int sight(Player player) {
        final ItemStack stack = player.isSpectator() ? null : active(player);
        return stack == null ? 0 : milestone(stack) >= FAR_SIGHT ? 2 : 1;
    }

    /** Whether nothing gets through this block (bedrock, barriers, the inner self's walls). */
    public static boolean unbreakable(Level level, BlockPos pos, BlockState state) {
        return state.getDestroySpeed(level, pos) < 0;
    }

    /**
     * The movement of a phasing player: sideways through anything breakable; down onto the tops of blocks
     * below their feet (not while sneaking); up freely; unbreakable blocks stop them every way.
     */
    public static Vec3 collide(Entity entity, Vec3 move) {
        final Level level = entity.level();
        AABB box = entity.getBoundingBox();
        final AABB swept = box.expandTowards(move).inflate(1.0E-7);
        final boolean sink = entity.isShiftKeyDown();
        final List<VoxelShape> hard = new ArrayList<>(), floors = new ArrayList<>();
        final CollisionContext context = CollisionContext.of(entity);
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(swept.minX), Mth.floor(swept.minY) - 1, Mth.floor(swept.minZ),
                Mth.floor(swept.maxX), Mth.floor(swept.maxY), Mth.floor(swept.maxZ))) {
            final BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            final VoxelShape shape = state.getCollisionShape(level, pos, context);
            if (shape.isEmpty()) {
                continue;
            }
            final VoxelShape at = shape.move(pos.getX(), pos.getY(), pos.getZ());
            if (unbreakable(level, pos, state)) {
                hard.add(at);
            } else if (!sink && at.max(Direction.Axis.Y) <= box.minY + 1.0E-5) {
                floors.add(at);
            }
        }
        double dy = move.y;
        if (dy != 0.0) {
            final List<VoxelShape> below = dy < 0.0 ? new ArrayList<>(hard) : hard;
            if (dy < 0.0) {
                below.addAll(floors);
            }
            dy = Shapes.collide(Direction.Axis.Y, box, below, dy);
            box = box.move(0, dy, 0);
        }
        double dx = move.x, dz = move.z;
        if (Math.abs(dx) < Math.abs(dz)) {
            dz = Shapes.collide(Direction.Axis.Z, box, hard, dz);
            box = box.move(0, 0, dz);
            dx = Shapes.collide(Direction.Axis.X, box, hard, dx);
        } else {
            dx = Shapes.collide(Direction.Axis.X, box, hard, dx);
            box = box.move(dx, 0, 0);
            dz = Shapes.collide(Direction.Axis.Z, box, hard, dz);
        }
        return new Vec3(dx, dy, dz);
    }

    /** The nearest place (up to 8 blocks away) where the player stands free, preferring one with ground under it. */
    private static Vec3 open(ServerPlayer player) {
        final Level level = player.level();
        final BlockPos from = player.blockPosition();
        Vec3 best = null, floating = null;
        double bestD = Double.MAX_VALUE, floatingD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.withinManhattan(from, 8, 8, 8)) {
            final Vec3 at = Vec3.atBottomCenterOf(pos);
            final double d = at.distanceToSqr(player.position());
            if (d >= bestD) {
                continue;
            }
            final AABB box = player.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(at);
            if (!level.noCollision(player, box)) {
                continue;
            }
            if (!level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()) {
                best = at;
                bestD = d;
            } else if (d < floatingD) {
                floating = at;
                floatingD = d;
            }
        }
        return best != null ? best : floating;
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID)
    public static final class Events {
        private Events() {
        }

        /** No suffocating while phasing. */
        @SubscribeEvent
        public static void onDamage(LivingIncomingDamageEvent event) {
            if (event.getSource().is(DamageTypes.IN_WALL) && phasing(event.getEntity())) {
                event.setCanceled(true);
            }
        }

        /** Phasing ended inside a wall: out into the nearest open space. */
        @SubscribeEvent
        public static void onPlayerTick(PlayerTickEvent.Post event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            if (phasing(player)) {
                PHASING.add(player.getUUID());
            } else if (PHASING.remove(player.getUUID()) && !player.level().noCollision(player, player.getBoundingBox().deflate(1.0E-3))) {
                final Vec3 open = open(player);
                if (open != null) {
                    player.teleportTo(open.x, open.y, open.z);
                }
            }
        }
    }
}
