package ing.prometheusreengineer.minecraft.loader.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ing.prometheusreengineer.minecraft.log.Log;
import ing.prometheusreengineer.minecraft.util.io.AtomicFiles;
import ing.prometheusreengineer.minecraft.util.http.Hashing;
import ing.prometheusreengineer.minecraft.util.http.Http;
import ing.prometheusreengineer.minecraft.util.os.OS;
import ing.prometheusreengineer.minecraft.util.Settings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * A jar from a repo's latest GitHub release: a patch ({@link #resolve}) or a newer core ({@link #resolveCore}).
 * <ul>
 *     <li>GitHub is asked at most once per {@code update.interval} minutes (default 60).</li>
 *     <li>Downloads are checked against the asset's published SHA-256 ({@code digest}), and cached jars against the
 *     hash stored when they were downloaded.</li>
 *     <li>Offline, or if GitHub fails, the last verified jar is used.</li>
 * </ul>
 * {@code -Dprometheus.patch.<repo>.path} or {@code -Dprometheus.core.path} loads a local jar instead, for development.
 * {@code github.api} points at another API host (tests, a mirror).
 */
final class ReleaseResolver {
    private static final Log log = Log.get("loader", "releases");
    private static final Gson GSON = new Gson();
    private static final Map<String, String> HEADERS = Collections.singletonMap("Accept", "application/vnd.github+json");

    private static final String CORE_REPOSITORY = "prometheusreengineering/prometheus-minecraft";

    private ReleaseResolver() {}

    /**
     * A patch: the first {@code .jar} asset of {@code prometheusreengineering/<repository>}, cached in
     * {@code <prometheus folder>/cache/patches/<repository>/}. Null when there is no usable jar. Never throws.
     */
    static @Nullable Path resolve(@NotNull String repository) {
        Path dir = OS.getConfigFolder().resolve("cache").resolve("patches").resolve(repository);
        return resolve(repository, "prometheusreengineering/" + repository, "patch." + repository + ".path", dir, "release.properties", "");
    }

    /**
     * The core: the {@code prometheus-core-*.jar} asset of {@code core.repository}, cached in
     * {@code <prometheus folder>/cache/core/}, the same files stage 0 1.x used, so the update interval carries over.
     * Null when there is no usable jar. Never throws.
     */
    static @Nullable Path resolveCore() {
        Path dir = OS.getConfigFolder().resolve("cache").resolve("core");
        return resolve("core", Settings.get("core.repository", CORE_REPOSITORY), "core.path", dir, "core.properties", "prometheus-core-");
    }

    /**
     * @param label      what the log lines start with
     * @param repository {@code owner/repo}
     * @param assetPrefix the asset is the first {@code .jar} whose name starts with this
     */
    private static @Nullable Path resolve(@NotNull String label, @NotNull String repository, @NotNull String overrideKey, @NotNull Path dir, @NotNull String stateName, @NotNull String assetPrefix) {
        String override = Settings.get(overrideKey);
        if (override != null) {
            Path jar = Paths.get(override).toAbsolutePath();
            if (Files.isRegularFile(jar)) {
                log.info("%s: using %s from %s", label, jar, overrideKey);
                return jar;
            }
            log.warn("%s: %s %s is not a file", label, overrideKey, jar);
            return null;
        }

        Path stateFile = dir.resolve(stateName);
        Properties state = load(stateFile);
        Path cached = verifiedCache(dir, state, label);

        long checkedAt = parseLong(state.getProperty("checkedAt"));
        long interval = TimeUnit.MINUTES.toMillis(Settings.getInt("update.interval", 60));
        boolean due = System.currentTimeMillis() - checkedAt >= interval;

        if (Settings.getBoolean("offline", false)) {
            log.info("%s: offline, not checking for updates", label);
        } else if (cached == null || due) {
            try {
                return update(label, repository, dir, stateFile, state, cached, assetPrefix);
            } catch (Throwable t) {
                log.warn("%s: could not check for updates: %s", label, t.toString()); // expected offline, one line
            }
        }

        if (cached != null)
            log.info("%s: using cached %s (%s)", label, cached.getFileName(), state.getProperty("tag"));
        else log.warn("%s: no verified jar in %s", label, dir);
        return cached;
    }

    private static @NotNull Path update(@NotNull String label, @NotNull String repository, @NotNull Path dir, @NotNull Path stateFile, @NotNull Properties state, @Nullable Path cached, @NotNull String assetPrefix) throws IOException {
        String api = Settings.get("github.api", "https://api.github.com");
        String url = api + "/repos/" + repository + "/releases/latest";
        JsonObject release = GSON.fromJson(Http.getString(url, HEADERS), JsonObject.class);
        String tag = release.get("tag_name").getAsString();
        JsonObject asset = jar(release, tag, assetPrefix);
        String name = asset.get("name").getAsString();
        String digest = string(asset, "digest"); // "sha256:<hex>", absent on releases made before GitHub added it

        Path result;
        if (cached != null && tag.equals(state.getProperty("tag")) && name.equals(state.getProperty("asset"))) {
            log.info("%s: %s (%s) is up to date", label, name, tag);
            result = cached;
        } else {
            // Never write over a jar that may be open: another running game holds it (Windows won't replace it then),
            // and so does an earlier load in this JVM. A taken name (a corrupted copy) gets a fresh one instead.
            Path target = dir.resolve(name);
            if (Files.exists(target))
                target = dir.resolve(name.substring(0, name.length() - ".jar".length()) + "-" + System.currentTimeMillis() + ".jar");

            log.info("%s: downloading %s (%s)", label, name, tag);
            Http.download(asset.get("browser_download_url").getAsString(), target, digest);
            String sha256 = Hashing.sha256(target);
            if (digest == null)
                log.warn("%s: release %s publishes no digest, trusting the download (%s)", label, tag, sha256);
            state.setProperty("sha256", sha256);
            state.setProperty("asset", name);
            state.setProperty("file", target.getFileName().toString());
            deleteOthers(dir, target);
            result = target;
        }

        state.setProperty("tag", tag);
        state.setProperty("checkedAt", Long.toString(System.currentTimeMillis()));
        save(stateFile, state);
        return result;
    }

    /**
     * The first .jar asset starting with {@code prefix}. For patches that's any jar, as the loader always took (it used
     * to take assets[0]); a core release also has the stage 0 jar.
     */
    private static @NotNull JsonObject jar(@NotNull JsonObject release, @NotNull String tag, @NotNull String prefix) throws IOException {
        JsonArray assets = release.getAsJsonArray("assets");
        if (assets != null) {
            for (JsonElement element : assets) {
                JsonObject asset = element.getAsJsonObject();
                String name = string(asset, "name");
                if (name != null && name.startsWith(prefix) && name.endsWith(".jar")) return asset;
            }
        }
        throw new IOException("release " + tag + " has no " + prefix + "*.jar");
    }

    private static @Nullable Path verifiedCache(@NotNull Path dir, @NotNull Properties state, @NotNull String label) {
        String file = state.getProperty("file");
        String sha256 = state.getProperty("sha256");
        if (file == null || sha256 == null) return null;
        Path jar = dir.resolve(file);
        try {
            if (Hashing.matches(Hashing.sha256(jar), sha256)) return jar;
            log.warn("%s: cached %s changed, ignoring it", label, file);
        } catch (IOException e) {
            log.warn("%s: cached %s is missing or unreadable, ignoring it: %s", label, file, e);
        }
        return null;
    }

    /**
     * Best effort: a jar another running game still has open stays until a later launch.
     */
    static void deleteOthers(@NotNull Path dir, @NotNull Path keep) {
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path jar : jars) {
                if (jar.equals(keep)) continue;
                try {
                    Files.deleteIfExists(jar);
                } catch (IOException e) {
                    log.info("old %s is still in use, leaving it for now", jar.getFileName());
                }
            }
        } catch (IOException e) {
            log.warn("could not list old jars in %s: %s", dir, e);
        }
    }

    static @NotNull Properties load(@NotNull Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) return properties;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("ignoring unreadable %s: %s", file, e);
        }
        return properties;
    }

    static void save(@NotNull Path file, @NotNull Properties properties) {
        try {
            StringWriter out = new StringWriter();
            properties.store(out, "Prometheus core: the cached release");
            AtomicFiles.writeString(file, out.toString());
        } catch (IOException e) {
            log.warn("could not save %s: %s", file, e);
        }
    }

    private static @Nullable String string(@NotNull JsonObject object, @NotNull String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static long parseLong(@Nullable String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
