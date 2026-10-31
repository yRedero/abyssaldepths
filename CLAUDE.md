# Abyssal Depths: notes for working on the project

NeoForge 21.1 / Minecraft 1.21.1 mod (ModDevGradle). Adds the thalan layer below the deepslate (y -65 to
-128) with four biomes. See README.md for the features and tools/README.md for the dev tools.

## Layout

- `worldgen/`: the layer. `TunnelLayer` (cavern shape), `LayerFormations` (formations per biome),
  `DuneOpenness` are pure Java without Minecraft, so they run in tests and offline tools.
  `TunnelFeature` places everything per chunk (carve, `LayerSurface`, formations, `LayerWalls`) and keeps
  a per-world `Context` with thread-safe caches: never compute biome lookups per chunk again (they
  cost the full climate noise; that once made generation 12x slower).
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
  `tools/autoshot.sh <round> small`.
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
