# prometheus-minecraft
The Prometheus' loader for Minecraft.

![Compatibility: Infinite](https://img.shields.io/badge/COMPATIBILITY-1.8--1.12_%7C_1.15%2B-0?style=for-the-badge)
[![Download Count](https://img.shields.io/github/downloads/prometheusreengineering/prometheus-minecraft/total?style=for-the-badge)](https://github.com/prometheusreengineering/prometheus-minecraft/releases/)
[![Discord](https://img.shields.io/discord/1197794960985043034?style=for-the-badge&label=Discord&color=rgb(88%2C%20101%2C%20242)%20)](https://discord.gg/BFDWmPfmXg)

## Features
- Analyzes classpath and environment to always load the right patches.
- Lightweight and slightly invasive due to the overcomplicated inner workings of modern Forge (1.18+).
- Serves as a patch autoupdater, downloading patches on demand (with cache) from GitHub releases.
- Compatible with Fabric, Forge 1.8.9-1.12.2, Forge 1.15+ and NeoForge (see [Compatibility](#compatibility)).

## Compatibility
Against the versions Essential ships. ✅ means a patch was loaded in game through stage 0 and the core. The others use
the same route as a tested version but haven't been launched yet. Last updated 2026-10-26.

### Forge
| Versions | Route in stage 0 | Status |
|---|---|---|
| 1.8.9, 1.12.2 | LaunchWrapper tweaker | ✅ Tested (Essential; Lunar on 1.8.9) |
| 1.16.5 | ModLauncher 8, plus `LegacyLocator` to reach the game's class loader | ✅ Tested (Essential) |
| 1.21.1 | ModLauncher 10, Java 21 | ✅ Tested (Essential) |
| 1.21.4, 1.21.8 | Same as 1.21.1 | Should work |
| 1.18.2, 1.19.2, 1.20.1 | ModLauncher 9/10, Java 17 | Should work, test one (1.20.1) |

On Java 17 (1.18.2-1.20.1), stage 0 registers mixins the older way: `MixinBootstrap.init`, then `Mixins.addConfiguration`. The loader did the same before stage 0.

### NeoForge
| Versions | Route in stage 0 | Status |
|---|---|---|
| 1.21.1 | ModLauncher 11 (the NeoForge locator also fires, the first hook claims the launch) | ✅ Tested (Essential) |
| 1.21.4, 1.21.8 | Most likely ModLauncher 11, like 1.21.1 | Should work, test one (1.21.8) |

If a version no longer uses ModLauncher, it takes the NeoForge locator (`IModFileCandidateLocator`) instead. That has only been checked on NeoForge 26.3, without a patch.

### Fabric
| Versions | Route in stage 0 | Status |
|---|---|---|
| 1.21.11 | `preLaunch` entrypoint | ✅ Tested (Essential, Lunar) |
| 1.16.5, 1.18.2, 1.19.2, 1.20.1, 1.21.1, 1.21.4, 1.21.8, 1.21.10, 26.1, 26.1.1, 26.1.2, 26.2, 26.3 | Same `preLaunch` | Should work, test the oldest (1.16.5, Java 8) and newest (26.3, Java 25) |

### Not covered
- **Forge 1.14.4:** stage 0 starts, but ModLauncher 2 has no way to put the patch jars in the game's class loader. Essential doesn't support 1.14.
- **Forge 1.13.2:** Forge itself doesn't start.
- **Dawn:** not in `patches.json` yet.

## Layout
- `stage0/`: the jar users install (`prometheus-<version>-minecraft.jar`). It only depends on the JDK, and should rarely change.
  - One hook per platform: Fabric `preLaunch`, the LaunchWrapper tweaker (Forge 1.8-1.12), a ModLauncher transformation service (Forge 1.14+ and NeoForge up to 1.21.1), and a NeoForge locator (FML 11+, which has no ModLauncher).
  - The first hook to run downloads the core from this repo's latest release, verifies it and caches it, then starts it.
- `stage0-legacy/`: half of the ModLauncher hook. The `void beginScanning` of ModLauncher 2-8 can't share a source file with the `List` one of 9+, so this half is compiled separately and swapped into the stage0 jar.
- `core/`: the loader logic plus [minecraft-lib](https://github.com/prometheusreengineering/minecraft-lib) (`prometheus-core-<version>.jar`). It's downloaded and updated automatically, and loads the patches listed in `patches.json`.

Everything goes to `<prometheus folder>/logs/` (`stage0.log`, `loader.log`), and downloads are cached in `<prometheus folder>/cache/`. The prometheus folder is `%APPDATA%/.minecraft/prometheus` on Windows.

Settings, as `-Dprometheus.<key>=<value>` or `PROMETHEUS_<KEY>`:

| Key | Default | |
|---|---|---|
| `offline` | `false` | Never contact GitHub, use the cache |
| `update.interval` | `60` | Minutes between update checks |
| `core.path` | | Use this core jar instead of downloading one (development) |
| `patch.<repo>.path` | | Use this jar for a patch, ex: `patch.minecraft-lunar.path` (development) |
| `debug` | `false` | Debug lines in the logs |
| `folder` | | Another prometheus folder |

## Compiling
Use JDK 21+. Everything still targets Java 8, but the platform APIs we compile against (modern Forge, NeoForge) are newer class files. Be careful not to use APIs newer than Java 8.

1. `mvn install` in [minecraft-lib](https://github.com/prometheusreengineering/minecraft-lib). The core includes it.
2. `mvn package` in this repo's root (`prometheus-parent`), not in `stage0/`: stage0 needs the `stage0-legacy` module built in the same run. To build only stage0, use `mvn -pl stage0 -am package`. This builds `stage0/target/prometheus-<version>-minecraft.jar` and `core/target/prometheus-core-<version>.jar`.

## Releasing
Attach **both** jars to a GitHub release. Stage 0 picks the newest `prometheus-core-*.jar` asset of the latest release and checks it against the SHA-256 GitHub publishes for it. Users only need a new stage0 jar if stage 0 itself changed.

## Adding patches
- Add your entry to `patches.json`
```json
{
    "name": "", // name of the patch (used for logging)
    "classPaths": [""], // classes of the target, one per platform if they differ (the patch applies if any of them is present)
    "repositoryName": "" // name of the github repository (used to download the patch from release)
}
```

## Disclaimer
This project is intended for educational purposes only. We are not responsible for any damage caused by this project.

## License
GPLv3 © Prometheus Reengineering
