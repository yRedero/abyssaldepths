package redero.abyssaldepths.fracture;

import java.util.Map;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.LootTableLoadEvent;
import redero.abyssaldepths.AbyssalDepths;

/** Where Cleansing Shards are found (they are never made): the chests of ancient cities and strongholds, rarely. */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class FractureLoot {
    /** Chest loot tables, with the chance of a shard in each chest. */
    private static final Map<ResourceLocation, Float> CHANCES = Map.of(
            ResourceLocation.withDefaultNamespace("chests/ancient_city"), 0.12F,
            ResourceLocation.withDefaultNamespace("chests/stronghold_library"), 0.10F,
            ResourceLocation.withDefaultNamespace("chests/stronghold_corridor"), 0.04F);

    private FractureLoot() {
    }

    @SubscribeEvent
    public static void onLootTable(LootTableLoadEvent event) {
        final Float chance = CHANCES.get(event.getName());
        if (chance != null) {
            event.getTable().addPool(LootPool.lootPool().name("abyssaldepths_cleansing_shard").setRolls(ConstantValue.exactly(1))
                    .when(LootItemRandomChanceCondition.randomChance(chance)).add(LootItem.lootTableItem(AbyssalDepths.CLEANSING_SHARD.get())).build());
        }
    }
}
