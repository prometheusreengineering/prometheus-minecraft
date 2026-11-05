package ing.prometheusreengineer.minecraft.loader.stage0;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * What the stage 0 tests share: a clean JVM state, jars built on the fly, and a fake core.
 */
public final class TestSupport {
    private TestSupport() {}

    private static final String[] PROPERTIES = {
            "prometheus.stage0.claimed", "test.bootstrap.throw", "test.bootstrap.broken", "test.bootstrap.jars",
            "test.bootstrap.configs", "test.bootstrap.context",
    };

    /**
     * A fresh folder under target/, for tests that load a jar: stage 0 never closes the core's class loader, so on
     * Windows the jar stays locked and a @TempDir couldn't be deleted. mvn clean removes these.
     */
    public static Path folder() throws IOException {
        // next to target/test-classes, whatever the working directory (Maven's or the IDE's)
        Path base;
        try {
            base = Paths.get(TestSupport.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().resolve("test-folders");
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        return Files.createTempDirectory(Files.createDirectories(base), "test-");
    }

    /**
     * Forgets every claim, fake core instruction, log line and the embedded core.
     */
    public static void reset(Path folder) {
        clear();
    }

    public static void clear() {
        for (String property : PROPERTIES) System.getProperties().remove(property);
        Stage0Log.reset();
        try {
            Files.deleteIfExists(embeddedCore());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * A jar with each entry: a {@code name=content} pair, or just a name (content "x"). Names ending in / are folders.
     */
    public static Path jar(Path file, String... entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (String entry : entries) {
                int equals = entry.indexOf('=');
                String name = equals < 0 ? entry : entry.substring(0, equals);
                out.putNextEntry(new JarEntry(name));
                if (!name.endsWith("/")) out.write((equals < 0 ? "x" : entry.substring(equals + 1)).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    /**
     * A jar holding {@code classes}, with their real bytes from the test class path.
     */
    public static Path jarOf(Path file, Class<?>... classes) throws IOException {
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (Class<?> type : classes) {
                String name = type.getName().replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(name));
                out.write(bytes(type.getClassLoader().getResourceAsStream(name)));
                out.closeEntry();
            }
        }
        return file;
    }

    /**
     * A core jar with the fake {@code ing.prometheusreengineer.minecraft.loader.core.Bootstrap}, put where stage 0
     * looks for the core it carries: {@code prometheus-core.jar} next to {@code Stage0.class}, in target/test-classes (the real one
     * only goes into target/classes at prepare-package, after the tests).
     */
    public static Path fakeCore(Path folder) throws IOException {
        Path core = jarOf(folder.resolve("fake-core.jar"), ing.prometheusreengineer.minecraft.loader.core.Bootstrap.class);
        Files.copy(core, embeddedCore(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return core;
    }

    /**
     * Where the stage 0 code under test finds its core: Stage0.class.getResourceAsStream("prometheus-core.jar").
     */
    static Path embeddedCore() throws IOException {
        try {
            Path testClasses = Paths.get(TestSupport.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return testClasses.resolve("ing/prometheusreengineer/minecraft/loader/stage0/prometheus-core.jar");
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }

    public static byte[] bytes(InputStream in) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    public static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    public static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
