package ing.prometheusreengineer.minecraft.loader.core;

import ing.prometheusreengineer.minecraft.util.http.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core stage 0 carries handing the launch over to a newer one ({@link Bootstrap#handOff}), and taking stage 0's
 * log lines.
 */
class HandOffTest {
    private static final String CORE_REPO = "prometheus-minecraft";
    private static final String PACKAGE = "ing/prometheusreengineer/minecraft/loader/core/";

    private Path folder;
    private FakeGitHub github;
    private Path carried;

    private final List<Path> classpath = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        folder = TestSupport.folder();
        TestSupport.reset(folder);
        github = new FakeGitHub();
        System.setProperty("prometheus.github.api", github.api());
        System.setProperty("prometheus.patches.url", github.api() + "/patches.json");
        carried = TestSupport.jar(folder.resolve("carried.jar"), "x.txt");
    }

    @AfterEach
    void tearDown() {
        github.close();
        TestSupport.clear();
    }

    private Map<String, Object> context() {
        Map<String, Object> context = new HashMap<>();
        context.put("contract", 2);
        context.put("stage0Version", "test");
        context.put("platform", "test");
        context.put("gameDir", folder);
        context.put("coreJar", carried);
        context.put("addToClasspath", (Consumer<Path>) classpath::add);
        context.put("addMixinConfig", (Consumer<String>) config -> {});
        return context;
    }

    /**
     * A real core: this module's compiled classes and a core.properties with {@code version}.
     */
    private Path core(String name, String version) throws IOException {
        Path classes;
        try {
            classes = Paths.get(Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve(PACKAGE);
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        Path jar = folder.resolve("cores").resolve(name);
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             DirectoryStream<Path> files = Files.newDirectoryStream(classes, "*.class")) {
            for (Path file : files) {
                out.putNextEntry(new JarEntry(PACKAGE + file.getFileName()));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry(PACKAGE + "core.properties"));
            out.write(("version=" + version).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    /**
     * A core whose Bootstrap is {@code source}, compiled now.
     */
    private Path fakeCore(String name, String source) throws IOException {
        Path src = folder.resolve("src").resolve(name).resolve("Bootstrap.java");
        Path classes = folder.resolve("classes").resolve(name);
        Files.createDirectories(src.getParent());
        Files.write(src, ("package ing.prometheusreengineer.minecraft.loader.core;\n" + source).getBytes(StandardCharsets.UTF_8));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "-d", classes.toString(), src.toString()));
        Path jar = folder.resolve("cores").resolve(name);
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(PACKAGE + "Bootstrap.class"));
            out.write(Files.readAllBytes(classes.resolve(PACKAGE).resolve("Bootstrap.class")));
            out.closeEntry();
        }
        return jar;
    }

    private void release(Path core) throws IOException {
        byte[] bytes = Files.readAllBytes(core);
        String name = core.getFileName().toString();
        github.release(CORE_REPO, "v-" + name, name, bytes, "sha256:" + Hashing.sha256(bytes));
    }

    private boolean handOff(Map<String, Object> context) {
        return Bootstrap.handOff(context, getClass().getClassLoader());
    }

    private List<String> loaded() {
        List<String> names = new ArrayList<>();
        for (Path jar : classpath) names.add(jar.getFileName().toString());
        return names;
    }

    @Test
    void toANewerRelease() throws IOException {
        release(core("prometheus-core-99.0.0.jar", "99.0.0"));
        assertTrue(handOff(context()));
        assertEquals(Collections.singletonList("prometheus-core-99.0.0.jar"), loaded(), "the newer core ran, with its own jar");
        assertTrue(Files.exists(folder.resolve("cache").resolve("core").resolve("core.properties")));
    }

    @Test
    void notToAnOlderOrTheSameRelease() throws IOException {
        release(core("prometheus-core-1.0.0.jar", "1.0.0"));
        assertFalse(handOff(context()));

        System.setProperty("prometheus.update.interval", "0");
        release(core("prometheus-core-2.0.0.jar", "2.0.0"));
        assertFalse(handOff(context()));
        assertTrue(classpath.isEmpty());
    }

    @Test
    void notAgainOnceHandedOff() {
        Map<String, Object> context = context();
        context.put("handedOffBy", "1.0.0");
        assertFalse(handOff(context));
        assertEquals(0, github.releaseCalls.get());
    }

    @Test
    void notWithoutACore() {
        assertFalse(handOff(context()), "no release, nothing cached");
    }

    @Test
    void notToItself() {
        // stage 0 1.x already resolved core.path, and passes it as coreJar
        System.setProperty("prometheus.core.path", carried.toString());
        assertFalse(handOff(context()));
    }

    @Test
    void corePathIsUsedEvenIfOlder() throws IOException {
        Path old = core("old.jar", "0.1.0");
        System.setProperty("prometheus.core.path", old.toString());
        assertTrue(handOff(context()));
        assertEquals(Collections.singletonList("old.jar"), loaded());
    }

    @Test
    void aCoreThatCantLoadIsSkipped() throws IOException {
        // without a Bootstrap in the jar, and none in the parent (stage 0 has none either)
        System.setProperty("prometheus.core.path", TestSupport.jar(folder.resolve("empty.jar"), "x.txt").toString());
        assertFalse(Bootstrap.handOff(context(), null));
    }

    @Test
    void aCoreThatFailsStillCounts() throws IOException {
        // it may have loaded patches before failing, so the carried core must not load them again
        System.setProperty("prometheus.core.path", fakeCore("throws.jar",
                "public final class Bootstrap { public static void run(java.util.Map<String, Object> c) { throw new IllegalStateException(\"broken\"); } }").toString());
        assertTrue(handOff(context()));

        System.setProperty("prometheus.core.path", fakeCore("hidden.jar",
                "final class Bootstrap { public static void run(java.util.Map<String, Object> c) {} }").toString());
        assertTrue(handOff(context()));
    }

    @Test
    void runHandsOffLikeInTheGame() throws Exception {
        // the carried core in its own child-first loader, as stage 0 starts it, so its loader's parent is stage 0's
        System.setProperty("prometheus.core.path", core("new.jar", "99.0.0").toString());
        try (ChildFirstClassLoader stage0Core = new ChildFirstClassLoader(core("carried-core.jar", "1.0.0"), getClass().getClassLoader())) {
            Class.forName(Bootstrap.class.getName(), true, stage0Core).getMethod("run", Map.class).invoke(null, context());
        }
        assertEquals(Collections.singletonList("new.jar"), loaded());
    }

    @Test
    void runKeepsTheCarriedCoreIfTheNewOneCantLoad() throws IOException {
        // run() parents the new core on its own loader's parent, which in this test can't see the lib
        System.setProperty("prometheus.core.path", core("new.jar", "99.0.0").toString());
        Bootstrap.run(context());
        assertEquals(Collections.singletonList("carried.jar"), loaded());
    }

    @Test
    void takesStage0sLogLines() throws IOException {
        List<String> handedOver = new ArrayList<>();
        Map<String, Object> context = context();
        context.put("stage0Log", (Consumer<BiConsumer<String, String>>) sink -> {
            handedOver.add("taken");
            sink.accept("WARN", "a stage 0 warning");
            sink.accept("SHOUT", "an unknown level");
            sink.accept(null, "no level");
        });
        // loader.log is opened once per JVM, wherever the first test put it, so read the console instead
        System.setProperty("prometheus.log.console", "stdout");
        java.io.PrintStream stdout = System.out;
        java.io.ByteArrayOutputStream console = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(console, true));
        try {
            Bootstrap.run(context);
        } finally {
            System.setOut(stdout);
            System.setProperty("prometheus.log.console", "off");
        }

        assertEquals(Collections.singletonList("taken"), handedOver);
        String log = new String(console.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(log.contains("[Prometheus/loader/stage0] (WARN) a stage 0 warning"), log);
        assertTrue(log.contains("[Prometheus/loader/stage0] (INFO) an unknown level"), log);
        assertTrue(log.contains("[Prometheus/loader/stage0] (INFO) no level"), log);
    }

    @Test
    void newer() {
        assertTrue(Bootstrap.newer("2.0.1", "2.0.0"));
        assertTrue(Bootstrap.newer("v2.1", "2.0.9"));
        assertTrue(Bootstrap.newer("2.0.0.1", "2.0.0"));
        assertTrue(Bootstrap.newer("1.0.0", "unknown"));
        assertFalse(Bootstrap.newer("2.0.0", "2.0.0-SNAPSHOT"));
        assertFalse(Bootstrap.newer("2.0", "2.0.0"));
        assertFalse(Bootstrap.newer("1.9.9", "2.0.0"));
        assertFalse(Bootstrap.newer("unknown", "1.0.0"));
        assertFalse(Bootstrap.newer("unknown", "unknown"));
    }

    @Test
    void jarVersion() throws IOException {
        assertEquals("3.1.4", Bootstrap.jarVersion(core("versioned.jar", "3.1.4")));
        assertEquals("unknown", Bootstrap.jarVersion(TestSupport.jar(folder.resolve("plain.jar"), "x.txt")));
        assertEquals("unknown", Bootstrap.jarVersion(folder.resolve("missing.jar")));
    }

    @Test
    void theCoreAssetOfAReleaseWithSeveralJars() throws IOException {
        Path core = core("prometheus-core-99.0.0.jar", "99.0.0");
        release(core);
        String url = github.api() + "/download/prometheus-core-99.0.0.jar";
        github.releaseJson(CORE_REPO, "{\"tag_name\":\"v99\",\"assets\":["
                + "{\"name\":\"prometheus-99.0.0-minecraft.jar\",\"browser_download_url\":\"" + github.api() + "/download/missing\"},"
                + "{\"name\":\"prometheus-core-99.0.0.jar\",\"browser_download_url\":\"" + url + "\"}]}");
        Path resolved = ReleaseResolver.resolveCore();
        assertTrue(Arrays.equals(Files.readAllBytes(core), Files.readAllBytes(resolved)));
    }

    @Test
    void theCoreRepositoryCanBeChanged() throws IOException {
        System.setProperty("prometheus.core.repository", "someone/fork");
        byte[] bytes = Files.readAllBytes(core("prometheus-core-5.0.0.jar", "5.0.0"));
        github.release("fork", "v5", "prometheus-core-5.0.0.jar", bytes, null);
        assertEquals("prometheus-core-5.0.0.jar", ReleaseResolver.resolveCore().getFileName().toString());
    }

    @Test
    void stage0OnesCacheIsKept() throws IOException {
        // what stage 0 1.x left: same folder, same file, same keys
        Path dir = folder.resolve("cache").resolve("core");
        Path jar = core("prometheus-core-4.0.0.jar", "4.0.0");
        Files.createDirectories(dir);
        Path cached = Files.copy(jar, dir.resolve("prometheus-core-4.0.0.jar"));
        Properties state = new Properties();
        state.setProperty("tag", "v4");
        state.setProperty("asset", "prometheus-core-4.0.0.jar");
        state.setProperty("file", "prometheus-core-4.0.0.jar");
        state.setProperty("sha256", Hashing.sha256(cached));
        state.setProperty("checkedAt", Long.toString(System.currentTimeMillis()));
        ReleaseResolver.save(dir.resolve("core.properties"), state);

        assertEquals(cached, ReleaseResolver.resolveCore());
        assertEquals(0, github.releaseCalls.get(), "within stage 0's update interval");
    }
}
