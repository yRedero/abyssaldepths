package redero.abyssaldepths;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import redero.abyssaldepths.worldgen.TunnelFeature;

@Mod(AbyssalDepths.MODID)
public class AbyssalDepths {
    public static final String MODID = "abyssaldepths";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, MODID);

    /** Carves the tunnel layer. Added to all overworld biomes by data/abyssaldepths/neoforge/biome_modifier. */
    public static final DeferredHolder<Feature<?>, TunnelFeature> TUNNEL_LAYER =
            FEATURES.register("tunnel_layer", () -> new TunnelFeature(NoneFeatureConfiguration.CODEC));

    public AbyssalDepths(IEventBus modEventBus) {
        FEATURES.register(modEventBus);
    }
}
