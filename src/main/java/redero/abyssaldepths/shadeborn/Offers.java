package redero.abyssaldepths.shadeborn;

import java.util.Optional;
import java.util.function.BiFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.TunnelFeature;

/**
 * What each Shadeborn trades, by role and by the stage of trust (DESIGN.md 5.2.1): they want what the layer lacks (food,
 * light) and give what it has (its materials, their crafts, their knowledge). Better trades from Welcome, the best from
 * Kin. Things the design names but the mod does not have yet are left out (the Vent Forge the Smith teaches, the
 * Weaver's capes, relics).
 */
final class Offers {
    private Offers() {
    }

    private static MerchantOffer offer(ItemLike pay, int count, ItemStack get, int uses) {
        return new MerchantOffer(new ItemCost(pay, count), Optional.empty(), get, uses, 0, 0.05F);
    }

    private static MerchantOffer offer(ItemLike pay, int count, ItemLike pay2, int count2, ItemStack get, int uses) {
        return new MerchantOffer(new ItemCost(pay, count), Optional.of(new ItemCost(pay2, count2)), get, uses, 0, 0.05F);
    }

    private static ItemStack of(ItemLike item, int count) {
        return new ItemStack(item, count);
    }

    static MerchantOffers of(Shadeborn s, ServerLevel level, int stage) {
        final MerchantOffers o = new MerchantOffers();
        final boolean welcome = stage >= Camps.WELCOME, kin = stage >= Camps.KIN;
        final Item food = Items.BREAD, meat = Items.COOKED_BEEF, light = Items.TORCH;
        switch (s.role()) {
            case TRADER -> {
                o.add(offer(food, 6, of(AbyssalDepths.GLOAM_CRYSTAL.get(), 1), 8));
                o.add(offer(light, 12, of(AbyssalDepths.DUSKTHREAD.get(), 4), 8));
                o.add(offer(meat, 4, of(AbyssalDepths.DUSK_STALK.get(), 4), 6));
                if (welcome) {
                    o.add(offer(Items.LANTERN, 2, of(AbyssalDepths.GLOAM_CLUSTER.get(), 2), 6));
                    o.add(offer(meat, 6, of(Items.IRON_INGOT, 3), 6));
                }
                if (kin) {
                    o.add(offer(meat, 12, Items.LANTERN, 2, of(Items.DIAMOND, 1), 3));
                }
            }
            case SMITH -> {
                o.add(offer(food, 8, of(Items.IRON_INGOT, 2), 8));
                o.add(offer(meat, 6, of(Items.GOLD_INGOT, 2), 6));
                if (welcome) {
                    o.add(offer(Items.IRON_INGOT, 4, meat, 4, of(Items.CHAINMAIL_CHESTPLATE, 1), 2));
                    o.add(offer(Items.IRON_INGOT, 3, light, 8, of(Items.IRON_PICKAXE, 1), 2));
                }
                if (kin) {
                    o.add(offer(Items.DIAMOND, 2, meat, 8, of(Items.DIAMOND_PICKAXE, 1), 1));
                }
            }
            case WEAVER -> {
                o.add(offer(food, 4, of(AbyssalDepths.DUSKTHREAD.get(), 6), 8));
                o.add(offer(AbyssalDepths.DUSKTHREAD.get(), 8, of(AbyssalDepths.DUSKWEAVE.get(), 2), 8));
                if (welcome) {
                    o.add(offer(AbyssalDepths.DUSKWEAVE.get(), 3, food, 4, of(Items.BUNDLE, 1), 2)); // a bag
                    o.add(offer(AbyssalDepths.DUSKWEAVE.get(), 2, of(AbyssalDepths.DUSKWEAVE_MAT.get(), 2), 4));
                }
                if (kin) {
                    o.add(offer(AbyssalDepths.DUSKWEAVE.get(), 6, meat, 4, of(Items.ARROW, 32), 3)); // a quiver's worth
                }
            }
            case LAMP_KEEPER -> {
                o.add(offer(food, 4, of(light, 8), 12)); // they keep the light and share it
                o.add(offer(meat, 3, of(Items.LANTERN, 1), 8));
                if (welcome) {
                    o.add(offer(food, 6, of(AbyssalDepths.GLOAM_CLUSTER.get(), 3), 6));
                    o.add(offer(Items.COAL, 4, of(light, 16), 12)); // refilling: fuel for light
                }
                if (kin) {
                    o.add(offer(meat, 8, of(AbyssalDepths.GLOAM_CRYSTAL.get(), 4), 4));
                }
            }
            case FORAGER -> {
                o.add(offer(light, 6, of(meat, 3), 10));
                o.add(offer(light, 4, of(Items.SWEET_BERRIES, 8), 10));
                if (welcome) {
                    o.add(offer(Items.LANTERN, 1, of(Items.COOKED_MUTTON, 6), 8));
                    o.add(offer(light, 8, of(Items.GLOW_BERRIES, 8), 8));
                }
                if (kin) {
                    o.add(offer(Items.LANTERN, 2, of(Items.GOLDEN_CARROT, 4), 4));
                }
            }
            case MENDER -> {
                o.add(offer(food, 6, of(Items.HONEY_BOTTLE, 2), 6));
                if (welcome) {
                    o.add(offer(meat, 6, of(Items.GOLDEN_APPLE, 1), 2));
                }
                if (kin) {
                    o.add(offer(meat, 16, Items.LANTERN, 4, of(AbyssalDepths.CLEANSING_SHARD.get(), 1), 1)); // what cleanses a scar
                }
            }
            case SEER -> {
                o.add(offer(light, 8, of(Items.ECHO_SHARD, 1), 3));
                if (kin) {
                    o.add(offer(Items.LANTERN, 4, of(Items.ENDER_EYE, 1), 2));
                }
            }
            case PATHFINDER -> {
                o.add(offer(food, 4, of(Items.COMPASS, 1), 4));
                if (welcome) {
                    map(o, level, s, "grotto", TunnelFeature::nearestGrotto);
                    map(o, level, s, "frozenfall", TunnelFeature::nearestFrozenFall);
                    map(o, level, s, "tidelake", TunnelFeature::nearestTideLake);
                }
                if (kin) {
                    map(o, level, s, "skeleton", TunnelFeature::nearestSkeleton);
                    map(o, level, s, "lavafall", TunnelFeature::nearestLavaFall);
                    map(o, level, s, "ventlung", TunnelFeature::nearestVentLung);
                }
            }
            case DELVER -> {
                o.add(offer(food, 6, of(Items.RAW_IRON, 4), 8));
                o.add(offer(light, 8, of(Items.COAL, 8), 8));
                if (welcome) {
                    o.add(offer(meat, 6, of(Items.RAW_GOLD, 4), 6));
                }
                if (kin) {
                    o.add(offer(meat, 12, Items.LANTERN, 2, of(AbyssalDepths.NUMINOR_ORE.get(), 1), 2)); // they know where it forms
                }
            }
            case STORMWATCHER -> {
                o.add(offer(food, 6, of(AbyssalDepths.STORM_SHARD.get(), 2), 8));
                if (welcome) {
                    o.add(offer(AbyssalDepths.STORM_SHARD.get(), 6, meat, 4, of(AbyssalDepths.STORM_GOGGLES.get(), 1), 2));
                }
                if (kin) {
                    o.add(offer(light, 12, of(AbyssalDepths.STORM_SHARD.get(), 6), 4));
                }
            }
            case WANDERER -> {
                o.add(offer(food, 3, of(AbyssalDepths.GLOAM_MOSS.get(), 4), 4));
                o.add(offer(light, 4, of(Items.STRING, 6), 4));
            }
            case OUTCAST -> { // odd things, few
                final Item[] odd = {Items.ENDER_PEARL, Items.NAUTILUS_SHELL, Items.MUSIC_DISC_OTHERSIDE, Items.AMETHYST_SHARD, Items.GHAST_TEAR};
                final java.util.Random r = new java.util.Random(s.getUUID().getLeastSignificantBits());
                o.add(offer(meat, 8 + r.nextInt(8), of(odd[r.nextInt(odd.length)], 1), 1));
                o.add(offer(Items.LANTERN, 2 + r.nextInt(3), of(odd[r.nextInt(odd.length)], 1), 1));
            }
            default -> {
            }
        }
        return o;
    }

    /** A map with the way to the nearest set piece of a kind (marked with a cross), for food and light. */
    private static void map(MerchantOffers o, ServerLevel level, Shadeborn s, String name, BiFunction<ServerLevel, BlockPos, BlockPos> find) {
        final BlockPos at = find.apply(level, s.blockPosition());
        if (at == null) {
            return;
        }
        final ItemStack map = MapItem.create(level, at.getX(), at.getZ(), (byte) 2, true, true);
        MapItemSavedData.addTargetDecoration(map, at, "+", MapDecorationTypes.RED_X);
        map.set(DataComponents.ITEM_NAME, Component.translatable("item.abyssaldepths.shadeborn_map." + name));
        o.add(offer(Items.BREAD, 8, Items.TORCH, 8, map, 1));
    }
}
