#!/bin/bash
# Screenshots of the layer biomes without anyone at the keyboard (see AutoShot in src/dev).
#
#   tools/autoshot.sh <round> [biomes] [commands] [seed]
#
#   round     name of the output folder, e.g. r1
#   biomes    comma separated, default: shale_flats,silt_dunes,thermal_reaches,thalan_depths
#   commands  run as the player before the shots, ';' separated, e.g. "abyssaldepths storm start 600"
#   seed      world seed, default 12345
#
# Creates a fresh world, flies to two spots per biome and takes 4 views plus one looking down. Output
# in build/autoshot/<round>/: downscaled shots, brightened copies (bright/, the layer is dark by design),
# one contact sheet per spot (sheet_<biome>_<n>.jpg) and the game log (game.log, with the worldgen
# profile lines: grep "worldgen profile").
set -e
cd "$(dirname "$0")/.."
ROUND="${1:?round name missing}"
BIOMES="${2:-shale_flats,silt_dunes,thermal_reaches,thalan_depths}"
OUT="build/autoshot/$ROUND"
rm -rf run/saves/abyss-autoshot run/screenshots/autoshot_* "$OUT"
mkdir -p "$OUT"
timeout 1500 ./gradlew runAutoshot -Pautoshot="$BIOMES" -PautoshotCommands="${3:-}" -PautoshotSeed="${4:-12345}" \
    -Pprofile=true --console=plain > "$OUT/game.log" 2>&1 || echo "game exited with an error, see $OUT/game.log"
grep "autoshot:" "$OUT/game.log" | sed 's/.*autoshot: //'
grep "worldgen profile" "$OUT/game.log" | tail -1 | sed 's/.*: worldgen/worldgen/' || true
python -I "tools/shots.py" run/screenshots "$OUT"
