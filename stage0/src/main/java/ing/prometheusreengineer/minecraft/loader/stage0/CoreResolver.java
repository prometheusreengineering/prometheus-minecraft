package ing.prometheusreengineer.minecraft.loader.stage0;

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
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Finds the core jar: {@code prometheus-core.jar} of this repo's latest GitHub release, cached in
 * {@code <prometheus folder>/cache/core/}.
 * <ul>
 *     <li>Two fixed URLs and no JSON: GitHub redirects {@code releases/latest/download/<asset>} to the latest release's
 *     asset. That isn't the REST API, so it doesn't use up the patches' 60 calls an hour.</li>
 *     <li>{@code prometheus-core.jar.sha256} says which core is the latest, and is read at most once per
 *     {@code update.interval} minutes (default 60). The jar is only downloaded when that hash changes.</li>
 *     <li>Every download is checked against that hash, and the cached jar against the hash stored when it was
 *     downloaded.</li>
 *     <li>Offline, or if GitHub fails, the last verified jar is used. Without one, Prometheus is off this launch.</li>
 * </ul>
 * Settings: {@code core.path} (use this jar, for development), {@code offline}, {@code update.interval},
 * {@code core.url} (another jar, with its {@code .sha256} next to it), {@code http.timeout} (ms).
 */
final class CoreResolver {
    private CoreResolver() {}

    private static final String CORE_URL = "https://github.com/prometheusreengineering/prometheus-minecraft/releases/latest/download/prometheus-core.jar";
    private static final String FILE_PREFIX = "prometheus-core-";
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
            Stage0Log.info("using cached core %s", cached.getFileName());
        } else {
            Stage0Log.warn("no verified core in %s", dir);
        }
        return cached;
    }

    private static Path update(Path dir, Path stateFile, Properties state, Path cached) throws IOException {
        String url = Settings.get("core.url", CORE_URL);
        String sha256 = parseSha256(new String(get(url + ".sha256"), StandardCharsets.UTF_8));

        Path result;
        if (cached != null && sha256.equals(state.getProperty("sha256"))) {
            Stage0Log.info("core %s is up to date", cached.getFileName());
            result = cached;
        } else {
            // Never write over a jar that may be open: another running game holds it (Windows won't replace it then),
            // and so does an earlier load in this JVM. A taken name (a corrupted copy) gets a fresh one instead.
            Path target = dir.resolve(FILE_PREFIX + sha256 + ".jar");
            if (Files.exists(target)) target = dir.resolve(FILE_PREFIX + sha256 + "-" + System.currentTimeMillis() + ".jar");

            Stage0Log.info("downloading core %s", sha256);
            download(url, target, sha256);
            state.setProperty("sha256", sha256);
            state.setProperty("file", target.getFileName().toString());
            deleteOtherCores(dir, target);
            result = target;
        }

        state.setProperty("checkedAt", Long.toString(System.currentTimeMillis()));
        save(stateFile, state);
        return result;
    }

    /**
     * Just the hash, or {@code sha256sum}'s output ({@code <hash>  <file>}). Anything else, like an error page served
     * with a 200, must not count as a new core.
     */
    static String parseSha256(String text) throws IOException {
        String hash = text.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("not a sha256: " + hash);
        return hash;
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
            if (sha256.equalsIgnoreCase(sha256(jar))) return jar;
            Stage0Log.warn("cached core %s changed, ignoring it", jar.getFileName());
        } catch (IOException e) {
            Stage0Log.warn("cached core %s is missing or unreadable, ignoring it: %s", jar.getFileName(), e);
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
     * Checks the byte count (a server hanging up early can look like a normal end of stream) and the hash.
     */
    private static void download(String url, Path target, String expected) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        HttpURLConnection connection = open(url);
        try {
            MessageDigest sha256 = digest("SHA-256");
            long length = connection.getContentLengthLong();
            long copied;
            try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
                copied = copy(in, out, sha256);
            }
            if (length >= 0 && copied != length) {
                throw new IOException("incomplete download from " + url + ": got " + copied + " of " + length + " bytes");
            }
            String actual = hex(sha256.digest());
            if (!actual.equals(expected)) {
                throw new IOException("sha256 mismatch for " + url + ": expected " + expected + ", got " + actual);
            }
            moveIntoPlace(tmp, target, Files::move);
        } finally {
            connection.disconnect();
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * {@link Files#move}, swappable so tests can reach the fallback for file systems without atomic moves.
     */
    interface Mover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    static void moveIntoPlace(Path tmp, Path target, Mover mover) throws IOException {
        try {
            mover.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            mover.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
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
    static void deleteOtherCores(Path dir, Path keep) {
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(dir, FILE_PREFIX + "*.jar")) {
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

    static Properties load(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) return properties;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException e) {
            Stage0Log.warn("ignoring unreadable %s: %s", file.getFileName(), e);
        }
        return properties;
    }

    static void save(Path file, Properties properties) {
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
        MessageDigest digest = digest("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) != -1; ) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }

    static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is required of every JRE", e);
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
