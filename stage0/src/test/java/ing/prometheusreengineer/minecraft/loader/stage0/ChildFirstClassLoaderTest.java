package ing.prometheusreengineer.minecraft.loader.stage0;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChildFirstClassLoaderTest {
    @TempDir
    Path folder;

    /**
     * On the test class path and in the jar, like minecraft-lib once the core put itself on the game's class path.
     */
    static final class Probe {}

    private ChildFirstClassLoader loader() throws IOException {
        Path jar = TestSupport.jarOf(folder.resolve("core.jar"), Probe.class);
        // add a resource next to the class
        Path withResource = folder.resolve("core-with-resource.jar");
        try (JarOutputStream out = new JarOutputStream(java.nio.file.Files.newOutputStream(withResource));
             java.util.jar.JarFile in = new java.util.jar.JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(in.entries())) {
                out.putNextEntry(new JarEntry(entry.getName()));
                out.write(TestSupport.bytes(in.getInputStream(entry)));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("core.properties"));
            out.write("version=test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return new ChildFirstClassLoader(withResource, getClass().getClassLoader());
    }

    @Test
    void theJarWinsOverTheParent() throws Exception {
        try (ChildFirstClassLoader loader = loader()) {
            Class<?> probe = loader.loadClass(Probe.class.getName());
            assertSame(loader, probe.getClassLoader());
            assertSame(probe, loader.loadClass(Probe.class.getName()), "loaded once");
        }
    }

    @Test
    void everythingElseComesFromTheParent() throws Exception {
        try (ChildFirstClassLoader loader = loader()) {
            assertSame(String.class, loader.loadClass("java.lang.String"));
            assertSame(Settings.class, loader.loadClass(Settings.class.getName()));
        }
    }

    @Test
    void resolvesWhenAsked() throws Exception {
        try (ChildFirstClassLoader loader = loader()) {
            assertEquals(Probe.class.getName(), loader.loadClass(Probe.class.getName(), true).getName());
        }
    }

    @Test
    void resourcesTooAreJarFirst() throws Exception {
        try (ChildFirstClassLoader loader = loader()) {
            URL own = loader.getResource("core.properties");
            assertNotNull(own);
            assertTrue(own.toString().contains("core-with-resource.jar"), own.toString());
            assertNotNull(loader.getResource("ing/prometheusreengineer/minecraft/loader/stage0/stage0.properties"), "from the parent");
        }
    }
}
