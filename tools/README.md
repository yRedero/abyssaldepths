# Development tools

Everything here is for working on the mod; nothing of it ends up in the mod jar. The Java tools live in
the `dev` source set (`src/dev/java`), the scripts in this folder.

## Look at it in game without playing: `tools/autoshot.sh`

```
tools/autoshot.sh <round> [biomes] [commands] [seed]
tools/autoshot.sh r1 silt_dunes "abyssaldepths storm start 600"
```

Starts the game (`runAutoshot`), creates a fresh world, flies to two spots per biome in spectator mode
with night vision and takes four views plus one looking down, then quits. Commands (`;` separated) run
as the player first. Output in `build/autoshot/<round>/`: the shots, brightened copies in `bright/` (the
layer is dark by design), one contact sheet per spot and `game.log`. The run also measures the world
generation; the last line of `grep "worldgen profile" game.log` is the average time per chunk and phase.

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

## Textures

Block textures are requested from mctex (`../texture-tool`), never drawn by hand: the user picks them in
`mctex-proposals.bat`. Photo credits go into `TEXTURE_CREDITS.md`.
