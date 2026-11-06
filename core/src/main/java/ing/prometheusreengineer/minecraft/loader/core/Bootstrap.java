package ing.prometheusreengineer.minecraft.loader.core;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import ing.prometheusreengineer.minecraft.boot.ReleaseResolver;
import ing.prometheusreengineer.minecraft.log.Log;
import ing.prometheusreengineer.minecraft.util.io.AtomicFiles;
import ing.prometheusreengineer.minecraft.util.http.Http;
import ing.prometheusreengineer.minecraft.util.os.OS;
import ing.prometheusreengineer.minecraft.util.Settings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * The core's entry point, called by minecraft-lib's stage 1 ({@code ing.prometheusreengineer.minecraft.boot.Stage1})
 * through {@link #run(Map)} with only JDK types: stage 0's map (see its {@code Stage0.CONTRACT}), plus {@code coreJar},
 * {@code libJar} and {@code libVersion}.
 * <br>
 * Stage 1 has already updated the lib, put it on the game's class path and started the newest core in a child-first
 * class loader parented on its own, so this shares stage 1's copy of the lib. What's left is what the loader always
 * did: read patches.json, keep the patches whose target class is present, fetch each one's latest release, add it to
 * the game's class path and register its mixin configs.
 */
public final class Bootstrap {
    private static final Log log = Log.get("loader");
    private static final Gson GSON = new Gson();
    private static final String PATCHES_URL = "https://raw.githubusercontent.com/prometheusreengineering/prometheus-minecraft/main/patches.json";

    private final Map<String, Object> context;
    private final Consumer<Path> addToClasspath;
    private final Consumer<String> addMixinConfig;

    @SuppressWarnings("unchecked") //checked by stage 0
    private Bootstrap(@NotNull Map<String, Object> context) {
        this.context = context;
        addToClasspath = (Consumer<Path>) context.get("addToClasspath");
        addMixinConfig = (Consumer<String>) context.get("addMixinConfig");
    }

    /**
     * Never throws: the worst case is no patches this launch.
     */
    @SuppressWarnings("unused") //called by stage 1
    public static void run(@NotNull Map<String, Object> context) {
        try {
            new Bootstrap(context).patch();
        } catch (Throwable t) {
            log.error("the core failed, no patches this launch", t);
        }
    }

    private void patch() {
        log.info("core %s on lib %s, stage0 %s (contract %s), platform %s", version(), context.get("libVersion"),
                context.get("stage0Version"), context.get("contract"), context.get("platform"));

        List<Patch> applicable = new ArrayList<>();
        Set<String> repositories = new HashSet<>();
        for (Patch patch : patches()) {
            // one jar per repo, even if two entries point at it
            if (isTargetPresent(patch) && repositories.add(patch.repositoryName)) applicable.add(patch);
        }
        log.info("%d applicable patches: %s", applicable.size(), names(applicable));

        for (Patch patch : applicable) {
            try {
                Path jar = ReleaseResolver.patch(patch.repositoryName);
                if (jar == null) {
                    log.warn("%s: skipped, no jar", patch.name);
                    continue;
                }
                addToClasspath.accept(jar);
                for (String config : mixinConfigs(jar)) addMixinConfig.accept(config);
                log.info("%s: loaded %s", patch.name, jar.getFileName());
            } catch (Throwable t) {
                // one broken patch must not take the others down
                log.error(patch.name + ": failed to load", t);
            }
        }
    }

    /**
     * patches.json from GitHub, else the last copy that was fetched.
     */
    private static @NotNull List<Patch> patches() {
        Path cache = OS.getConfigFolder().resolve("cache").resolve("patches.json");
        String json = null;
        if (!Settings.getBoolean("offline", false)) {
            try {
                json = Http.getString(Settings.get("patches.url", PATCHES_URL), Collections.emptyMap());
                AtomicFiles.writeString(cache, json);
            } catch (IOException e) {
                log.warn("could not fetch patches.json: %s", e.toString()); // expected offline, one line
            }
        }
        try {
            if (json == null && Files.isRegularFile(cache)) {
                json = new String(Files.readAllBytes(cache), StandardCharsets.UTF_8);
                log.info("using the cached patches.json");
            }
            if (json == null) return Collections.emptyList();
            List<Patch> patches = GSON.fromJson(json, new TypeToken<List<Patch>>() {}.getType());
            return patches == null ? Collections.emptyList() : patches;
        } catch (IOException | JsonParseException e) {
            log.error("patches.json is unreadable", e);
            return Collections.emptyList();
        }
    }

    /**
     * Any of the patch's classes: its target usually has a different entry class on each platform.
     */
    private static boolean isTargetPresent(@NotNull Patch patch) {
        if (patch.classPaths == null) return false;
        for (String className : patch.classPaths) {
            if (className != null && isClassPresent(className)) return true;
        }
        return false;
    }

    /**
     * Several class loaders, because which one can see the target differs between platforms.
     */
    private static boolean isClassPresent(@NotNull String className) {
        ClassLoader[] loaders = {Thread.currentThread().getContextClassLoader(), Bootstrap.class.getClassLoader(), ClassLoader.getSystemClassLoader()};
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                Class.forName(className, false, loader);
                return true;
            } catch (Throwable ignored) {
                // not here
            }
        }
        return false;
    }

    private static @NotNull List<String> mixinConfigs(@NotNull Path jar) throws IOException {
        List<String> configs = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(file.entries())) {
                if (entry.getName().endsWith(".mixins.json")) configs.add(entry.getName());
            }
        }
        return configs;
    }

    private static @NotNull String names(@NotNull List<Patch> patches) {
        List<String> names = new ArrayList<>();
        for (Patch patch : patches) names.add(patch.name);
        return names.toString();
    }

    private static @NotNull String version() {
        return version(Bootstrap.class.getResourceAsStream("core.properties"));
    }

    /**
     * The {@code version} in a core.properties, or "unknown" if there's none or it can't be read.
     */
    static @NotNull String version(@Nullable InputStream properties) {
        try (InputStream in = properties) {
            Properties read = new Properties();
            if (in != null) read.load(in);
            return read.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }
}
