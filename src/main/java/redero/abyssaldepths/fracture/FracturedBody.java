package redero.abyssaldepths.fracture;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.entity.AbsentBody;

/**
 * The body a fractured player leaves standing while they are in their trial: their likeness, frozen in black
 * and white corruption (drawn so by {@code client.FractureView}), untouchable, unmoving. It goes when its player
 * is out of the trial again (or gone); if they failed it stays where it stood, decomposing over
 * {@link #DECAY_TICKS} (pixels falling away), while the corruption takes them.
 */
public class FracturedBody extends AbsentBody {
    public static final int DECAY_TICKS = 20 * 30;
    /** The world's tick it began to decompose (the same on every client, however long they have seen it); -1 not. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Long> DECAY = net.minecraft.network.syncher.SynchedEntityData.defineId(FracturedBody.class,
            net.minecraft.network.syncher.EntityDataSerializers.LONG);

    public FracturedBody(EntityType<? extends FracturedBody> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DECAY, -1L);
    }

    /** Its player failed: it stays, decomposing. */
    void decay() {
        entityData.set(DECAY, level().getGameTime());
    }

    /** How far it has decomposed (0 .. 1); -1 not decomposing. */
    public float decayed(float partialTick) {
        final long from = entityData.get(DECAY);
        return from < 0 ? -1.0F : Mth.clamp((level().getGameTime() - from + partialTick) / DECAY_TICKS, 0.0F, 1.0F);
    }

    /** Leaves the player's frozen body where they stand. */
    static FracturedBody standIn(ServerPlayer player) {
        final FracturedBody body = new FracturedBody(AbyssalDepths.FRACTURED_BODY.get(), player.level());
        body.dressAs(player);
        body.moveTo(player.getX(), player.getY(), player.getZ(), player.yBodyRot, player.getXRot());
        body.setYHeadRot(player.getYHeadRot());
        body.yBodyRot = body.yBodyRotO = player.yBodyRot;
        player.level().addFreshEntity(body);
        return body;
    }

    /** Those who come near see it in its corruption too. */
    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && tickCount % 40 == 1) {
            Fracturing.showCorrupted(this, 60);
        }
        if (!level().isClientSide && decayed(0.0F) >= 1.0F) {
            discard(); // nothing left
        }
    }

    @Override
    protected boolean vulnerableTo(DamageSource source) {
        return false; // not even the void: it waits for its player
    }

    @Override
    protected boolean shouldVanish(Player owner) {
        return decayed(0.0F) < 0.0F && (owner == null || !FractureTrials.busy(owner));
    }

    /** It keeps its player's look exactly (it stands in for them: no easing round). */
    @Override
    protected boolean easesHead() {
        return false;
    }

    @Override
    public boolean bobs() {
        return false;
    }
}
