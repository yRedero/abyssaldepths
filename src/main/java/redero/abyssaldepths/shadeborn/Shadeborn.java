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
    private static final EntityDataAccessor<Integer> ROLE = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TEMPER = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> POSTURE = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<ItemStack> WANT = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Boolean> SHOWING = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> VISION = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> VARIANT = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> GESTURE = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> GESTURE_AT = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> SITTING = SynchedEntityData.defineId(Shadeborn.class, EntityDataSerializers.BOOLEAN);
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
        builder.define(GESTURE, "");
        builder.define(GESTURE_AT, 0);
        builder.define(SITTING, false);
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
        goalSelector.addGoal(1, new PanicGoal(this, 1.35) {
            @Override
            public boolean canUse() {
                return !role().fights() && temper() != HOSTILE && super.canUse();
            }
        });
        goalSelector.addGoal(2, new AvoidEntityGoal<>(this, Player.class, 9.0F, 1.0, 1.35, p -> p instanceof Player pl && afraidOf(pl)));
        goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.15, false));
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
        return trust(p) < -20 || stage == Camps.STRANGER && (temper() == FEARFUL || young()) || stage == Camps.STRANGER && temper() == WARY
                && p.distanceTo(this) < 4;
    }

    private boolean hostileTo(Player p) {
        if (p.isCreative() || p.isSpectator()) {
            return false;
        }
        return role() == Role.RAIDER || temper() == HOSTILE && !young() || role() == Role.WATCHER && trust(p) < -20;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide || tickCount % 10 != 0) {
            return;
        }
        if (isInWall()) { // caught in the rock (land laid round them, a cave-in): out, upwards, or home
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
        } else if (near != null && !near.isSpectator()) {
            final int stage = stage(near);
            posture = trust(near) < -20 || stage == Camps.STRANGER && (temper() == FEARFUL || young()) ? COWER
                    : stage == Camps.STRANGER || stage == Camps.TOLERATED && temper() == FEARFUL ? GUARDED : OPEN;
        } else {
            posture = temper() == FEARFUL ? GUARDED : OPEN;
        }
        entityData.set(POSTURE, posture);
        // what they lack, held up to one near enough to see it (not by the fearful to strangers, nor in a fight)
        entityData.set(SHOWING, near != null && near.distanceTo(this) < 7 && posture != THREAT && !role().fights() && tickCount >= nextWant);
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
        // a gift of what they hold up
        if (!want.isEmpty() && held.is(want.getItem()) && tickCount >= nextWant) {
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            nextWant = tickCount + 600; // satisfied for a while, then something else
            entityData.set(WANT, WANTS[random.nextInt(WANTS.length)].copy());
            gave(player, 6);
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
        if (camp != 0 && level() instanceof ServerLevel server && Camps.of(server).change(camp, player.getUUID(), by)) {
            server.sendParticles(ParticleTypes.END_ROD, getX(), getEyeY() + 0.5, getZ(), 12, 0.3, 0.3, 0.3, 0.03);
            server.playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1.0F, 0.8F);
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        final boolean hurt = super.hurt(source, amount);
        if (hurt && camp != 0 && source.getEntity() instanceof Player p && level() instanceof ServerLevel server) {
            Camps.of(server).change(camp, p.getUUID(), isDeadOrDying() ? -50 : -20); // harming them
        }
        return hurt;
    }

    // ------------------------------------------------------------------ goals of their own

    /** The young follow a trusted player round the camp; a watcher escorts its Keeper. */
    private final class FollowTrusted extends Goal {
        @Override
        public boolean canUse() {
            return follow != null && (young() || role() == Role.WATCHER);
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
            return home != null && !blockPosition().closerThan(home, 22) && getTarget() == null;
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
        tag.putLong("camp", camp);
        if (home != null) {
            tag.put("home", NbtUtils.writeBlockPos(home));
        }
        if (!entityData.get(WANT).isEmpty()) {
            tag.put("want", entityData.get(WANT).save(registryAccess()));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(ROLE, tag.getInt("role"));
        entityData.set(TEMPER, tag.getInt("temper"));
        entityData.set(VARIANT, tag.getInt("variant"));
        camp = tag.getLong("camp");
        home = NbtUtils.readBlockPos(tag, "home").orElse(null);
        if (home != null) {
            restrictTo(home, 20);
        }
        if (tag.contains("want")) {
            entityData.set(WANT, ItemStack.parse(registryAccess(), tag.get("want")).orElse(ItemStack.EMPTY));
        }
        refreshDimensions();
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
