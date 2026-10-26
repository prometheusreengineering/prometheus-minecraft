package studio.dreamys.prometheus.stage0.modlauncher;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import studio.dreamys.prometheus.stage0.Stage0;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Forge 1.14+ and NeoForge up to 1.21.1: one {@code ITransformationService} for every ModLauncher version.
 * <br>
 * The ModLauncher 2-8 methods ({@code void beginScanning}, {@code runScan}) are in {@link LegacyBase}, compiled
 * against ModLauncher 8, because ModLauncher 9+'s {@code List<Resource> beginScanning} can't share a source file with
 * them. Everything here has the same descriptor in every version. Tested in game on Forge 1.14.4-26.3 and NeoForge
 * 1.21.1. ModLauncher calls {@code onLoad} first, then {@code initialize}, the scan, and {@code transformers}.
 */
public class ModLauncherHook extends LegacyBase {
    /** Java 21+: Forge 1.21+, where the patches go in the GAME layer and Mixin bootstraps itself. */
    private static final boolean MODERN = isJava21();

    private final List<Path> jars = new ArrayList<>();
    private final List<String> mixinConfigs = new ArrayList<>();
    private boolean active;

    @Override
    public String name() {
        return "prometheus-minecraft";
    }

    @Override
    public void initialize(IEnvironment environment) {
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
        if (!Stage0.claim("modlauncher")) return;
        active = true;
        Stage0.run("modlauncher", Paths.get("").toAbsolutePath(), jars::add, mixinConfigs::add);
        if (!jars.isEmpty()) registerMixins();
    }

    /**
     * Unchanged from the loader before stage 0, where it works on 1.18.2 and 1.21.x: the jars only join the game's
     * class loader after the scan, so make them visible to Mixin through the context class loader while it reads them.
     */
    private void registerMixins() {
        ClassLoader backup = Thread.currentThread().getContextClassLoader();
        try {
            URL[] urls = new URL[jars.size()];
            for (int i = 0; i < urls.length; i++) urls[i] = jars.get(i).toUri().toURL();
            Thread.currentThread().setContextClassLoader(new URLClassLoader(urls, backup));

            if (MODERN) {
                // 1.21+ bootstraps Mixin strictly as a ModLauncher service, calling MixinBootstrap.init() crashes
                MixinEnvironment.getDefaultEnvironment();
            } else {
                try {
                    MixinBootstrap.init();
                    MixinEnvironment.getDefaultEnvironment();
                } catch (Throwable ignored) {
                    // already initialized by another mod, or (1.14.4) no Mixin at all
                }
                for (String config : mixinConfigs) Mixins.addConfiguration(config);
            }
        } catch (Throwable t) {
            // "Environment conflict, mismatched versions or you didn't call MixinBootstrap.init()" is a known,
            // harmless side effect of this
            System.out.println("[Prometheus/stage0] (WARN) Mixin environment setup threw: " + t);
        } finally {
            Thread.currentThread().setContextClassLoader(backup);
        }
    }

    /** ModLauncher 9+. Never called on 2-8, so ModernResources (and its 9+ types) never load there. */
    @Override
    public List<ITransformationService.Resource> beginScanning(IEnvironment environment) {
        if (!active) return Collections.emptyList();
        return ModernResources.of(jars, MODERN);
    }

    /** ModLauncher 4-8, through {@link LegacyBase#runScan}. */
    @Override
    protected List<Map.Entry<String, Path>> legacyScan() {
        List<Map.Entry<String, Path>> entries = new ArrayList<>();
        if (!active) return entries;
        for (Path jar : jars) entries.add(new AbstractMap.SimpleImmutableEntry<>(jar.getFileName().toString(), jar));
        return entries;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<ITransformer> transformers() {
        return Collections.emptyList();
    }

    private static boolean isJava21() {
        try {
            List.class.getMethod("getFirst");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
