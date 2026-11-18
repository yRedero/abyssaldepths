package redero.abyssaldepths.shadeborn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.entity.BigWorm;
import redero.abyssaldepths.worldgen.ShadebornCamps;
import redero.abyssaldepths.worldgen.TunnelLayer;

/**
 * The Shadeborn over time (DESIGN.md 5.2.1):
 * <ul>
 * <li>trust rises for defending a camp (a hostile creature killed near it), for being there to warn it when a worm
 * comes near; it falls for robbing the store (before they welcome you) and for leading a worm to them;</li>
 * <li>camps grow with the world's progress (a huddle, a camp, a hold: more of them), faster where a player is Kin;</li>
 * <li>a camp whose Keeper a player became sends supplies: its store is filled again each day;</li>
 * <li>wanderers travel alone between camps (the usual first contact); raiders' bands prey on camps and players.</li>
 * </ul>
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class ShadebornLife {
    /** World days after which camps have grown to a camp, and to a hold. */
    private static final int CAMP_DAYS = 10, HOLD_DAYS = 30;
    private static final Map<String, Long> ONCE = new HashMap<>();

    private ShadebornLife() {
    }

    /** The camp whose ground a place is (within so many blocks of its hearth), or null. */
    public static ShadebornCamps.Spot campAt(ServerLevel level, BlockPos at, int radius) {
        final redero.abyssaldepths.worldgen.ShadebornStructure.Piece p = redero.abyssaldepths.worldgen.ShadebornStructure.at(level, at, radius);
        return p == null || p.hut ? null : p.spot;
    }

    private static boolean once(String key, long now, long every) {
        if (now < ONCE.getOrDefault(key, 0L)) {
            return false;
        }
        ONCE.put(key, now + every);
        return true;
    }

    /** Defending the camp: a hostile creature killed by a player near it. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || !(event.getSource().getEntity() instanceof Player p)
                || event.getEntity().getY() > TunnelLayer.LAYER_TOP_Y) {
            return;
        }
        if (event.getEntity() instanceof Monster || event.getEntity() instanceof Shadeborn sb && sb.role() == Role.RAIDER
                || event.getEntity() instanceof BigWorm) {
            final ShadebornCamps.Spot s = campAt(level, event.getEntity().blockPosition(), 28);
            if (s != null) {
                Camps.of(level).change(s.id(), p.getUUID(), event.getEntity() instanceof BigWorm ? 60 : 3);
            }
        }
    }

    /** Taking from the store before they welcome you. */
    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getPos().getY() > TunnelLayer.LAYER_TOP_Y
                || !(level.getBlockEntity(event.getPos()) instanceof BarrelBlockEntity) || event.getEntity().isCreative()) {
            return;
        }
        final ShadebornCamps.Spot s = campAt(level, event.getPos(), 6);
        if (s != null && Math.hypot(event.getPos().getX() - s.store().getX(), event.getPos().getZ() - s.store().getZ()) < 2.5 && Camps.of(level).camp(s.id()).stage(event.getEntity().getUUID()) < Camps.WELCOME) {
            Camps.of(level).change(s.id(), event.getEntity().getUUID(), -10);
            level.playSound(null, event.getPos(), SoundEvents.FOX_SCREECH, SoundSource.NEUTRAL, 1.2F, 0.6F); // they see it
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        final ServerLevel level = event.getServer().overworld();
        final long now = level.getGameTime();
        if (now % 100 != 0) {
            return;
        }
        final Camps camps = Camps.of(level);
        for (ServerPlayer p : level.players()) {
            if (p.getY() > TunnelLayer.LAYER_TOP_Y || p.isSpectator()) {
                continue;
            }
            final ShadebornCamps.Spot s = campAt(level, p.blockPosition(), 64);
            if (s != null) {
                final Camps.Camp c = camps.camp(s.id());
                if (c.center == BlockPos.ZERO) {
                    c.center = s.hearth();
                    c.store = s.store();
                    camps.setDirty();
                }
                worm(level, s, p, now);
                grow(level, s, c, now);
                supplies(level, s, c, now);
            }
            if (now % 600 == 0) {
                wanderers(level, p);
            }
        }
    }

    /** The worm and the camp: leading it to them costs trust; being there when it comes near, to warn them, earns it. */
    private static void worm(ServerLevel level, ShadebornCamps.Spot s, ServerPlayer p, long now) {
        final Vec3 hearth = Vec3.atCenterOf(s.hearth());
        for (BigWorm worm : level.getEntities(AbyssalDepths.BIG_WORM.get(), w -> w.isAlive() && w.position().distanceTo(hearth) < 120)) {
            final boolean hunted = worm.hunting() == p;
            if (hunted && worm.position().distanceTo(hearth) < 48 && once("lead" + s.id() + p.getUUID(), now, 2400)) {
                Camps.of(level).change(s.id(), p.getUUID(), -15); // they led it here
            } else if (!hunted && p.blockPosition().closerThan(s.hearth(), 20) && once("warn" + s.id() + p.getUUID(), now, 6000)) {
                Camps.of(level).change(s.id(), p.getUUID(), 4); // there to warn them
                level.playSound(null, s.hearth(), SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 1.5F, 0.6F);
            }
        }
    }

    /** The camp grows with the world (faster where a player is Kin): who is missing comes, one at a time. */
    private static void grow(ServerLevel level, ShadebornCamps.Spot s, Camps.Camp c, long now) {
        if (s.hostile()) {
            return;
        }
        final long days = level.getDayTime() / 24000L;
        final boolean kin = c.trust.values().stream().anyMatch(t -> t >= Camps.STAGE[Camps.KIN]) || c.keeper != null;
        final int growth = days >= (kin ? HOLD_DAYS / 2 : HOLD_DAYS) ? Camps.HOLD : days >= (kin ? CAMP_DAYS / 2 : CAMP_DAYS) ? Camps.CAMP : Camps.HUDDLE;
        if (growth > c.growth) {
            c.growth = growth;
            Camps.of(level).setDirty();
        }
        if (!level.isLoaded(s.hearth()) || now % 600 != 0) {
            return;
        }
        final List<Shadeborn> members = Shadeborn.of(level, s.id(), s.hearth(), 40);
        final Role[] roles = ShadebornCamps.members(s.biome());
        if (members.size() >= ShadebornCamps.count(c.growth)) {
            return;
        }
        for (int i = 0; i < ShadebornCamps.count(c.growth); i++) {
            final Role want = roles[i];
            final long have = members.stream().filter(m -> m.role() == want).count();
            final long should = java.util.Arrays.stream(roles, 0, ShadebornCamps.count(c.growth)).filter(r -> r == want).count();
            if (have < should) {
                final Shadeborn sb = AbyssalDepths.SHADEBORN.get().create(level);
                if (sb != null) {
                    sb.moveTo(s.x() + 0.5 + level.random.nextInt(5) - 2, s.floor(), s.z() + 0.5 + level.random.nextInt(5) - 2, 0, 0);
                    sb.setup(want, ShadebornCamps.temper(s, want, i + members.size()), s.id(), s.hearth());
                    sb.finalizeSpawn(level, level.getCurrentDifficultyAt(s.hearth()), MobSpawnType.EVENT, null);
                    level.addFreshEntity(sb);
                }
                return;
            }
        }
    }

    /** A camp that follows a Keeper sends supplies: each day its store is filled again with food and light. */
    private static void supplies(ServerLevel level, ShadebornCamps.Spot s, Camps.Camp c, long now) {
        if (c.keeper == null || now - c.lastSupply < 24000 || !level.isLoaded(s.store())) {
            return;
        }
        if (level.getBlockEntity(s.store()) instanceof BarrelBlockEntity barrel) {
            final ItemStack[] gifts = {new ItemStack(Items.BREAD, 6), new ItemStack(Items.COOKED_MUTTON, 4), new ItemStack(Items.TORCH, 12),
                    new ItemStack(AbyssalDepths.DUSKTHREAD.get(), 4)};
            int slot = 0;
            for (ItemStack g : gifts) {
                while (slot < barrel.getContainerSize() && !barrel.getItem(slot).isEmpty()) {
                    slot++;
                }
                if (slot < barrel.getContainerSize()) {
                    barrel.setItem(slot, g.copy());
                }
            }
            c.lastSupply = now;
            Camps.of(level).setDirty();
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, s.store().getX() + 0.5, s.store().getY() + 1.2, s.store().getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0);
        }
    }

    /** Now and then a wanderer crosses a player's way; more rarely a band of raiders comes. */
    private static void wanderers(ServerLevel level, ServerPlayer p) {
        if (level.getEntitiesOfClass(Shadeborn.class, p.getBoundingBox().inflate(80), s -> s.camp() == 0).size() >= 2) {
            return;
        }
        final boolean raiders = level.random.nextFloat() < 0.06F;
        if (!raiders && level.random.nextFloat() > 0.25F) {
            return;
        }
        final double a = level.random.nextDouble() * Math.PI * 2;
        final BlockPos at = floor(level, BlockPos.containing(p.getX() + Math.cos(a) * 36, p.getY(), p.getZ() + Math.sin(a) * 36));
        if (at == null || !level.isLoaded(at)) {
            return;
        }
        spawn(level, at, raiders ? 3 : 1, raiders, (float) (a * 57.3));
    }

    /** A band of raiders (or one wanderer) at a place on the floor, facing a way. */
    private static void spawn(ServerLevel level, BlockPos at, int count, boolean raiders, float yaw) {
        for (int i = 0; i < count; i++) {
            final Shadeborn sb = AbyssalDepths.SHADEBORN.get().create(level);
            if (sb == null) {
                return;
            }
            sb.moveTo(at.getX() + 0.5 + i, at.getY(), at.getZ() + 0.5, yaw, 0);
            sb.setup(raiders ? Role.RAIDER : Role.WANDERER, raiders ? Shadeborn.HOSTILE : Shadeborn.FEARFUL, 0, null);
            sb.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
            level.addFreshEntity(sb);
        }
    }

    /** A band of raiders some blocks before a player (a test command); whether there was floor for them. */
    public static boolean raiders(ServerLevel level, ServerPlayer p, int count, double distance) {
        final net.minecraft.world.phys.Vec3 ahead = p.position().add(net.minecraft.world.phys.Vec3.directionFromRotation(0, p.getYRot()).scale(distance));
        final BlockPos at = floor(level, BlockPos.containing(ahead));
        if (at == null) {
            return false;
        }
        spawn(level, at, count, true, p.getYRot() + 180);
        return true;
    }

    /** The open floor near a place (feet room over solid ground), or null. */
    private static BlockPos floor(ServerLevel level, BlockPos at) {
        final BlockPos.MutableBlockPos pos = at.mutable().move(0, 8, 0);
        for (int k = 0; k < 24; k++) {
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir() && level.getBlockState(pos.below()).isSolid()) {
                return pos.immutable();
            }
            pos.move(0, -1, 0);
        }
        return null;
    }
}
