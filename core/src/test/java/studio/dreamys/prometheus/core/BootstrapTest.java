package studio.dreamys.prometheus.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapTest {
    private Path folder;
    private FakeGitHub github;
    private Path coreJar;

    private final List<Path> classpath = new ArrayList<>();
    private final List<String> configs = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        folder = TestSupport.folder();
        TestSupport.reset(folder);
        github = new FakeGitHub();
        System.setProperty("prometheus.github.api", github.api());
        System.setProperty("prometheus.patches.url", github.api() + "/patches.json");
        coreJar = TestSupport.jar(folder.resolve("prometheus-core-test.jar"), "studio/dreamys/prometheus/core/Bootstrap.class");
    }

    @AfterEach
    void tearDown() {
        github.close();
        TestSupport.clear();
    }

    private Map<String, Object> context() {
        Map<String, Object> context = new HashMap<>();
        context.put("contract", 1);
        context.put("stage0Version", "test");
        context.put("platform", "test");
        context.put("gameDir", folder);
        context.put("coreJar", coreJar);
        context.put("addToClasspath", (Consumer<Path>) jar -> {
            if (jar.getFileName().toString().startsWith("broken")) throw new IllegalStateException("can't add " + jar);
            classpath.add(jar);
        });
        context.put("addMixinConfig", (Consumer<String>) configs::add);
        return context;
    }

    private void release(String repo, String jarName, String... entries) throws IOException {
        Path jar = TestSupport.jar(folder.resolve("releases").resolve(jarName), entries);
        github.release(repo, "v1", jarName, TestSupport.bytes(jar), null);
    }

    private List<String> loaded() {
        List<String> names = new ArrayList<>();
        for (Path jar : classpath) names.add(jar.getFileName().toString());
        return names;
    }

    @Test
    void loadsThePatchesWhoseTargetIsPresent() throws IOException {
        github.patchesJson = "["
                + "{\"name\":\"Present\",\"classPaths\":[\"com.example.Missing\",\"java.lang.String\"],\"repositoryName\":\"minecraft-present\"},"
                + "{\"name\":\"Same repo again\",\"classPaths\":[\"java.lang.Integer\"],\"repositoryName\":\"minecraft-present\"},"
                + "{\"name\":\"Absent\",\"classPaths\":[null,\"com.example.Missing\"],\"repositoryName\":\"minecraft-absent\"},"
                + "{\"name\":\"No classes\",\"repositoryName\":\"minecraft-none\"},"
                + "{\"name\":\"No release\",\"classPaths\":[\"java.lang.Long\"],\"repositoryName\":\"minecraft-unreleased\"},"
                + "{\"name\":\"Broken\",\"classPaths\":[\"java.lang.Short\"],\"repositoryName\":\"minecraft-broken\"}"
                + "]";
        release("minecraft-present", "present-1.jar", "present.mixins.json", "present/A.class");
        release("minecraft-broken", "broken-1.jar", "broken/A.class");

        Bootstrap.run(context());

        assertEquals(java.util.Arrays.asList("prometheus-core-test.jar", "present-1.jar"), loaded(), "core first, then the patches");
        assertEquals(Collections.singletonList("present.mixins.json"), configs);
    }

    @Test
    void offlineUsesTheLastPatchList() throws IOException {
        github.patchesJson = "[{\"name\":\"Present\",\"classPaths\":[\"java.lang.String\"],\"repositoryName\":\"minecraft-present\"}]";
        release("minecraft-present", "present-1.jar", "present/A.class");
        Bootstrap.run(context());
        classpath.clear();

        System.setProperty("prometheus.offline", "true");
        Bootstrap.run(context());
        assertEquals(java.util.Arrays.asList("prometheus-core-test.jar", "present-1.jar"), loaded());
    }

    @Test
    void noPatchListAtAll() {
        // patches.json answers 404 and nothing is cached
        Bootstrap.run(context());
        assertEquals(Collections.singletonList("prometheus-core-test.jar"), loaded());

        classpath.clear();
        System.setProperty("prometheus.offline", "true");
        Bootstrap.run(context());
        assertEquals(Collections.singletonList("prometheus-core-test.jar"), loaded());
    }

    @Test
    void anUnreadablePatchList() {
        github.patchesJson = "{ not a list";
        Bootstrap.run(context());
        assertEquals(Collections.singletonList("prometheus-core-test.jar"), loaded());

        classpath.clear();
        github.patchesJson = "null";
        Bootstrap.run(context());
        assertEquals(Collections.singletonList("prometheus-core-test.jar"), loaded());
    }

    @Test
    void lookingForTargetsWithoutAContextClassLoader() throws IOException {
        github.patchesJson = "[{\"name\":\"Present\",\"classPaths\":[\"java.lang.String\"],\"repositoryName\":\"minecraft-present\"}]";
        release("minecraft-present", "present-1.jar", "present/A.class");
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(null);
        try {
            Bootstrap.run(context());
        } finally {
            thread.setContextClassLoader(original);
        }
        assertTrue(loaded().contains("present-1.jar"));
    }

    @Test
    void aBrokenContextIsOnlyLogged() {
        Map<String, Object> context = context();
        context.remove("coreJar");
        assertDoesNotThrow(() -> Bootstrap.run(context));
        assertTrue(classpath.isEmpty());
    }

    @Test
    void version() throws IOException {
        assertEquals("2.0.0", Bootstrap.version(Bootstrap.class.getResourceAsStream("core.properties")));
        assertEquals("unknown", Bootstrap.version(null));
        assertEquals("unknown", Bootstrap.version(new ByteArrayInputStream("other=1".getBytes(StandardCharsets.UTF_8))));
        assertEquals("unknown", Bootstrap.version(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("unreadable");
            }
        }));
    }

    @Test
    void patchEntries() {
        Patch patch = new Patch("Essential", Collections.singletonList("gg.essential.Essential"), "minecraft-essential");
        assertEquals("Essential", patch.name);
        assertEquals("minecraft-essential", patch.repositoryName);
        assertEquals(1, patch.classPaths.size());
        assertTrue(Files.exists(coreJar));
    }
}
