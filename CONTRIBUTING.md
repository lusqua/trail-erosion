# Contributing to TrailErosion

Thanks for your interest! This is a small, focused plugin — the goal is to keep it
readable (~250 lines of logic) and correct on Folia.

## Getting set up

- **JDK 25** is required to compile (the Paper API for 26.1 targets it). Gradle's own
  launcher runs on JDK 21–25. Temurin builds work well; Gradle auto-detects JDKs under
  `~/.jdks`.
- Build with `./gradlew build` → `build/libs/trail-erosion-<version>.jar`.
- There are no runtime dependencies beyond the Paper API.

## Testing your change

There are no unit tests yet (the logic is mostly Bukkit-event-driven). Test on a real
server:

1. Drop the jar in a Paper **or Folia** 26.1 test server's `plugins/`.
2. Walk repeatedly over a strip of a chain's root material → it should erode in stages.
3. Stand still / spin the camera → nothing should change (proves the move filter works).
4. Set `revertAfterMinutes` low (e.g. `1`) and leave the area → blocks revert one stage
   at a time.
5. **Check the console for zero wrong-thread / `IllegalStateException` errors** — this is
   the most important check on Folia.

## The one rule that matters: Folia threading

Folia ticks each world region on its own thread. **Never mutate a block off its region's
thread.** Any `setType` / `setBlockData` must be wrapped in:

```java
Bukkit.getRegionScheduler().execute(plugin, location, () -> { /* mutate here */ });
```

The periodic sweep uses the **global** region scheduler only to drive the timer, then hops
to each block's region before touching it. Shared state is in `ConcurrentHashMap`s because
multiple region threads call the listener concurrently. If your PR touches blocks, explain
in the description which thread the mutation runs on.

## Good fits for a PR

- New erosion chains (verify material names against the target MC version!)
- Per-world configuration overrides
- Claim/region-plugin integration behind `ignoreInClaims`
- Optional persistence of progress counters across restarts
- Performance tuning of the move handler (it's the hot path)

## Style

- Match the surrounding code: small classes, clear names, comments only where the *why*
  isn't obvious (especially the Folia scheduler dance).
- Keep new features config-driven and disabled-by-default where they'd surprise a server
  owner.
- Don't add features that aren't discussed in an issue first — open one so we can align.

## Opening a PR

1. Fork, branch from `main`.
2. Make your change; build cleanly (`./gradlew build`).
3. Describe what you changed, how you tested it, and (if relevant) the threading model.
