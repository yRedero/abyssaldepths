#!/bin/bash
# Screenshots of the layer biomes without anyone at the keyboard (see AutoShot in src/dev).
#
#   tools/autoshot.sh <round> [biomes] [commands] [seed] [spot commands]
#
#   round     name of the output folder, e.g. r1
#   biomes    comma separated, default: shale_flats,silt_dunes,thermal_reaches,thalan_depths
#   commands  run as the player before the shots, ';' separated, e.g. "abyssaldepths storm start 600";
#             "client:reload" reloads the resources after the first spot (like F3+T)
#   seed      world seed, default 12345
#   spot commands  run as the player at every spot before the shots, ';' separated, relative to the
#             camera, e.g. "fill ~-1 ~-3 ~4 ~1 ~-1 ~6 abyssaldepths:numinor_ore" (something to look at)
#
# Creates a fresh world, flies to two spots per biome and takes 4 views plus one looking down. Output
# in build/autoshot/<round>/: downscaled shots, brightened copies (bright/, the layer is dark by design),
# one contact sheet per spot (sheet_<biome>_<n>.jpg) and the game log (game.log, with the worldgen
# profile lines: grep "worldgen profile").
#
# The game runs in its own folder (run-autoshot/, its options and config copied from run/ each time, windowed,
# not pausing without focus, muted), so it can run while your own game is open; tools/autoshot_window.ps1 puts its window
# on a second monitor and gives the focus back to the window that had it (AUTOSHOT_SCREEN=DISPLAY2 to choose one).
# AUTOSHOT_PROFILE=<Modrinth profile folder> runs it with that instance's mods and their settings (to check them together).
set -e
cd "$(dirname "$0")/.."
ROUND="${1:?round name missing}"
BIOMES="${2:-shale_flats,silt_dunes,thermal_reaches,thalan_depths}"
OUT="build/autoshot/$ROUND"
GAME=run-autoshot
rm -rf "$GAME/saves/abyss-autoshot" "$GAME/screenshots" "$OUT"
mkdir -p "$OUT" "$GAME/config"
# your settings, but windowed and running on without focus
if [ -f run/options.txt ]; then
    sed -e 's/^fullscreen:.*/fullscreen:false/' -e 's/^pauseOnLostFocus:.*/pauseOnLostFocus:false/' -e '/^soundCategory_master:/d' run/options.txt > "$GAME/options.txt"
fi
echo "soundCategory_master:0.0" >> "$GAME/options.txt" # (muted: it runs beside you)
[ -d run/config ] && cp -r run/config/. "$GAME/config/"
[ -d run/resourcepacks ] && [ ! -d "$GAME/resourcepacks" ] && cp -r run/resourcepacks "$GAME/resourcepacks"
# with another instance's mods (AUTOSHOT_PROFILE=<a Modrinth profile folder>): its mods (not its copy of this one) and their
# settings, to see how the mod looks beside them; without it, no other mods
mkdir -p "$GAME/mods"
rm -f "$GAME/mods/"*.jar
if [ -n "$AUTOSHOT_PROFILE" ]; then
    for j in "$AUTOSHOT_PROFILE/mods/"*.jar; do
        case "$(basename "$j")" in abyssaldepths*) ;; *) cp "$j" "$GAME/mods/" ;; esac
    done
    for f in "$AUTOSHOT_PROFILE/config/"*; do
        case "$(basename "$f")" in abyssaldepths*|fml.toml|neoforge-*) ;; *) cp -r "$f" "$GAME/config/" ;; esac
    done
    echo "with the mods of $AUTOSHOT_PROFILE: $(ls "$GAME/mods" | wc -l) jars"
fi
powershell -NoProfile -ExecutionPolicy Bypass -File tools/autoshot_window.ps1 > /dev/null 2>&1 &
WINDOW=$!
timeout 1500 ./gradlew runAutoshot -Pautoshot="$BIOMES" -PautoshotCommands="${3:-}" -PautoshotSeed="${4:-12345}" -PautoshotSpotCommands="${5:-}" \
    -Pprofile=true --console=plain > "$OUT/game.log" 2>&1 || echo "game exited with an error, see $OUT/game.log"
grep "autoshot:" "$OUT/game.log" | sed 's/.*autoshot: //'
grep "worldgen profile" "$OUT/game.log" | tail -1 | sed 's/.*: worldgen/worldgen/' || true
kill $WINDOW 2>/dev/null || true
python -I "tools/shots.py" "$GAME/screenshots" "$OUT"
