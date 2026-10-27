package studio.dreamys.prometheus.stage0;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * {@code -Dprometheus.<key>} first, then {@code PROMETHEUS_<KEY>} (dots become underscores). Blank counts as unset.
 * <br>
 * A copy of minecraft-lib's {@code Settings} and {@code OS.getConfigFolder()}: stage 0 can't depend on the lib, because
 * the lib ships inside the core it downloads. Keep both in sync.
 */
final class Settings {
    private Settings() {}

    static String get(String key) {
        String value = System.getProperty("prometheus." + key);
        if (isBlank(value)) value = System.getenv("PROMETHEUS_" + key.toUpperCase(Locale.ROOT).replace('.', '_'));
        return isBlank(value) ? null : value.trim();
    }

    @SuppressWarnings("SameParameterValue") //intellij overthinking it
    static boolean getBoolean(String key, boolean fallback) {
        String value = get(key);
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return fallback;
    }

    static long getLong(String key, long fallback) {
        String value = get(key);
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Same as minecraft-lib's {@code OS.getConfigFolder()}.
     */
    static Path configFolder() {
        String override = get("folder");
        if (override != null) return Paths.get(override).toAbsolutePath();

        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Path base;
        if (os.contains("win")) {
            base = fromEnvOr("APPDATA", home("AppData", "Roaming")).resolve(".minecraft");
        } else if (os.contains("darwin") || os.contains("mac")) {
            base = home("Library", "Application Support");
        } else {
            base = fromEnvOr("XDG_DATA_HOME", home(".local", "share"));
        }
        return base.resolve("prometheus").toAbsolutePath();
    }

    private static Path home(String... parts) {
        return Paths.get(System.getProperty("user.home"), parts);
    }

    private static Path fromEnvOr(String env, Path fallback) {
        String value = System.getenv(env);
        return isBlank(value) ? fallback : Paths.get(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
