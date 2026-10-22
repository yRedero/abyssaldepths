package redero.abyssaldepths;

import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import redero.abyssaldepths.worldgen.TunnelLayer;

/**
 * Natural spawning in the thalan layer: only mobs in the entity type tag
 * {@code abyssaldepths:thalan_layer_spawns} (data/abyssaldepths/tags/entity_type) are allowed.
 * The biome spawn lists are filtered before a mob is picked, so the allowed ones keep their weights.
 * Spawners and spawn eggs are not affected.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class ThalanLayerSpawns {
    public static final TagKey<EntityType<?>> WHITELIST =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "thalan_layer_spawns"));

    private ThalanLayerSpawns() {
    }

    @SubscribeEvent
    public static void onPotentialSpawns(LevelEvent.PotentialSpawns event) {
        if (event.getPos().getY() > TunnelLayer.LAYER_TOP_Y) {
            return;
        }
        // copy, the list is modified while removing
        for (var spawn : List.copyOf(event.getSpawnerDataList())) {
            if (!spawn.type.is(WHITELIST)) {
                event.removeSpawnerData(spawn);
            }
        }
    }
}
