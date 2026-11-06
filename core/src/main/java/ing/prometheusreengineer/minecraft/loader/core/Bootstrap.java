package ing.prometheusreengineer.minecraft.loader.core;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import ing.prometheusreengineer.minecraft.log.Level;
import ing.prometheusreengineer.minecraft.log.Log;
import ing.prometheusreengineer.minecraft.util.io.AtomicFiles;
import ing.prometheusreengineer.minecraft.util.http.Http;
import ing.prometheusreengineer.minecraft.util.os.OS;
import ing.prometheusreengineer.minecraft.util.Settings;
import ing.prometheusreengineer.minecraft.util.java.ChildFirstClassLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * The core's entry point, called by stage 0 through {@link #run(Map)} with only JDK types (see stage 0's
 * {@code Stage0.CONTRACT} for the keys).
 * <br>
 * Stage 0 starts the core it carries, which may be older than the latest release, so this first looks for a newer
 * core and hands the launch over to it ({@link #handOff}). The core that keeps it puts its own jar (and so
 * minecraft-lib) on the game's class path for the patches, then does what the loader always did: read patches.json,
 * keep the patches whose target class is present, fetch each one's latest release, add it to the game's class path and
 * register its mixin configs. Runs inside stage 0's child-first class loader.
 */
public final class Bootstrap {
    private static final Log log = Log.get("loader");
    private static final Gson GSON = new Gson();
    private static final String PATCHES_URL = "https://raw.githubusercontent.com/prometheusreengineering/prometheus-minecraft/main/patches.json";
    private static final String CORE_PROPERTIES = "ing/prometheusreengineer/minecraft/loader/core/core.properties";
    /**
     * Set on the map given to the core this one handed off to, with this one's version, so that one doesn't look again.
     */
    private static final String HANDED_OFF_BY = "handedOffBy";
    private static final Pattern VERSION = Pattern.compile("^v?(\\d{1,9}(?:\\.\\d{1,9})*)");

    private final Map<String, Object> context;
    private final Consumer<Path> addToClasspath;
    private final Consumer<String> addMixinConfig;

    @SuppressWarnings("unchecked") //checked by stage0
    private Bootstrap(@NotNull Map<String, Object> context) {
        this.context = context;
        addToClasspath = (Consumer<Path>) context.get("addToClasspath");
        addMixinConfig = (Consumer<String>) context.get("addMixinConfig");
    }

    /**
     * Never throws: the worst case is no patches this launch.
     */
    @SuppressWarnings("unused") //used by stage0
    public static void run(@NotNull Map<String, Object> context) {
        // there's one copy of the lib in the game, the core's, so its own log lines belong to the loader
        Log.setOwner("loader");
        try {
            if (handOff(context, Bootstrap.class.getClassLoader().getParent())) return;
            attachStage0Log(context);
            new Bootstrap(context).patch();
        } catch (Throwable t) {
            log.error("the core failed, no patches this launch", t);
        }
    }

    /**
     * Starts the newest core instead of this one, if it's not this one. True if it did, then this copy has nothing
     * left to do. Never throws.
     *
     * @param parent stage 0's class loader: the new core is a sibling of this one, so none of this copy's classes leak
     *               into it
     */
    static boolean handOff(@NotNull Map<String, Object> context, @Nullable ClassLoader parent) {
        if (context.containsKey(HANDED_OFF_BY)) return false;
        Path jar = ReleaseResolver.resolveCore();
        if (jar == null || jar.equals(context.get("coreJar"))) return false;

        String own = version();
        String theirs = jarVersion(jar);
        // core.path is for development: always used, even if older
        if (Settings.get("core.path") == null && !newer(theirs, own)) {
            log.info("core %s is not older than %s (%s), keeping it", own, jar.getFileName(), theirs);
            return false;
        }

        Method run;
        try {
            ClassLoader loader = new ChildFirstClassLoader(jar, parent);
            run = Class.forName(Bootstrap.class.getName(), true, loader).getMethod("run", Map.class);
        } catch (Throwable t) {
            log.error("could not load core " + jar.getFileName() + ", keeping " + own, t);
            return false;
        }

        log.info("handing off from core %s to %s (%s)", own, jar.getFileName(), theirs);
        Map<String, Object> next = new HashMap<>(context);
        next.put(HANDED_OFF_BY, own);
        next.put("coreJar", jar);
        try {
            run.invoke(null, next);
        } catch (InvocationTargetException | IllegalAccessException e) {
            // it may have loaded patches already, so not this one too
            log.error("core " + theirs + " failed", e instanceof InvocationTargetException ? e.getCause() : e);
        }
        return true;
    }

    /**
     * Stage 0 2+ keeps its log lines until the core gives it somewhere to put them ({@code stage0Log}). Stage 0 1.x
     * writes its own stage0.log instead.
     */
    @SuppressWarnings("unchecked") //checked by stage0
    private static void attachStage0Log(@NotNull Map<String, Object> context) {
        Object stage0Log = context.get("stage0Log");
        if (stage0Log == null) return;
        Log lines = Log.get("loader", "stage0");
        ((Consumer<BiConsumer<String, String>>) stage0Log).accept((level, message) -> lines.log(level(level), null, message));
    }

    private static @NotNull Level level(@Nullable String name) {
        for (Level level : Level.values()) {
            if (level.name().equals(name)) return level;
        }
        return Level.INFO;
    }

    private void patch() {
        Object handedOffBy = context.get(HANDED_OFF_BY);
        log.info("core %s%s, stage0 %s (contract %s), platform %s", version(), handedOffBy != null ? " (handed off by core " + handedOffBy + ")" : "",
                context.get("stage0Version"), context.get("contract"), context.get("platform"));

        // patches compile against minecraft-lib as provided, so it has to be where their classes are
        Path coreJar = (Path) context.get("coreJar");
        log.info("adding %s to the class path for the patches", coreJar.getFileName());
        addToClasspath.accept(coreJar);

        List<Patch> applicable = new ArrayList<>();
        Set<String> repositories = new HashSet<>();
        for (Patch patch : patches()) {
            // one jar per repo, even if two entries point at it
            if (isTargetPresent(patch) && repositories.add(patch.repositoryName)) applicable.add(patch);
        }
        log.info("%d applicable patches: %s", applicable.size(), names(applicable));

        for (Patch patch : applicable) {
            try {
                Path jar = ReleaseResolver.resolve(patch.repositoryName);
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
     * The version of the core in {@code jar}, or "unknown".
     */
    static @NotNull String jarVersion(@NotNull Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry entry = file.getEntry(CORE_PROPERTIES);
            return version(entry == null ? null : file.getInputStream(entry));
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * Compares the leading dotted numbers ({@code v2.1.0-SNAPSHOT} is 2.1.0). A version without any is never newer, and
     * anything is newer than one without (a core built outside Maven).
     */
    static boolean newer(@NotNull String candidate, @NotNull String current) {
        int[] a = numbers(candidate);
        int[] b = numbers(current);
        if (a == null) return false;
        if (b == null) return true;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int @Nullable [] numbers(@NotNull String version) {
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.find()) return null;
        String[] parts = matcher.group(1).split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) numbers[i] = Integer.parseInt(parts[i]);
        return numbers;
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
