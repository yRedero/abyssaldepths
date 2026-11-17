See @DESIGN.md for the design philosophy.

# Abyssal Depths: notes for working on the project

NeoForge 21.1 / Minecraft 1.21.1 mod (ModDevGradle). Adds the thalan layer below the deepslate (y -65 to
-128) with four biomes. See README.md for the features and tools/README.md for the dev tools.

## Layout

- `worldgen/`: the layer. `TunnelLayer` (cavern shape), `LayerFormations` (formations per biome),
  `DuneOpenness` are pure Java without Minecraft, so they run in tests and offline tools.
  `TunnelFeature` places everything per chunk (carve, `LayerSurface`, formations, `LayerWalls`) and keeps
  a per-world `Context` with thread-safe caches: never compute biome lookups per chunk again (they
  cost the full climate noise; that once made generation 12x slower).
  The gloam (`GloamPockets`) and the set pieces (`GloamGrotto`, `FrozenFall`, `BuriedSkeleton`, `FractureCanyon`, `LavaFall`, `VentLung`, `TideLake`) come after,
  each from grid cells; go to one with `/abyssaldepths grotto|frozenfall|skeleton|canyon|lavafall|ventlung|tidelake`, shoot it with
  `tools/autoshot.sh <round> grotto|frozenfall|skeleton|canyon|lavafall|ventlung|tidelake`. Dune storms (`DuneStorm`: gusts, grinding silt) and the storm crystal spires (`worldgen/StormSpires`, kept in step by `StormCrystals`, their waking drawn by `client/StormCrystalView`): `tools/autoshot.sh <round> silt_dunes,stormwake`.
- `client/`: all visuals and sounds (fog, ambience, reverb, storms, thermal effects, deep pressure).
- `artifact/`: artifacts (rank, frame, mastery data component); their tooltip frame is
  `client/ArtifactTooltip`. Preview with `tools/autoshot.sh <round> artifacts`. Inner Haven: `InnerSelf`
  (rooms or `InnerIslands`, crossing), `entity/AbsentBody`, the mat's `entity/Seat`. Reprise: `artifact/reprise` (change log via the LevelChunk and
  BlockEntity mixins, moments every 5 s, `Rewind`); check with `tools/autoshot.sh <round> reprise`.
  Toggled artifacts (Interlude, Parallax) extend `ToggleArtifactItem`; mastery milestones (every 25 %,
  `ArtifactItem.milestone`) set their strength and show in the tooltip (`powerLine`). The shadow of this world (Parallax, Oblivion):
  `Shades` (rules as events, the rest in the `Shade*Mixin`s), `client/ShadeView` (who sees whom, the
  crossing, the post shader `shaders/program/shade.fsh`), `client/ShadeVanish` (vanishing for others). Firmament: `Firmament` works out the blocks under its
  users on every side from the synced item use; only the user's own client collides (`FirmamentCollideMixin`),
  others see the outline (`client/FirmamentView`); check with `tools/autoshot.sh <round> firmament`.
  Convergence (walking through blocks): `ConvergenceItem.collide` via `ConvergenceMixin`, both sides alike;
  the wall view in `client/ConvergenceView`. Anchor, Aegis, Recall, Repulsion: check them all with
  `tools/autoshot.sh <round> small`; their looks (config `aegisLook`, `aegisOverlay`, `convergenceLook`, `convergenceApply`, `convergenceLayer`, `repulsionWave`; the Convergence layer on the player in `client/ConvergenceLayer`) with `looks`.
  Mastery belongs to the player (`artifact/ArtifactOwnership`, attachment `MASTERIES`), written onto every
  copy they carry; one of each artifact per inventory.
- `fracture/`: Fracturing (`Fracturing`: creatures frozen; `FractureTrials`: the player's onset, trial dimension,
  kept inventory, pass/fail, death and `Scars`; one class per `Trial`: Fracturing picks one of `ReachTrial` (way or duel: a black-and-white copy of the land round the player via `TrialLand`, their body at the far end, their `FractureDouble`, the heart), `HushTrial` (a maze in the dark, the double hunting by sound), `FallTrial` (down a shaft of black floors); the older three by command only); a failed body rises as a `Remnant` (registry id `hollow` for older worlds; killing it takes the scar back); `client/FractureControl` (control slipping from 50 %); client side in `client/FractureScreen`
  (screen breaking), `FractureView` (corruption look, strikes), `TrialHud`, `FractureCoreRenderer`, `FractureSky`.
  Check with `tools/autoshot.sh <round> fracture` (trials, endings, the body left decomposing) and `fracmarks` (the mark styles, config `fractureMarks`, the auras, config `fractureAura`, the Core's warnings, a passed trial); `/abyssaldepths fracture end pass|fail` ends one's trial; `/abyssaldepths fracture [targets|trial <name>]`, `/abyssaldepths scars <n>`.
- The thalan worm (first version): `entity/BigWorm` (burrows along the ground of the thalan layer, boring down into it from outside, gapes, bites; four levels of cunning (circling, cutting off); a solid body one can stand on; its body follows the way the
  head went, struck through `entity/BigWormPart`s; boss bar; kept going up to 128 chunks out by `entity/WormManager`, which also sends
  the crackling heard from 400 blocks, played by `client/WormRumble` with the growls, dust, pebbles, trembling and, hunted, the heartbeat and darkening view), drawn by `client/BigWormRenderer` from the
  user's Blockbench model through `client/BbModel` (bones, box-UV cubes and the model's animations, as Blockbench shows
  them). Update it from a new .bbmodel with `python tools/bbmodel_to_entity.py <in.bbmodel> <models/entity/big_worm.json>
  <textures/entity/big_worm.png> <out.bbmodel>` (lays out the UVs and paints the texture; the out.bbmodel carries both
  back to Blockbench). `/abyssaldepths worm [1-4]`, `/abyssaldepths wormskill <name>`; check with `tools/autoshot.sh <round> worm` and `wormskills` (every skill in turn).
- `block/`, `mixin/`, `network/` (one server-to-client effect payload), config in `ClientConfig`.
- `src/dev/java`: dev-only (AutoShot, LayerMap, TunnelLayerCheck); not in the jar.
- `src/test/java`: JUnit regressions of the generation (`./gradlew test`).

## Working on it

- Check worldgen changes in game with `tools/autoshot.sh` (screenshots + profile), or offline first with
  `./gradlew layerMap` and `./gradlew test`.
- Before committing: `./gradlew build test`, `python tools/check_resources.py`, `python tools/format_json.py`.
- Textures only via mctex requests (the user chooses); credits in TEXTURE_CREDITS.md.
- The user's design rules so far: walls, pillars and formations are one block (plus at most a vertical
  vein), never horizontal bands; dark textures with a little red; the ground of a biome uses the
  biome's own blocks.
- Commit and push only when asked.
