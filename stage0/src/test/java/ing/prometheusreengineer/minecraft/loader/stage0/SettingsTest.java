package ing.prometheusreengineer.minecraft.loader.stage0;

import ing.prometheusreengineer.minecraft.util.Env;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {
    private final String osName = System.getProperty("os.name");
    private final String appData = System.getenv("APPDATA");
    private final String xdgDataHome = System.getenv("XDG_DATA_HOME");

    @AfterEach
    void restore() {
        System.setProperty("os.name", osName);
        // blank counts as unset, which is as close to removing a variable as Env gets
        Env.setenv("APPDATA", appData == null ? "" : appData);
        Env.setenv("XDG_DATA_HOME", xdgDataHome == null ? "" : xdgDataHome);
        System.clearProperty("prometheus.test.value");
        System.clearProperty("prometheus.folder");
    }

    private static Path home(String... parts) {
        return Paths.get(System.getProperty("user.home"), parts);
    }

    @Test
    void propertyThenEnvThenNothing() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        assertNull(Settings.get("test.env." + suffix));
        Env.setenv("PROMETHEUS_TEST_ENV_" + suffix.toUpperCase(), " from env ");
        assertEquals("from env", Settings.get("test.env." + suffix));

        System.setProperty("prometheus.test.value", "  hello ");
        assertEquals("hello", Settings.get("test.value"));
        assertEquals("hello", Settings.get("test.value", "fallback"));
        System.setProperty("prometheus.test.value", "   ");
        assertNull(Settings.get("test.value"), "blank is unset");
        assertEquals("fallback", Settings.get("test.value", "fallback"));
    }

    @Test
    void booleans() {
        System.setProperty("prometheus.test.value", "TRUE");
        assertTrue(Settings.getBoolean("test.value", false));
        System.setProperty("prometheus.test.value", "false");
        assertFalse(Settings.getBoolean("test.value", true));
        System.setProperty("prometheus.test.value", "yes");
        assertFalse(Settings.getBoolean("test.value", false), "only true and false");
    }

    @Test
    void longs() {
        assertEquals(60, Settings.getLong("test.value", 60));
        System.setProperty("prometheus.test.value", "5");
        assertEquals(5, Settings.getLong("test.value", 60));
        System.setProperty("prometheus.test.value", "five");
        assertEquals(60, Settings.getLong("test.value", 60));
    }

    @Test
    void folderOverride() {
        System.setProperty("prometheus.folder", "some/relative");
        assertTrue(Settings.configFolder().isAbsolute());
        assertTrue(Settings.configFolder().endsWith("some/relative"));
    }

    @Test
    void folderPerOs() {
        System.setProperty("os.name", "Mac OS X");
        assertEquals(home("Library", "Application Support", "prometheus"), Settings.configFolder());
        System.setProperty("os.name", "Darwin");
        assertEquals(home("Library", "Application Support", "prometheus"), Settings.configFolder(), "darwin contains win");

        System.setProperty("os.name", "Windows 11");
        Env.setenv("APPDATA", "C:/AppData");
        assertEquals(Paths.get("C:/AppData", ".minecraft", "prometheus").toAbsolutePath(), Settings.configFolder());
        Env.setenv("APPDATA", "");
        assertEquals(home("AppData", "Roaming", ".minecraft", "prometheus"), Settings.configFolder());

        System.setProperty("os.name", "Linux");
        Env.setenv("XDG_DATA_HOME", "/data");
        assertEquals(Paths.get("/data", "prometheus").toAbsolutePath(), Settings.configFolder());
        Env.setenv("XDG_DATA_HOME", " ");
        assertEquals(home(".local", "share", "prometheus"), Settings.configFolder());
    }
}
