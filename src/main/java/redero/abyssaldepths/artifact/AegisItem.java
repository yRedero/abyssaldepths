package redero.abyssaldepths.artifact;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Aegis: right click switches on a force field of {@link #RADIUS} blocks round its holder. Nothing alive gets
 * in (creatures and other players inside are pushed out), projectiles bounce off it; at full mastery they
 * fly back at whoever shot them. The holder's own pets, mounts and shots pass. Uses up its charge while on
 * (a full charge lasts longer with the mastery, {@link #SECONDS}). The clients learn who holds a field from
 * {@code aegis/<entity id>} effects ({@code client.AegisView}).
 */
public class AegisItem extends ToggleArtifactItem {
    public static final double RADIUS = 4.0;
    /** Seconds a full charge lasts, per milestone (0 .. 4). */
    static final int[] SECONDS = {20, 30, 40, 50, 60};
    /** The milestone from which projectiles fly back at their shooter. */
    static final int REFLECT = 4;
    /** Client: entity ids holding a field, with the client tick until which they do. */
    private static final Int2LongOpenHashMap CLIENT = new Int2LongOpenHashMap();

    public AegisItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks, 40);
    }

    @Override
    protected int drainTicks(ItemStack stack) {
        return SECONDS[milestone(stack)] * 20 / Math.max(1, maxCharge());
    }

    @Override
    public Component powerLine(ItemStack stack) {
        final int m = milestone(stack);
        return Component.translatable(m >= REFLECT ? "item.abyssaldepths.aegis.power_reflect" : "item.abyssaldepths.aegis.power", SECONDS[m]);
    }

    @Override
    protected String noChargeKey() {
        return "abyssaldepths.aegis.no_charge";
    }

    @Override
    protected void playSwitch(Level level, Player player, boolean on) {
        playSwitch(level, player, on ? SoundEvents.BEACON_POWER_SELECT : SoundEvents.BEACON_DEACTIVATE, on ? 1.4F : 1.2F);
    }

    @Override
    protected void whileActive(ServerPlayer player, ItemStack stack) {
        final ServerLevel level = player.serverLevel();
        if (level.getGameTime() % 10 == 0) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new EffectPayload("aegis/" + player.getId(), 15));
        }
        hold(level, player, milestone(stack) >= REFLECT);
    }

    /** Whether the entity belongs with the holder (passes the field). */
    private static boolean theirs(Player holder, Entity entity) {
        return entity == holder || entity.hasPassenger(holder) || holder.hasPassenger(entity)
                || entity instanceof OwnableEntity pet && pet.getOwner() == holder
                || entity instanceof Projectile shot && shot.getOwner() == holder;
    }

    /** One tick of the field: pushes the living out, turns the projectiles back. */
    static void hold(ServerLevel level, Player holder, boolean reflect) {
        final Vec3 centre = holder.position().add(0, holder.getBbHeight() * 0.5, 0);
        for (Entity entity : level.getEntities(holder, holder.getBoundingBox().inflate(RADIUS + 4.0),
                e -> !e.isSpectator() && !theirs(holder, e) && !Shades.apart(holder, e))) {
            final Vec3 middle = entity.position().add(0, entity.getBbHeight() * 0.5, 0);
            final Vec3 out = middle.subtract(centre);
            final double d = out.length();
            final Vec3 normal = d < 1.0E-3 ? new Vec3(1, 0, 0) : out.scale(1.0 / d);
            if (entity instanceof Projectile projectile) {
                final Vec3 v = projectile.getDeltaMovement();
                final double speed = v.length();
                if (speed < 0.05 || d > RADIUS + speed + 0.5 || v.dot(normal) >= 0) {
                    continue; // lying still, still far, or going away
                }
                turn(level, holder, projectile, normal, reflect);
            } else if (entity instanceof LivingEntity && d < RADIUS + entity.getBbWidth() * 0.5 && !AnchorItem.anchored(entity)) {
                // pushed out, the deeper the harder, never inwards
                final double push = 0.25 + 0.5 * (1.0 - d / RADIUS);
                final Vec3 v = entity.getDeltaMovement();
                final Vec3 flat = new Vec3(normal.x, 0, normal.z);
                final Vec3 dir = flat.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : flat.normalize();
                entity.setDeltaMovement(dir.x * push, Math.max(v.y, normal.y > 0.5 ? 0.2 : v.y), dir.z * push);
                entity.hasImpulse = true;
                entity.hurtMarked = true;
            }
        }
    }

    /** A projectile meets the field: it bounces off, or (reflecting) flies back at its shooter, now the holder's. */
    private static void turn(ServerLevel level, Player holder, Projectile projectile, Vec3 normal, boolean reflect) {
        final Entity shooter = projectile.getOwner();
        if (projectile instanceof AbstractHurtingProjectile) {
            projectile.deflect(reflect && shooter != null ? ProjectileDeflection.AIM_DEFLECT : ProjectileDeflection.REVERSE, holder, holder, true);
        } else {
            final Vec3 v = projectile.getDeltaMovement();
            final Vec3 back;
            if (reflect && shooter != null && shooter.isAlive() && shooter.level() == level) {
                back = shooter.getEyePosition().subtract(projectile.position()).normalize().scale(Math.max(1.5, v.length()));
                projectile.setOwner(holder);
            } else {
                back = v.subtract(normal.scale(2.0 * v.dot(normal))).scale(0.6); // bounced off the sphere
            }
            projectile.setDeltaMovement(back);
            projectile.hasImpulse = true;
            projectile.hurtMarked = true;
        }
        level.sendParticles(ParticleTypes.ENCHANTED_HIT, projectile.getX(), projectile.getY(), projectile.getZ(), 8, 0.1, 0.1, 0.1, 0.2);
        level.playSound(null, projectile.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.7F, 1.4F);
    }

    /** Client: the entity holds a field for the next {@code ticks} ticks. */
    public static void clientField(int entityId, int ticks, long clientTick) {
        CLIENT.put(entityId, clientTick + ticks);
    }

    /** Client: whether the entity holds a field now. */
    public static boolean clientHolds(Entity entity) {
        return !CLIENT.isEmpty() && CLIENT.get(entity.getId()) >= entity.level().getGameTime();
    }
}
