package redero.abyssaldepths;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import redero.abyssaldepths.worldgen.TunnelFeature;

@Mod(AbyssalDepths.MODID)
public class AbyssalDepths {
    public static final String MODID = "abyssaldepths";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, MODID);

    /** Carves the tunnel layer. Added to all overworld biomes by data/abyssaldepths/neoforge/biome_modifier. */
    public static final DeferredHolder<Feature<?>, TunnelFeature> TUNNEL_LAYER =
            FEATURES.register("tunnel_layer", () -> new TunnelFeature(NoneFeatureConfiguration.CODEC));

    /** Texture: assets/abyssaldepths/textures/block/thalan.png */
    public static final DeferredBlock<Block> THALAN = BLOCKS.registerSimpleBlock("thalan",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DEEPSLATE)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F, 6.0F)
                    .sound(SoundType.DEEPSLATE));
    public static final DeferredItem<BlockItem> THALAN_ITEM = ITEMS.registerSimpleBlockItem(THALAN);

    public AbyssalDepths(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        FEATURES.register(modEventBus);
        modEventBus.addListener(AbyssalDepths::addCreative);
    }

    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            event.accept(THALAN_ITEM);
        }
    }
}
