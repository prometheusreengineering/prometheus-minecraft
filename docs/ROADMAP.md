# Roadmap: the loader as the only way in

Prometheus is moving from "every patch is its own mod, plus a loader that downloads them" to "the loader is the only way
in". This page is the plan, why each decision was made, and what's done. Last updated 2026-11-05.

## Why

Before, each patch (Lunar, Essential, Dawn) also loaded by itself, through its own mod metadata. Shared code was going
to be [minecraft-lib](https://github.com/prometheusreengineering/minecraft-lib), and every patch would have bundled its
own renamed copy of it.

Instead, the loader becomes the only entry point:

- **One copy of minecraft-lib on the game's class path**, unrenamed, so there are no copies to keep in sync.
- **Everything updates itself.** The lib from Maven, the core and the patches from GitHub releases.
- **One place handles platforms.** The loader deals with Fabric, Forge and NeoForge, so patches don't each need entry
  points for every platform.

## Architecture

```
installed: prometheus-<version>-minecraft.jar   (stage 0, Java 8, JDK only, rarely changes)
   │  one hook per platform: Fabric language adapter | LaunchWrapper tweaker | ModLauncher service | NeoForge locator
   │  unpacks the minecraft-lib jar it carries and starts its stage 1
   ▼
minecraft-lib (stage 1, boot.Stage1)
   │  hands off to a newer lib of the same major (Maven, verified + cached) if there is one
   │  puts its jar on the game's class path, for the patches
   │  starts the newest core (GitHub release, verified + cached)
   ▼
prometheus-core-<version>.jar   (loader logic, on stage 1's lib)
   │  reads patches.json, keeps the patches whose target is installed
   │  downloads + verifies each, adds it to the class path, registers its mixin configs, calls PrometheusPatch.init
   ▼
GitHub releases of each patch: lunar, essential, dawn
   (compiled against minecraft-lib as provided)
```

- **Stage 0 → stage 1 → core** pass only JDK types: each calls the next's `run(Map<String, Object>)` by reflection, so
  it doesn't matter which class loader each side lives in. Stage 0's keys are versioned by `Stage0.CONTRACT`.
- **Stage 0 carries the lib, and the lib updates itself and the core** (like EssentialLoader's stages 0/1/2). Stage 0
  can't make a network call without the lib, and is the one jar users can't easily update, so the update logic lives in
  the lib. The lib jar stays whole inside stage 0, because unpacked its packages would be on the class path twice
  (ModLauncher 9+ refuses that).
- **Stage 0 never touches the lib's classes.** It has its own small `ChildFirstClassLoader` to load the lib, and
  doesn't even compile against it: a missing class there fails while a hook links `Stage0`, outside every try, and
  crashes the launch (seen on Fabric 1.21.4).
- **Lib versions:** stage 1 only updates within its major version, so a new major needs a new stage 0. The core records
  the lib it was built against (`lib.version`), and stage 1 refuses a core that needs a newer lib or another major.
- **The core shares stage 1's lib.** It runs in a child-first loader parented on stage 1's, and doesn't contain the lib.
  The patches see the copy stage 1 put on the game's class path.
- **Downloads are safe.**
  - Every jar is checked against the published SHA-256: a GitHub release asset's `digest`, or a Maven `.sha256`.
  - Each is checked at most once per `update.interval` (the unauthenticated GitHub API allows 60 calls an hour).
  - Offline launches use the last verified cache.
  - A jar that another running game may have open is never overwritten.

## Findings that shaped it

### One ModLauncher service for every version

ModLauncher 2-8 (Forge 1.14-1.16) declares `void beginScanning(IEnvironment)`, and 9+ declares
`List<Resource> beginScanning(IEnvironment)` under the same name. No single source file can implement both. But the
JVM tells methods apart by their full descriptor, so both can exist in one class at runtime:

- `LegacyBase` is compiled against ModLauncher 8 (`stage0-legacy`) and has the 2-8 methods.
- `ModLauncherHook` extends it and is compiled against ModLauncher 10 with a stub of `LegacyBase`.
- The build swaps the real `LegacyBase` in.

Checked in game on Forge 1.14.4, 1.15.2, 1.16.5, 1.18.2, 1.21.1 and 26.3, and NeoForge 1.21.1. On every version
ModLauncher calls `onLoad`, then `initialize`, the scan, then `transformers`.

### ModLauncher 4-8 doesn't load scanned jars

On Forge 1.15-1.16, the jars returned by `runScan` only reach launch plugins: Mixin reads their configs from there.
They never reach the game's class loader, so Mixin found a patch's config but not its classes. ModLauncher 4-10 also
asks each service for `additionalClassesLocator`/`additionalResourcesLocator`, and stage 0 serves the patch jars
through those (`LegacyLocator`). ModLauncher throws on a package another service already claimed, which crashes the
launch, so those are skipped.

### NeoForge without ModLauncher

NeoForge on FML 11+ (NeoForge 26.x) has no ModLauncher. FML rejects a jar in `mods/` without a mod toml unless it
declares an "early service". `IModFileCandidateLocator` is the one with the same signature in FML 4, 11 and 12, and its
pipeline also adds the patch jars as mod files. On NeoForge 1.21.1 (FML 4 plus ModLauncher 11) both hooks fire, each
from a different class loader. That's why the "first hook wins" claim is a JVM-wide system property rather than a
static field.

### Dropped

- **Forge 1.13.2:** Forge itself doesn't start.
- **Forge 1.14.4:** stage 0 starts, but ModLauncher 2 has no way to put patch jars in the game's class loader.
  Essential doesn't support 1.14 anyway.

## Phases

### Phase 1: minecraft-lib. Done.

- The patch contract: `PrometheusPatch.init(PatchContext)`, declared in `META-INF/services`.
- What the core needs: `AtomicFiles`, `Hashing`, `Http` (verified, atomic downloads), `Settings`, and a logger that
  behaves the same on every platform.
- Compiled against the oldest game dependencies (Gson 2.2.4, Log4j 2.0-beta9), and the API only grows.
- 100% line and branch coverage, enforced by the build.

### Phase 2: the loader split into stage 0, stage 1 (in the lib) and the core. Done, not released.

- Built and tested in game with today's patches. The README's Compatibility section has the version table.
- Stage 1 moved into minecraft-lib (1.2.0) on 2026-11-05, after stage 0 crashed the launch by using a lib class. Since
  then checked in game on Fabric 1.21.4 only, without patches. The other platforms need checking again.
- `patches.json` lists several target classes per patch (`classPaths`), so Essential is one entry instead of three.
  Loaders from before stage 0 can't read this format, which is intended.
- To release:
  1. deploy minecraft-lib 1.2.0 to Maven, with its `.sha256` files
  2. merge `stage0-core`
  3. publish a release with **both** jars attached (stage 1 takes the core from it)

### Phase 3: the patches. Not started.

For each of [minecraft-lunar](https://github.com/prometheusreengineering/minecraft-lunar),
[minecraft-essential](https://github.com/prometheusreengineering/minecraft-essential) and
[minecraft-dawn](https://github.com/prometheusreengineering/minecraft-dawn):

- **Use the lib:** delete the copied `OS`, `PathUtil` and `GsonUtil`, depend on minecraft-lib as `provided`, and log
  through `Log.get("<project>")`. Essential keeps its own `GsonUtil` for Essential's types, which use Essential's
  relocated Gson annotations.
- **Remove the entry points:**
  - Fabric `entrypoints`/`mixins`
  - manifest `TweakClass`/`MixinConfigs`
  - the mod entries in `mods.toml` and `neoforge.mods.toml`
- **Add one `PrometheusPatch`**, registered in `META-INF/services`:
  - Essential: `PrometheusEssential.initMixins`
  - Lunar: `StaffMods.unlock` and the `FileUtils` setup
  - Dawn: the body of its language adapter's static block
- **Dawn:** bundle and rename jsonproxy and the Kotlin standard library in its jar (Gradle shadow) instead of Loom's
  `include`, because jars the loader adds can't load bundled jars. Then add it to `patches.json`.

Still to check before or during this phase:

- **Dawn's timing:** stage 0 now starts from a Fabric language adapter, as Dawn's own entry did. Is `DAWN_API_URL`
  still set before Feather reads it?
- **Mixins on ModLauncher 1.21+:** do patch mixins load through the core's registration, or through the patch's
  `mods.toml` `[[mixins]]`? Patches may need to keep `[[mixins]]` (metadata only, no entry point).
- **Calling `PrometheusPatch.init`:** at the right moment on each platform. On ModLauncher that's after the GAME layer
  exists.
- **A minimum core version per patch** (`minCore` in `patches.json`), once patches depend on newer lib API.

## Verification

- **The game matrix:** install only stage 0. Check that `logs/loader.log` names the platform and lists every
  applicable patch as loaded, and that each patch's `init` ran (its own log file appears). Test:
  - Forge 1.8.9 and 1.12.2
  - Forge 1.16.5, 1.20.1 and 1.21.x
  - NeoForge 1.21.1 and 1.21.8
  - Fabric 1.16.5, 1.21.x and 26.x
  - Lunar 1.8.9 and on Fabric
  - Dawn 26.3: its cosmetics show in game
- **Failure cases:**
  - Offline after one online launch: patches load from the cache.
  - Offline on a fresh install: a clear log line, and the game still starts.
  - A corrupted cached jar: it's detected and downloaded again.
  - Two launches within the update interval: the second makes no GitHub or Maven calls.
  - A newer lib on Maven: stage 1 hands off to it (`handed off by lib` in `loader.log`).
