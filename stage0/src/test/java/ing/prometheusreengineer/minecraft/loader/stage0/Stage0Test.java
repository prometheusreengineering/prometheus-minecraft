package ing.prometheusreengineer.minecraft.loader.stage0;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Stage0Test {
    private Path folder;

    private final List<Path> classpath = new ArrayList<>();
    private final List<String> configs = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        folder = TestSupport.folder();
        TestSupport.reset(folder);
    }

    @AfterEach
    void tearDown() {
        TestSupport.clear();
    }

    private void run() {
        Stage0.run(Loader.UNKNOWN, Paths.get("game"), classpath::add, configs::add);
    }

    /**
     * Takes stage 0's log the way the core does.
     */
    private void takeLog() {
        Stage0Log.handOver((level, message) -> lines.add(level + " " + message));
    }

    private boolean logged(String text) {
        for (String line : lines) {
            if (line.contains(text)) return true;
        }
        return false;
    }

    @Test
    void theFirstHookClaimsTheJvm() {
        assertTrue(Stage0.claim("modlauncher"));
        assertFalse(Stage0.claim("neoforge"));
        assertEquals("modlauncher", System.getProperty("prometheus.stage0.claimed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void startsTheCarriedCoreWithTheContract() throws IOException {
        Path core = TestSupport.fakeCore(folder);
        System.setProperty("test.bootstrap.jars", core.toString());
        System.setProperty("test.bootstrap.configs", "a.mixins.json,b.mixins.json");

        run();

        Map<?, ?> context = (Map<?, ?>) System.getProperties().get("test.bootstrap.context");
        assertEquals(Stage0.CONTRACT, context.get("contract"));
        assertEquals("2.0.0-minecraft", context.get("stage0Version"));
        assertEquals("test", context.get("platform"));
        assertEquals(Paths.get("game"), context.get("gameDir"));
        Path coreJar = (Path) context.get("coreJar");
        assertEquals("core-" + Stage0.sha256(Files.readAllBytes(core)) + ".jar", coreJar.getFileName().toString());
        assertArrayEquals(Files.readAllBytes(core), Files.readAllBytes(coreJar));
        assertEquals(1, classpath.size());
        assertEquals(2, configs.size());

        // the core takes the lines logged so far
        ((Consumer<BiConsumer<String, String>>) context.get("stage0Log")).accept((level, message) -> lines.add(level + " " + message));
        assertTrue(logged("INFO stage0 2.0.0-minecraft on test"), lines.toString());
    }

    @Test
    void noCarriedCoreMeansOff() {
        takeLog();
        Stage0.run(Loader.UNKNOWN, Paths.get("game"), classpath::add, configs::add, null);
        assertNull(System.getProperties().get("test.bootstrap.context"));
        assertTrue(logged("carries no core"), lines.toString());
    }

    @Test
    void aFailingCoreDoesNotEscape() throws IOException {
        TestSupport.fakeCore(folder);
        System.setProperty("test.bootstrap.throw", "true");
        takeLog();
        run();
        assertTrue(logged("ERROR the core failed"), lines.toString());
        assertTrue(logged("java.lang.IllegalStateException: broken core"), lines.toString());
    }

    @Test
    void aCoreThatCantLoadDoesNotEscape() throws IOException {
        TestSupport.fakeCore(folder);
        System.setProperty("test.bootstrap.broken", "true");
        takeLog();
        run();
        assertTrue(logged("could not start the core"), lines.toString());
    }

    @Test
    void extractingReusesTheSameCore() throws IOException {
        byte[] core = "core one".getBytes(StandardCharsets.UTF_8);
        Path jar = Stage0.extract(new ByteArrayInputStream(core), folder);
        assertEquals(folder.resolve("core-" + Stage0.sha256(core) + ".jar"), jar);
        assertArrayEquals(core, Files.readAllBytes(jar));

        long modified = Files.getLastModifiedTime(jar).toMillis() - 10_000;
        Files.setLastModifiedTime(jar, java.nio.file.attribute.FileTime.fromMillis(modified));
        assertEquals(jar, Stage0.extract(new ByteArrayInputStream(core), folder));
        assertEquals(modified, Files.getLastModifiedTime(jar).toMillis(), "not written again");

        byte[] other = "core two".getBytes(StandardCharsets.UTF_8);
        Path otherJar = Stage0.extract(new ByteArrayInputStream(other), folder);
        assertFalse(otherJar.equals(jar), "another core gets its own file");
        assertTrue(Files.exists(jar));
    }

    @Test
    void extractingReplacesADamagedCopy() throws IOException {
        byte[] core = "core one".getBytes(StandardCharsets.UTF_8);
        Path jar = Stage0.extract(new ByteArrayInputStream(core), folder);
        TestSupport.write(jar, "damaged");
        assertEquals(jar, Stage0.extract(new ByteArrayInputStream(core), folder));
        assertArrayEquals(core, Files.readAllBytes(jar));
    }

    @Test
    void extractingNeedsAWritableFolder() throws IOException {
        Path notAFolder = Files.createFile(folder.resolve("file"));
        assertThrows(IOException.class, () -> Stage0.extract(new ByteArrayInputStream(new byte[1]), notAFolder));
    }

    @Test
    void aMissingDigestIsABrokenJre() {
        assertThrows(IllegalStateException.class, () -> Stage0.digest("no-such-algorithm"));
    }

    @Test
    void logLinesAreKeptUntilTheCoreTakesThem() {
        Stage0Log.info("hook %s", "fabric");
        Stage0Log.warn("then %d more", 2);
        Stage0Log.handOver().accept((level, message) -> lines.add(level + " " + message));
        assertEquals(java.util.Arrays.asList("INFO hook fabric", "WARN then 2 more"), lines);

        Stage0Log.info("after");
        assertEquals("INFO after", lines.get(2), "straight to the core");
    }

    @Test
    void logErrorsKeepTheStackTrace() {
        takeLog();
        Stage0Log.error("the core failed", new IllegalStateException("broken"));
        assertTrue(logged("java.lang.IllegalStateException: broken"), lines.toString());
    }

    @Test
    void logNoArgsAndBadPatternsAreKept() {
        takeLog();
        Stage0Log.info("100% literal");
        Stage0Log.info("bad %d", "not a number");
        assertTrue(logged("100% literal"), lines.toString());
        assertTrue(logged("bad %d"), lines.toString());
    }

    @Test
    void aBrokenCoreLogFallsBackToStdout() {
        Stage0Log.info("kept");
        Stage0Log.handOver((level, message) -> {
            throw new IllegalStateException("broken log");
        });
        Stage0Log.warn("still on stdout");
    }

    @Test
    void version() {
        assertEquals("2.0.0-minecraft", Stage0.version());
        assertEquals("unknown", Stage0.version(null));
        assertEquals("unknown", Stage0.version(new ByteArrayInputStream("other=1".getBytes(StandardCharsets.UTF_8))));
        assertEquals("unknown", Stage0.version(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("unreadable");
            }
        }));
    }
}
