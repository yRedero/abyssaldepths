package redero.abyssaldepths.shadeborn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.DeepPressure;

/**
 * A Shadeborn: hooded figures of the dark region, faces hidden, a faint light in the eyes (DESIGN.md 5.2.1). They do not
 * speak the player's language: their mood shows in their posture (cowering, wary, open, hostile) and what they want
 * shows in their hand, held up. Each has a {@link Role} and a temperament (fearful, wary, neutral, hostile) and belongs
 * to a camp (its grid cell; {@link Camps} keeps the trust there) or to none (wanderers, outcasts, raiders).
 * <p>
 * Giving what one holds up (food, light: what the layer lacks) raises the camp's trust; so does trading, defending the
 * camp, warning it of the worm. Harming them, robbing the store or leading the worm to them lowers it. From Tolerated on
 * those with wares trade; from Welcome the Mender heals and eases Deep Pressure; from Kin the Seer senses the worm
 * (its vision: the model's "zooooooosh"); at the trust of a Keeper the camp's Keeper accepts the player as its own.
 */
public class Shadeborn extends PathfinderMob implements Merchant, redero.abyssaldepths.cinema.CutsceneActor {
    public static final int FEARFUL = 0, WARY = 1, NEUTRAL = 2, HOSTILE = 3;
    /** How they hold themselves (what the client draws). */
    public static final int OPEN = 0, GUARDED = 1, COWER = 2, THREAT = 3;
    /** After an alarm, still shaken: watchful, looking where it came from (a wary face), not yet back at work. */
    public static final int ALERT = 4;
    private static final EntityDataAccessor<Integer> ROLE = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TEMPER = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> POSTURE = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<ItemStack> WANT = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Boolean> SHOWING = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> VISION = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> VARIANT = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.INT);
    /** Where they come from (depths, flats, dunes, reaches): their regional look; "" until known (taken from where they stand). */
    private static final EntityDataAccessor<String> REGION = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> GESTURE = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> GESTURE_AT = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> SITTING = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.BOOLEAN);
    /** What it is doing now (a key: on_watch, resting, expedition, ...), for the marker over its head. */
    private static final EntityDataAccessor<String> ACTIVITY = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.STRING);
    /** For the marker: how tired (0..1), and its camp's progress (0..100; -1 without a camp). */
    private static final EntityDataAccessor<Float> TIRED = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> PROGRESS = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.INT);
    /** What they lack and hold up: food and light. */
    private static final ItemStack[] WANTS = {new ItemStack(Items.BREAD), new ItemStack(Items.COOKED_BEEF), new ItemStack(Items.COOKED_MUTTON),
            new ItemStack(Items.BAKED_POTATO), new ItemStack(Items.TORCH), new ItemStack(Items.LANTERN), new ItemStack(Items.SWEET_BERRIES),
            new ItemStack(Items.GLOW_BERRIES), new ItemStack(Items.COOKED_COD), new ItemStack(Items.CANDLE)};

    private long camp;
    private BlockPos home;
    private Player trading;
    private MerchantOffers offers;
    private int offersStage = -1;
    private UUID offersFor, follow;
    private int nextWant;
    private final Map<UUID, Long> served = new HashMap<>(), greeted = new HashMap<>(), gifted = new HashMap<>();
    /** Who struck this one, till when it holds it against them (game time; its own memory, longer than the camp's). */
    private final Map<UUID, Long> grudges = new HashMap<>();
    /** A raider's raid, or null. */
    Raid raid;
    /** What a raider carries off (dropped where it falls). */
    final List<ItemStack> loot = new java.util.ArrayList<>();
    /** The last alarm heard: from what, when (game time). */
    LivingEntity alarmedBy;
    long alarmAt = -1000;
    Vec3 alarmPos;
    /** What it fears now (judged by {@link Shelter#danger}, every half second), and whether it is taking shelter. */
    Shelter.Danger danger;
    long dangerAt = -100;
    boolean sheltering;

    /** Whether a player is one this one is wary of: kept at a distance, not fled from (see {@link Shelter}). */
    boolean wary(Player p) {
        return afraidOf(p);
    }

    /**
     * Whether this one is still shaken by the last alarm: the fearful longest, the hostile hardly; each a little
     * differently, so they go back to their work one by one.
     */
    boolean shaken() {
        final int calm = switch (temper()) {
            case FEARFUL -> 900;
            case WARY -> 600;
            case NEUTRAL -> 400;
            default -> 200;
        } + Math.floorMod(getId(), 5) * 60;
        return level().getGameTime() - alarmAt < calm;
    }

    /** A raid in hand: on which camp, its hearth and store, how many set out, when, and how far it has got. */
    public static final class Raid {
        static final int CREEP = 0, ATTACK = 1, FLEE = 2, STALK = 3;
        final long camp;
        final BlockPos hearth, store;
        final int bandSize;
        final long started;
        int phase;
        long chaseSince, nextPick, stalkUntil, attackSince;
        /** Where the band watched the camp from (and makes for with its loot). */
        BlockPos rally;

        Raid(long camp, BlockPos hearth, BlockPos store, int bandSize, long started) {
            this.camp = camp;
            this.hearth = hearth;
            this.store = store;
            this.bandSize = bandSize;
            this.started = started;
        }
    }

    /** Whether this one (or its camp) still holds a blow against a player. */
    public boolean begrudges(Player p) {
        if (!(level() instanceof ServerLevel server)) {
            return false;
        }
        final long now = server.getGameTime();
        return grudges.getOrDefault(p.getUUID(), 0L) > now || camp != 0 && Camps.of(server).camp(camp).grudge(p.getUUID(), now) > 0;
    }

    /** What this one runs from: raiders, whatever it heard the alarm about, players it fears. (Fighters run from nothing.) */
    private boolean fears(LivingEntity e) {
        if (role().fights() || temper() == HOSTILE) {
            return false;
        }
        if (e instanceof Shadeborn o) {
            return o.role() == Role.RAIDER;
        }
        if (e == alarmedBy && level().getGameTime() - alarmAt < 400) {
            return !(e instanceof Player p) || !p.isCreative() && !p.isSpectator();
        }
        return e instanceof Player p && afraidOf(p);
    }

    public Shadeborn(EntityType<? extends Shadeborn> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 22.0).add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 4.0).add(Attributes.FOLLOW_RANGE, 24.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ROLE, Role.WANDERER.ordinal());
        builder.define(TEMPER, WARY);
        builder.define(POSTURE, GUARDED);
        builder.define(WANT, ItemStack.EMPTY);
        builder.define(SHOWING, false);
        builder.define(VISION, -1);
        builder.define(VARIANT, 0);
        builder.define(REGION, "");
        builder.define(GESTURE, "");
        builder.define(GESTURE_AT, 0);
        builder.define(SITTING, false);
        builder.define(ACTIVITY, "idle");
        builder.define(TIRED, 0.0F);
        builder.define(PROGRESS, -1);
    }

    // ------------------------------------------------------------------ who they are

    public Role role() {
        return Role.of(entityData.get(ROLE));
    }

    public int temper() {
        return entityData.get(TEMPER);
    }

    public int posture() {
        return entityData.get(POSTURE);
    }

    /** What they hold up (when someone is near to show it to), or empty. */
    public ItemStack shown() {
        return entityData.get(SHOWING) ? entityData.get(WANT) : ItemStack.EMPTY;
    }

    /** When their vision began (game tick), or -1. */
    public int vision() {
        return entityData.get(VISION);
    }

    /** Their look (texture variant; 0 the first). */
    /** The gesture being made (an animation of the model) and since when (game tick), or "". */
    public String gesture() {
        return entityData.get(GESTURE);
    }

    public int gestureAt() {
        return entityData.get(GESTURE_AT);
    }

    /** Makes a gesture now (nod, wave, bow, beckon, point, warn). */
    public void gesture(String name) {
        entityData.set(GESTURE, name);
        entityData.set(GESTURE_AT, (int) level().getGameTime());
    }

    /** What it is doing now (a key, see the lang file's shadeborn.abyssaldepths.activity.*). */
    public String activity() {
        return entityData.get(ACTIVITY);
    }

    /** How tired it is, as seen (0..1). */
    public float tired() {
        return entityData.get(TIRED);
    }

    /** Its camp's progress, as seen (0..100), or -1 without a camp. */
    public int campProgress() {
        return entityData.get(PROGRESS);
    }

    /** What it wants (shown held up or not). */
    public ItemStack wants() {
        return entityData.get(WANT);
    }

    /** How tired it is (0 rested .. 1 worn out): working tires, resting in the tent restores. */
    float fatigue;
    /** Whether it stands the camp's watch now (the Elder sees to who does). */
    boolean onWatch;
    /** Whether it is resting now (in its tent, till rested). */
    boolean resting;
    /** What its routine has it doing (an activity key). */
    String task = "idle";
    /** The expedition it is on, or null. */
    Expeditions.Trip trip;
    /** Where it was when it last seemed stuck, since when (raiders try elsewhere when they get nowhere). */
    Vec3 stuckAt;
    long stuckSince;
    /** A raider band's life between raids (see Raids.wild): what it is doing, where it goes, since when; its next raid not before. */
    String wild = "roaming";
    BlockPos wildGoal;
    /** The camp a band last raided: kept away from till it may raid again. */
    BlockPos avoid;
    long wildSince, raidAgain;

    /** Whether they sit (round the hearth, in their tent). */
    public boolean sitting() {
        return entityData.get(SITTING);
    }

    void sitting(boolean sit) {
        if (sitting() != sit) {
            entityData.set(SITTING, sit);
        }
    }

    public BlockPos home() {
        return home;
    }

    /** Whether they follow a player (the young, a watcher escorting the Keeper). */
    boolean following() {
        return follow != null;
    }

    /** Whether a player has lost their trust (harmed them). */
    boolean distrusted(Player p) {
        return trust(p) < -20;
    }

    public int variant() {
        return entityData.get(VARIANT);
    }

    /** Their region (depths, flats, dunes, reaches, or "other"); "" until known. */
    public String region() {
        return entityData.get(REGION);
    }

    /** The region of the land at a place. */
    private static String regionAt(net.minecraft.world.level.Level level, BlockPos at) {
        final var biome = level.getBiome(at);
        return biome.is(AbyssalDepths.SILT_DUNES) ? "dunes" : biome.is(AbyssalDepths.THERMAL_REACHES) ? "reaches"
                : biome.is(AbyssalDepths.SHALE_FLATS) ? "flats" : biome.is(AbyssalDepths.THALAN_DEPTHS) ? "depths" : "other";
    }

    public boolean young() {
        return role() == Role.YOUNG;
    }

    public long camp() {
        return camp;
    }

    /** Sets them up: their role, temperament, camp (0: none) and home (where they keep to). */
    public void setup(Role role, int temper, long camp, BlockPos home) {
        entityData.set(ROLE, role.ordinal());
        entityData.set(TEMPER, temper);
        entityData.set(VARIANT, random.nextInt(4));
        this.camp = camp;
        this.home = home;
        if (home != null) {
            restrictTo(home, role == Role.WATCHER ? 14 : 20);
        }
        entityData.set(WANT, WANTS[random.nextInt(WANTS.length)].copy());
        refreshDimensions();
        setPersistenceRequired();
    }

    @Override
    public EntityDimensions getDefaultDimensions(Pose pose) {
        final EntityDimensions d = super.getDefaultDimensions(pose);
        return young() ? d.scale(0.6F) : d;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (ROLE.equals(key)) {
            refreshDimensions();
        }
    }

    // ------------------------------------------------------------------ what they do

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new Shelter(this)); // danger: keeping away, or fleeing to a shelter (those who do not fight)
        goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.15, false));
        goalSelector.addGoal(3, new Expeditions.Go(this)); // out on an expedition
        goalSelector.addGoal(4, new Raids.Raid(this));
        goalSelector.addGoal(4, new Raids.Band(this));
        goalSelector.addGoal(4, new CampLife.Formation(this));
        goalSelector.addGoal(4, new FollowTrusted());
        goalSelector.addGoal(5, new GoHome());
        goalSelector.addGoal(5, new CampLife.Routine(this));
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this) {
            @Override
            public boolean canUse() {
                return (role().fights() || temper() == HOSTILE) && super.canUse();
            }
        });
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false, p -> p instanceof Player pl && hostileTo(pl)));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Shadeborn.class, 10, true, false, // watchers drive raiders off
                o -> role() == Role.WATCHER && o instanceof Shadeborn r && r.role() == Role.RAIDER && (home == null || r.blockPosition().closerThan(home, 28))));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false,
                m -> role() == Role.WATCHER && home != null && m.blockPosition().closerThan(home, 24)));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, redero.abyssaldepths.fracture.Remnant.class, 10, true, false,
                m -> role() == Role.MOURNER)); // the Mourner lays the Remnants to rest
    }

    /** The stage a player stands at with their camp (Stranger without a camp, Tolerated for a wanderer). */
    public int stage(Player p) {
        if (!(level() instanceof ServerLevel server)) {
            return Camps.STRANGER;
        }
        if (camp == 0) {
            return role() == Role.WANDERER || role() == Role.OUTCAST ? Camps.TOLERATED : Camps.STRANGER;
        }
        return Camps.of(server).camp(camp).stage(p.getUUID());
    }

    private int trust(Player p) {
        return level() instanceof ServerLevel server && camp != 0 ? Camps.of(server).camp(camp).trust(p.getUUID()) : 0;
    }

    private boolean afraidOf(Player p) {
        if (role().fights() || temper() == HOSTILE || p.isCreative() || p.isSpectator()) {
            return false;
        }
        final int stage = stage(p);
        return begrudges(p) || trust(p) < -20 || stage == Camps.STRANGER && (temper() == FEARFUL || young()) || stage == Camps.STRANGER && temper() == WARY
                && p.distanceTo(this) < 4;
    }

    private boolean hostileTo(Player p) {
        if (p.isCreative() || p.isSpectator()) {
            return false;
        }
        // raiders: in a raid, whoever is in the way; otherwise only those who side with the camps (Kin somewhere), when near
        return role() == Role.RAIDER && !"lying_in_wait".equals(wild) && (raid != null
                || p.distanceTo(this) < 12 && level() instanceof ServerLevel sl && Camps.of(sl).kinAnywhere(p.getUUID())) || temper() == HOSTILE && !young()
                || role() == Role.WATCHER && (trust(p) < -20 || begrudges(p) && trust(p) < Camps.STAGE[Camps.WELCOME]);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide || tickCount % 10 != 0) {
            return;
        }
        if (region().isEmpty()) { // where they come from: the land they first stand on (the camp's own)
            entityData.set(REGION, regionAt(level(), home != null ? home : blockPosition()));
        }
        if (isInWall() && !level().getBlockState(BlockPos.containing(getEyePosition())).is(AbyssalDepths.QUICKSILT.get())) { // caught in the rock (land laid round them, a cave-in): out, upwards, or home
            final BlockPos.MutableBlockPos at = blockPosition().mutable();
            for (int k = 0; k < 8; k++) {
                at.move(0, 1, 0);
                if (level().getBlockState(at).isAir() && level().getBlockState(at.above()).isAir()) {
                    teleportTo(getX(), at.getY(), getZ());
                    return;
                }
            }
            if (home != null) {
                teleportTo(home.getX() + 0.5, home.getY() + 1, home.getZ() + 0.5);
            }
        }
        final Player near = level().getNearestPlayer(this, 10);
        int posture = OPEN;
        if (getTarget() != null || role() == Role.RAIDER || temper() == HOSTILE && !young()) {
            posture = THREAT;
        } else if (near != null && !near.isSpectator() && begrudges(near)) { // the one who struck them: shrinking back, or squaring up
            posture = role().fights() ? THREAT : COWER;
        } else if (near != null && !near.isSpectator()) {
            final int stage = stage(near);
            posture = trust(near) < -20 || stage == Camps.STRANGER && (temper() == FEARFUL || young()) ? COWER
                    : stage == Camps.STRANGER || stage == Camps.TOLERATED && temper() == FEARFUL ? GUARDED : OPEN;
        } else {
            posture = temper() == FEARFUL ? GUARDED : OPEN;
        }
        final Shelter.Danger fear = Shelter.danger(this);
        if (sheltering && (fear == null || fear.flee())) {
            posture = COWER; // fled, or crouching in shelter
        }
        if (shaken() && (posture == OPEN || posture == GUARDED)) { // still shaken: watchful, eyes on where it came from
            posture = ALERT;
            if (alarmPos != null && getTarget() == null && random.nextInt(3) == 0) {
                getLookControl().setLookAt(alarmPos.x, alarmPos.y + 1.2, alarmPos.z);
            }
        }
        entityData.set(POSTURE, posture);
        // what they lack, held up to one near enough to see it (not by the fearful to strangers, nor in a fight)
        // what they want, held out only when calm: not afraid, not shaken, not in danger, nothing against the one near
        // and only by camp folk with nothing to do, at home (not out on an expedition, not sitting: their hands are busy)
        final String now0 = activity();
        entityData.set(SHOWING, near != null && near.distanceTo(this) < 7 && (posture == OPEN || posture == GUARDED) && !role().fights()
                && tickCount >= nextWant && !begrudges(near) && !afraidOf(near) && fear == null && !sheltering
                && camp != 0 && trip == null && !sitting() && !onWatch && !resting
                && ("at_hearth".equals(now0) || "standing_by".equals(now0) || "idle".equals(now0)));
        if (role() == Role.WATCHER && home != null && getTarget() != null && getTarget().distanceToSqr(Vec3.atCenterOf(home)) > 28 * 28) {
            setTarget(null); // a watcher guards its camp: it does not chase the enemy off across the layer
        }
        if (raid != null) {
            Raids.think(this); // a raider at its raid
        } else if (role() == Role.RAIDER && camp == 0) {
            Raids.wild(this); // a raider band between raids
        }
        // what it is doing, and how that tires it (or rests it)
        final String doing = trip != null ? (trip.phase == Expeditions.Trip.OUT ? "expedition_out" : trip.phase == Expeditions.Trip.SEARCH ? "searching" : "returning")
                : getTarget() != null ? "fighting" : raid != null ? (raid.phase == Raid.STALK ? "stalking" : raid.phase == Raid.FLEE ? "making_off" : "raiding")
                : role() == Role.RAIDER && camp == 0 ? (onWatch && "resting".equals(wild) ? "keeping_watch" : wild)
                : sheltering ? (fear != null && !fear.flee() ? "keeping_away" : "taking_shelter")
                : onWatch ? "on_watch" : resting ? "resting" : shaken() ? "alert" : task;
        entityData.set(ACTIVITY, doing);
        final float tire = switch (doing) {
            case "resting" -> -0.006F;
            case "on_watch", "expedition_out", "searching", "returning", "fighting", "raiding", "taking_shelter", "roaming", "making_off" -> 0.0018F;
            case "keeping_watch", "stalking", "lying_in_wait" -> 0.0012F;
            case "tending_fire", "at_store", "overseeing" -> 0.0012F;
            case "at_hearth" -> -0.0015F; // (sitting by the fire rests them a little)
            default -> 0.0003F;
        };
        fatigue = Mth.clamp(fatigue + tire, 0, 1);
        entityData.set(TIRED, Math.round(fatigue * 20) / 20.0F);
        if (tickCount % 40 == 0) {
            entityData.set(PROGRESS, camp != 0 && level() instanceof ServerLevel sl ? Math.round(Camps.of(sl).camp(camp).progress) : -1);
        }
        // a raider coming into sight: the alarm
        if (camp != 0 && role() != Role.RAIDER && tickCount % 20 == 0) {
            final List<Shadeborn> raiders = level().getEntitiesOfClass(Shadeborn.class, getBoundingBox().inflate(20), o -> o.role() == Role.RAIDER && o.isAlive());
            if (!raiders.isEmpty()) {
                Raids.alarm(this, raiders.get(0));
            }
        }
        greet(near);
        if (vision() >= 0 && level().getGameTime() - vision() > 30) {
            entityData.set(VISION, -1);
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!(level() instanceof ServerLevel server) || hand != InteractionHand.MAIN_HAND || posture() == THREAT) {
            return posture() == THREAT ? InteractionResult.PASS : InteractionResult.sidedSuccess(level().isClientSide);
        }
        final ItemStack held = player.getItemInHand(hand);
        final ItemStack want = entityData.get(WANT);
        final int stage = stage(player);
        // what raiders took from this camp, brought back: into the store, and a deep bow
        if (camp != 0 && Raids.stolenFrom(held) == camp) {
            final net.minecraft.world.level.block.entity.BarrelBlockEntity store = Raids.store(server, Camps.of(server).camp(camp).store.equals(BlockPos.ZERO)
                    ? blockPosition() : Camps.of(server).camp(camp).store);
            final ItemStack back = held.copy();
            back.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            back.remove(net.minecraft.core.component.DataComponents.LORE);
            if (store != null) {
                for (int i = 0; i < store.getContainerSize() && !back.isEmpty(); i++) {
                    if (store.getItem(i).isEmpty()) {
                        store.setItem(i, back.copy());
                        back.setCount(0);
                    }
                }
            }
            held.setCount(0);
            gave(player, 12);
            Camps.of(server).soften(camp, player.getUUID(), server.getGameTime());
            gesture("bow");
            server.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getEyeY() + 0.3, getZ(), 10, 0.4, 0.4, 0.4, 0);
            playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 0.8F);
            return InteractionResult.SUCCESS;
        }
        // a gift of what they hold up
        if (!want.isEmpty() && held.is(want.getItem()) && tickCount >= nextWant) {
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            nextWant = tickCount + 600; // satisfied for a while, then something else
            entityData.set(SHOWING, false); // (the hand lowered at once: what it wants next is shown only later)
            if (begrudges(player)) { // a gift of what they lack: they let go of half of what they hold against you
                Camps.of(server).soften(camp, player.getUUID(), server.getGameTime());
                final long left = grudges.getOrDefault(player.getUUID(), 0L) - server.getGameTime();
                if (left > 0) {
                    grudges.put(player.getUUID(), server.getGameTime() + left / 2);
                }
            }
            entityData.set(WANT, WANTS[random.nextInt(WANTS.length)].copy());
            gave(player, 6);
            gesture("face_glad"); // (glad, once the model has the face)
            server.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getEyeY() + 0.3, getZ(), 8, 0.3, 0.3, 0.3, 0);
            playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 0.6F);
            return InteractionResult.SUCCESS;
        }
        if (posture() == COWER) {
            playSound(SoundEvents.FOX_SNIFF, 0.6F, 0.5F); // they shrink from you
            return InteractionResult.CONSUME;
        }
        if (role() == Role.WATCHER && player.isShiftKeyDown() && camp != 0 && player.getUUID().equals(Camps.of(server).camp(camp).keeper)) {
            follow = player.getUUID().equals(follow) ? null : player.getUUID(); // the Keeper's escort
            gesture(follow != null ? "bow" : "nod");
            return InteractionResult.SUCCESS;
        }
        switch (role()) {
            case YOUNG -> {
                if (stage >= Camps.WELCOME) {
                    follow = player.getUUID().equals(follow) ? null : player.getUUID();
                    playSound(SoundEvents.ALLAY_ITEM_GIVEN, 0.8F, 1.4F);
                    return InteractionResult.SUCCESS;
                }
            }
            case KEEPER -> {
                final Camps.Camp c = camp == 0 ? null : Camps.of(server).camp(camp);
                if (c != null && player.isShiftKeyDown() && held.isEmpty() && stage >= Camps.KIN && player instanceof ServerPlayer sp) {
                    final var piece = redero.abyssaldepths.worldgen.ShadebornStructure.at(server, home != null ? home : blockPosition(), 24);
                    if (piece != null) {
                        final String said = Expeditions.send(server, piece.spot, c, of(server, camp, piece.spot.hearth(), 48), server.getGameTime(), sp, false);
                        sp.displayClientMessage(Component.translatable("shadeborn.abyssaldepths.expedition.asked", said), true);
                        return InteractionResult.SUCCESS;
                    }
                }
                if (c != null && player.getUUID().equals(c.keeper) && KeeperCamp.use(this, server, player, held)) {
                    return InteractionResult.SUCCESS;
                }
                if (c != null && c.keeper == null && c.trust(player.getUUID()) >= Camps.STAGE[Camps.KEEPER]) {
                    c.keeper = player.getUUID(); // the camp is theirs now: it follows them
                    Camps.of(server).setDirty();
                    server.sendParticles(ParticleTypes.END_ROD, getX(), getEyeY(), getZ(), 30, 0.6, 0.8, 0.6, 0.05);
                    playSound(SoundEvents.BEACON_ACTIVATE, 1.0F, 0.7F);
                    if (player instanceof ServerPlayer sp) {
                        redero.abyssaldepths.cinema.Cutscenes.play(sp, "abyssaldepths:keeper", position());
                    }
                    return InteractionResult.SUCCESS;
                }
            }
            case MENDER -> {
                if (stage >= Camps.WELCOME && held.isEmpty() && ready(player, 2400)) {
                    player.heal(player.getMaxHealth());
                    player.getActiveEffects().stream().filter(e -> e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL
                            && !e.getEffect().is(AbyssalDepths.DEEP_PRESSURE)).map(MobEffectInstance::getEffect).toList().forEach(player::removeEffect);
                    DeepPressure.ease(player, 2400); // the pressure recedes for a while
                    server.sendParticles(ParticleTypes.HEART, player.getX(), player.getEyeY(), player.getZ(), 6, 0.4, 0.4, 0.4, 0);
                    playSound(SoundEvents.BEACON_POWER_SELECT, 0.8F, 1.3F);
                    return InteractionResult.SUCCESS;
                }
            }
            case SEER -> {
                if (stage >= Camps.KIN && held.isEmpty() && ready(player, 1200)) {
                    sense(server, player);
                    return InteractionResult.SUCCESS;
                }
            }
            default -> {
            }
        }
        if (begrudges(player)) { // they will not deal with the one who struck them
            playSound(SoundEvents.FOX_SNIFF, 0.6F, 0.5F);
            gesture("face_sceptical");
            return InteractionResult.CONSUME;
        }
        if (role().trades() && stage >= Camps.TOLERATED && !isTrading()) {
            if (offers == null || offersStage != stage || !player.getUUID().equals(offersFor)) {
                offers = Offers.of(this, server, stage);
                offersStage = stage;
                offersFor = player.getUUID();
            }
            setTradingPlayer(player);
            openTradingScreen(player, Component.translatable("entity.abyssaldepths.shadeborn." + role().id), 0);
            return InteractionResult.SUCCESS;
        }
        playSound(SoundEvents.FOX_SNIFF, 0.5F, 0.6F); // nothing for you (yet)
        gesture("face_sceptical"); // and the look that goes with it
        return InteractionResult.CONSUME;
    }

    /**
     * Trust you can see: one coming near is met by the stage they stand at. Strangers are warned off by the watchers;
     * the Tolerated get a nod, the Welcome a wave, the Kin a bow; the camp's own Keeper is given, once, the camp's mark, and a
     * Forager gives them a little food now and then (no gifts below that).
     */
    private void greet(Player near) {
        if (near == null || near.isSpectator() || near.distanceTo(this) > 7 || !(level() instanceof ServerLevel server) || camp == 0
                || temper() == HOSTILE || level().getGameTime() < greeted.getOrDefault(near.getUUID(), 0L)) {
            return;
        }
        if (begrudges(near)) { // no greeting for the one who struck them: the watchers warn them off
            if (role() == Role.WATCHER) {
                gesture("warn");
            }
            greeted.put(near.getUUID(), level().getGameTime() + 200);
            return;
        }
        final int stage = stage(near);
        if (stage == Camps.STRANGER) {
            if (role() == Role.WATCHER) {
                gesture("warn");
            }
            greeted.put(near.getUUID(), level().getGameTime() + 200); // (strangers are met each time they come)
            return;
        }
        greeted.put(near.getUUID(), level().getGameTime() + 1200);
        gesture(stage == Camps.TOLERATED ? "nod" : stage == Camps.WELCOME ? "wave" : "bow");
        if (role() == Role.KEEPER && stage >= Camps.KEEPER && Camps.of(server).camp(camp).marked.add(near.getUUID())) {
            final ItemStack mark = new ItemStack(AbyssalDepths.KIN_MARK.get());
            mark.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                    Component.translatable("item.abyssaldepths.kin_mark.of", home == null ? 0 : home.getX(), home == null ? 0 : home.getZ())
                            .withStyle(net.minecraft.ChatFormatting.GRAY))));
            spawnAtLocation(mark, 1.0F);
            Camps.of(server).setDirty();
        }
        final long day = level().getDayTime() / 24000L;
        if (role() == Role.FORAGER && stage >= Camps.KEEPER && gifted.getOrDefault(near.getUUID(), -1L) < day && random.nextInt(2) == 0) {
            gifted.put(near.getUUID(), day); // (once a day at most: food is scarce down here)
            spawnAtLocation(new ItemStack(random.nextBoolean() ? Items.SWEET_BERRIES : Items.COOKED_MUTTON, 1 + random.nextInt(2)), 1.0F);
        }
    }

    /** Opens their trades for a player as at a stage of trust (a test command). */
    public void showTrades(ServerPlayer player, int stage) {
        offers = Offers.of(this, (ServerLevel) level(), stage);
        offersStage = stage;
        offersFor = player.getUUID();
        setTradingPlayer(player);
        openTradingScreen(player, Component.translatable("entity.abyssaldepths.shadeborn." + role().id), 0);
    }

    /** Whether a service is ready again for a player (each so often), marking it used. */
    private boolean ready(Player player, int every) {
        final long now = level().getGameTime();
        if (now < served.getOrDefault(player.getUUID(), 0L)) {
            return false;
        }
        served.put(player.getUUID(), now + every);
        return true;
    }

    /** The Seer's vision: its head stretches out (the "zooooooosh"), and a trail of light points the way to the worm. */
    private void sense(ServerLevel server, Player player) {
        entityData.set(VISION, (int) server.getGameTime());
        gesture("point");
        playSound(SoundEvents.ILLUSIONER_PREPARE_MIRROR, 1.0F, 0.5F);
        final Vec3 worm = redero.abyssaldepths.entity.WormManager.nearestWorm(server, position());
        if (worm == null) {
            server.sendParticles(ParticleTypes.SMOKE, getX(), getEyeY(), getZ(), 20, 0.3, 0.3, 0.3, 0.02); // nothing near
            return;
        }
        final Vec3 dir = worm.subtract(position()).multiply(1, 0, 1).normalize();
        for (int i = 2; i < 18; i++) {
            final Vec3 at = position().add(dir.scale(i)).add(0, 1.2, 0);
            server.sendParticles(ParticleTypes.SCULK_SOUL, at.x, at.y, at.z, 1, 0.05, 0.05, 0.05, 0.0);
        }
    }

    /** Trust from a player's kindness here (and a stage reached: shown). */
    private void gave(Player player, int by) {
        if (camp != 0 && level() instanceof ServerLevel server0) {
            Camps.of(server0).progress(camp, by / 4.0F); // help moves the camp on
        }
        if (camp != 0 && level() instanceof ServerLevel server && Camps.of(server).change(camp, player.getUUID(), by)) {
            server.sendParticles(ParticleTypes.END_ROD, getX(), getEyeY() + 0.5, getZ(), 12, 0.3, 0.3, 0.3, 0.03);
            server.playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1.0F, 0.8F);
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        final int before = source.getEntity() instanceof Player p0 ? trust(p0) : 0;
        final boolean hurt = super.hurt(source, amount);
        if (hurt && source.getEntity() instanceof LivingEntity attacker && attacker != this && level() instanceof ServerLevel server) {
            if (attacker instanceof Player p && !p.isCreative()) {
                final long now = server.getGameTime(), hold = Camps.grudgeFor(before, isDeadOrDying());
                if (camp != 0) {
                    Camps.of(server).change(camp, p.getUUID(), isDeadOrDying() ? -50 : -20); // harming them
                    Camps.of(server).progress(camp, -3);
                    Camps.of(server).hold(camp, p.getUUID(), now, hold); // and they remember it
                }
                grudges.put(p.getUUID(), Math.max(now, grudges.getOrDefault(p.getUUID(), 0L)) + hold * 3 / 2); // this one, longer still
            }
            if (!(attacker instanceof Shadeborn o && o.camp() == camp && camp != 0)) {
                Raids.alarm(this, attacker);
            }
        }
        return hurt;
    }

    // ------------------------------------------------------------------ goals of their own

    /** The young follow a trusted player round the camp; a watcher escorts its Keeper. */
    private final class FollowTrusted extends Goal {
        @Override
        public boolean canUse() {
            return Shelter.danger(Shadeborn.this) == null && follow != null && (young() || role() == Role.WATCHER);
        }

        @Override
        public void tick() {
            final Player p = level().getPlayerByUUID(follow);
            if (role() == Role.WATCHER) {
                restrictTo(blockPosition(), 64);
            }
            if (p == null || p.distanceTo(Shadeborn.this) > (role() == Role.WATCHER ? 48 : 24)) {
                follow = null;
                return;
            }
            if (p.distanceTo(Shadeborn.this) > 3) {
                getNavigation().moveTo(p, 1.1);
            }
        }
    }

    /** Back to their camp when they strayed (not the wanderers). */
    private final class GoHome extends Goal {
        @Override
        public boolean canUse() {
            return home != null && !blockPosition().closerThan(home, 22) && getTarget() == null && Shelter.danger(Shadeborn.this) == null && !shaken();
        }

        @Override
        public void start() {
            getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0);
        }
    }

    // ------------------------------------------------------------------ trading (they show, you give)

    @Override
    public void setTradingPlayer(Player player) {
        trading = player;
    }

    @Override
    public Player getTradingPlayer() {
        return trading;
    }

    private boolean isTrading() {
        return trading != null;
    }

    @Override
    public MerchantOffers getOffers() {
        return offers == null ? new MerchantOffers() : offers;
    }

    @Override
    public void overrideOffers(MerchantOffers offers) {
        this.offers = offers;
    }

    @Override
    public void notifyTrade(MerchantOffer offer) {
        offer.increaseUses();
        if (trading != null) {
            gave(trading, 2); // trade builds trust
        }
    }

    @Override
    public void notifyTradeUpdated(ItemStack stack) {
    }

    @Override
    public int getVillagerXp() {
        return 0;
    }

    @Override
    public void overrideXp(int xp) {
    }

    @Override
    public boolean showProgressBar() {
        return false;
    }

    @Override
    public SoundEvent getNotifyTradeSound() {
        return SoundEvents.AMETHYST_BLOCK_CHIME;
    }

    @Override
    public boolean isClientSide() {
        return level().isClientSide;
    }

    // ------------------------------------------------------------------ in cutscenes

    private Acting acting;

    @Override
    public void act(String animation, long fromTick) {
        acting = animation.isEmpty() ? null : new Acting(animation, fromTick);
    }

    @Override
    public Acting acted() {
        return acting;
    }

    @Override
    public void setUpActor(com.google.gson.JsonObject data) {
        if (data.has("role")) {
            for (Role r : Role.values()) {
                if (r.id.equals(data.get("role").getAsString())) {
                    entityData.set(ROLE, r.ordinal());
                }
            }
        }
        if (data.has("posture")) {
            entityData.set(POSTURE, data.get("posture").getAsInt());
        }
        if (data.has("variant")) {
            entityData.set(VARIANT, data.get("variant").getAsInt());
        }
        if (data.has("region")) {
            entityData.set(REGION, data.get("region").getAsString());
        }
        refreshDimensions();
    }

    // ------------------------------------------------------------------ the rest

    @Override
    public boolean removeWhenFarAway(double distance) {
        return camp == 0 && role() != Role.OUTCAST && distance > 128 * 128;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.FOX_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.FOX_DEATH;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("role", entityData.get(ROLE));
        tag.putInt("temper", temper());
        tag.putInt("variant", variant());
        tag.putString("region", region());
        tag.putLong("camp", camp);
        if (home != null) {
            tag.put("home", NbtUtils.writeBlockPos(home));
        }
        if (!entityData.get(WANT).isEmpty()) {
            tag.put("want", entityData.get(WANT).save(registryAccess()));
        }
        final net.minecraft.nbt.ListTag held = new net.minecraft.nbt.ListTag();
        grudges.forEach((p, until) -> {
            final CompoundTag e = new CompoundTag();
            e.putUUID("p", p);
            e.putLong("until", until);
            held.add(e);
        });
        tag.put("grudges", held);
        tag.putFloat("fatigue", fatigue);
        tag.putBoolean("watch", onWatch);
        tag.putBoolean("resting", resting);
        if (trip != null) {
            tag.put("trip", trip.save());
        }
        if (raid != null) {
            final CompoundTag r = new CompoundTag();
            r.putLong("camp", raid.camp);
            r.put("hearth", NbtUtils.writeBlockPos(raid.hearth));
            r.put("store", NbtUtils.writeBlockPos(raid.store));
            r.putInt("band", raid.bandSize);
            r.putLong("started", raid.started);
            r.putInt("phase", raid.phase);
            tag.put("raid", r);
        }
        final net.minecraft.nbt.ListTag carried = new net.minecraft.nbt.ListTag();
        for (ItemStack st : loot) {
            if (!st.isEmpty()) {
                carried.add(st.save(registryAccess()));
            }
        }
        tag.put("loot", carried);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(ROLE, tag.getInt("role"));
        entityData.set(TEMPER, tag.getInt("temper"));
        entityData.set(VARIANT, tag.getInt("variant"));
        entityData.set(REGION, tag.getString("region"));
        camp = tag.getLong("camp");
        home = NbtUtils.readBlockPos(tag, "home").orElse(null);
        if (home != null) {
            restrictTo(home, 20);
        }
        if (tag.contains("want")) {
            entityData.set(WANT, ItemStack.parse(registryAccess(), tag.get("want")).orElse(ItemStack.EMPTY));
        }
        fatigue = tag.getFloat("fatigue");
        onWatch = tag.getBoolean("watch");
        resting = tag.getBoolean("resting");
        trip = tag.contains("trip") ? Expeditions.Trip.load(tag.getCompound("trip")) : null;
        for (net.minecraft.nbt.Tag e : tag.getList("grudges", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            grudges.put(((CompoundTag) e).getUUID("p"), ((CompoundTag) e).getLong("until"));
        }
        if (tag.contains("raid")) {
            final CompoundTag r = tag.getCompound("raid");
            raid = new Raid(r.getLong("camp"), NbtUtils.readBlockPos(r, "hearth").orElse(BlockPos.ZERO), NbtUtils.readBlockPos(r, "store").orElse(BlockPos.ZERO),
                    r.getInt("band"), r.getLong("started"));
            raid.phase = r.getInt("phase");
        }
        for (net.minecraft.nbt.Tag e : tag.getList("loot", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            ItemStack.parse(registryAccess(), e).ifPresent(loot::add);
        }
        refreshDimensions();
    }

    /** What a raider carried off falls where it falls. */
    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        for (ItemStack st : loot) {
            if (!st.isEmpty()) {
                spawnAtLocation(st);
            }
        }
        loot.clear();
    }

    /** All Shadeborn of a camp near a place. */
    public static List<Shadeborn> of(ServerLevel level, long camp, BlockPos near, int radius) {
        return level.getEntitiesOfClass(Shadeborn.class, new net.minecraft.world.phys.AABB(near).inflate(radius), s -> s.camp() == camp);
    }

    /** How far a living thing is from this one (for the camp's defenders). */
    public double from(LivingEntity e) {
        return Math.sqrt(distanceToSqr(e));
    }

    @Override
    public float getVoicePitch() {
        return Mth.clamp(super.getVoicePitch() * (young() ? 1.4F : 0.8F), 0.5F, 2.0F);
    }
}
