package redero.abyssaldepths.shadeborn;

import java.util.Map;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.ShadebornCamps;

/**
 * A camp whose Keeper the player became (DESIGN.md 5.2.1: they follow the player; the camp grows and can be expanded).
 * The Keeper, given:
 * <ul>
 * <li>duskweave (8): has a tent more put up round the hearth (up to four more);</li>
 * <li>what a craft needs: takes in a newcomer of that craft (up to twelve in the camp): an iron ingot a smith, duskthread
 * a weaver, a lantern a lamp-keeper, bread a forager, a golden apple a mender, an echo shard a seer, a compass a
 * pathfinder, a pickaxe a delver, a storm shard a stormwatcher, a sword a watcher.</li>
 * </ul>
 * A watcher, sneak-used by the Keeper, escorts them (and stops again).
 */
final class KeeperCamp {
    private static final int MAX_TENTS = 4, MAX_MEMBERS = 12;

    private KeeperCamp() {
    }

    private static Role craft(ItemStack held) {
        final Map<Item, Role> crafts = Map.ofEntries(Map.entry(Items.IRON_INGOT, Role.SMITH), Map.entry(AbyssalDepths.DUSKTHREAD.get(), Role.WEAVER),
                Map.entry(Items.LANTERN, Role.LAMP_KEEPER), Map.entry(Items.BREAD, Role.FORAGER), Map.entry(Items.GOLDEN_APPLE, Role.MENDER),
                Map.entry(Items.ECHO_SHARD, Role.SEER), Map.entry(Items.COMPASS, Role.PATHFINDER), Map.entry(Items.IRON_PICKAXE, Role.DELVER),
                Map.entry(AbyssalDepths.STORM_SHARD.get(), Role.STORMWATCHER), Map.entry(Items.IRON_SWORD, Role.WATCHER));
        return crafts.get(held.getItem());
    }

    /** What the Keeper does with what its own Keeper gives it; false if nothing. */
    static boolean use(Shadeborn keeper, ServerLevel level, Player player, ItemStack held) {
        final Camps camps = Camps.of(level);
        final Camps.Camp c = camps.camp(keeper.camp());
        if (held.is(AbyssalDepths.DUSKWEAVE.get().asItem()) && held.getCount() >= 8 && c.extraTents < MAX_TENTS && keeper.home() != null) {
            held.shrink(8);
            ShadebornCamps.raiseTent(level, keeper.home(), c.extraTents * Math.PI / 2 + Math.PI / 4);
            c.extraTents++;
            camps.setDirty();
            keeper.gesture("bow");
            level.playSound(null, keeper.blockPosition(), SoundEvents.WOOL_PLACE, net.minecraft.sounds.SoundSource.NEUTRAL, 1.2F, 0.8F);
            return true;
        }
        final Role craft = craft(held);
        if (craft != null && keeper.home() != null && Shadeborn.of(level, keeper.camp(), keeper.home(), 40).size() < MAX_MEMBERS) {
            held.shrink(1);
            final Shadeborn newcomer = AbyssalDepths.SHADEBORN.get().create(level);
            if (newcomer != null) {
                newcomer.moveTo(keeper.home().getX() + 0.5, keeper.home().getY(), keeper.home().getZ() + 2.5, 0, 0);
                newcomer.setup(craft, Shadeborn.NEUTRAL, keeper.camp(), keeper.home());
                newcomer.finalizeSpawn(level, level.getCurrentDifficultyAt(keeper.home()), MobSpawnType.EVENT, null);
                level.addFreshEntity(newcomer);
                level.sendParticles(ParticleTypes.END_ROD, newcomer.getX(), newcomer.getEyeY(), newcomer.getZ(), 15, 0.4, 0.6, 0.4, 0.03);
                newcomer.gesture("bow");
            }
            keeper.gesture("nod");
            return true;
        }
        return false;
    }
}
