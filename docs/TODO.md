# What's missing

The open work, most urgent first. The plan and the reasoning behind it are in [ROADMAP.md](ROADMAP.md). Last updated
2026-10-27.

## Before merging `stage0-core` and releasing

1. **Re-test the current build in game.** The in-game tests ran before `patches.json` switched to `classPaths` and
   before minecraft-lib was refactored for coverage. One launch on Forge 1.8.9 and one on Fabric is enough. Until the
   branch is merged, point the core at the branch's `patches.json`:
   `-Dprometheus.patches.url=https://raw.githubusercontent.com/prometheusreengineering/prometheus-minecraft/stage0-core/patches.json`
2. **Launch the versions nobody has tried yet:**
   - Forge 1.20.1, the Java 17 route
   - NeoForge 1.21.8
   - Fabric 1.16.5 (the oldest) and 26.3 (the newest)

   The README's Compatibility section has the full table.
3. **Release in the right order.** Merging changes `patches.json` on `main`:
   - Loaders installed before stage 0 stop loading patches as soon as it's merged.
   - The new stage 0 does nothing until a release has a `prometheus-core-*.jar`.

   So merge and publish the release (with **both** jars) together, and tell users to replace their jar.

## Infrastructure

4. ~~**Automated tests for the loader.**~~ Done:
   - JUnit tests in every module, with JaCoCo coverage enforced by `mvn verify`
   - `core` and `stage0-legacy` at 100%
   - `stage0` at 100% except the Mixin calls in the hooks, which only run in a game
5. **CI.** Nothing runs minecraft-lib's tests or its coverage check on push, and loader releases are built and
   uploaded by hand. A GitHub Actions workflow could build both jars and attach them to the release, so neither one
   can be forgotten.
6. **Publish minecraft-lib.** It only exists in each developer's `~/.m2` after a `mvn install`. Anyone else, and any
   CI, has to clone and install it before the loader or a patch can build. GitHub Packages or JitPack would fix this.

## Phase 3: the patches

7. **The core calls `PrometheusPatch.init`.** It needs the right moment on each platform. On ModLauncher that's after
   the GAME layer exists.
8. **Convert the patches** ([minecraft-lunar](https://github.com/prometheusreengineering/minecraft-lunar),
   [minecraft-essential](https://github.com/prometheusreengineering/minecraft-essential),
   [minecraft-dawn](https://github.com/prometheusreengineering/minecraft-dawn)):
   - use minecraft-lib as `provided`
   - remove their own entry points and mod metadata
   - add one `PrometheusPatch`
9. **Dawn:**
   - bundle and rename jsonproxy and the Kotlin standard library in its jar
   - add it to `patches.json`
   - check that Fabric `preLaunch` still sets `DAWN_API_URL` before Feather reads it
10. **Mixins on ModLauncher 1.21+.** Find out whether patch mixins load through the core's registration or through the
    patch's `mods.toml` `[[mixins]]`, before that metadata is deleted.
11. **`minCore` in `patches.json`:** the oldest core a patch works with, once patches use lib API newer than a cached
    core might have.

## Risks to keep in mind

12. **`Env` uses `sun.misc.Unsafe` methods the JDK deprecated for removal** (JEP 471). Java 24+ warns when they're
    used, and Minecraft 26.x runs on Java 25, which is where Dawn uses `Env`. It works today, but a future Java will
    remove them.
13. **Stage 0 has its own copy of minecraft-lib's `Settings`/`OS`**, kept in sync by hand, because stage 0 can't
    depend on the lib.
14. **Not checked in game yet:**
    - Lunar with `Console.STDERR` as its default console
    - the NeoForge locator (FML 11+) loading an actual patch
