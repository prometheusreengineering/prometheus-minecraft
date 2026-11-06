package ing.prometheusreengineer.minecraft.loader.stage0.modlauncher;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import ing.prometheusreengineer.minecraft.loader.stage0.Loader;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import ing.prometheusreengineer.minecraft.loader.stage0.Stage0;
import ing.prometheusreengineer.minecraft.loader.stage0.Stage0Log;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Forge 1.14+ and NeoForge up to 1.21.1: one {@code ITransformationService} for every ModLauncher version.
 * <br>
 * The ModLauncher 2-8 methods ({@code void beginScanning}, {@code runScan}) are in {@link LegacyBase}, compiled
 * against ModLauncher 8, because ModLauncher 9+'s {@code List<Resource> beginScanning} can't share a source file with
 * them. Everything here has the same descriptor in every version. Tested in game on Forge 1.14.4-26.3 and NeoForge
 * 1.21.1. ModLauncher calls {@code onLoad} first, then {@code initialize}, the scan, and {@code transformers}.
 */
public class ModLauncherHook extends LegacyBase {
    /**
     * ModLauncher 9+: module layers, and Mixin as a ModLauncher service of its own. Not final so tests can take both
     * paths on one JVM.
     */
    static boolean layered = hasClass("cpw.mods.modlauncher.api.IModuleLayerManager");

    private final List<Path> jars = new ArrayList<>();
    private final List<String> mixinConfigs = new ArrayList<>();
    private boolean active;
    private LegacyLocator legacyLocator;

    @Override
    public @NotNull String name() {
        return "prometheus-minecraft";
    }

    @Override
    public void initialize(IEnvironment environment) {
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
        Stage0.run(Loader.MODLAUNCHER, Paths.get("").toAbsolutePath(), jars::add, mixinConfigs::add);
        active = true;
        // 9+ registers them in completeScan: Mixin is running by then, and the jars are about to join the GAME layer
        if (!jars.isEmpty() && !layered) {
            try {
                MixinBootstrap.init();
                MixinEnvironment.getDefaultEnvironment();
            } catch (Throwable ignored) {
                // already initialized by another mod, or (1.14.4) no Mixin at all
            }
            addMixinConfigs();
        }
    }

    /**
     * Mixin reads a config as soon as it's added, through the context class loader, and the jars only join the game's
     * class loader after the scan. So they're made visible there while it reads them.
     */
    private void addMixinConfigs() {
        List<URL> urls = new ArrayList<>();
        for (Path jar : jars) LegacyLocator.url(jar.toUri().toString()).ifPresent(urls::add);
        ClassLoader backup = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(urls.toArray(new URL[0]), backup));
        try {
            for (String config : mixinConfigs) {
                try {
                    Mixins.addConfiguration(config);
                } catch (Throwable t) {
                    Stage0Log.error("could not register mixin config " + config, t);
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(backup);
        }
    }

    /**
     * ModLauncher 9+ adds only the PLUGIN entries of these to a layer, and drops GAME ones (checked in 9.1.3, 10.2.4
     * and 11.0.4), so the jars come from {@link #completeScan} instead.
     */
    @Override
    public List<ITransformationService.Resource> beginScanning(IEnvironment environment) {
        return Collections.emptyList();
    }

    /**
     * ModLauncher 9+: the jars go in the GAME layer, with the mods they patch. Never called on 2-8, so ModernResources
     * (and its 9+ types) never load there.
     */
    @Override
    public List<ITransformationService.Resource> completeScan(IModuleLayerManager layerManager) {
        if (!active) return Collections.emptyList();
        // nothing reads the jars' own MixinConfigs: they aren't mods (checked on Forge 1.18.2)
        addMixinConfigs();
        return ModernResources.of(jars);
    }

    /**
     * ModLauncher 4-8, through {@link LegacyBase#runScan}. This only shows the jars to launch plugins (Mixin finds
     * their configs here); {@link #additionalClassesLocator} is what puts them in the game's class loader.
     */
    @SuppressWarnings("JavadocReference") //unreachable link to other module
    @Override
    protected List<Map.Entry<String, Path>> legacyScan() {
        List<Map.Entry<String, Path>> entries = new ArrayList<>();
        if (!active) return entries;
        for (Path jar : jars) entries.add(new AbstractMap.SimpleImmutableEntry<>(jar.getFileName().toString(), jar));
        if (!jars.isEmpty()) legacyLocator = new LegacyLocator(jars);
        return entries;
    }

    /*
     * ModLauncher 4-10 ask for these when they build the game's class loader, after the scan. Only JDK types, so they
     * need no LegacyBase trick. ModLauncher 9-10 also ask, but there the jars are already GAME-layer resources, so
     * this only answers when runScan ran (4-8). Not @Override: NeoForge's ModLauncher doesn't have them.
     */
    @SuppressWarnings("removal") //deprecated stuff, we basically breathe it
    public Map.Entry<Set<String>, Supplier<Function<String, Optional<URL>>>> additionalClassesLocator() {
        return legacyLocator == null ? null : legacyLocator.classes();
    }

    @SuppressWarnings("removal") //deprecated stuff, we basically breathe it
    public Map.Entry<Set<String>, Supplier<Function<String, Optional<URL>>>> additionalResourcesLocator() {
        return legacyLocator == null ? null : legacyLocator.resources();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public @NotNull List<ITransformer> transformers() {
        return Collections.emptyList();
    }

    /**
     * Whether this hook's class loader can see {@code name}: ModLauncher's API, here.
     */
    static boolean hasClass(String name) {
        try {
            Class.forName(name, false, ModLauncherHook.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
