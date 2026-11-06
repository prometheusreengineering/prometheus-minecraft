# prometheus-minecraft

The Prometheus loader for Minecraft. It finds which patches apply to the running game, downloads them from GitHub
releases and loads them. Each patch is a repo of its own ([minecraft-lunar](https://github.com/prometheusreengineering/minecraft-lunar),
[minecraft-essential](https://github.com/prometheusreengineering/minecraft-essential), ...), listed in `patches.json`.

Shared code lives in [minecraft-lib](https://github.com/prometheusreengineering/minecraft-lib) (package
`ing.prometheusreengineer.minecraft`, on Maven at `https://maven.prometheusreengineer.ing/`). Its `CLAUDE.md` has the
rules for the lib itself: logging, `Settings`, the provided-dependency versions. Stage 1, which updates the lib and
starts the core, lives there too (`boot/`).

Where this is going, why, and what's left: [docs/ROADMAP.md](docs/ROADMAP.md).

## Architecture

```
installed: prometheus-<version>-minecraft.jar   (stage0/, JDK only, rarely changes)
   │  one hook per platform, the first to fire claims the JVM
   │  unpacks the lib it carries (minecraft-lib.jar inside it) and starts its stage 1
   ▼
the carried lib: minecraft-lib-<version>.jar   (stage 1: ing.prometheusreengineer.minecraft.boot.Stage1)
   │  finds the newest lib of its major version: lib.path, the cache, or Maven
   │  newer than itself → starts that one instead (the hand-off) and stops
   ▼
the newest lib
   │  puts its own jar on the game's class path, for the patches
   │  finds the newest core: core.path, the cache, or this repo's latest GitHub release
   │  starts it in a child-first loader parented on its own, so the core shares this lib
   ▼
the core: prometheus-core-<version>.jar   (core/, no lib inside)
   │  reads patches.json, keeps the patches whose target class is present
   │  downloads + verifies each, adds it to the class path, registers its mixin configs
   ▼
GitHub release of each patch repo: the patch jar
```

- **Stage 0 → stage 1** passes only JDK types: `Stage1.run(Map<String, Object>)`, called by reflection. The lib runs in
  stage 0's own child-first class loader. The keys are documented on `Stage0.CONTRACT`.
- **Stage 1 → core** is the same map plus `coreJar`, `libJar` and `libVersion`, to `Bootstrap.run(Map)`. The core
  never looks for a newer core: stage 1 already started the newest.
- **Stage 0 carries a whole lib jar, not its classes** (the EssentialLoader approach). Stage 1 puts the newest lib on
  the game's class path, and unpacked into stage 0 it would be there twice, which ModLauncher 9+ refuses. Stage 0
  extracts it to `<tmp>/prometheus/lib-<sha256>.jar` (named by content, so it never writes over an open jar).
- **Stage 0 must not use the lib, not even compile against it.** It isn't on stage 0's class path in the game, and a
  missing class fails while a hook links `Stage0`, outside every try: on Fabric that crashed the launch. Stage 0 has
  its own JDK-only `ChildFirstClassLoader` for loading the lib, and the build copies the lib jar in by its coordinates
  (`embed-lib`), not as a dependency.
- **The lib hand-off** (`Stage1.handOff`): the carried lib starts a newer one in a child-first loader that is its
  sibling (same parent, stage 0's loader), with `handedOffBy` and the new `libJar` added to the map. A lib with
  `handedOffBy` never looks again. Only libs of the same major version are taken, so lib updates (and updates to how
  cores are found) never need a new stage 0, and a new major always does.
- **Lib and core versions:** the core's `core.properties` has `lib.version`, the lib it was built against. Stage 1
  refuses a core that needs a newer lib or another major, with a log line and no patches that launch.
- **Two copies of the lib, one on the class path.** Stage 1 and the core share the copy in stage 1's loader. The
  patches see the jar stage 1 added to the game's class path. Stage 1 calls `Log.setOwner("loader")`.
- **Logs:** `<prometheus folder>/logs/loader.log`. Stage 0 can't find that folder without the lib, so it keeps its
  lines (and prints them to stdout, the game's log) until stage 1 takes them through `stage0Log`; they appear as
  `Prometheus/loader/lib/stage0`. The prometheus folder is `%APPDATA%/.minecraft/prometheus` on Windows
  (`OS.getConfigFolder()`).
- **Caches:** `<prometheus folder>/cache/`: `lib/`, `core/`, `patches/<repo>/`, `patches.json`. Each keeps a
  `.properties` file with the tag (or version), the asset, the file and its SHA-256.

## Modules

| Module | Artifact | What |
|---|---|---|
| `stage0-legacy/` | `prometheus-stage0-legacy` | The ModLauncher 2-8 half of the transformation service (`LegacyBase`), compiled against ModLauncher 8.0.9. |
| `core/` | `prometheus-core` → `prometheus-core-<version>.jar` | `Bootstrap`: patches.json and loading the patches, through the lib's `ReleaseResolver`. Compiled against minecraft-lib as `provided`; the lib isn't in the jar. |
| `stage0/` | `prometheus` → `prometheus-<version>-minecraft.jar` | The jar users install. The platform hooks, and the lib jar it carries (`minecraft-lib.jar` next to `Stage0`, copied in at `prepare-package`). |

The lib version both use is `minecraft-lib.version` in the parent pom.

### Stage 0 hooks (`stage0/src/main/java/ing/prometheusreengineer/minecraft/loader/stage0/`)

| Hook | Platform | Registered in |
|---|---|---|
| `fabric/FabricHook` | Fabric: a language adapter (before any entrypoint) starts stage 0, `preLaunch` registers the mixin configs (Mixin isn't running yet when language adapters load) | `fabric.mod.json` `languageAdapters` and `entrypoints` |
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
  lib or the core goes there. Stage 0 depends on nothing but the JDK and the platform APIs its hooks implement (all
  `provided`). It must not use minecraft-lib (see Architecture), and doesn't need to: it reads no settings and makes
  no network calls.
- **The contracts only grow.** An old stage 0 can run a new lib, and an old lib a new core. Only add keys to the
  maps, bump `Stage0.CONTRACT` when stage 0's grows, and make the receiver cope with a key that's missing. Never
  rename or remove `Stage1.run(Map)` or `Bootstrap.run(Map)`.
- **Never crash the game.** Every hook, `Stage1.run` and `Bootstrap.run` catch everything. The worst case is no
  patches this launch, with a log line saying why.
- **Respect GitHub's rate limit.** The unauthenticated API allows 60 calls an hour per IP. The lib, the core and every
  patch check at most once per `update.interval` (default 60 minutes). Don't add calls that run on every launch.
- **Verify before loading.** Every downloaded jar is checked against its published SHA-256 (a GitHub asset's
  `digest`, `sha256:<hex>`, or the lib's `.sha256` on Maven), and every cached jar against the hash stored when it was
  downloaded.
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
| `lib.path` | | Hand off to this lib jar, even if older |
| `lib.repository` | `https://maven.prometheusreengineer.ing/` | Maven repo the lib is downloaded from |
| `core.path` | | Start this core jar instead of the latest release |
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

1. The build needs `minecraft-lib` at `minecraft-lib.version` (parent pom), from the Prometheus Maven repo or from
   `~/.m2`. For an unreleased version, clone [minecraft-lib](https://github.com/prometheusreengineering/minecraft-lib)
   and run `mvn install` in it first.
2. `mvn package` in **this repo's root** (`prometheus-parent`). Running it in `stage0/` alone fails, because Maven
   then can't see `stage0-legacy`. To build only stage 0: `mvn -pl stage0 -am package`.

Output: `stage0/target/prometheus-<version>-minecraft.jar` and `core/target/prometheus-core-<version>.jar`. Ignore
the `stage0-legacy` jar. Build from `clean` after changing what stage 0 carries: a jar left in `target/classes` ends
up inside it.

Check after a build:

- the real `LegacyBase` is in the stage 0 jar (about 1 KB, with `runScan`):
  `javap -p -cp stage0/target/prometheus-*-minecraft.jar ing.prometheusreengineer.minecraft.loader.stage0.modlauncher.LegacyBase`
- the classes are Java 8 (`major version: 52` in `javap -v`)
- the stage 0 jar has `minecraft-lib.jar` next to `Stage0`, and none of its classes refer to the lib:
  `ing/prometheusreengineer/minecraft/(util|log|boot)/` appears in no class file
- the core jar has no lib classes, keeps its own manifest (`FMLModType: GAMELIBRARY`,
  `Automatic-Module-Name: prometheus.core`), and `core.properties` has `lib.version`

Compiler annotation processing is off (`<proc>none</proc>`), because Mixin's processor crashes javac without Gson on
the processor path.

## Testing

JUnit 5 in each module's `src/test/java`, run by `mvn test` (and so by `mvn package`). They never touch the network.

- **Coverage (JaCoCo):** each module's report is `target/site/jacoco/index.html`, and `mvn verify` fails below the
  minimum.
  - The minimum is 100% of lines and branches for `core` and `stage0-legacy`.
  - `stage0` is lower: the hooks' Mixin calls (`Mixins.addConfiguration`, `MixinBootstrap.init`,
    `MixinEnvironment.getDefaultEnvironment`) only work inside a running game. Everything else is covered.
  - Keep it that way: every new line gets a test.
- **Package-private seams:** a path a test can't reach on its own gets one, and the public behavior stays the same.
  - `ModLauncherHook.modern`
  - `Stage0.run(.., InputStream embedded)`, `extract(InputStream, Path)` and `digest(String)`
  - `LegacyLocator.unclaimed(.., owner, field)` and `url(String)`
  - `LaunchWrapperHook.url(URI)`
  - `Stage0.version(InputStream)` and `Bootstrap.version(InputStream)`
- **`FakeGitHub`** (core): the releases API, asset downloads and `patches.json` on the loopback address.
- **Stage 1, the lib hand-off and `ReleaseResolver` are tested in minecraft-lib** (`boot/`).
- **Stage 0's fake lib:** `stage0/src/test/java/ing/prometheusreengineer/minecraft/boot/Stage1.java`.
  - `TestSupport.fakeLib` jars it into `target/test-classes/.../stage0/minecraft-lib.jar`, where stage 0 looks for
    the lib it carries (the real one only reaches `target/classes` at `prepare-package`). `clear()` deletes it.
  - It loads in stage 0's child-first class loader, so tests drive it through `test.stage1.*` system properties.
- **Test folders:** tests that load a jar (stage 0 never closes the core's class loader) or write `loader.log` use
  `TestSupport.folder()` under `target/test-folders`, not `@TempDir`, because Windows won't delete a file that's
  still open.
- **The platform hooks are tested against the real platform jars:**
  - a `LaunchClassLoader`
  - Fabric's `FabricLoaderImpl` and launcher, set by reflection
  - NeoForge's interfaces as JDK proxies
- **Test-only dependencies:** SLF4J (which NeoForge's classes need).

Beyond the tests:

- **In game:**
  - Put only the stage 0 jar in `mods/`. It carries the lib it was built with. Until a release has a core asset,
    start with `-Dprometheus.core.path=<path>/core/target/prometheus-core-<version>.jar`, and
    `-Dprometheus.lib.path=<jar>` tries another lib.
  - Check `loader.log` for which hook fired (`Prometheus/loader/lib/stage0`), the lib check (`lib: ...`),
    `starting core`, `N applicable patches` and `<patch>: loaded`.
  - Prism Launcher can launch an instance from a shell: `prismlauncher --launch "<instance>" [--offline <name>]`.
    It needs the network for its metadata, and `--offline` only works once its libraries are downloaded.
  - Record the result in the README's **Compatibility** tables.
- **ModLauncher internals:** the ModLauncher jars are on Maven (`cpw.mods:modlauncher`,
  `net.minecraftforge:modlauncher`, `net.neoforged.fancymodloader:loader`). Their classes can be read with `javap`.
  The tests compile against ModLauncher 10, so checking something against an older ModLauncher's own code (like
  `LegacyLocator` against 8's `TransformationServiceDecorator`) needs a small harness run with that version.

## Releasing

1. Deploy the lib version in `minecraft-lib.version` to Maven first (with its `.sha256` files, see the lib's
   `CLAUDE.md`). A core needing a lib that isn't out yet won't run anywhere.
2. Attach **both** jars to a GitHub release. Stage 1 takes the `prometheus-core-*.jar` asset of the latest release.

Users only need a new stage 0 jar when stage 0 itself changed, or the lib's major version did.

## Style

- Commits: `type: message` (`feat:`, `fix:`, `build:`, `docs:`, `chore:`), small, and each one builds on its own.
- Short lowercase `//` comments that explain why. Javadoc on classes and non-obvious methods, including which
  platform versions something was checked on.
- `org.jetbrains.annotations` (`@NotNull`, `@Nullable`). In stage 0 it's `provided`: the annotations only exist at
  compile time, so the stage 0 jar still needs nothing but the JDK.
- Utility classes are `final` with a private constructor.

## Known gaps

- Patches still load through their own mod metadata and mixin configs. Not built yet: calling their
  `PrometheusPatch.init`, and removing their entry points.
- Dawn isn't in `patches.json`. It needs its jsonproxy and Kotlin dependencies bundled first, and a check that
  stage 0's language adapter runs early enough for it.
- Since stage 1 moved into the lib, only Fabric 1.21.4 (without patches) has been checked in game.
- `FabricHook` calls `Stage0` from a static block: anything thrown there (a linking error) crashes the launch instead
  of being logged.
- The NeoForge locator path (FML 11+) has only been checked in game without a patch.
- The full list, and the reasoning behind the design: [docs/ROADMAP.md](docs/ROADMAP.md).
