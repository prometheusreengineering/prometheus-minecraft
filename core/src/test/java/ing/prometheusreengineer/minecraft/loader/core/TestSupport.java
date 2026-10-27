package ing.prometheusreengineer.minecraft.loader.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * What the core tests share.
 */
final class TestSupport {
    private TestSupport() {}

    private static final String[] PROPERTIES = {
            "prometheus.folder", "prometheus.offline", "prometheus.update.interval", "prometheus.github.api",
            "prometheus.patches.url", "prometheus.patch.minecraft-test.path",
    };

    /**
     * A fresh folder under target/, not a @TempDir: the core's loader.log stays open for the whole JVM, which on
     * Windows would stop a @TempDir from being deleted. mvn clean removes these.
     */
    static Path folder() throws IOException {
        // next to target/test-classes, whatever the working directory (Maven's or the IDE's)
        Path base;
        try {
            base = Paths.get(TestSupport.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().resolve("test-folders");
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        return Files.createTempDirectory(Files.createDirectories(base), "test-");
    }

    static void reset(Path folder) {
        clear();
        System.setProperty("prometheus.folder", folder.toString());
        System.setProperty("prometheus.log.console", "off");
    }

    static void clear() {
        for (String property : PROPERTIES) System.clearProperty(property);
    }

    /**
     * A jar with each entry, all containing "x".
     */
    static Path jar(Path file, String... entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (String entry : entries) {
                out.putNextEntry(new JarEntry(entry));
                out.write("x".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    static byte[] bytes(Path file) throws IOException {
        return Files.readAllBytes(file);
    }
}
