# Development tools

Everything here is for working on the mod; nothing of it ends up in the mod jar. The Java tools live in
the `dev` source set (`src/dev/java`), the scripts in this folder.

## Look at it in game without playing: `tools/autoshot.sh`

```
tools/autoshot.sh <round> [biomes] [commands] [seed] [spot commands]
tools/autoshot.sh r1 silt_dunes "abyssaldepths storm start 600"
tools/autoshot.sh r2 thalan_depths "" 12345 "fill ~-1 ~-3 ~4 ~1 ~-1 ~6 abyssaldepths:numinor_ore"
```

Starts the game (`runAutoshot`), creates a fresh world, flies to two spots per biome in spectator mode
with night vision and takes four views plus one looking down, then quits. Commands (`;` separated) run
as the player first; spot commands at every spot, relative to the camera (to put a block in view). Output in `build/autoshot/<round>/`: the shots, brightened copies in `bright/` (the
layer is dark by design), one contact sheet per spot and `game.log`. The run also measures the world
generation; the last line of `grep "worldgen profile" game.log` is the average time per chunk and phase.

The pseudo biomes `surface` (above the world spawn), `artifacts` (a screen with the artifact tooltips,
three shots a quarter second apart for animations) and `haven` (Inner Haven's crossing: a body, the
fade, the room, the way back) `reprise` (changes the world, rewinds, and checks in `game.log`: grep
"reprise check" and "block data") and `interlude` (two falling sand blocks, normal and slowed: their
heights after 20 ticks in `game.log`; the afterimage trail in third person) and `parallax` (the shadow's
view before, in and after; grep "parallax check" in `game.log`) `oblivion` (creatures sent away and back, the strong sooner; grep "oblivion check") and `vanish` (every way a shade vanishes and
comes back, with a fake player; at the end the shadow's view with that player as a shade) can be listed like biomes, e.g. `tools/autoshot.sh tt artifacts`.

Takes 3 to 6 minutes. Needs Python with Pillow.

## Offline maps and checks (no game): Gradle tasks

```
./gradlew layerMap   -Pargs="flats 12345 0 0 512 build/layer-map.png"
./gradlew layerCheck -Pargs="12345 -1024 -1024 2048 build/layer-check"
./gradlew test
```

- `layerMap` (`LayerMap`): the cavern of an area as if it were all one biome (`flats`, `dunes`,
  `thermal`, `depths`): headroom as grey (dark red = under 8 blocks), rock black, every formation as a
  coloured ring of its reach; prints how many of each kind stand there. For tuning formation density
  and spacing in seconds instead of minutes in game.
- `layerCheck` (`TunnelLayerCheck`): flood-fills the walkable floor of the cavern and reports cut-off
  pockets, clearances and air shares; optional PNGs. Exits with 1 if the cavern breaks a hard rule.
- `test` (`LayerGenerationTest`): regressions that must not come back (grid-aligned walls at the dune
  border, unwalkable floor steps, empty stretches in the shale flats, non-deterministic formations).

## Profiling world generation

Any run with `-Dabyssaldepths.profile=true` logs "worldgen profile ..." every 200 chunks (autoshot does
it with `-Pprofile=true`). Phases: columns, connectors, carve, formations.near, surface, formations,
walls. At the time of writing about 4 ms per chunk in total.

## Resources: `tools/check_resources.py`, `tools/format_json.py`

```
python tools/check_resources.py     # every block has blockstate, item model, loot, name; textures exist; sounds registered
python tools/format_json.py         # all JSON in one format (2 spaces, LF); --check only lists
```

Run both before committing.

## Finding ores: `tools/make_xray_pack.py`

```
python tools/make_xray_pack.py [resourcepacks dir]     # default: run/resourcepacks
```

Builds the resource pack "Numinor X-Ray": the rock turns invisible and Numinor ore is drawn through it.
In game it must sit above "Mod Resources" in the pack list. Rebuild after adding blocks.

## Textures

Block textures are requested from mctex (`../texture-tool`), never drawn by hand: the user picks them in
`mctex-proposals.bat`. Photo credits go into `TEXTURE_CREDITS.md`.
