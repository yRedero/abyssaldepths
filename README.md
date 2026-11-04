# Abyssal Depths

A NeoForge mod for Minecraft 1.21.1 that extends the overworld down to y = -128. Below the deepslate
lies the **thalan layer**: one huge, dark cavern of thalan stone, with its own biomes, rock formations
and ambience.

## What's in it

- **The thalan layer** (y -65 to -128): a cavern with tall halls, rock masses, massifs, pillars and low
  crawl stretches, connector tunnels and up-shafts into the deepslate. Thalan ores, lava lakes, and a
  deep pressure effect that builds up the longer you stay down (tunnel vision, heartbeat, ringing,
  hallucinations).
- **Biomes**
  - *Thalan depths*: plain thalan; the coldest part, where the gloam is faintest.
  - *Shale flats*: shale plateaus full of leaning shards of fractured shale; you find your way along
    winding paths between them.
  - *Silt dunes*: one big open hall of fine silt dunes with lone pillars, slender arches and siltstone
    outcrops, quicksilt pools that pull you down, and dune storms.
  - *Thermal reaches*: thermal rock, glowing smouldering fissures, vent cones and vent fields with steam,
    oxide and sulfur deposits, sinter terraces with warm pools.
- **The gloam**, the glow that ties the layer together (none in the dunes): rare pockets where gloam
  crystal breaks out of the rock, gloam moss and clusters round them, often a dark pool, in the richest
  ones giant gloamcaps (the layer's only wood: gloam stalk and its wood set; their caps give gloam fibre,
  woven into gloamweave).
- **Set pieces**, rare landmarks (`/abyssaldepths <name>` takes you to the nearest):
  - *Gloam grotto* (`grotto`, depths): a still dark lake glowing from its bed, gloam spires round it.
  - *Frozen fall* (`frozenfall`, depths): a fall frozen mid-fall in dark ice, gloam caught in its core.
  - *Tide lake* (`tidelake`, depths): black water with an island; every two minutes the tide goes out and
    a rock spine comes up stone by stone to the island, then goes under again shore first.
  - *Fracture canyon* (`canyon`, flats): a zigzag crack through the plain, lava glowing at its bottom.
  - *Buried skeleton* (`skeleton`, dunes): the ribs of something enormous rising out of the silt.
  - *Lava fall* (`lavafall`, reaches): lava pouring from the ceiling into a cup in a steaming lake.
  - *Vent lung* (`ventlung`, reaches): a bowl of vents in rings that breathes: every 20 seconds it hisses,
    then bursts ring after ring outwards.
- **Blocks**: the thalan and siltstone building sets, shale, silt, thermal and vent blocks, dark ice,
  the gloam family, Numinor ore.
- **Artifacts**: ranked items (Lesser, Greater, Exalted, Primordial) with mastery and charge and their
  own tooltip. *Inner Haven* carries you into your inner self, a room of your own in the Inner Self
  dimension, while your body waits behind. *Reprise* remembers the last two minutes of the world while
  carried, and takes everything (blocks, chests, creatures, every player) back to them. *Interlude*
  slows everything around you to half speed while you leave afterimages behind. *Parallax* makes you a
  shade in the shadow of this world: unseen and untouchable, unable to touch anything, only to walk, and
  to meet whoever else is in the shadow (the world's creatures and players stay hidden from you until
  its mastery is full). *Oblivion*
  casts everything alive around you into that shadow for thirty seconds (the mightiest for less). Mastery
  strengthens Reprise (30 to 240 s back), Interlude (slower and longer) and Parallax (longer).
- **Ambience**: biome sounds, echo in the halls, dust, drips, gusts, fog tinted per biome, no game
  music in the layer.

## Playing

Settings (fog distance, particles, storms, echo, sounds, music, performance) are in the mod's config
screen or `config/abyssaldepths-client.toml`.

Test commands for operators:

```
/abyssaldepths hallucinate footsteps|mob|digging|shadow
/abyssaldepths ambience groan|creature|steps|rockfall|gust
/abyssaldepths storm start [seconds] | stop
/abyssaldepths mastery <percent> | charge <n>     (the artifact in your main hand)
/abyssaldepths innerhaven                       (into your inner self or back, like Inner Haven)
/abyssaldepths innerhaven form room|islands     (the form of your inner self; rebuilt at once)
/abyssaldepths reprise [status]                 (rewind now / how far back it reaches and what is kept)
/abyssaldepths vanish style <style>             (how shades vanish: pixels, ash, slide, shadow, disintegrate, dissolve, none)
/abyssaldepths vanish play [style]              (plays it on a copy of you, three blocks ahead)
/abyssaldepths oblivion                         (casts Oblivion around you, without charge)
/abyssaldepths showcase                         (every artifact's tooltip, at any mastery, and Reprise's waiting signs)
/abyssaldepths shadelook <look>                 (how shades look in the shadow: contrast, print, silhouette, inverted, none)
```

## Building

```
./gradlew build        # the mod jar in build/libs
./gradlew test         # offline checks of the layer generation
./gradlew runClient    # play it
```

Development tools (screenshot autopilot, offline maps, resource checks) are described in
[tools/README.md](tools/README.md).

## Credits

Textures are made with mctex from openly licensed photos, see [TEXTURE_CREDITS.md](TEXTURE_CREDITS.md).
Sounds are pitched and mixed vanilla sounds.
