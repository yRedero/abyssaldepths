package redero.abyssaldepths;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;

import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import redero.abyssaldepths.worldgen.TunnelFeature;

@Mod(AbyssalDepths.MODID)
public class AbyssalDepths {
    public static final String MODID = "abyssaldepths";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, MODID);
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MODID);

    /** Icon: textures/mob_effect/deep_pressure.png */
    public static final DeferredHolder<MobEffect, DeepPressure> DEEP_PRESSURE = MOB_EFFECTS.register("deep_pressure", DeepPressure::new);
    /** Ticks a player has spent in the thalan layer without Deep Pressure, see {@link DeepPressure}. */
    public static final Supplier<AttachmentType<Integer>> TICKS_BELOW = ATTACHMENT_TYPES.register("ticks_below",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());

    // ambience of the thalan layer, files in assets/abyssaldepths/sounds.json
    public static final DeferredHolder<SoundEvent, SoundEvent> AMBIENT_LOOP = sound("ambient.thalan_layer.loop");
    public static final DeferredHolder<SoundEvent, SoundEvent> AMBIENT_DRIP = sound("ambient.thalan_layer.drip");

    /** Carves the tunnel layer. Added to all overworld biomes by data/abyssaldepths/neoforge/biome_modifier. */
    public static final DeferredHolder<Feature<?>, TunnelFeature> TUNNEL_LAYER =
            FEATURES.register("tunnel_layer", () -> new TunnelFeature(NoneFeatureConfiguration.CODEC));

    /** Main stone below y=-64. A pillar like deepslate, harder (deepslate: 3 / 6). Textures: thalan, thalan_top. */
    public static final DeferredBlock<RotatedPillarBlock> THALAN = BLOCKS.registerBlock("thalan", RotatedPillarBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DEEPSLATE)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops()
                    .strength(5.0F, 9.0F)
                    .sound(SoundType.DEEPSLATE));
    public static final DeferredItem<BlockItem> THALAN_ITEM = ITEMS.registerSimpleBlockItem(THALAN);

    // Building blocks, the full deepslate set. Models, loot, recipes and tags are the vanilla deepslate ones renamed.
    public static final DeferredBlock<Block> COBBLED_THALAN = building("cobbled_thalan", Blocks.COBBLED_DEEPSLATE);
    public static final DeferredBlock<StairBlock> COBBLED_THALAN_STAIRS = stairs("cobbled_thalan_stairs", COBBLED_THALAN);
    public static final DeferredBlock<SlabBlock> COBBLED_THALAN_SLAB = slab("cobbled_thalan_slab", COBBLED_THALAN);
    public static final DeferredBlock<WallBlock> COBBLED_THALAN_WALL = wall("cobbled_thalan_wall", COBBLED_THALAN);
    public static final DeferredBlock<Block> POLISHED_THALAN = building("polished_thalan", Blocks.POLISHED_DEEPSLATE);
    public static final DeferredBlock<StairBlock> POLISHED_THALAN_STAIRS = stairs("polished_thalan_stairs", POLISHED_THALAN);
    public static final DeferredBlock<SlabBlock> POLISHED_THALAN_SLAB = slab("polished_thalan_slab", POLISHED_THALAN);
    public static final DeferredBlock<WallBlock> POLISHED_THALAN_WALL = wall("polished_thalan_wall", POLISHED_THALAN);
    public static final DeferredBlock<Block> THALAN_BRICKS = building("thalan_bricks", Blocks.DEEPSLATE_BRICKS);
    public static final DeferredBlock<Block> CRACKED_THALAN_BRICKS = building("cracked_thalan_bricks", Blocks.CRACKED_DEEPSLATE_BRICKS);
    public static final DeferredBlock<StairBlock> THALAN_BRICK_STAIRS = stairs("thalan_brick_stairs", THALAN_BRICKS);
    public static final DeferredBlock<SlabBlock> THALAN_BRICK_SLAB = slab("thalan_brick_slab", THALAN_BRICKS);
    public static final DeferredBlock<WallBlock> THALAN_BRICK_WALL = wall("thalan_brick_wall", THALAN_BRICKS);
    public static final DeferredBlock<Block> THALAN_TILES = building("thalan_tiles", Blocks.DEEPSLATE_TILES);
    public static final DeferredBlock<Block> CRACKED_THALAN_TILES = building("cracked_thalan_tiles", Blocks.CRACKED_DEEPSLATE_TILES);
    public static final DeferredBlock<StairBlock> THALAN_TILE_STAIRS = stairs("thalan_tile_stairs", THALAN_TILES);
    public static final DeferredBlock<SlabBlock> THALAN_TILE_SLAB = slab("thalan_tile_slab", THALAN_TILES);
    public static final DeferredBlock<WallBlock> THALAN_TILE_WALL = wall("thalan_tile_wall", THALAN_TILES);
    public static final DeferredBlock<Block> CHISELED_THALAN = building("chiseled_thalan", Blocks.CHISELED_DEEPSLATE);

    // Thalan variants of the deepslate ores, placed by the vanilla ore features where they hit thalan
    // (data/minecraft/worldgen/configured_feature). Same behaviour as the deepslate ores they replace.
    // No coal, emerald or lapis: those do not generate in the thalan layer.
    public static final DeferredBlock<Block> THALAN_IRON_ORE = ore("thalan_iron_ore", Blocks.DEEPSLATE_IRON_ORE,
            p -> new DropExperienceBlock(ConstantInt.of(0), p));
    public static final DeferredBlock<Block> THALAN_COPPER_ORE = ore("thalan_copper_ore", Blocks.DEEPSLATE_COPPER_ORE,
            p -> new DropExperienceBlock(ConstantInt.of(0), p));
    public static final DeferredBlock<Block> THALAN_GOLD_ORE = ore("thalan_gold_ore", Blocks.DEEPSLATE_GOLD_ORE,
            p -> new DropExperienceBlock(ConstantInt.of(0), p));
    public static final DeferredBlock<Block> THALAN_REDSTONE_ORE = ore("thalan_redstone_ore", Blocks.DEEPSLATE_REDSTONE_ORE,
            RedStoneOreBlock::new);
    public static final DeferredBlock<Block> THALAN_DIAMOND_ORE = ore("thalan_diamond_ore", Blocks.DEEPSLATE_DIAMOND_ORE,
            p -> new DropExperienceBlock(UniformInt.of(3, 7), p));

    /** Blobs in the thalan layer, generated like tuff (data/abyssaldepths/worldgen). */
    public static final DeferredBlock<Block> SHALE = BLOCKS.registerSimpleBlock("shale",
            BlockBehaviour.Properties.ofFullCopy(Blocks.TUFF).mapColor(MapColor.COLOR_GRAY));
    public static final DeferredItem<BlockItem> SHALE_ITEM = ITEMS.registerSimpleBlockItem(SHALE);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_TABS.register("abyssaldepths",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.abyssaldepths"))
                    .icon(() -> THALAN_ITEM.get().getDefaultInstance())
                    .displayItems((params, output) -> ITEMS.getEntries().forEach(item -> output.accept(item.get())))
                    .build());

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, name)));
    }

    /** Sound etc. of the deepslate counterpart, as much harder than thalan as those are than deepslate (3.5 vs 3). */
    private static DeferredBlock<Block> building(String name, Block deepslateBlock) {
        return withItem(BLOCKS.register(name, () -> new Block(BlockBehaviour.Properties.ofFullCopy(deepslateBlock).strength(5.5F, 9.0F))));
    }

    private static DeferredBlock<StairBlock> stairs(String name, DeferredBlock<Block> base) {
        return withItem(BLOCKS.register(name, () -> new StairBlock(base.get().defaultBlockState(), BlockBehaviour.Properties.ofFullCopy(base.get()))));
    }

    private static DeferredBlock<SlabBlock> slab(String name, DeferredBlock<Block> base) {
        return withItem(BLOCKS.register(name, () -> new SlabBlock(BlockBehaviour.Properties.ofFullCopy(base.get()))));
    }

    private static DeferredBlock<WallBlock> wall(String name, DeferredBlock<Block> base) {
        return withItem(BLOCKS.register(name, () -> new WallBlock(BlockBehaviour.Properties.ofFullCopy(base.get()).forceSolidOn())));
    }

    private static <B extends Block> DeferredBlock<B> withItem(DeferredBlock<B> block) {
        ITEMS.registerSimpleBlockItem(block);
        return block;
    }

    /** Ores keep the deepslate ore behaviour, but are as much harder than thalan as those are than deepslate. */
    private static DeferredBlock<Block> ore(String name, Block deepslateOre, Function<BlockBehaviour.Properties, Block> factory) {
        return withItem(BLOCKS.register(name,
                () -> factory.apply(BlockBehaviour.Properties.ofFullCopy(deepslateOre).strength(6.5F, 9.0F))));
    }

    public AbyssalDepths(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        FEATURES.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        MOB_EFFECTS.register(modEventBus);
        SOUND_EVENTS.register(modEventBus);
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
