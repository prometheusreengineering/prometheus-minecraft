package studio.dreamys.prometheus.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ing.prometheusreengineer.minecraft.log.Log;
import ing.prometheusreengineer.minecraft.util.AtomicFiles;
import ing.prometheusreengineer.minecraft.util.Hashing;
import ing.prometheusreengineer.minecraft.util.Http;
import ing.prometheusreengineer.minecraft.util.OS;
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
 * A patch jar: the first {@code .jar} asset of a repo's latest GitHub release, cached in
 * {@code <prometheus folder>/cache/patches/<repo>/}. Works like stage 0's CoreResolver:
 * <ul>
 *     <li>GitHub is asked at most once per {@code update.interval} minutes (default 60).</li>
 *     <li>Downloads are checked against the asset's published SHA-256 ({@code digest}), and cached jars against the
 *     hash stored when they were downloaded.</li>
 *     <li>Offline, or if GitHub fails, the last verified jar is used.</li>
 * </ul>
 * {@code -Dprometheus.patch.<repo>.path} loads a local jar instead, for development. {@code github.api} points at
 * another API host (tests, a mirror).
 */
final class ReleaseResolver {
    private static final Log log = Log.get("loader", "releases");
    private static final Gson GSON = new Gson();
    private static final Map<String, String> HEADERS = Collections.singletonMap("Accept", "application/vnd.github+json");

    private ReleaseResolver() {}

    /**
     * Null when there is no usable jar. Never throws.
     */
    static @Nullable Path resolve(@NotNull String repository) {
        String override = Settings.get("patch." + repository + ".path");
        if (override != null) {
            Path jar = Paths.get(override).toAbsolutePath();
            if (Files.isRegularFile(jar)) {
                log.info("%s: using %s from patch.%s.path", repository, jar, repository);
                return jar;
            }
            log.warn("%s: patch.%s.path %s is not a file", repository, repository, jar);
            return null;
        }

        Path dir = OS.getConfigFolder().resolve("cache").resolve("patches").resolve(repository);
        Path stateFile = dir.resolve("release.properties");
        Properties state = load(stateFile);
        Path cached = verifiedCache(dir, state, repository);

        long checkedAt = parseLong(state.getProperty("checkedAt"));
        long interval = TimeUnit.MINUTES.toMillis(Settings.getInt("update.interval", 60));
        boolean due = System.currentTimeMillis() - checkedAt >= interval;

        if (Settings.getBoolean("offline", false)) {
            log.info("%s: offline, not checking for updates", repository);
        } else if (cached == null || due) {
            try {
                return update(repository, dir, stateFile, state, cached);
            } catch (Throwable t) {
                log.warn("%s: could not check for updates: %s", repository, t.toString()); // expected offline, one line
            }
        }

        if (cached != null)
            log.info("%s: using cached %s (%s)", repository, cached.getFileName(), state.getProperty("tag"));
        else log.warn("%s: no verified jar in %s", repository, dir);
        return cached;
    }

    private static @NotNull Path update(@NotNull String repository, @NotNull Path dir, @NotNull Path stateFile, @NotNull Properties state, @Nullable Path cached) throws IOException {
        String api = Settings.get("github.api", "https://api.github.com");
        String url = api + "/repos/prometheusreengineering/" + repository + "/releases/latest";
        JsonObject release = GSON.fromJson(Http.getString(url, HEADERS), JsonObject.class);
        String tag = release.get("tag_name").getAsString();
        JsonObject asset = firstJar(release, tag);
        String name = asset.get("name").getAsString();
        String digest = string(asset, "digest"); // "sha256:<hex>", absent on releases made before GitHub added it

        Path result;
        if (cached != null && tag.equals(state.getProperty("tag")) && name.equals(state.getProperty("asset"))) {
            log.info("%s: %s (%s) is up to date", repository, name, tag);
            result = cached;
        } else {
            // never write over a jar that may be open (another running game, see stage 0's CoreResolver)
            Path target = dir.resolve(name);
            if (Files.exists(target))
                target = dir.resolve(name.substring(0, name.length() - ".jar".length()) + "-" + System.currentTimeMillis() + ".jar");

            log.info("%s: downloading %s (%s)", repository, name, tag);
            Http.download(asset.get("browser_download_url").getAsString(), target, digest);
            String sha256 = Hashing.sha256(target);
            if (digest == null)
                log.warn("%s: release %s publishes no digest, trusting the download (%s)", repository, tag, sha256);
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
     * The first .jar asset, as the loader always took (it used to take assets[0]).
     */
    private static @NotNull JsonObject firstJar(@NotNull JsonObject release, @NotNull String tag) throws IOException {
        JsonArray assets = release.getAsJsonArray("assets");
        if (assets != null) {
            for (JsonElement element : assets) {
                JsonObject asset = element.getAsJsonObject();
                String name = string(asset, "name");
                if (name != null && name.endsWith(".jar")) return asset;
            }
        }
        throw new IOException("release " + tag + " has no jar");
    }

    private static @Nullable Path verifiedCache(@NotNull Path dir, @NotNull Properties state, @NotNull String repository) {
        String file = state.getProperty("file");
        String sha256 = state.getProperty("sha256");
        if (file == null || sha256 == null) return null;
        Path jar = dir.resolve(file);
        try {
            if (Hashing.matches(Hashing.sha256(jar), sha256)) return jar;
            log.warn("%s: cached %s changed, ignoring it", repository, file);
        } catch (IOException e) {
            log.warn("%s: cached %s is missing or unreadable, ignoring it: %s", repository, file, e);
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
