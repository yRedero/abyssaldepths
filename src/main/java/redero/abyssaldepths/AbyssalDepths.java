package redero.abyssaldepths;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;

import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.ColorRGBA;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ColoredFallingBlock;
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
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import redero.abyssaldepths.artifact.ArtifactFrame;
import redero.abyssaldepths.artifact.ArtifactItem;
import redero.abyssaldepths.artifact.ArtifactRank;
import redero.abyssaldepths.artifact.InnerHavenItem;
import redero.abyssaldepths.artifact.InterludeItem;
import redero.abyssaldepths.artifact.AegisItem;
import redero.abyssaldepths.artifact.AnchorItem;
import redero.abyssaldepths.artifact.ConvergenceItem;
import redero.abyssaldepths.artifact.CounterweightItem;
import redero.abyssaldepths.artifact.FirmamentItem;
import redero.abyssaldepths.artifact.RecallItem;
import redero.abyssaldepths.artifact.RepulsionItem;
import redero.abyssaldepths.artifact.MirageItem;
import redero.abyssaldepths.artifact.OblivionItem;
import redero.abyssaldepths.artifact.SeveranceItem;
import redero.abyssaldepths.artifact.UndertowItem;
import redero.abyssaldepths.entity.MirageDecoy;
import redero.abyssaldepths.entity.UndertowEntity;
import redero.abyssaldepths.artifact.ParallaxItem;
import redero.abyssaldepths.artifact.RepriseItem;
import redero.abyssaldepths.entity.AbsentBody;
import redero.abyssaldepths.entity.Seat;
import redero.abyssaldepths.block.MeditationMatBlock;
import redero.abyssaldepths.block.QuicksiltBlock;
import redero.abyssaldepths.block.SmoulderingRockBlock;
import redero.abyssaldepths.block.ThermalVentBlock;
import redero.abyssaldepths.worldgen.TunnelFeature;

@Mod(AbyssalDepths.MODID)
public class AbyssalDepths {
    public static final String MODID = "abyssaldepths";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    /** Mastery of an artifact, 0 .. 100 %. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> MASTERY = DATA_COMPONENTS.registerComponentType("mastery",
            builder -> builder.persistent(ExtraCodecs.intRange(0, 100)).networkSynchronized(ByteBufCodecs.VAR_INT));
    /** Game time since which an artifact's charge has been below its maximum (for regenerating). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> CHARGE_TIME = DATA_COMPONENTS.registerComponentType("charge_time",
            builder -> builder.persistent(com.mojang.serialization.Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG));
    /** An artifact that is switched on (Interlude). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> ARTIFACT_ACTIVE = DATA_COMPONENTS.registerComponentType("artifact_active",
            builder -> builder.persistent(com.mojang.serialization.Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));
    /** Charge of an artifact, 0 .. its maximum (not every artifact has one). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> CHARGE = DATA_COMPONENTS.registerComponentType("charge",
            builder -> builder.persistent(ExtraCodecs.NON_NEGATIVE_INT).networkSynchronized(ByteBufCodecs.VAR_INT));
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, MODID);
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES = DeferredRegister.create(Registries.PARTICLE_TYPE, MODID);
    /** Dust of the dune storms: small fast grains, and big faint puffs of haze seen from further away. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> STORM_DUST = PARTICLE_TYPES.register("storm_dust", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> STORM_HAZE = PARTICLE_TYPES.register("storm_haze", () -> new SimpleParticleType(true));
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MODID);

    /** Icon: textures/mob_effect/deep_pressure.png */
    public static final DeferredHolder<MobEffect, DeepPressure> DEEP_PRESSURE = MOB_EFFECTS.register("deep_pressure", DeepPressure::new);
    /** Mirage under way: creatures overlook its holder (the effect is the sign of it; Mirage does the work). */
    public static final DeferredHolder<MobEffect, MobEffect> VEILED = MOB_EFFECTS.register("veiled",
            () -> new MobEffect(net.minecraft.world.effect.MobEffectCategory.BENEFICIAL, 0x8E52D6) {
            });
    /** Anchor under way: no knockback (the resistances cover explosions; Anchor cancels the rest). */
    public static final DeferredHolder<MobEffect, MobEffect> ANCHORED = MOB_EFFECTS.register("anchored",
            () -> new MobEffect(net.minecraft.world.effect.MobEffectCategory.BENEFICIAL, 0x727D87) {
            }.addAttributeModifier(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "anchored"), 1.0,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE)
                    .addAttributeModifier(net.minecraft.world.entity.ai.attributes.Attributes.EXPLOSION_KNOCKBACK_RESISTANCE,
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "anchored_explosion"), 1.0,
                            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
    /** Ticks a player has spent in the thalan layer without Deep Pressure, see {@link DeepPressure}. */
    public static final Supplier<AttachmentType<Integer>> TICKS_BELOW = ATTACHMENT_TYPES.register("ticks_below",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());

    // ambience of the thalan layer, played by the biome; files in assets/abyssaldepths/sounds.json
    public static final DeferredHolder<SoundEvent, SoundEvent> AMBIENT_LOOP = sound("ambient.thalan_layer.loop");
    public static final DeferredHolder<SoundEvent, SoundEvent> AMBIENT_DRIP = sound("ambient.thalan_layer.drip");
    /** Soft popping and bubbling of the thermal reaches, played by the biome. */
    public static final DeferredHolder<SoundEvent, SoundEvent> THERMAL_ADDITIONS = sound("ambient.thermal_reaches.additions");
    /** Ear ringing of Deep Pressure from stage 3 on. */
    public static final DeferredHolder<SoundEvent, SoundEvent> PRESSURE_RINGING = sound("effect.deep_pressure.ringing");
    // ambience of the layer: biome loops, sounds from somewhere in the halls, rare deep sounds
    public static final DeferredHolder<SoundEvent, SoundEvent> FLATS_LOOP = sound("ambient.shale_flats.loop");
    public static final DeferredHolder<SoundEvent, SoundEvent> DUNES_LOOP = sound("ambient.silt_dunes.loop");
    public static final DeferredHolder<SoundEvent, SoundEvent> THERMAL_LOOP = sound("ambient.thermal_reaches.loop");
    public static final DeferredHolder<SoundEvent, SoundEvent> FLATS_CRACK = sound("ambient.shale_flats.crack");
    public static final DeferredHolder<SoundEvent, SoundEvent> ROCKFALL = sound("ambient.thalan_layer.rockfall");
    public static final DeferredHolder<SoundEvent, SoundEvent> DUNES_SLIDE = sound("ambient.silt_dunes.slide");
    public static final DeferredHolder<SoundEvent, SoundEvent> VENT_HISS = sound("ambient.thermal_reaches.hiss");
    public static final DeferredHolder<SoundEvent, SoundEvent> DEEP_GROAN = sound("ambient.thalan_layer.groan");
    public static final DeferredHolder<SoundEvent, SoundEvent> DISTANT_CREATURE = sound("ambient.thalan_layer.distant_creature");
    public static final DeferredHolder<SoundEvent, SoundEvent> DISTANT_STEPS = sound("ambient.thalan_layer.distant_steps");
    public static final DeferredHolder<SoundEvent, SoundEvent> GUST = sound("ambient.thalan_layer.gust");
    public static final DeferredHolder<SoundEvent, SoundEvent> DUNE_STORM = sound("ambient.silt_dunes.storm");
    /** The ambience of the inner self (placeholder from vanilla sounds, see sounds.json). */
    public static final DeferredHolder<SoundEvent, SoundEvent> INNER_SELF_LOOP = sound("ambient.inner_self.loop");

    /** Biome of the thalan layer (data/abyssaldepths/worldgen/biome), it brings the layer's features and sounds. */
    public static final ResourceKey<Biome> THALAN_DEPTHS = biome("thalan_depths");
    /** Dry, open plains of flat fractured shale. */
    public static final ResourceKey<Biome> SHALE_FLATS = biome("shale_flats");
    /** Rolling dunes of pale silt over the dark thalan. */
    public static final ResourceKey<Biome> SILT_DUNES = biome("silt_dunes");
    /** Geothermally active: stained rock, steaming vents, shallow mineral pools. */
    public static final ResourceKey<Biome> THERMAL_REACHES = biome("thermal_reaches");
    /** Climate depth below y=-64, set in the overworld noise router; see mixin.OverworldBiomeBuilderMixin. */
    public static final float THALAN_DEPTH = 3.0F;

    /** Carves the tunnel layer. Part of the thalan depths biome, which covers every chunk below y=-64. */
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
    /** Thalan with faintly glowing mineral specks, scattered sparsely over the walls of the layer. */
    public static final DeferredBlock<Block> GLIMMERING_THALAN = withItem(BLOCKS.registerSimpleBlock("glimmering_thalan",
            BlockBehaviour.Properties.of().mapColor(MapColor.DEEPSLATE).instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(5.0F, 9.0F).sound(SoundType.DEEPSLATE)
                    .lightLevel(state -> 2).emissiveRendering((state, level, pos) -> true)));

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
    /**
     * Rare ore of the thalan layer only: small veins, drops nuggets. Its purple veins are drawn at full
     * brightness (a second model layer), so they glow in the dark without emitting light.
     */
    public static final DeferredBlock<Block> NUMINOR_ORE = ore("numinor_ore", Blocks.DEEPSLATE_DIAMOND_ORE,
            p -> new DropExperienceBlock(UniformInt.of(2, 5), p));
    public static final DeferredItem<Item> NUMINOR_NUGGET = ITEMS.registerSimpleItem("numinor_nugget");
    public static final DeferredItem<Item> NUMINOR_INGOT = ITEMS.registerSimpleItem("numinor_ingot");

    // artifacts (see artifact/): Inner Haven is the first one, its powers come later
    /** Carries its holder into their inner self and back; 5 charges, 2 per crossing, 1 back every 10 minutes. */
    public static final DeferredItem<InnerHavenItem> INNER_HAVEN = ITEMS.register("inner_haven",
            () -> new InnerHavenItem(new Item.Properties(), ArtifactRank.PRIMORDIAL, ArtifactFrame.ZENITH, 5, 20 * 60 * 10));

    /** Remembers the last 120 seconds of the world while carried, and takes everything back to them; 1 charge, 30 minutes. */
    public static final DeferredItem<RepriseItem> REPRISE = ITEMS.register("reprise",
            () -> new RepriseItem(new Item.Properties(), ArtifactRank.PRIMORDIAL, ArtifactFrame.ZENITH, 1, 20 * 60 * 30));

    /** Slows everything around its holder to half speed while switched on; 10 charges, 1 used every 2 s, 1 back every 30 s. */
    public static final DeferredItem<InterludeItem> INTERLUDE = ITEMS.register("interlude",
            () -> new InterludeItem(new Item.Properties(), ArtifactRank.GREATER, ArtifactFrame.HOLLOW, 10, 20 * 30));

    /** Sends everything alive around its user into the shadow for 30 s (the strong for less); 3 charges, 1 back every 30 minutes. */
    public static final DeferredItem<OblivionItem> OBLIVION = ITEMS.register("oblivion",
            () -> new OblivionItem(new Item.Properties(), ArtifactRank.PRIMORDIAL, ArtifactFrame.HOLLOW, 3, 20 * 60 * 30));

    /** Opens a gravitational anomaly that draws creatures in, then bursts; 3 charges, 1 back every 5 minutes. */
    public static final DeferredItem<UndertowItem> UNDERTOW = ITEMS.register("undertow",
            () -> new UndertowItem(new Item.Properties(), ArtifactRank.PRIMORDIAL, ArtifactFrame.HOLLOW, 3, 20 * 60 * 5));

    /** Lightens its holder while switched on (lower gravity, less fall damage); 10 charges, 1 back every 30 s. */
    public static final DeferredItem<CounterweightItem> COUNTERWEIGHT = ITEMS.register("counterweight",
            () -> new CounterweightItem(new Item.Properties(), ArtifactRank.GREATER, ArtifactFrame.ZENITH, 10, 20 * 30));

    /** Cuts away every harmful effect; 3 charges, 1 back every 5 minutes (sooner with mastery). */
    public static final DeferredItem<SeveranceItem> SEVERANCE = ITEMS.register("severance",
            () -> new SeveranceItem(new Item.Properties(), ArtifactRank.LESSER, ArtifactFrame.ZENITH, 3, 20 * 60 * 5));

    /** Leaves a decoy likeness that draws the creatures; 2 charges, 1 back every 3 minutes. */
    public static final DeferredItem<MirageItem> MIRAGE = ITEMS.register("mirage",
            () -> new MirageItem(new Item.Properties(), ArtifactRank.EXALTED, ArtifactFrame.HOLLOW, 2, 20 * 60 * 3));

    /** Makes its holder immovable for a while (no knockback); 3 charges, 1 back every 2 minutes. */
    public static final DeferredItem<AnchorItem> ANCHOR = ITEMS.register("anchor",
            () -> new AnchorItem(new Item.Properties(), ArtifactRank.LESSER, ArtifactFrame.HOLLOW, 3, 20 * 60 * 2));

    /** Lets its holder walk through blocks while switched on; 10 charges, 1 back every minute. */
    public static final DeferredItem<ConvergenceItem> CONVERGENCE = ITEMS.register("convergence",
            () -> new ConvergenceItem(new Item.Properties(), ArtifactRank.EXALTED, ArtifactFrame.HOLLOW, 10, 20 * 60));

    /** A force field round its holder while switched on; 10 charges, 1 back every minute. */
    public static final DeferredItem<AegisItem> AEGIS = ITEMS.register("aegis",
            () -> new AegisItem(new Item.Properties(), ArtifactRank.PRIMORDIAL, ArtifactFrame.ZENITH, 10, 20 * 60));

    /** Takes its holder home (bed, anchor or world spawn) after a short hold; 1 charge, back after 10 minutes (sooner with mastery). */
    public static final DeferredItem<RecallItem> RECALL = ITEMS.register("recall",
            () -> new RecallItem(new Item.Properties(), ArtifactRank.LESSER, ArtifactFrame.HOLLOW, 1, 20 * 60 * 10));

    /** A pressure wave throwing everything around away; 5 charges, 1 back every 30 s. */
    public static final DeferredItem<RepulsionItem> REPULSION = ITEMS.register("repulsion",
            () -> new RepulsionItem(new Item.Properties(), ArtifactRank.GREATER, ArtifactFrame.ZENITH, 5, 20 * 30));

    /** Bears its holder on the air while right click is held (one charge a hold); 5 charges, 1 back every minute. */
    public static final DeferredItem<FirmamentItem> FIRMAMENT = ITEMS.register("firmament",
            () -> new FirmamentItem(new Item.Properties(), ArtifactRank.EXALTED, ArtifactFrame.ZENITH, 5, 20 * 60));

    /** Makes its holder a shade in the shadow of this world while switched on; 10 charges, 1 used every 6 s, 1 back every minute. */
    public static final DeferredItem<ParallaxItem> PARALLAX = ITEMS.register("parallax",
            () -> new ParallaxItem(new Item.Properties(), ArtifactRank.EXALTED, ArtifactFrame.HOLLOW, 10, 20 * 60));

    /** The walls of the rooms of the inner self: pure white, can not be broken. */
    public static final DeferredBlock<Block> HAVEN_WALL = withItem(BLOCKS.registerSimpleBlock("haven_wall",
            BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).strength(-1.0F, 3600000.0F).noLootTable()
                    .isValidSpawn((state, level, pos, type) -> false).pushReaction(PushReaction.BLOCK)
                    .lightLevel(state -> 0).emissiveRendering((state, level, pos) -> true)));

    /** A mat to sit on; at the heart of the island inner self. */
    public static final DeferredBlock<MeditationMatBlock> MEDITATION_MAT = withItem(BLOCKS.registerBlock("meditation_mat", MeditationMatBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.WHITE_CARPET).mapColor(MapColor.COLOR_RED)));

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    /** The body left behind while its player is in their inner self. */
    public static final DeferredHolder<EntityType<?>, EntityType<AbsentBody>> ABSENT_BODY = ENTITY_TYPES.register("absent_body",
            () -> EntityType.Builder.<AbsentBody>of(AbsentBody::new, MobCategory.MISC).sized(0.6F, 1.8F).clientTrackingRange(10)
                    .build("absent_body"));
    /** Undertow's anomaly (particles only). */
    public static final DeferredHolder<EntityType<?>, EntityType<UndertowEntity>> UNDERTOW_ENTITY = ENTITY_TYPES.register("undertow",
            () -> EntityType.Builder.<UndertowEntity>of(UndertowEntity::new, MobCategory.MISC).sized(0.5F, 0.5F).noSummon().clientTrackingRange(8)
                    .build("undertow"));
    /** Mirage's decoy: a likeness of its player. */
    public static final DeferredHolder<EntityType<?>, EntityType<MirageDecoy>> MIRAGE_DECOY = ENTITY_TYPES.register("mirage_decoy",
            () -> EntityType.Builder.<MirageDecoy>of(MirageDecoy::new, MobCategory.MISC).sized(0.6F, 1.8F).noSummon().clientTrackingRange(10)
                    .build("mirage_decoy"));
    /** What a player sits on on a meditation mat (invisible). */
    public static final DeferredHolder<EntityType<?>, EntityType<Seat>> SEAT = ENTITY_TYPES.register("seat",
            () -> EntityType.Builder.<Seat>of(Seat::new, MobCategory.MISC).sized(0.01F, 0.01F).noSummon().clientTrackingRange(10)
                    .build("seat"));

    /** Blobs in the thalan layer, generated like tuff (data/abyssaldepths/worldgen). */
    public static final DeferredBlock<Block> SHALE = BLOCKS.registerSimpleBlock("shale",
            BlockBehaviour.Properties.ofFullCopy(Blocks.TUFF).mapColor(MapColor.COLOR_GRAY));
    public static final DeferredItem<BlockItem> SHALE_ITEM = ITEMS.registerSimpleBlockItem(SHALE);

    // shale flats: shale stays the ground, these two support it
    public static final DeferredBlock<Block> FRACTURED_SHALE = withItem(BLOCKS.registerSimpleBlock("fractured_shale",
            BlockBehaviour.Properties.ofFullCopy(Blocks.TUFF).mapColor(MapColor.COLOR_BLACK)));
    public static final DeferredBlock<Block> SHALE_RUBBLE = withItem(BLOCKS.registerSimpleBlock("shale_rubble",
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(0.8F).sound(SoundType.GRAVEL)));

    // thermal reaches: heat-stained rock, decorative steam vents, oxide around vents and pools
    public static final DeferredBlock<Block> THERMAL_ROCK = withItem(BLOCKS.registerSimpleBlock("thermal_rock",
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_GRAY).instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(2.0F, 6.0F).sound(SoundType.DEEPSLATE)));
    public static final DeferredBlock<ThermalVentBlock> THERMAL_VENT = withItem(BLOCKS.registerBlock("thermal_vent",
            ThermalVentBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_BLACK)
                    .instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(2.0F, 6.0F).sound(SoundType.DEEPSLATE)));
    /** Glowing through its cracks, at the bottom of fissures and craters; burns like magma. */
    public static final DeferredBlock<SmoulderingRockBlock> SMOULDERING_ROCK = withItem(BLOCKS.registerBlock("smouldering_rock",
            SmoulderingRockBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.NETHER).instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(2.0F, 6.0F).sound(SoundType.DEEPSLATE)
                    .lightLevel(state -> 5).emissiveRendering((state, level, pos) -> true)
                    .isValidSpawn((state, level, pos, type) -> type.fireImmune())));
    public static final DeferredBlock<Block> OXIDE_DEPOSIT = withItem(BLOCKS.registerSimpleBlock("oxide_deposit",
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_RED).instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(1.2F, 4.0F).sound(SoundType.TUFF)));

    /** Pale mineral precipitated by the hot water: terraces, chimneys, wall crusts. */
    public static final DeferredBlock<Block> SINTER = withItem(BLOCKS.registerSimpleBlock("sinter",
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(1.2F, 4.0F).sound(SoundType.CALCITE)));
    /** Thermal mineral around the vents, drops sulfur. */
    public static final DeferredBlock<DropExperienceBlock> SULFUR_DEPOSIT = withItem(BLOCKS.registerBlock("sulfur_deposit",
            p -> new DropExperienceBlock(UniformInt.of(0, 2), p), BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_YELLOW)
                    .instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.5F, 4.0F).sound(SoundType.TUFF)));
    /** Mats of heat loving microbes around the warm pools. */
    public static final DeferredBlock<CarpetBlock> THERMOPHILE_MAT = withItem(BLOCKS.registerBlock("thermophile_mat", CarpetBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_YELLOW).strength(0.1F).sound(SoundType.MOSS_CARPET)
                    .pushReaction(PushReaction.DESTROY)));
    public static final DeferredItem<Item> SULFUR = ITEMS.registerSimpleItem("sulfur");

    // silt dunes: fine silt falls like sand, packed silt holds the slopes, siltstone is the hardened sediment
    public static final DeferredBlock<ColoredFallingBlock> FINE_SILT = withItem(BLOCKS.registerBlock("fine_silt",
            p -> new ColoredFallingBlock(new ColorRGBA(0xff59585a), p),
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY).instrument(NoteBlockInstrument.SNARE)
                    .strength(0.5F).sound(SoundType.SAND)));
    /** Loose silt in the dips between the dunes: living things sink into it slowly. */
    public static final DeferredBlock<QuicksiltBlock> QUICKSILT = withItem(BLOCKS.registerBlock("quicksilt", QuicksiltBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY).instrument(NoteBlockInstrument.SNARE)
                    .strength(0.5F).sound(SoundType.SAND).isSuffocating((state, level, pos) -> true)
                    .isViewBlocking((state, level, pos) -> true).isValidSpawn((state, level, pos, type) -> false)));
    public static final DeferredBlock<Block> PACKED_SILT = withItem(BLOCKS.registerSimpleBlock("packed_silt",
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(0.9F).sound(SoundType.PACKED_MUD)));
    public static final DeferredBlock<Block> SILTSTONE = withItem(BLOCKS.registerSimpleBlock("siltstone",
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(1.5F, 6.0F).sound(SoundType.STONE)));
    /** Siltstone with bones in it, exposed in collapsed basins and cavities of the dunes; drops bones. */
    public static final DeferredBlock<Block> FOSSILIZED_SILTSTONE = withItem(BLOCKS.registerSimpleBlock("fossilized_siltstone",
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(1.5F, 6.0F).sound(SoundType.BONE_BLOCK)));

    // Siltstone building blocks, the thalan set without cobbled and cracked versions: siltstone itself takes the place of cobbled.
    public static final DeferredBlock<StairBlock> SILTSTONE_STAIRS = stairs("siltstone_stairs", SILTSTONE);
    public static final DeferredBlock<SlabBlock> SILTSTONE_SLAB = slab("siltstone_slab", SILTSTONE);
    public static final DeferredBlock<WallBlock> SILTSTONE_WALL = wall("siltstone_wall", SILTSTONE);
    public static final DeferredBlock<Block> POLISHED_SILTSTONE = siltBuilding("polished_siltstone");
    public static final DeferredBlock<StairBlock> POLISHED_SILTSTONE_STAIRS = stairs("polished_siltstone_stairs", POLISHED_SILTSTONE);
    public static final DeferredBlock<SlabBlock> POLISHED_SILTSTONE_SLAB = slab("polished_siltstone_slab", POLISHED_SILTSTONE);
    public static final DeferredBlock<WallBlock> POLISHED_SILTSTONE_WALL = wall("polished_siltstone_wall", POLISHED_SILTSTONE);
    public static final DeferredBlock<Block> SILTSTONE_BRICKS = siltBuilding("siltstone_bricks");
    public static final DeferredBlock<StairBlock> SILTSTONE_BRICK_STAIRS = stairs("siltstone_brick_stairs", SILTSTONE_BRICKS);
    public static final DeferredBlock<SlabBlock> SILTSTONE_BRICK_SLAB = slab("siltstone_brick_slab", SILTSTONE_BRICKS);
    public static final DeferredBlock<WallBlock> SILTSTONE_BRICK_WALL = wall("siltstone_brick_wall", SILTSTONE_BRICKS);
    public static final DeferredBlock<Block> SILTSTONE_TILES = siltBuilding("siltstone_tiles");
    public static final DeferredBlock<StairBlock> SILTSTONE_TILE_STAIRS = stairs("siltstone_tile_stairs", SILTSTONE_TILES);
    public static final DeferredBlock<SlabBlock> SILTSTONE_TILE_SLAB = slab("siltstone_tile_slab", SILTSTONE_TILES);
    public static final DeferredBlock<WallBlock> SILTSTONE_TILE_WALL = wall("siltstone_tile_wall", SILTSTONE_TILES);
    public static final DeferredBlock<Block> CHISELED_SILTSTONE = siltBuilding("chiseled_siltstone");

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_TABS.register("abyssaldepths",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.abyssaldepths"))
                    .icon(() -> THALAN_ITEM.get().getDefaultInstance())
                    .displayItems((params, output) -> ITEMS.getEntries().stream().filter(item -> !(item.get() instanceof ArtifactItem))
                            .forEach(item -> output.accept(item.get())))
                    .build());
    /** The artifacts, in a tab of their own (all of them carry the item tag abyssaldepths:artifacts too). */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ARTIFACTS_TAB = CREATIVE_TABS.register("artifacts",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.abyssaldepths.artifacts"))
                    .icon(() -> INNER_HAVEN.get().getDefaultInstance())
                    .withTabsBefore(ResourceLocation.fromNamespaceAndPath(MODID, "abyssaldepths"))
                    .displayItems((params, output) -> ITEMS.getEntries().stream().filter(item -> item.get() instanceof ArtifactItem)
                            .forEach(item -> output.accept(item.get())))
                    .build());

    private static ResourceKey<Biome> biome(String name) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MODID, name));
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, name)));
    }

    /** Sound etc. of the deepslate counterpart, as much harder than thalan as those are than deepslate (3.5 vs 3). */
    private static DeferredBlock<Block> building(String name, Block deepslateBlock) {
        return withItem(BLOCKS.register(name, () -> new Block(BlockBehaviour.Properties.ofFullCopy(deepslateBlock).strength(5.5F, 9.0F))));
    }

    /** Worked siltstone: like the raw stone, a bit harder (siltstone: 1.5 / 6). */
    private static DeferredBlock<Block> siltBuilding(String name) {
        return withItem(BLOCKS.register(name, () -> new Block(BlockBehaviour.Properties.ofFullCopy(SILTSTONE.get()).strength(2.0F, 6.0F))));
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

    public AbyssalDepths(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener((net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) ->
                {
                    event.put(ABSENT_BODY.get(), net.minecraft.world.entity.LivingEntity.createLivingAttributes().build());
                    event.put(MIRAGE_DECOY.get(), net.minecraft.world.entity.LivingEntity.createLivingAttributes().build());
                });
        FEATURES.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        MOB_EFFECTS.register(modEventBus);
        SOUND_EVENTS.register(modEventBus);
        ATTACHMENT_TYPES.register(modEventBus);
        PARTICLE_TYPES.register(modEventBus);
    }
}
