# prometheus-minecraft

The Prometheus loader for Minecraft. It finds which patches apply to the running game, downloads them from GitHub
releases and loads them. Each patch is a repo of its own (`minecraft-lunar`, `minecraft-essential`, ...), listed in
`patches.json`.

Shared code lives in [minecraft-lib](https://github.com/prometheusreengineering/minecraft-lib) (`../minecraft-lib`,
package `ing.prometheusreengineer.minecraft`). Its `CLAUDE.md` has the rules for the lib itself: logging,
`Settings`, the provided-dependency versions.

## Architecture

```
installed: prometheus-<version>-minecraft.jar   (stage0/, JDK only, rarely changes)
   │  one hook per platform, the first to fire claims the JVM
   │  downloads + verifies the core, or uses the cached one
   ▼
GitHub release of this repo: prometheus-core-<version>.jar   (core/ + minecraft-lib, unrenamed)
   │  reads patches.json, keeps the patches whose target class is present
   │  downloads + verifies each, adds it to the class path, registers its mixin configs
   ▼
GitHub release of each patch repo: the patch jar
```

- **Stage 0 → core** passes only JDK types: `Bootstrap.run(Map<String, Object>)`, called by reflection. The core
  runs in a child-first class loader. The keys are documented on `Stage0.CONTRACT`.
- **The core is the only copy of minecraft-lib in the game.** It puts its own jar on the class path, so patches
  compile against the lib as `provided`. The core calls `Log.setOwner("loader")`.
- **Logs:** `<prometheus folder>/logs/stage0.log` (stage 0) and `loader.log` (core). The prometheus folder is
  `%APPDATA%/.minecraft/prometheus` on Windows (`OS.getConfigFolder()`).
- **Caches:** `<prometheus folder>/cache/`: `core/`, `patches/<repo>/`, `patches.json`. Each keeps a `.properties`
  file with the tag, the asset, the file and its SHA-256.

## Modules

| Module | Artifact | What |
|---|---|---|
| `stage0-legacy/` | `prometheus-stage0-legacy` | The ModLauncher 2-8 half of the transformation service (`LegacyBase`), compiled against ModLauncher 8.0.9. |
| `stage0/` | `prometheus` → `prometheus-<version>-minecraft.jar` | The jar users install. The platform hooks, `CoreResolver`, and copies of `Settings`/`OS` (in `Settings.java`). |
| `core/` | `prometheus-core` → `prometheus-core-<version>.jar` | `Bootstrap` (patch loading) and `ReleaseResolver` (patch jars), with minecraft-lib shaded in, unrenamed. |

### Stage 0 hooks (`stage0/src/main/java/studio/dreamys/prometheus/stage0/`)

| Hook | Platform | Registered in |
|---|---|---|
| `fabric/FabricHook` | Fabric (`preLaunch`) | `fabric.mod.json` |
| `launchwrapper/LaunchWrapperHook` | Forge 1.8-1.12 (`ITweaker`) | `META-INF/MANIFEST.MF` `TweakClass` |
| `modlauncher/ModLauncherHook` | Forge 1.14+, NeoForge ≤ 1.21.1 (`ITransformationService`) | `META-INF/services/cpw.mods.modlauncher.api.ITransformationService` |
| `neoforge/NeoForgeLocator` | NeoForge without ModLauncher, FML 11+ (`IModFileCandidateLocator`) | `META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator` |

- **One service class for every ModLauncher version.** ModLauncher 2-8 declare `void beginScanning(IEnvironment)`,
  and 9+ a `List<Resource> beginScanning(IEnvironment)`. They can't be in one source file, but the JVM tells methods
  apart by their full descriptor, so they can share one class at runtime.
  - The real `LegacyBase` (in `stage0-legacy`) has the 2-8 methods.
  - `stage0/.../modlauncher/LegacyBase.java` is a **stub** so `ModLauncherHook` compiles against ModLauncher 10.
  - `maven-dependency-plugin` overwrites the stub's class with the real one at `prepare-package`.
  - Keep the stub's members the same as the real one's, apart from the 2-8 methods.
- **ModLauncher 2-8 (Forge 1.14-1.16):**
  - `runScan` only shows the jars to launch plugins: Mixin reads their configs from there.
  - `LegacyLocator` (through `additionalClassesLocator`/`additionalResourcesLocator`) is what puts them in the game's
    class loader.
  - ModLauncher keeps every service's prefixes in one static set, and **throws on a duplicate or invalid one, which
    crashes the launch**. `LegacyLocator` leaves out anything already claimed, `net.minecraft.*` and generic
    root-level files.
  - ModLauncher 2 (Forge 1.14.4) has neither, so patches can't load there.
- **ModLauncher 9+:** the jars become GAME-layer resources (`ModernResources`). `MODERN` (Java 21+, Forge 1.21+) skips
  `MixinBootstrap.init()`, which crashes there.
- **NeoForge 1.21.1 fires both hooks**, ModLauncher first, each from a different class loader. That's why
  `Stage0.claim` uses a system property, not a static field.
- Every hook calls `Stage0.claim(...)`, then `Stage0.run(...)`. Platform API types stay inside the hook class for
  that platform, so the other hooks never load them.

## Rules

- **Java 8 bytecode, compiled with JDK 21+.** The platform APIs we compile against (modern Forge, NeoForge) are newer
  class files, so nothing stops a post-8 API call at compile time. No `var`, `List.of`, `Path.of`, records and so on.
  `ModLauncherHook.isJava21()` is the one deliberate check, done by reflection.
- **Stage 0 almost never changes.** Users only get a new stage 0 by reinstalling, so anything that can live in the
  core goes in the core. Stage 0 depends on nothing but the JDK and the platform APIs its hooks implement (all
  `provided`). It must not use minecraft-lib, so `Settings.java` and `Stage0Log` are its own copies. **Keep
  `Settings`/`OS` in sync with the lib.**
- **The stage 0 ↔ core contract only grows.** An old stage 0 can run a new core, and the other way round. Only add
  keys to the map, bump `Stage0.CONTRACT` when you do, and make the core cope with a key that's missing. Never rename
  or remove `Bootstrap.run(Map)`.
- **Never crash the game.** Every hook and `Bootstrap.run` catch everything. The worst case is no patches this launch,
  with a log line saying why.
- **Respect GitHub's rate limit.** The unauthenticated API allows 60 calls an hour per IP. The core and every patch
  check at most once per `update.interval` (default 60 minutes). Don't add calls that run on every launch.
- **Verify before loading.** Every downloaded jar is checked against the asset's `digest` (`sha256:<hex>`), and every
  cached jar against the hash stored when it was downloaded.
- **Never write over a jar that may be open.** Another running game keeps the jar open, and Windows then refuses to
  replace it. Download to a new name, and delete old jars one at a time, best effort.
- **`patches.json` is read from `main` by every installed core.** One entry per patch repo: `name`, `classPaths` (any
  one present means the target is installed) and `repositoryName` (`Patch`). Loaders from before stage 0 are not
  supported anymore: they expected `classPath` as a single string. A format change breaks every core older than it,
  so prefer adding fields, which Gson ignores in older cores.

## Settings

`-Dprometheus.<key>=<value>` or `PROMETHEUS_<KEY>` (`Settings`):

| Key | Default | |
|---|---|---|
| `offline` | `false` | Never contact GitHub, use the cache |
| `update.interval` | `60` | Minutes between update checks |
| `core.path` | | Use this core jar instead of downloading one |
| `patch.<repo>.path` | | Use this jar for a patch, ex: `patch.minecraft-lunar.path` |
| `core.repository` | `prometheusreengineering/prometheus-minecraft` | `owner/repo` the core is downloaded from |
| `patches.url` | raw `patches.json` on `main` | Where the core reads the patch list |
| `github.api` | `https://api.github.com` | Another API host (tests, a mirror) |
| `http.timeout` | `10000` | Milliseconds |
| `debug` | `false` | Debug lines in the logs |
| `folder` | | Another prometheus folder |

## Build

There may be no `mvn` on the PATH. IntelliJ's bundled Maven works:
`%APPDATA%/JetBrains/IntelliJIdea<version>/plugins/maven-plugin/lib/maven3/bin/mvn`.

1. `mvn install` in `../minecraft-lib` first. The core depends on `minecraft-lib` `1.0.0`.
2. `mvn package` in **this repo's root** (`prometheus-parent`). Running it in `stage0/` alone fails, because Maven
   then can't see `stage0-legacy`. To build only stage 0: `mvn -pl stage0 -am package`.

Output: `stage0/target/prometheus-<version>-minecraft.jar` and `core/target/prometheus-core-<version>.jar`. Ignore
`original-*.jar` and the `stage0-legacy` jar.

Check after a build:

- the real `LegacyBase` is in the stage 0 jar (about 1 KB, with `runScan`):
  `javap -p -cp stage0/target/prometheus-*-minecraft.jar studio.dreamys.prometheus.stage0.modlauncher.LegacyBase`
- the classes are Java 8 (`major version: 52` in `javap -v`)
- the core jar contains `ing/prometheusreengineer/minecraft/**` and keeps its own manifest
  (`FMLModType: GAMELIBRARY`, `Automatic-Module-Name: prometheus.core`)

Compiler annotation processing is off (`<proc>none</proc>`), because Mixin's processor crashes javac without Gson on
the processor path.

## Testing

There are no automated tests in the repo yet. What has been used:

- **In game:**
  - Put only the stage 0 jar in `mods/`, and start with
    `-Dprometheus.core.path=<path>/core/target/prometheus-core-<version>.jar`.
  - Check `stage0.log` for which hook fired and `loader.log` for `N applicable patches` and `<patch>: loaded`.
  - Record the result in the README's **Compatibility** tables.
- **ModLauncher internals:** the ModLauncher jars are on Maven (`cpw.mods:modlauncher`,
  `net.minecraftforge:modlauncher`, `net.neoforged.fancymodloader:loader`). Their classes can be read with `javap`,
  and a small harness can instantiate `ModLauncherHook` and call into ModLauncher's own code. That's how
  `LegacyLocator` was checked against `TransformationServiceDecorator`.
- **Download and cache logic:** a fake GitHub on loopback (`-Dprometheus.github.api=http://127.0.0.1:<port>`,
  `-Dprometheus.folder=<temp>`) covered first launch, relaunch within the interval, offline, a tampered cache, a wrong
  digest, GitHub down, and a release without a core.

## Releasing

Attach **both** jars to a GitHub release. Stage 0 takes the newest `prometheus-core-*.jar` asset of the latest
release. Users only need a new stage 0 jar when stage 0 itself changed.

## Style

- Commits: `type: message` (`feat:`, `fix:`, `build:`, `docs:`, `chore:`), small, and each one builds on its own.
- Short lowercase `//` comments that explain why. Javadoc on classes and non-obvious methods, including which
  platform versions something was checked on.
- `org.jetbrains.annotations` (`@NotNull`, `@Nullable`) in the core. Stage 0 has no dependencies, so it has none.
- Utility classes are `final` with a private constructor.

## Known gaps

- Patches still load through their own mod metadata and mixin configs. Not built yet: calling their
  `PrometheusPatch.init`, and removing their entry points.
- Dawn isn't in `patches.json`. It needs its jsonproxy and Kotlin dependencies bundled first, and a check that
  `preLaunch` is early enough for it.
- The NeoForge locator path (FML 11+) has only been checked in game without a patch.
- Plan: `~/.claude/plans/okay-im-thinking-of-goofy-fern.md`.
