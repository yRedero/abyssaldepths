# Project Design Brief — Minecraft Mod

**Purpose of this document:** Give Claude the broader creative and design context behind the mod, so that implementation decisions are informed by the world, progression, tone and long-term goals—not just by isolated feature requests.

**Important:** This document combines established design decisions, current working concepts and unfinished ideas. It is not a claim that every described feature is already implemented. When working on the codebase, inspect the existing source, assets, registries and configuration first. The implementation is the source of truth for what currently exists; this document explains the intended direction and reasoning behind it.

---

## 1. The Project in One Paragraph

The mod is a large Minecraft expansion built around the contrast and relationship between light and darkness. It expands the world vertically and introduces two major, contrasting regions, their environments, inhabitants, resources, creatures, equipment and systems. The project should add a strong identity, atmosphere, exploration and progression while still feeling like Minecraft: blocks, survival, exploration, combat, crafting and player freedom remain central. The world contains a larger mystery involving an ancient catastrophe and two peoples, but players should be able to enjoy and progress through the gameplay without having to understand the lore. The most important design goal is to make new mechanics feel tangible and coherent in actual gameplay, rather than relying on abstract explanations or lore alone.

## 2. Design Philosophy and Working Principles

### 2.1 Minecraft should remain recognisable

New content should feel like a natural but ambitious extension of Minecraft. New dimensions, regions, blocks, mobs, items and systems are welcome, but they should have clear interactions with familiar gameplay. Avoid turning the mod into a completely different genre or adding complexity merely for its own sake.

A feature should ideally answer at least one practical gameplay question:

- What can the player do with it?
- What does it change in exploration, survival, combat, building or progression?
- What motivates the player to discover or use it?
- How does the player understand it without needing an external explanation?

Atmosphere and lore are important, but they should reinforce a feature rather than substitute for a meaningful mechanic.

### 2.2 Concrete mechanics over abstract concepts

Prefer mechanics that can be explained in a few clear sentences and translated into understandable Minecraft behaviour. Powerful visual effects are welcome, but they should communicate or accompany a gameplay function.

For example, an artifact should have a real active or passive ability—not merely reveal an invisible layer, change the player's perception or provide a puzzle effect unless that is explicitly requested. Do not turn every mysterious visual into a hidden dimension, secret world or perception mechanic. The player should be able to understand what an item actually does.

### 2.3 Strong atmosphere, restrained exposition

The mod should create atmosphere through the world itself: terrain, scale, sound, lighting, structures, particles, mobs, weather and environmental behaviour. Avoid explaining every mystery through dialogue or text. Discovery should often happen through exploration and observation.

The intended atmosphere can be strange, quiet, threatening or beautiful without constantly bombarding the player with enemies, effects or exposition. Empty space can be intentional, but it still needs visual composition, environmental detail or a reason to exist.

### 2.4 Meaningful progression without excessive restrictions

The player should be free to explore either side of the world. Some areas can be substantially more dangerous or demanding than others, but the design should not force a rigid story route unless a specific feature requires it. The light-side region is generally intended to be more approachable once discovered; the dark region is more demanding because of its environmental pressure and hostile elements.

Progression should reward exploration, preparation, experimentation and mastery. Do not make every useful item depend on mining a new ore. Structures should often reward unique items, materials, knowledge or access, while especially rich ore deposits should be placed behind meaningful locations or challenges.

### 2.5 Everything has an explainable origin (decided)

The user's main point from the worldbuilding videos (see the Obsidian vault): every block, creature, place and power should have an origin that can be explained — why it is here, what it lives on, what made it — even when the explanation stays unspoken in play. The living-world ideas (things doing something, stewardship over exploitation, fair consequences, restrained predators) are a **guideline**, not a rule for every feature. The layer's nature is **sparse and mostly dead**: a few creatures live off the layer's energy or its sparse plants; plants need no growth or feedback loops of their own.

- **Self-change:** where the layer does change on its own (creatures moving and so on), it happens **only in the wild**, never near blocks a player placed.
- **Grazers and hunters (decided direction, not built):** creatures that feed on the layer's energy or sparse plants, and hunters that prey on them with restraint (they eat, then rest); the player's food comes mostly from them. Designed **one creature at a time** (no rule of one per biome).
- **The layer's energy:** the world's two core energies, **light and darkness in their pure form**, are more common in the layer, and some creatures feed on them (the same two forces that slip into the soul in Fracturing). They also feed on the layer's other energies: the gloam's held light, the reaches' heat, the storms' charge. Pure light and darkness are not visible as such yet; how to show them is open (something creative wanted).

**World rules for the core energies (decided 2026-11-18):** **pure darkness consumes light and warmth** (pure light carries both); **the gloam is pure light settled into crystal**, like salt out of water: it grows where pure light gathers and rises.

### 2.6 Distinguish implementation status from design status

Some ideas in this brief are established; others are provisional. Never assume that a suggested ability, name, recipe, balancing value or visual effect is already final. Before changing an existing system:

1. Inspect how it currently works in code.
2. Identify the parts already implemented and any known constraints.
3. Preserve working behaviour unless the requested change explicitly replaces it.
4. Explain important assumptions before making a large architectural change.
5. Avoid silently replacing an established design with a new interpretation.

The user values iterative collaboration and precise interpretation. When a design detail is ambiguous, ask one focused question rather than inventing a large set of assumptions.

---

## 3. World Structure and the Two Major Regions

The world is organised around two contrasting sides or layers. Their names have evolved during development. **Thalan** and **Solit** are older working terms; **Hollow** and **Zenith** are later working names used in design notes. Check the current code and assets before renaming anything. For this document, “dark region” and “light region” are used where the exact naming is not important.

### 3.1 The dark region — Thalan / Hollow

The dark region is an enormous, ancient-feeling underground layer beneath the normal Overworld. It is not meant to be a conventional cave dimension filled with constant combat. Its atmosphere should be vast, quiet, strange and inhabited.

Established or strongly developed design points:

- The region extends downward into a new rock layer, with the planned lower boundary around **Y = -128**.
- Its natural base stone is a neutral Thalan-type rock. The region should feel geologically coherent rather than like a collection of unrelated fantasy blocks.
- The underground space is intended to be exceptionally large, with enormous hollows and cavernous areas. Much of the layer is already naturally cave-like rather than consisting of ordinary solid terrain with occasional small caves.
- The transition into the layer should be discoverable through mining and exploration, while still having a recognisable boundary.
- The environment is quiet and inhabited. Silence and distance are part of its identity.
- Staying in the region causes a special darkness-related effect, referred to in later notes as **Deep Pressure**. It begins after the player has spent some time there (roughly over a minute in the original concept), intensifies the longer the player remains and recedes only after leaving. It is intended to make long expeditions dangerous, especially early in progression. The approximate idea was that after around fifteen minutes the impairment could become severe; exact timing and effects are balance decisions, not immutable requirements.
- Preparation matters. Food, a suitable pickaxe and a bed or other respawn planning can make expeditions more manageable. Experienced players should still be able to handle the region.
- The region should feel dangerous but potentially rewarding. Resources are bleak and unusual, but exploration can be lucrative.

Biome and environment concepts include:

- **Shale Flats** — broad, relatively open areas associated with the region's shale-like stone family.
- **Silt Dunes** — a dune-like environment with fine sediment and a sandstorm concept. Sandstorms should have a visible and gameplay-relevant presence, not merely be a cosmetic overlay. **Storm crystals** (built): landmarks of the dunes, spires of storm crystal 3 to 5 blocks high standing on the silt with small crystals round their feet. **Storm shards** are for storm goggles, a crafting material, and a trading good with the Shadeborn tribes native to the dunes (decided). They charge only while a storm rages: crystals standing together wake as one, the light spreading from their lowest point (upwards first, outwards slower) and flashing at the top, draining back down the same way when the storm leaves; then it glows, draws the storm's lightning, and only then can it be broken (storm shards), so a storm is a reason to go out into it. **Storm dangers** (decided): low visibility (storm goggles to come), gusts shoving players along the wind (crouching braces them), silt grinding the armour of the unsheltered, lightning drawn to charged crystals (warned by crackling), burrowers surfacing and turning aggressive, and things the storm uncovers (some better left buried). Built: visibility, gusts, grinding silt, lightning; the burrowers and the uncovering wait for the creature and the buried caches.
- **Thermal Reaches** — a warmer or geothermally influenced area, with its own environmental blocks and features.

**Decided direction for the layer's identity (the gloam and the set pieces are built; camps not yet):**

- **Never safe, never deadly:** there should always be something that makes the player careful. Deep Pressure exists to say "this is no safe place, prepare first"; later things may negate most of it once it has done that job.
- **Food and light are scarce.** Food comes mostly from the layer's animals and a few rare plants (new berries and the like), not from fishing.
- **The gloam ties the layer together:** a mineral, a crystal that holds light (Gloam Crystal, Gloam Cluster: pure light settled into crystal), in seams glowing deep blue/violet in the rock. Living things do not glow of themselves: they live off the gloam, which is why life gathers round it (Gloam Moss on and around the seams; the giant **Duskcap** fungus with Dusk Stalk wood and Dusk Planks, whose caps give Duskthread, woven into Duskweave and Duskweave Mat). Only the crystal is called gloam; the moss's name may still change. Life gathers round the gloam pockets, water pools where it meets them, Shadeborn camps sit near them, Numinor forms where the gloam is strongest. Blue/violet colour appears only in the glows; stone and fog stay as they are. The Silt Dunes have no gloam (the silt keeps it buried). The biomes are stages of one system: the Thermal Reaches bring the heat up, the heat drives the winds that build the Silt Dunes, the Shale Flats are rock the heat cracked, the Thalan Depths are the coldest, where the gloam is faintest.
- **Water in places:** a few dark lakes and falls, rare enough to be landmarks. **Where it comes from (decided):** thalan and deepslate barely let water through; the overworld's groundwater seeps down through the rare cracks. So water in the layer always comes from above (drips, a thin fall from a ceiling crack) and collects at low points: every body of water needs a crack above it and a low point to gather in. Be careful with water: no more water features or water-based content than that.
- **Rare set pieces** (decided 2026-11-18, each with an origin that holds at every step and a unique find):
  - **The Stilled Fall** (Thalan Depths): seep water falls from a ceiling crack, carrying gloam grains washed from a seam; a seep of pure darkness behind it drew the heat out and froze it, and still does, so every new drop freezes and the fall *grows*. A haze of dark motes; lights go out near it. Find: **Stilled Gloam** from its oldest core.
  - **The Wellspring** (Thalan Depths, formerly the gloam grotto): pure light rises through the bed of a basin where seep water collects; most gloam settles here, into spires. Rising light motes; standing in them eases Deep Pressure. Find: **a vessel of pure light**.
  - **The Tidal Lake** (Thalan Depths, formerly the tide lake): a seep fall fills a lake; a natural **siphon** (a rock channel with a bend, from the lake down to a lower cave) drains it once the water rises over the bend, until air breaks it; then the seep refills it (a real ebb-and-flow spring). At low water the lakebed's ridge lies bare as a path to the island, a rock knob where the siphon's throat gurgles. Find: **cave pearls**, grown layer on layer round grains in the throat, where the water churns with every cycle (seep water carries only dissolved minerals and fine silt, so that is what gathers).
  - **The Dead Worm** (Silt Dunes, formerly the buried skeleton): a Thalan Worm dead long ago (why stays open). A worm has no bones; what lasts is what was hard: the armoured skin it wore (and sheds when hurt) and its fangs. So it lies as a hollow shell of hardened skin (**worm shell**), half sunk in the silt, curving the way it crawled, ringed where its segments met, broken in places; its jaw plates gape at the head. The wind buries it and the storms strip it bare again, more or less each time; the dry silt kept the shell. Find: **Worm Fangs**, the worm's summoning item, lying in the silt round its mouth, reachable only when a storm uncovers them.
  - **The Lava Fall** (Thermal Reaches): magma pushed up by the heat from below spread into a pocket in the rock over a tall hall (as rising magma spreads into weak layers) and broke through the roof; the lava falls into a cup standing in a lake of seep water: steam (it only happens where the heat comes from below, so only in the reaches). Find: **fire-hardened slag**.
  - **The Vent Lung** (Thermal Reaches): vents over a magma chamber whose pressure cracked the rock above it in rings; gas gathers until it lifts the inner ring and the burst runs outwards ring by ring. Find: **lung ash** thrown out with the bursts.
  - **The Fracture Canyon** (Shale Flats): the deepest crack the heat made in the flats, reaching down to the heat (lava at its bottom). Find: **heat-forged crystals** in its walls.
  - A few **camp graveyards** (old camps abandoned round a dead gloam pocket) wait for the camps. The **Fallen Spire** (a broken Solit pillar in the dark) waits until a Solit biome exists and only lies beneath one.
- **Camps** are built mainly of the layer's stone, with a new wood from the giant Duskcap fungi (Dusk Stalk; scarce, found in the richest gloam pockets) and Duskweave (fibre from the fungi and the gloam moss) for tents, curtains and mats. Shadeborn camps exist from the start, spread out; they grow stronger as the player progresses. A camp's find is supplies: a little food and light, what the layer lacks. (Camps are deferred for now.)

These are working biome concepts, not a complete final biome specification. Their visual identity should be supported by distinctive blocks, terrain shapes, ambient details and gameplay features. Avoid making them feel empty simply because they are sparse; their scale, silhouettes and environmental storytelling should do some of the work.

### 3.2 The light region — Solit / Zenith

The light-side region is the counterpart to the dark layer, but it should not simply be the same terrain recoloured brighter. It needs its own geography, traversal, hazards, resources and atmosphere.

One developed surface-biome concept uses enormous Solit pillars:

- Pillars may be around 10–20 blocks thick.
- They extend high into the sky, potentially reaching approximately Y = 420.
- Their lower parts can be relatively straight, while higher sections become less regular and may branch or form multiple vertical levels.
- The biome should be relatively small, making verticality more important than endless horizontal travel.
- Hazards and traversal challenges should make the height and pillar structure meaningful.

This is a distinctive example of the region's intended identity, not a final definition of every light-side biome.

### 3.3 The two sides should be distinct, not simple opposites

Hollow and Zenith should have recognisable identities, but avoid reducing every design choice to “dark versus bright.” Give each side its own material language, terrain, creatures, inhabitants, environmental rules and gameplay strengths. Their colour palettes can help identify them, but they should remain different places with their own logic.

---

## 4. Materials, Equipment and Progression

### 4.1 Main materials

The current working material concepts include:

- **Kerun:** A common foundational material found throughout both sides, as an **ore on both sides** (decided). It is used frequently in basic recipes and is broadly comparable to an iron-tier foundational resource. It is not intended to be exceptionally powerful or rare.
- **Numinor:** A rare material associated with the dark side / Hollow.
- **Luminor:** A rare material associated with the light side / Zenith.

Numinor and Luminor are provisional names but are established working terms. Their rarity and importance should make finding them meaningful. The dark-side ore was initially envisioned as roughly Netherite-tier in rarity, though final distribution and balance remain open.

### 4.2 Equipment progression

The overall equipment progression should have approximately three or four main material tiers. Vanilla equipment should remain useful and recognisable rather than becoming obsolete immediately.

Side-specific armour sets are intended to have their own identities and be made from their respective rare materials. They should be around diamond level or higher, depending on the exact tier and balancing. The strongest armour concept goes beyond Netherite and requires combining matching pieces from both sides: for example, one side's helmet with the corresponding piece from the other side to create an incomplete or intermediate result. A final catalyst material, made using both side-specific materials and an additional catalyst, is intended to be the most difficult bottleneck.

The important principle is that the final equipment should represent meaningful cooperation or synthesis between the two sides, not simply a higher number on another ingot.

### 4.3 Crafting stations and knowledge

The mod may use multiple crafting stations with different levels of complexity:

- Simple stations can be crafted by the player or found in structures.
- More advanced stations may need to be discovered through exploration or learned from the Shadeborn / Lightborn.
- Most stations should be placeable and usable by the player. A special station could be tied to a particular location if that is mechanically justified.
- Discoveries can unlock new crafting or gameplay features without forcing the player into a fixed side choice.

---

## 5. Peoples, Creatures and Environmental Life

### 5.1 Shadeborn and Lightborn

The two regions have associated peoples:

- **Shadeborn** inhabit the dark region.
- **Lightborn** are their counterpart on the light side.

Their histories are tied to an ancient catastrophe. They are survivors or descendants of peoples who were changed by that event. Their current identities and territories are distinct, and each side has limited reliable knowledge of the other. Stories about the other people may be vague, distorted or dismissed as rumours. The two groups were not necessarily always separate or fundamentally different in the way they are now.

Do not over-explain or prematurely settle this history. It is background for discovery, not a requirement for ordinary progression.

### 5.2 Camps, leaders and interaction

Shadeborn camps should make the dark region feel inhabited. Camps can be small, with a leader or central figure. Individual inhabitants may vary in attitude: some may be hostile, some neutral, some fearful or cautious. They are not intended to be uniformly powerful enemies.

Longer-term possibilities include:

- Trading with inhabitants.
- Building trust through actions or exchanges.
- Learning information or unlocking features.
- Eventually earning influence or becoming a leader.
- Using special NPCs to reveal selected parts of the story or offer unique services.

The people should primarily enrich the world and create local relationships. Their existence should not turn the whole mod into a mandatory faction or quest system.

### 5.2.1 The Shadeborn system (built 2026-11-18, first version, on the user's temporary model; texture variants to come)

**Built:** camps beside gloam pockets (open silt in the dunes) with a hearth, a store, tents and a watch post; members by region growing from a huddle (3) to a camp (6) to a hold (8) with world days (faster where a player is Kin); temperaments and posture; what they want held up, given for trust; trust per camp through the five stages; trades by role and stage; the Mender's healing, the Seer's vision (the "zooooooosh") pointing to the worm, the Pathfinder's maps to set pieces, storm goggles from the Stormwatcher; the Keeper accepting a player (a cutscene), whose camp then sends supplies; wanderers, outcasts in huts, raider bands, hostile camps; the Mourner hunting Remnants. Camps and outcast huts are real structures (`/locate structure abyssaldepths:shadeborn_camp` / `outcast_hut`). They keep together (the young in the middle, the rest round them, the fighters outside towards a threat); their day: work by day (watcher at the post, lamp-keeper tending the fire, trader by the store, forager out and back with food), the hearth in the evening, the tents at night. Trust shows: strangers warned off, the Tolerated nodded to, the Welcome waved at, the Kin bowed to; gifts only at the highest trust: the camp's Keeper is given its Kin Mark once and food by the foragers now and then. A Keeper's camp grows by the Keeper: duskweave for a tent more, a craft's item for a newcomer of that craft; a watcher escorts the Keeper on request. Gestures (nod, wave, bow, beckon, point, warn, sit) are first versions written into the user's model, to be refined in Blockbench. **Left for later:** the Vent Forge (not yet in the mod), the Weaver's capes, relics from the storms, the camp graveyards, the final trading screen (a first one built: only the trade part is theirs; the user's idea to weigh: the Keeper trades for the whole camp), a new model.

**Who they are:** hooded figures in Duskweave, faces hidden, a faint light in the eyes. Their history stays open.

**They do not speak the player's language.** Mood shows in posture (cowering, wary, open); wants show by holding up what they need.

**Camps** hold 3 to 8 Shadeborn, near the gloam, built of the layer's stone, Dusk Stalk and Duskweave. A camp has:
- a **hearth**;
- a **store**;
- **tents**;
- a **watch post**.

Camps differ by region (one people, regional: the biomes have their own variants of the Shadeborn in roles, gear and trades, e.g. the dune tribes, who trade in storm shards):
- **Dunes:** stormwatchers.
- **Thermal Reaches:** smiths at the vents.
- **Shale Flats:** delvers.
- **Thalan Depths:** seers and lamp-keepers.

**Each Shadeborn has a temperament:** fearful, wary, neutral or hostile. Some bands are hostile outright.

**Roles:**

| Role | What they do for the player |
|---|---|
| **Keeper** | the camp's leader; where trust is decided, and in the end the role the player can take |
| **Trader** | everyday trade (supplies, materials) |
| **Smith** | metalwork: teaches the Vent Forge, works Kerun and Numinor |
| **Weaver** | Duskweave work: linings, capes, bags, quivers |
| **Lamp-keeper** | keeps the camp's lights; trades and refills light (the layer's scarcest thing) |
| **Forager / Hunter** | goes out and comes back with food; met out hunting; trades food, shows where game is |
| **Mender** | heals, cures harmful effects, eases Deep Pressure for a while; perhaps the way to cleanse a Corruption Scar |
| **Seer** | senses the deep: warns of the worm, points the way to it; keeps a few fragments of the old stories (told sparingly) |
| **Pathfinder** | sells marks on the map (set pieces, rich pockets), can guide to them |
| **Delver** | mines; trades ores, knows where Numinor forms |
| **Stormwatcher** | dune camps: reads the storms, makes storm goggles, trades storm shards |
| **Watcher** | guards the camp; turns on whoever harms it |
| **Wanderer** | travels alone between camps; timid; the usual first contact |
| **Mourner** | tends the dead at the camp graveyards; perhaps lays Remnants to rest |
| **Young** | fearful, follow a trusted player round the camp |
| **Outcast** | lives apart; rare and odd trades; sometimes hostile |
| **Raider** | hostile bands that prey on camps (and players) |

**Trust is kept per camp** and rises through five stages:

| Stage | Unlocks |
|---|---|
| **Stranger** | they hide or watch |
| **Tolerated** | basic trade |
| **Welcome** | crafters' knowledge; rest at the hearth (eases Deep Pressure); supplies |
| **Kin** | the best trades; map marks; the Seer's warnings |
| **Keeper** | they follow the player: the camp grows, sends supplies, can be expanded |

**How trust changes:**
- **Rises** through trade and gifts of what they lack (food, light).
- **Rises** by defending the camp, warning it of the worm, and returning relics the storms uncover.
- **Falls** by harming them, taking from the store, or leading the worm to them.

**Growth:** camps grow with the world's progress (*Huddle → Camp → Hold*: more inhabitants, better stock), faster where the player is trusted.

**Ties into the rest:**
- **Graveyards:** camp graveyards are camps that died with their gloam pocket.
- **The worm:** it threatens camps.
- **Fracturing:** a Mender may know of it.

### 5.3 Monsters and horror encounters

**Bosses (decided rule):** a boss occurs **once** naturally. After it is defeated, it can be **summoned again with special items**; adding **extra ritual items** makes it stronger, smarter and more versatile, and its loot better.

The dark region can contain creatures that create a sense of danger without making every expedition a constant fight. One planned large creature is an earthworm-like Thalan monster / boss. It is intended as a rare horror encounter unlocked through a combination of playtime, exploration and discovering a trigger.

The encounter should provide signs and directional sound cues. A player who pays attention should have a chance to escape; the goal is dread and anticipation, not an unavoidable surprise death. A possible compass-like item could indicate the creature's direction and approximate distance through colour or intensity. Its pathfinding may be limited beyond the render distance.

Fighting it should make sense only with very strong equipment. Recurring or strengthened versions and higher-difficulty rewards are possible, but the encounter's tension and escape gameplay are central.

**First version (built, summoned by command only):**
- **The Thalan Worm:** the user's Blockbench model, 85 to 125 blocks long, burrowing along the floor of the thalan layer (it bores down into the layer from outside and never leaves it). Its body is solid, follows the way its head went and can be struck anywhere along it. Fire and lava do not hurt it.
- **Behaviour:** it notices only players down in the layer. Within about 96 blocks (the boss bar's range) it hunts the nearest: it rears its maw open, comes for them along the ground (boring through rock only when hemmed in, or when they are right above or below it), and bites hard (72). Its body is solid like a boat: it can be stood on.
- **Cunning (1 to 4, each worm its own, drawn at spawn; `/abyssaldepths worm <1-4>`):** 1 comes straight on; 2 aims where its prey is going; 3 circles its prey, the ring narrowing so its body walls them in, then lunges; 4 circles the way they run to cut them off, closes the ring tighter, intercepts breakouts, and lunges when they are cornered, stand still or look away. Close in, every worm strikes: it turns tight, rears up out of the ground at its prey and snaps (the bite lands if its mouth came near at any moment of it); 3 and 4 crush a prey their ring walls in, and lunge again and again, the ring tighter each time.
- **Skills (built, each warned first):** tail sweep (the tail coils, then lashes across; all), tremor (passing under one heaves them; all), shrug (rolls off one standing on it too long; all); eruption (dives under where one stands, the ground cracks, it bursts up; 2+), charge (rears up roaring with its maw held open, then rushes straight; into rock it stuns itself and takes blows harder; 2+), spit (throws rock in arcs, the landing marked; 2+); inhale (draws everything before it in, cover or crouching holds; 3+), body slam (a stretch of its body rises over one, dust and shadow beneath, and crashes; 3+), ceiling fall (dust pours from the roof over one, then the roof comes down there; 3+); cave-in (brings the roof down across the way of one running, walling it off; 4), undermine (the ground cracks, then gives way into a pit over its maw, filling in later; 4), ambush (goes silent and deep, gets ahead and bursts up; 4); cunning 4 chains ring, inhale, bite. As it is hurt: at two thirds it sheds its hard skin (no armour), at half it rages (faster, a red breath, skills sooner), at a quarter it slinks away silent to heal for a minute. `/abyssaldepths wormskill <name>` makes the nearest use one.
- **Its range:** it keeps going while no one is near, within 128 chunks of the players. For now one comes 5 minutes into a world, about 100 chunks away (a stand-in for the trigger).
- **Until then (built):** it wanders and hunts only those within its range (about 96 blocks): a rare threat for a beginner, met by chance; feared once known, and avoidable with the worm compass.
- **Its trigger (decided, not built, later): a mix.** Noise and vibration in the layer (mining, explosions, fighting) draw it nearer; time spent in the layer raises the odds; a specific act can wake it at once.
- **How many (decided, not built):** players close together share **one** worm; players spread apart each draw **their own**, which gives the illusion of countless worms.
- **After its defeat (decided, the boss rule):** it does not come back by itself; special items summon it again, extra ritual items make it stronger (its cunning and skills fit this) and its loot better.
- **Warning signs (built):** faint growls from its head from far off, then dust and grit from the ceiling, stone crackling from its direction, stones breaking round the player, pebbles, the ground trembling. Hunted, the player hears their heart and the view darkens at the edges.
- **The worm compass (built):** made of a compass, a worm fang and an echo shard; held, a dial over the hotbar points to the nearest worm, its rim lit by nearness, the distance and its cunning (a pip per level) shown; red, pulsing and trembling when the worm hunts the one holding it. `/abyssaldepths whereis worm` tells the same in words.
- **Still open:** the trigger's details (thresholds, which act wakes it), its own sounds and its rewards.

---

## 6. Lore and Storytelling

The story should be discoverable rather than mandatory. The player can reconstruct the history through structures, objects, writings, environmental clues and exceptional NPCs, but the main gameplay progression should remain understandable without solving the mysteries.

### 6.1 The ancient catastrophe

An ancient catastrophe transformed the world and the peoples associated with the two sides. Its climax was unmistakable, but its origin and underlying cause are deliberately unresolved. Some parts of the past may be knowable; others may remain uncertain even at the end.

Do not invent a definitive explanation for the catastrophe unless the user explicitly chooses one. Mystery is intentional, not a gap that needs to be filled automatically.

### 6.2 The Founder World / Gründerwelt

The current working name for the final major dimension is **Gründerwelt** (Founder World). It is envisioned as a strange, energy-rich environment where forces and characteristics of Hollow and Zenith are mixed. Its inhabitants may be autonomous beings that do not fit neatly into either side's categories.

The world existed before the catastrophe but was later radically changed. One possibility is that it was transformed or repurposed as a refuge for selected beings. Its original purpose and who altered it are unknown.

The dimension is intended to provide a major late-game destination and a semi-closed main-story culmination, comparable in progression significance to defeating the Ender Dragon. After reaching that milestone, the player should still be able to continue exploring and playing.

### 6.3 The player

The player may have a connection to both light and darkness and may possess unusual potential. Only the highest beings may be able to sense this. This is background flavour and should not automatically become a conventional “chosen one” plot or an excuse to remove player freedom.

---

## 7. Artifacts

Artifacts are powerful, individual items with distinctive gameplay functions. They are not intended to be ordinary equipment pieces that are simply stronger than the previous tier. Each should have a memorable mechanic and a clear reason to exist.

### 7.1 Core rules

- Artifacts should provide concrete active or passive gameplay effects.
- Avoid vague, purely cosmetic or lore-only abilities.
- Do not introduce hidden second worlds, secret layers, perception-only effects or puzzle mechanics as a default way to make artifacts mysterious. These can exist only when explicitly part of a chosen concept.
- Strong effects may require charges, cooldowns, limited duration or other balancing constraints.
- Artifacts can be upgraded through mastery. Mastery is developed through use and/or suitable materials, increasing the item's effectiveness or capacity.
- Rarity is fixed. There should not be duplicate rarity tiers such as “Lesser I” and “Lesser II”; mastery levels belong within the artifact itself.
- Bosses and exploration can be important sources of powerful artifacts. Strong artifacts should often feel earned through distinctive encounters or discoveries.
- **How they are obtained (decided):** bosses mainly drop the **resources** artifacts are **crafted** from; mini bosses drop them too, fewer and worse. The more powerful artifacts also need an **extra item of their own**, one per artifact, found in the world and combined with the resources.
- Hollow artifacts have tended towards active abilities, while Zenith artifacts may lean towards passive effects, but this is a direction rather than a strict rule.

### 7.2 Rarity and colour language

The current rarity colours are:

| Rarity | Colour identity | Palette examples |
|---|---|---|
| Lesser | Cool slate grey / silver | `#444B55`, `#525963`, `#727D87`, `#A0A8AD`, `#C5CDD2` |
| Greater | Dark petrol / blue-grey | `#203F4A`, `#294C58`, `#426C78`, `#789AA1`, `#B4CED0` |
| Exalted | Muted violet / plum | `#382941`, `#49344F`, `#725276`, `#A17BA3`, `#D0B5D2` |
| Primordial | Deep crimson red | `#4B0D20`, `#671629`, `#96253E`, `#C4475F`, `#F08A94` |

These colours identify rarity and should not be confused with an artifact's origin. Origin can be communicated separately through tooltip borders or other details: Hollow items use a black/purple identity, while Zenith items use white/red. A tooltip shimmer or sheen can communicate rarity, with a subtle effect for Lesser and increasingly pronounced effects at higher rarities.

### 7.3 Artifacts as implemented

These are built (names, rarity and origin as in the code; the ability in one line). Item IDs can differ from display names for older worlds' sake (Concuss is `repulsion`, Revenant is `recall`). Read the code before changing any of them.

| Artifact | Rarity | Origin | What it does |
|---|---|---|---|
| **Severance** | Lesser | Zenith | Cuts every harmful effect away; mastered, keeps them off for a while and finally turns them into their opposites. |
| **Anchor** | Lesser | Hollow | For a while nothing knocks you back (blows, explosions, waves). |
| **Revenant** | Lesser | Hollow | Hold to be taken home (bed, anchor or world spawn) from anywhere; a blow breaks it. |
| **Counterweight** | Greater | Zenith | Toggle: jump higher, fall slower, land softer. |
| **Interlude** | Greater | Hollow | Toggle: everything around you slows down while you do not. |
| **Concuss** | Greater | Zenith | A pressure wave that throws everything around you away. |
| **Mirage** | Exalted | Hollow | Leaves a decoy likeness that draws creatures while you fade from their notice. |
| **Parallax** | Exalted | Hollow | Toggle: step into the shadow of this world, unseen and untouchable, unable to affect anything. |
| **Convergence** | Exalted | Hollow | Toggle: walk through walls (sneak to sink, jump to climb). |
| **Firmament** | Exalted | Zenith | Toggle: the air bears you where you step; others see only its glowing edge. |
| **Undertow** | Primordial | Hollow | A gravitational wound where you look drags creatures in, then bursts. |
| **Oblivion** | Primordial | Hollow | Casts everything living nearby into the shadow of this world for thirty seconds; the mightiest slip back sooner. |
| **Inner Haven** | Primordial | Zenith | Fade into a personal inner room while your body waits, untouchable. |
| **Reprise** | Primordial | Zenith | Rewinds the surroundings (blocks, creatures, players) by up to two minutes. |
| **Aegis** | Primordial | Zenith | Toggle: a field nothing living enters and shots slow to nothing in; mastered, shots are sent back. |
| **Empyrean** | Primordial | Zenith | Calls a column of light from the sky where you look (warned first); blocks above dim it; mastered, it follows a target. |

### 7.4 Artifact ideas not built

- **Evanesce:** a concealment concept that makes creatures lose track of the player. Exact effect, rarity and balance are unresolved.
- **Eventide:** name retained as a possible idea, but no satisfactory ability has been established.

Do not treat this as a required roadmap. The user prefers developing artifacts one by one and rejecting concepts that feel weak, generic or mechanically unclear.

---

## 8. Fracturing — Corruption Damage and Trial System

Fracturing is a special corruption-type damage effect. It should be rare and difficult to inflict, and it should feel fundamentally different from ordinary damage or a normal status effect.

Its visual identity is **black and white**, separate from the distinctive colours of Hollow and Zenith. The monochrome treatment communicates that Fracturing is a universal force rather than something belonging to either region.

**What it is (in-world):** weapons and beings that deal Fracturing touch a creature on a deeper level: they hurt the soul. Through the wound the two core forces of the world (the light and the dark) slip in. Once they do, the mind gives way, the soul is torn from the body, and the player has to face themselves to win their body back. The trials are that struggle; it is why they take place in a broken copy of the player's own land, why their own double stands against them, and why their memories help. Keep this as the meaning behind the system; the details stay unspoken in play.

### 8.1 Visual signature

Pixel cracks spread over the screen as the corruption builds up, growing from a few places that differ every time (not always the edges), into a network of fractures; how many pieces it forms varies and is configurable, and how much of the network shows matches the corruption level exactly. At 100 % the screen breaks away along those lines (after the view has left the body), revealing a black layer underneath. The effect should feel like the screen or the visible world is being fractured, not like a conventional coloured damage vignette.

### 8.2 Effect on ordinary creatures

When a normal creature is affected, it becomes paralysed and cannot move, attack or perform its usual actions while the effect lasts. Its body is covered by black-and-white digital corruption effects, with fragmented or unstable visual details.

### 8.3 Effect on the player

The player experiences a different and more consequential process. After the initial screen-fracturing sequence, the player is taken into a separate trial environment. The player must complete a challenge to regain control and return to normal play.

The trial is not an ordinary optional minigame. It is the gameplay consequence of the corruption attacking the player. The exact current presentation and implementation should be inspected in the project. Do not silently redesign the system while working on unrelated features.

### 8.4 Trials (current)

The trial is the player fighting to take their own body back. When the corruption takes them, the view leaves the body and the screen shatters; they wake in a black-and-white copy of the land around where they stood, broken into floating pieces, with their own frozen body waiting at the far end and their corrupted double stepping out of it. Two trials use this frame (implemented):

1. **Reach Yourself — the way:** cross the pieces (crumbling ones, glass pieces that phase out, single-block pillars) to the body while the double hunts, strikes and blocks; two gates it guards must be forced; memories (things the player carried) win back lost control; at the body the corruption's heart rises and must be broken, then the body touched.
2. **Face Yourself — the fight:** a short way to a wide piece where the double duels the player in two stages (varied, telegraphed attacks; blows land only while it stands open); broken, the body can be touched.

Losing control (three hits, or a fall) fails the trial. The earlier list of separate trial arenas (Collapse, Hunter, Resistance, Core, Anchoring, False Exit, Breakdown) is superseded; Collapse, Breakdown and Core remain only as commands. New trials are added when the user comes up with them.

### 8.4.1 Sources (decided direction, not yet implemented)

Fracturing is inflicted only by rare, hard enemies and by specific weapons the player can use — both to come later. Ordinary mobs and the environment do not cause it. Until then it is command-only.

**Fracturing weapons (decided direction, not built; class name open, working name "Fault weapons"):** an end-game weapon class (around and beyond full enchanted netherite):
- **Their hits build corruption in the target.** A creature at 100 % is paralyzed, and the next hit **shatters** it for heavy bonus damage, its corruption reset. Bosses build up at half speed.
- **The weapon feeds on its wielder:** every shatter adds a little corruption to them, fading if they ease off. Pushed too hard, they are fractured themselves and face a trial.
- **Scars could make the weapons build faster,** a reason to keep one.
- **Shapes,** each building corruption its own way:
  - an Edge (sword, steady);
  - a Maul (slow, a ground slam cracking a line);
  - a Glaive (the corruption jumps to a second target);
  - a Splinter (ranged, shards set off together);
  - a Shiv (finisher, strong against the paralyzed).
- **They temper by trials passed while carried.**
- **Made from a material dropped by shattered creatures and the top metals,** the first of it from the rare enemy that fractures.
- **Strictly black and white.**

### 8.5 Communicating objectives

The player should be able to understand what to do through the environment and clear visual signals. A large objective label may communicate that a task exists, but it is not enough by itself to explain why the player is there or what the trial represents.

Each trial should have an immediately recognisable focal point or behaviour that demonstrates its objective: a route of platforms, a pursuing creature, a vulnerable core, threatened anchors or warning patterns on the ground. Important danger should be telegraphed, and successful actions should have visible feedback.

A small contextual interface or stability indicator was considered as supplemental support, but it should not replace environmental communication. Whether this needs further revision should be determined by playtesting and the current implementation.

### 8.6 Failure and Corruption Scars

The proposed failure outcome is that failing a trial results in the player's death and normal respawn, but the player returns with a lasting **Corruption Scar**. The scar represents damage left behind by the corruption.

A suggested primary consequence is a reduction in maximum health (one proposed value was two hearts per scar). Additional possible effects included occasional subtle monochrome visual distortions or increased vulnerability to future corruption damage. These values and secondary effects are proposals, not necessarily final rules.

A failed player's body rises as a **Remnant** (built): a black-and-white walker in their likeness; killing it takes the scar back.

Scars should be removable through a deliberate recovery method, such as a rare cleansing item or a special cleansing station requiring particular materials. The punishment should matter, but it should not create an irreversible death spiral. The number or severity of scars may need limits or diminishing effects so repeated failures do not make recovery impossible.

---

## 9. Structures, Exploration and Rewards

Structures should give the player reasons to explore beyond collecting ordinary resources. Their rewards can include unique items, materials, story clues, access to stations or features, and especially rich deposits when the location or challenge justifies them.

A useful design distinction:

- **Ordinary exploration:** environmental discoveries, small rewards, hints and useful resources.
- **Meaningful challenges:** unique items, rare materials, access or major clues.
- **Major encounters / bosses:** powerful artifacts, exceptional progression rewards or access to new systems.

Avoid making every structure a loot chest with a different skin. Location, architecture and environmental context should make it memorable.

---

## 10. Visual and Audio Direction

Each region and system should have a coherent visual language. Colour is useful but should not be the only source of identity.

- **Hollow / dark region:** black, deep blue and violet associations; ancient, quiet, cavernous, bleak, inhabited and potentially oppressive.
- **Zenith / light region:** white and red associations; strong vertical forms and a distinct environmental identity rather than simply a brighter recolour of Hollow.
- **Artifact rarity:** slate/silver, petrol blue-grey, muted plum and deep crimson, as specified above.
- **Fracturing:** strictly monochrome black and white, deliberately independent of either region's palette.

**Cinematic (decided 2026-11-18):** the final product should feel truly cinematic. That needs:
- **A cutscene library of our own** (camera paths, fades, framing, sound, models acting their animations), built so later add-ons can use it too.
- **An opening animation** that plays when the game has finished loading.
- **The opening's motion** should be fluid and never still, like a studio's animated end card: the scene drifting, layers at different depths, elements arriving one after another and landing with a small overshoot (decided 2026-11-18).
- **A crest system:** when a player joins a world for the first time, they choose their crest (four crests; an opaque screen of its own, before the player is set into the world). Its effects are mostly visual, but it ties the player into the story and gives them a stronger identity. **Crests are signs of old houses or lineages from before the catastrophe; what they meant stays a mystery to uncover** (decided). Their names describe only the sigil, never a history (provisional, the user may rename them).
- **The opening** shows **light and dark meeting**: the two core forces drifting together in the black, the title emerging where they touch (decided).
- **The cutscene library** covers camera paths, fades, letterboxing, sound, titles, and **in-world actors** (temporary figures such as a Shadeborn or the worm moving and playing their animations), defined in data files any add-on can ship (decided).
- The Shadeborn model's test animation ("zooooooosh") may be used for a special trigger.

Use sound as an active part of environmental design. Directional cues, distant noises, silence and changes in ambience can communicate threats and scale. Do not rely exclusively on visual indicators when sound can make an encounter more memorable and readable.

---

## 11. How Claude Should Collaborate on This Project

Please use this document as background context when discussing or implementing features. The following working approach is important:

1. **Inspect before changing.** Read the relevant classes, registries, data files, assets, recipes, tags, configs and dependencies before proposing or editing an implementation.
2. **Preserve existing work.** The artifacts, Fracturing and parts of the dark region have already been implemented or are actively being developed. Do not rebuild them from scratch or assume they are empty systems.
3. **Separate design from implementation.** State whether a detail is already present in code, a documented design decision, or a new suggestion.
4. **Do not invent missing canon.** Unresolved lore, names, recipes, exact stats and mechanics should remain unresolved until chosen.
5. **Prefer focused changes.** Avoid broad refactors for a small feature. Keep architecture consistent with the existing project.
6. **Keep Minecraft gameplay legible.** Make mechanics understandable through clear feedback, consistent rules and fair telegraphing.
7. **Avoid unnecessary abstraction.** A visually impressive effect still needs a clear gameplay purpose. Avoid adding systems that create complexity without improving the player's experience.
8. **Respect established terminology and naming.** Check existing names before introducing alternatives. Some names are provisional and have evolved over time.
9. **Explain trade-offs.** When there are multiple viable approaches, briefly explain differences in gameplay, complexity, performance and maintainability.
10. **Test the actual result.** Compilation is not the same as good gameplay. Where possible, consider edge cases, multiplayer behaviour, persistence, death/respawn, dimensions, server authority, performance and compatibility with the project's actual Minecraft/mod-loader version.

## 12. What Is Still Open

The following areas should be treated as evolving rather than fixed:

- Final naming of the two major regions and some materials or artifacts.
- Full biome roster and exact biome generation rules.
- Exact balancing values for Deep Pressure and other environmental effects.
- Full Lightborn behaviour and interaction systems.
- Exact Shadeborn trust, trading and leadership progression.
- The cause and complete details of the ancient catastrophe.
- The original purpose of Gründerwelt and who changed it.
- Several artifact abilities, names, rarities and upgrade details.
- Final Fracturing trial selection, difficulty, communication and failure balancing.
- The new materials beyond the ores (found by exploring, bosses, trading, a few by mining) and what they make.
- Names for the glowing family: whether "gloam" stays for everything, and what the gloam itself is (mineral, living or both).
- The Shadeborn system (camps, roles, trust, trading, leadership).
- The dangers of the dune storms.
- Exact crafting recipes, station unlock requirements and final armour values.

Do not fill these gaps automatically. Propose options and let the user choose.

---

## Final Direction

The mod should feel like a large, coherent Minecraft world with its own identity, history and progression—not a disconnected collection of features. The two major regions should be worth exploring for their environments and gameplay, artifacts should offer memorable and concrete abilities, Fracturing should be a rare and consequential event, and the lore should reward curiosity without becoming a barrier to playing.

When in doubt, prioritise **clear gameplay purpose, strong atmosphere, meaningful exploration, fair mechanics and consistency with what is already implemented**. The best feature is not necessarily the most complicated one; it is the one that fits the world, feels good to use and gives the player a reason to remember it.
