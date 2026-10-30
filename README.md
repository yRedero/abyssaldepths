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
  - *Thalan depths*: plain thalan, faintly glimmering specks in the walls.
  - *Shale flats*: shale plateaus full of leaning shards of fractured shale; you find your way along
    winding paths between them.
  - *Silt dunes*: one big open hall of fine silt dunes with lone pillars, slender arches and siltstone
    outcrops, quicksilt pools that pull you down, and dune storms.
  - *Thermal reaches*: thermal rock, glowing smouldering fissures, vent cones and vent fields with steam,
    oxide and sulfur deposits, sinter terraces with warm pools.
- **Blocks**: the thalan and siltstone building sets, shale, silt, thermal and vent blocks, Numinor ore.
- **Artifacts**: ranked items (Lesser, Greater, Exalted, Primordial) with mastery and charge and their
  own tooltip. *Inner Haven* carries you into your inner self, a room of your own in the Inner Self
  dimension, while your body waits behind. *Reprise* remembers the last two minutes of the world while
  carried, and takes everything (blocks, chests, creatures, every player) back to them. *Interlude*
  slows everything around you to half speed while you leave afterimages behind.
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
/abyssaldepths reprise [status]                 (rewind now / how far back it reaches and what is kept)
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
