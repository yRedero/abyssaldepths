package redero.abyssaldepths.entity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.MirageItem;

/**
 * Mirage's decoy: a likeness of its player (skin, armour, held items, their max health; the armour counts)
 * standing where they were, drawing the creatures around onto itself ({@link MirageItem}). It can be hurt
 * and killed; it is gone after its time, when killed, or when its player leaves or logs out.
 */
public class MirageDecoy extends AbsentBody {
    private int life = 200;

    public MirageDecoy(EntityType<? extends MirageDecoy> type, Level level) {
        super(type, level);
        setInvulnerable(false);
    }

    /** A decoy of the player where they stand, for {@code ticks}. */
    public static MirageDecoy of(ServerPlayer player, int ticks) {
        final MirageDecoy decoy = new MirageDecoy(AbyssalDepths.MIRAGE_DECOY.get(), player.level());
        decoy.dressAs(player);
        decoy.moveTo(player.getX(), player.getY(), player.getZ(), player.yBodyRot, player.getXRot());
        decoy.setYHeadRot(player.getYHeadRot());
        decoy.yBodyRot = decoy.yBodyRotO = player.yBodyRot;
        decoy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(player.getMaxHealth());
        decoy.setHealth(player.getMaxHealth());
        decoy.life = ticks;
        return decoy;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && --life <= 0) {
            discard();
        }
    }

    @Override
    protected boolean vulnerableTo(DamageSource source) {
        return true;
    }

    @Override
    protected boolean shouldVanish(Player owner) {
        return owner == null || owner.level() != level();
    }

    @Override
    public boolean bobs() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return true;
    }

    @Override
    public boolean shouldBeSaved() {
        return false; // it lasts seconds
    }
}
