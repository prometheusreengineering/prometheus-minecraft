package studio.dreamys.prometheus.stage0;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Finds the core jar: the newest {@code prometheus-core-*.jar} from this repo's latest GitHub release, cached in
 * {@code <prometheus folder>/cache/core/}.
 * <ul>
 *     <li>GitHub is asked at most once per {@code update.interval} minutes (default 60); the unauthenticated API
 *     allows 60 calls an hour per IP, and the core asks again for every patch.</li>
 *     <li>Every jar is checked against the SHA-256 GitHub publishes for the asset ({@code digest}), and the cached one
 *     against the hash stored when it was downloaded.</li>
 *     <li>Offline, or if GitHub fails, the last verified jar is used. Without one, Prometheus is off this launch.</li>
 * </ul>
 * Settings: {@code core.path} (use this jar, for development), {@code offline}, {@code update.interval},
 * {@code core.repository}, {@code github.api} (for tests or a mirror), {@code http.timeout} (ms).
 */
final class CoreResolver {
    private CoreResolver() {}

    private static final String ASSET_PREFIX = "prometheus-core-";
    private static final String USER_AGENT = "Prometheus-Minecraft-Stage0 (+https://github.com/prometheusreengineering)";

    /**
     * Null when there is no usable core. Never throws.
     */
    static Path resolve() {
        String override = Settings.get("core.path");
        if (override != null) {
            Path jar = Paths.get(override).toAbsolutePath();
            if (Files.isRegularFile(jar)) {
                Stage0Log.info("using core from core.path: %s", jar);
                return jar;
            }
            Stage0Log.warn("core.path %s is not a file", jar);
            return null;
        }

        Path dir = Settings.configFolder().resolve("cache").resolve("core");
        Path stateFile = dir.resolve("core.properties");
        Properties state = load(stateFile);
        Path cached = verifiedCache(dir, state);

        long checkedAt = parseLong(state.getProperty("checkedAt"));
        long interval = TimeUnit.MINUTES.toMillis(Settings.getLong("update.interval", 60));
        boolean due = System.currentTimeMillis() - checkedAt >= interval;

        if (Settings.getBoolean("offline", false)) {
            Stage0Log.info("offline: not checking for updates");
        } else if (cached == null || due) {
            try {
                return update(dir, stateFile, state, cached);
            } catch (Throwable t) {
                Stage0Log.warn("could not check for core updates: %s", t);
            }
        }

        if (cached != null) {
            Stage0Log.info("using cached core %s (%s)", cached.getFileName(), state.getProperty("tag"));
        } else {
            Stage0Log.warn("no verified core in %s", dir);
        }
        return cached;
    }

    private static Path update(Path dir, Path stateFile, Properties state, Path cached) throws IOException {
        String repository = Settings.get("core.repository");
        if (repository == null) repository = "prometheusreengineering/prometheus-minecraft";
        String api = Settings.get("github.api");
        if (api == null) api = "https://api.github.com";

        Map<?, ?> release = (Map<?, ?>) MiniJson.parse(new String(get(api + "/repos/" + repository + "/releases/latest"), StandardCharsets.UTF_8));
        String tag = (String) release.get("tag_name");
        Map<?, ?> asset = coreAsset(release);
        String name = (String) asset.get("name");
        String digest = (String) asset.get("digest"); // "sha256:<hex>", absent on releases made before GitHub added it

        Path result;
        if (cached != null && tag.equals(state.getProperty("tag")) && name.equals(state.getProperty("asset"))) {
            Stage0Log.info("core %s (%s) is up to date", name, tag);
            result = cached;
        } else {
            // Never write over a jar that may be open: another running game holds it (Windows won't replace it then),
            // and so does an earlier load in this JVM. A taken name (a corrupted copy) gets a fresh one instead.
            Path target = dir.resolve(name);
            if (Files.exists(target))
                target = dir.resolve(name.substring(0, name.length() - ".jar".length()) + "-" + System.currentTimeMillis() + ".jar");

            Stage0Log.info("downloading core %s (%s)", name, tag);
            String sha256 = download((String) asset.get("browser_download_url"), target, digest);
            if (digest == null)
                Stage0Log.warn("release %s publishes no digest, trusting the download (%s)", tag, sha256);
            state.setProperty("sha256", sha256);
            state.setProperty("asset", name);
            state.setProperty("file", target.getFileName().toString());
            deleteOtherCores(dir, target);
            result = target;
        }

        state.setProperty("tag", tag);
        state.setProperty("checkedAt", Long.toString(System.currentTimeMillis()));
        save(stateFile, state);
        return result;
    }

    private static Map<?, ?> coreAsset(Map<?, ?> release) throws IOException {
        Object assets = release.get("assets");
        if (assets instanceof List) {
            for (Object asset : (List<?>) assets) {
                Object name = asset instanceof Map ? ((Map<?, ?>) asset).get("name") : null;
                if (name instanceof String && ((String) name).startsWith(ASSET_PREFIX) && ((String) name).endsWith(".jar")) {
                    return (Map<?, ?>) asset;
                }
            }
        }
        throw new IOException("release " + release.get("tag_name") + " has no " + ASSET_PREFIX + "*.jar");
    }

    /**
     * The cached jar if it exists and still has the hash it was downloaded with, else null.
     */
    private static Path verifiedCache(Path dir, Properties state) {
        String file = state.getProperty("file");
        String sha256 = state.getProperty("sha256");
        if (file == null || sha256 == null) return null;
        Path jar = dir.resolve(file);
        try {
            if (Files.isRegularFile(jar) && sha256.equalsIgnoreCase(sha256(jar))) return jar;
            Stage0Log.warn("cached core %s is missing or changed, ignoring it", jar.getFileName());
        } catch (IOException e) {
            Stage0Log.warn("could not read cached core %s: %s", jar.getFileName(), e);
        }
        return null;
    }

    // ---- http -----------------------------------------------------------------------------------------------------

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        int timeout = (int) Settings.getLong("http.timeout", 10_000);
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        int status = connection.getResponseCode();
        if (status >= 400) {
            connection.disconnect();
            throw new IOException("HTTP " + status + " from " + url);
        }
        return connection;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out, null);
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Into a temp file next to {@code target}, then moved over it, so a failed download never replaces anything.
     * Checks the byte count (a server hanging up early can look like a normal end of stream) and {@code digest}.
     *
     * @return the SHA-256 of what was downloaded
     */
    private static String download(String url, Path target, String digest) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        HttpURLConnection connection = open(url);
        try {
            MessageDigest sha256 = digest();
            long length = connection.getContentLengthLong();
            long copied;
            try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
                copied = copy(in, out, sha256);
            }
            if (length >= 0 && copied != length) {
                throw new IOException("incomplete download from " + url + ": got " + copied + " of " + length + " bytes");
            }
            String actual = hex(sha256.digest());
            if (digest != null && !actual.equalsIgnoreCase(digest.substring(digest.indexOf(':') + 1).trim())) {
                throw new IOException("sha256 mismatch for " + url + ": expected " + digest + ", got " + actual);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return actual;
        } finally {
            connection.disconnect();
            Files.deleteIfExists(tmp);
        }
    }

    private static long copy(InputStream in, OutputStream out, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[1 << 16];
        long total = 0;
        for (int n; (n = in.read(buffer)) != -1; ) {
            out.write(buffer, 0, n);
            if (digest != null) digest.update(buffer, 0, n);
            total += n;
        }
        return total;
    }

    // ---- files ----------------------------------------------------------------------------------------------------

    /**
     * Best effort: a jar another running game still has open stays until a later launch.
     */
    private static void deleteOtherCores(Path dir, Path keep) {
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(dir, ASSET_PREFIX + "*.jar")) {
            for (Path jar : jars) {
                if (jar.equals(keep)) continue;
                try {
                    Files.deleteIfExists(jar);
                } catch (IOException e) {
                    Stage0Log.info("old core %s is still in use, leaving it for now", jar.getFileName());
                }
            }
        } catch (IOException e) {
            Stage0Log.warn("could not list old cores: %s", e);
        }
    }

    private static Properties load(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) return properties;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException e) {
            Stage0Log.warn("ignoring unreadable %s: %s", file.getFileName(), e);
        }
        return properties;
    }

    private static void save(Path file, Properties properties) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString() + ".", ".tmp");
            try {
                try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    properties.store(out, "Prometheus stage 0: the cached core");
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            Stage0Log.warn("could not save %s: %s", file.getFileName(), e);
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) != -1; ) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE", e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = digits[bytes[i] & 0xF];
        }
        return new String(out);
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
