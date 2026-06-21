# TrailErosion

> Desire paths for Minecraft. Blocks slowly wear into worn variants as players walk
> over them, and quietly heal back when left alone — no client mod required.

A server-side **Paper + Folia** plugin for Minecraft **26.1** that recreates the
["The Roads More Travelled"](https://www.curseforge.com/minecraft/mc-mods/the-roads-more-travelled)
mechanic as a vanilla-friendly plugin. Walk the same route enough times and the ground
remembers it: grass packs into a dirt path, then coarse dirt; smooth stone cracks into
cobblestone. Stop using a route and nature reclaims it, one stage at a time.

Real foot traffic carves real paths — between spawn and the shops, around the well, down
to the docks — without anyone placing a single block.

## How it works

1. The plugin watches players entering **new block columns** (camera-only movement is free).
2. Each "step" accumulates on the block under the player's feet.
3. Once a block crosses a **randomized** threshold, it erodes one stage along its chain.
4. Blocks left untouched for a while **revert** one stage back toward their original form.

The randomized thresholds (`thresholdJitter`) mean neighbouring blocks wear at slightly
different rates, so trails get organic, irregular edges instead of a perfect 1-to-1 strip.

### Default erosion chains

| From | → | → |
|------|---|---|
| `GRASS_BLOCK` | `DIRT_PATH` | `COARSE_DIRT` |
| `SMOOTH_STONE` | `COBBLESTONE` | |

More chains (`PODZOL`, `MYCELIUM`, …) ship commented-out in `config.yml` so server owners
opt in. Chains are fully config-driven — add your own.

## Requirements

- **Paper or Folia** for Minecraft **26.1** (built against `paper-api 26.1.2`).
- **Java 25+** (the server runtime; Folia/Paper 26.1 require it).

> Built on the Paper region/global schedulers, so it runs safely on **Folia** (regionalized
> ticking) *and* on plain Paper, where those schedulers are no-op-compatible.

## Install

1. Download `trail-erosion-<version>.jar` from
   [Releases](https://github.com/lusqua/trail-erosion/releases) (or build it — see below).
2. Drop it in your server's `plugins/` folder.
3. Restart the server. A default `config.yml` is generated under `plugins/TrailErosion/`.

## Configuration

`plugins/TrailErosion/config.yml`:

| Key | Default | What it does |
|-----|---------|--------------|
| `stepsPerStage` | `8` | Base steps to advance one stage. |
| `stepsPerStageByMaterial` | `{SMOOTH_STONE: 17}` | Per-chain override of `stepsPerStage`, keyed by the chain's root material. |
| `thresholdJitter` | `0.4` | Random ± applied per block (`0.4` → threshold in `[base×0.6, base×1.4]`). |
| `mountedMultiplier` | `2.0` | Players on a mount erode the ground this much faster. |
| `revertAfterMinutes` | `20` | Idle time before a block reverts one stage. |
| `revertScanIntervalSeconds` | `60` | How often the revert sweep runs. |
| `disabledWorlds` | `[]` | Worlds to skip entirely. |
| `ignoreInClaims` | `false` | Reserved hook for future region/claim integration (no-op in v1). |
| `chains` | grass + smooth stone | The erosion hierarchy: ordered material lists per root. |

Material names are validated against the running server's API on load — unknown names are
skipped with a warning rather than crashing.

## Commands & permissions

| Command | Permission | Description |
|---------|------------|-------------|
| `/trailerosion reload` | `trailerosion.admin` (op) | Reload `config.yml` live. |
| `/trailerosion convert-to-vanilla` | `trailerosion.admin` (op) | Reset tracked blocks near you back to their original material (bounded radius). |

Alias: `/te`.

## Building from source

Requires a JDK 25 (for the Paper API) — Gradle's launcher needs JDK 21–25.

```bash
./gradlew build
# -> build/libs/trail-erosion-<version>.jar
```

The plugin has no runtime dependencies beyond the Paper API (which the server provides),
so the produced jar is just the plugin.

## A note on Folia threading

Folia splits the world into regions that each tick on their own thread, so **you cannot
mutate a block from an arbitrary thread**. Every block change here is scheduled on the
region that owns that block's location via the region scheduler, and the revert sweep
hops to each block's region before touching it. The Folia-specific lines are commented in
the source — start with `MoveListener.java` and `TrailErosionPlugin#sweep`.

## Contributing

PRs welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for build/test notes and the kinds of
changes that are a good fit. Good first issues: new erosion chains, per-world config,
claim-plugin integration (`ignoreInClaims`), and persistence of progress across restarts.

## Roadmap / out of scope for v1

- Vegetation trampling (tall grass / flowers getting crushed underfoot)
- Claim/region plugin integration (WorldGuard / Lands / Towny)
- Custom "trampler" boots / items
- Persisting progress counters across restarts (eroded blocks already persist in the world)

## License

[MIT](LICENSE) © aguiar-labs
