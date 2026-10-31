package redero.abyssaldepths.artifact;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
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
 * Aegis: right click switches on a force field round its holder, wider with the mastery ({@link #RADIUS}).
 * Nothing alive gets in (creatures and other players inside are pushed out). Projectiles are held back too:
 * below half mastery they slow nearly to a stop in it and drop ({@link #SLOWING}); from half mastery they
 * bounce off it ({@link #DEFLECT}), and at full mastery they fly back at whoever shot them ({@link #REFLECT}). The holder's own pets, mounts and shots pass. Uses up its charge while on (a full
 * charge lasts longer with the mastery, {@link #SECONDS}). The clients learn who holds a field, and how
 * wide, from {@code aegis/<entity id>/<radius in hundredths>} effects ({@code client.AegisView}).
 */
public class AegisItem extends ToggleArtifactItem {
    /** The field's radius in blocks, and seconds a full charge lasts, per milestone (0 .. 4). */
    static final float[] RADIUS = {2.5F, 3.0F, 3.5F, 4.0F, 4.5F};
    static final int[] SECONDS = {20, 30, 40, 50, 60};
    /** The milestones from which projectiles bounce off, and fly back at their shooter. */
    static final int DEFLECT = 2, REFLECT = 4;
    /** Below {@link #DEFLECT}: what is left of a projectile's speed each tick it is in the field. */
    static final double SLOWING = 0.35;
    /** Client: entity ids holding a field, with the client tick until which they do, and its radius. */
    private static final Int2LongOpenHashMap CLIENT = new Int2LongOpenHashMap();
    private static final it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap CLIENT_RADIUS = new it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap();

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
        return Component.translatable(m >= REFLECT ? "item.abyssaldepths.aegis.power_reflect" : m >= DEFLECT ? "item.abyssaldepths.aegis.power_deflect"
                : "item.abyssaldepths.aegis.power", RADIUS[m], SECONDS[m]);
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
        final int m = milestone(stack);
        if (level.getGameTime() % 10 == 0) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new EffectPayload("aegis/" + player.getId() + "/" + Math.round(RADIUS[m] * 100), 15));
        }
        hold(level, player, RADIUS[m], m >= DEFLECT, m >= REFLECT);
    }

    /** Whether the entity belongs with the holder (passes the field). */
    private static boolean theirs(Player holder, Entity entity) {
        return entity == holder || entity.hasPassenger(holder) || holder.hasPassenger(entity)
                || entity instanceof OwnableEntity pet && pet.getOwner() == holder
                || entity instanceof Projectile shot && shot.getOwner() == holder;
    }

    /** Server: the tick each holder was last told of a hit (at most every few ticks). */
    private static final java.util.Map<java.util.UUID, Long> LAST_HIT = new java.util.HashMap<>();

    /** Tells the holder where a hit on their field came from (for their screen effect, {@code client.AegisOverlay}). */
    private static void hit(ServerLevel level, Player holder, Vec3 from, double speed) {
        if (holder instanceof ServerPlayer player) {
            final long now = level.getGameTime();
            final Long last = LAST_HIT.get(player.getUUID());
            if (last == null || now - last >= 4 || now < last) {
                LAST_HIT.put(player.getUUID(), now);
                PacketDistributor.sendToPlayer(player, new EffectPayload(String.format(java.util.Locale.ROOT, "aegis_hit/%.3f/%.3f/%.2f", from.x, from.z, speed), 0));
            }
        }
    }

    /** One tick of the field: pushes the living out, turns the projectiles back. */
    static void hold(ServerLevel level, Player holder, double radius, boolean deflect, boolean reflect) {
        final Vec3 centre = holder.position().add(0, holder.getBbHeight() * 0.5, 0);
        final StringBuilder pressing = new StringBuilder(); // what is in the field: from where, how deep
        for (Entity entity : level.getEntities(holder, holder.getBoundingBox().inflate(radius + 4.0),
                e -> !e.isSpectator() && !theirs(holder, e) && !Shades.apart(holder, e))) {
            final Vec3 middle = entity.position().add(0, entity.getBbHeight() * 0.5, 0);
            final Vec3 out = middle.subtract(centre);
            final double d = out.length();
            final Vec3 normal = d < 1.0E-3 ? new Vec3(1, 0, 0) : out.scale(1.0 / d);
            if (entity instanceof Projectile projectile) {
                final Vec3 v = projectile.getDeltaMovement();
                final double speed = v.length();
                if (speed < 0.05 || d > radius + speed + 0.5 || v.dot(normal) >= 0 && d > radius) {
                    continue; // lying still, still far, or going away outside
                }
                if (deflect && v.dot(normal) < 0) {
                    turn(level, holder, projectile, normal, reflect);
                    hit(level, holder, out, speed);
                } else if (!deflect) {
                    slow(level, projectile);
                    hit(level, holder, out, speed);
                    press(pressing, out, d, radius, Math.max(0.0, -v.dot(normal)));
                }
            } else if (entity instanceof LivingEntity && d < radius + entity.getBbWidth() * 0.5 && !AnchorItem.anchored(entity)) {
                // pushed out, the deeper the harder, never inwards
                final double push = 0.25 + 0.5 * (1.0 - d / radius);
                final Vec3 v = entity.getDeltaMovement();
                final double inward = Math.max(0.0, -v.dot(normal)); // how hard it comes on, before the push
                final Vec3 flat = new Vec3(normal.x, 0, normal.z);
                final Vec3 dir = flat.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : flat.normalize();
                entity.setDeltaMovement(dir.x * push, Math.max(v.y, normal.y > 0.5 ? 0.2 : v.y), dir.z * push);
                entity.hasImpulse = true;
                entity.hurtMarked = true;
                hit(level, holder, out, inward);
                press(pressing, out, d, radius, inward);
            }
        }
        if (holder instanceof ServerPlayer player && level.getGameTime() % 4 == 0) {
            PacketDistributor.sendToPlayer(player, new EffectPayload("aegis_press/" + pressing, 0));
        }
    }

    /**
     * Notes something in the field (at most eight): its direction, how deep in it is (0 at the rim .. 1 at the
     * middle), and how fast it comes on (blocks a tick).
     */
    private static void press(StringBuilder pressing, Vec3 out, double d, double radius, double speed) {
        if (pressing.chars().filter(c -> c == ';').count() < 8) {
            pressing.append(String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f,%.2f;", out.x, out.z, Mth.clamp(1.0 - d / radius, 0.0, 1.0), speed));
        }
    }

    /** A projectile in a weak field: it loses most of its speed (a fireball its drive too), and drops. */
    private static void slow(ServerLevel level, Projectile projectile) {
        projectile.setDeltaMovement(projectile.getDeltaMovement().scale(SLOWING));
        if (projectile instanceof AbstractHurtingProjectile fireball) {
            fireball.accelerationPower *= SLOWING;
        }
        projectile.hasImpulse = true;
        projectile.hurtMarked = true;
        if (level.getGameTime() % 3 == 0) {
            level.sendParticles(ParticleTypes.ENCHANTED_HIT, projectile.getX(), projectile.getY(), projectile.getZ(), 2, 0.05, 0.05, 0.05, 0.02);
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

    /** Client: the entity holds a field of this radius for the next {@code ticks} ticks. */
    public static void clientField(int entityId, float radius, int ticks, long clientTick) {
        CLIENT.put(entityId, clientTick + ticks);
        CLIENT_RADIUS.put(entityId, radius);
    }

    /** Client: the radius of the entity's field. */
    public static float clientRadius(Entity entity) {
        return CLIENT_RADIUS.getOrDefault(entity.getId(), RADIUS[0]);
    }

    /** Client: whether the entity holds a field now. */
    public static boolean clientHolds(Entity entity) {
        return !CLIENT.isEmpty() && CLIENT.get(entity.getId()) >= entity.level().getGameTime();
    }
}
