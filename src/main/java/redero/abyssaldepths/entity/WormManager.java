package redero.abyssaldepths.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.network.EffectPayload;

/**
 * Keeps the thalan worms going beyond where the world runs (it runs only near players): a worm whose land is no longer
 * active is kept here as a place, a heading and its health, and goes on crawling (wandering, drawn towards players it
 * senses from far off); near a player, in active land, it is a real worm again. A worm keeps within
 * {@link #KEEP_CHUNKS} chunks of the players: straying past {@link #FAR} it turns back towards them, and left far
 * behind it follows. Worms are saved with the world (here, not with the land). The first comes
 * {@link #FIRST_AT} ticks into the world, about {@link #FIRST_CHUNKS} chunks from a player.
 * <p>
 * Players hear the nearest worm coming from {@link #HEAR} blocks away, wherever it is: stone crackling from its
 * direction, more often and louder the nearer it is.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class WormManager extends SavedData {
    private static final String NAME = AbyssalDepths.MODID + "_worms";
    /** How far from every player a worm is let go (chunks); how near a player it becomes real (blocks); how far it is heard. */
    private static final int KEEP_CHUNKS = 128, FAR = 112, FIRST_CHUNKS = 100;
    /** When the first worm comes (ticks into the world). */
    private static final long FIRST_AT = 5 * 60 * 20;
    private static final double REAL = 120.0, HEAR = 400.0, SENSE = 640.0;
    private static final int STEP = 5;
    private static final double SPEED = 0.22 * STEP;
    private static final float TURN = 2.5F * STEP;

    /** A worm kept here. */
    private static final class Kept {
        UUID id;
        double x, y, z;
        float yaw, health, length;
    }

    /** Whether the world's first worm has come (some minutes in, far off). */
    private boolean first;

    private final List<Kept> kept = new ArrayList<>();
    private ServerLevel level;

    private static WormManager of(ServerLevel level) {
        final WormManager m = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(WormManager::new, WormManager::load, null), NAME);
        m.level = level;
        return m;
    }

    private static WormManager load(CompoundTag tag, HolderLookup.Provider registries) {
        final WormManager m = new WormManager();
        for (Tag t : tag.getList("worms", Tag.TAG_COMPOUND)) {
            final CompoundTag w = (CompoundTag) t;
            final Kept k = new Kept();
            k.id = w.getUUID("id");
            k.x = w.getDouble("x");
            k.y = w.getDouble("y");
            k.z = w.getDouble("z");
            k.yaw = w.getFloat("yaw");
            k.health = w.getFloat("health");
            k.length = w.contains("length") ? w.getFloat("length") : (float) BigWorm.LENGTH;
            m.kept.add(k);
        }
        m.first = tag.getBoolean("first");
        return m;
    }

    /** Saved: those kept here, and the real ones now (they are not saved with the land). */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        final ListTag list = new ListTag();
        final List<Kept> all = new ArrayList<>(kept);
        if (level != null) {
            for (BigWorm worm : level.getEntities(AbyssalDepths.BIG_WORM.get(), Entity::isAlive)) {
                all.removeIf(o -> o.id.equals(worm.getUUID()));
                all.add(keep(worm));
            }
        }
        for (Kept k : all) {
            final CompoundTag w = new CompoundTag();
            w.putUUID("id", k.id);
            w.putDouble("x", k.x);
            w.putDouble("y", k.y);
            w.putDouble("z", k.z);
            w.putFloat("yaw", k.yaw);
            w.putFloat("health", k.health);
            w.putFloat("length", k.length);
            list.add(w);
        }
        tag.put("worms", list);
        tag.putBoolean("first", first);
        return tag;
    }

    @Override
    public boolean isDirty() {
        return true; // the real worms move all the time
    }

    /** Keeps a worm (once: a later record of the same worm replaces the earlier). */
    private void add(Kept k) {
        kept.removeIf(o -> o.id.equals(k.id));
        kept.add(k);
    }

    private static Kept keep(BigWorm worm) {
        final Kept k = new Kept();
        k.id = worm.getUUID();
        k.x = worm.getX();
        k.y = worm.getY();
        k.z = worm.getZ();
        k.yaw = worm.getYRot();
        k.health = worm.getHealth();
        k.length = worm.length();
        return k;
    }

    /** A worm whose land was unloaded: kept here, it goes on. */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof BigWorm worm && event.getLevel() instanceof ServerLevel level && worm.getRemovalReason() != null
                && !worm.getRemovalReason().shouldDestroy() && worm.isAlive()) {
            of(level).add(keep(worm));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        final ServerLevel level = event.getServer().overworld();
        final long now = level.getGameTime();
        final WormManager m = of(level);
        if (now % STEP == 0) {
            m.tick(level);
        }
        if (now % 10 == 0) {
            m.sounds(level);
        }
    }

    private void tick(ServerLevel level) {
        final List<ServerPlayer> players = level.players();
        // the first worm: some minutes into the world, about a hundred chunks off a player, in the layer
        if (!first && level.getGameTime() >= FIRST_AT && !players.isEmpty()) {
            first = true;
            final ServerPlayer p = players.get(level.random.nextInt(players.size()));
            final double angle = level.random.nextDouble() * Math.PI * 2;
            final Kept k = new Kept();
            k.id = java.util.UUID.randomUUID();
            k.x = p.getX() + Math.cos(angle) * FIRST_CHUNKS * 16;
            k.z = p.getZ() + Math.sin(angle) * FIRST_CHUNKS * 16;
            k.y = -100;
            k.yaw = level.random.nextFloat() * 360.0F;
            k.health = 400.0F;
            k.length = BigWorm.SHORTEST + level.random.nextFloat() * (BigWorm.LONGEST - BigWorm.SHORTEST);
            kept.add(k);
        }
        // real worms whose land runs no more: kept here instead
        for (BigWorm worm : level.getEntities(AbyssalDepths.BIG_WORM.get(), Entity::isAlive)) {
            if (!level.isPositionEntityTicking(worm.blockPosition())) {
                add(keep(worm));
                worm.discard();
            }
        }
        for (int i = kept.size() - 1; i >= 0; i--) {
            final Kept k = kept.get(i);
            ServerPlayer nearest = null;
            double near = Double.MAX_VALUE;
            for (ServerPlayer p : players) {
                final double d = Math.hypot(p.getX() - k.x, p.getZ() - k.z);
                if (d < near) {
                    near = d;
                    nearest = p;
                }
            }
            if (nearest == null) {
                continue; // nobody about: it waits
            }
            if (near > KEEP_CHUNKS * 16) { // left far behind (the player went far off): it comes after, to the edge of its range
                final double back = near - FAR * 16;
                k.x += (nearest.getX() - k.x) / near * back;
                k.z += (nearest.getZ() - k.z) / near * back;
                near = FAR * 16;
            }
            // near a player, in running land: real again
            final BlockPos at = BlockPos.containing(k.x, k.y, k.z);
            if (near < REAL && level.isPositionEntityTicking(at)) {
                final BigWorm worm = AbyssalDepths.BIG_WORM.get().create(level);
                if (worm != null) {
                    worm.setUUID(k.id);
                    worm.restore(k.x, ground(level, k.x, k.y, k.z), k.z, k.yaw, k.health, k.length);
                    level.addFreshEntity(worm);
                }
                kept.remove(i);
                continue;
            }
            // on its way: towards a player it senses, else wandering
            float want = k.yaw;
            if (near < SENSE || near > FAR * 16) { // sensing a player, or straying too far from them: towards them
                want = (float) (Mth.atan2(-(nearest.getX() - k.x), nearest.getZ() - k.z) * Mth.RAD_TO_DEG);
            } else if (level.random.nextInt(24) == 0) {
                want = k.yaw + (level.random.nextFloat() - 0.5F) * 140.0F;
            }
            k.yaw += Mth.clamp(Mth.wrapDegrees(want - k.yaw), -TURN, TURN);
            final Vec3 step = Vec3.directionFromRotation(0, k.yaw).scale(SPEED);
            k.x += step.x;
            k.z += step.z;
            if (k.y > BigWorm.TOP) {
                k.y = Math.max(BigWorm.TOP - 8, k.y - SPEED * 0.5); // outside the layer: down into it
            }
        }
    }

    /** Where to bring a worm back at a place: the floor near that height (sunk as it crawls), or that height. */
    private static double ground(ServerLevel level, double x, double y, double z) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int d = 0; d <= 24; d++) {
            for (int s : new int[] {d, -d}) {
                pos.set(x, y + BigWorm.SINK + s, z);
                if (level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)
                        && !level.getBlockState(pos.above()).isCollisionShapeFullBlock(level, pos.above())) {
                    return pos.getY() + 1 - BigWorm.SINK;
                }
            }
        }
        return y;
    }

    /** Each player hears the nearest worm (any part of a real one, the head of one kept here) from its direction. */
    private void sounds(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            Vec3 nearest = null, head = null;
            double near = Double.MAX_VALUE;
            for (BigWorm worm : level.getEntities(AbyssalDepths.BIG_WORM.get(), Entity::isAlive)) {
                for (Vec3 p : points(worm)) {
                    final double d = p.distanceTo(player.position());
                    if (d < near) {
                        near = d;
                        nearest = p;
                        head = worm.position();
                    }
                }
            }
            for (Kept k : kept) {
                final double d = player.position().distanceTo(new Vec3(k.x, k.y, k.z));
                if (d < near) {
                    near = d;
                    nearest = head = new Vec3(k.x, k.y, k.z);
                }
            }
            if (nearest == null || near > HEAR) {
                continue;
            }
            PacketDistributor.sendToPlayer(player, new EffectPayload(String.format(Locale.ROOT, "worm_near/%.1f/%.1f/%.1f/%.1f/%.1f/%.1f/%.1f",
                    nearest.x, nearest.y, nearest.z, near, head.x, head.y, head.z), 0));
        }
    }

    private static List<Vec3> points(BigWorm worm) {
        final List<Vec3> points = new ArrayList<>();
        points.add(worm.position());
        for (var part : worm.getParts()) {
            points.add(part.position());
        }
        return points;
    }
}
