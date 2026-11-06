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
     * Takes stage 0's log the way stage 1 does.
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
        Stage0.run(Loader.MODLAUNCHER, Paths.get("game"), classpath::add, configs::add);
        Stage0.run(Loader.NEOFORGE, Paths.get("game"), classpath::add, configs::add);
        assertEquals("modlauncher", System.getProperty("prometheus.stage0.claimed"));
        takeLog();
        assertTrue(logged("neoforge hook skipped, modlauncher already started Prometheus"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void startsTheCarriedLibWithTheContract() throws IOException {
        Path lib = TestSupport.fakeLib(folder);
        System.setProperty("test.stage1.jars", lib.toString());
        System.setProperty("test.stage1.configs", "a.mixins.json,b.mixins.json");

        run();

        Map<?, ?> context = (Map<?, ?>) System.getProperties().get("test.stage1.context");
        assertEquals(Stage0.CONTRACT, context.get("contract"));
        assertEquals("2.0.0-minecraft", context.get("stage0Version"));
        assertEquals("unknown", context.get("platform"));
        assertEquals(Paths.get("game"), context.get("gameDir"));
        Path libJar = (Path) context.get("libJar");
        assertEquals("lib-" + Stage0.sha256(Files.readAllBytes(lib)) + ".jar", libJar.getFileName().toString());
        assertArrayEquals(Files.readAllBytes(lib), Files.readAllBytes(libJar));
        assertEquals(1, classpath.size());
        assertEquals(2, configs.size());

        // stage 1 takes the lines logged so far
        ((Consumer<BiConsumer<String, String>>) context.get("stage0Log")).accept((level, message) -> lines.add(level + " " + message));
        assertTrue(logged("INFO stage0 2.0.0-minecraft on UNKNOWN"), lines.toString());
    }

    @Test
    void noCarriedLibMeansOff() {
        takeLog();
        Stage0.run(Loader.UNKNOWN, Paths.get("game"), classpath::add, configs::add, null);
        assertNull(System.getProperties().get("test.stage1.context"));
        assertTrue(logged("carries no lib"), lines.toString());
    }

    @Test
    void aFailingStage1DoesNotEscape() throws IOException {
        TestSupport.fakeLib(folder);
        System.setProperty("test.stage1.throw", "true");
        takeLog();
        run();
        assertTrue(logged("ERROR stage 1 failed"), lines.toString());
        assertTrue(logged("java.lang.IllegalStateException: broken lib"), lines.toString());
    }

    @Test
    void aLibThatCantLoadDoesNotEscape() throws IOException {
        TestSupport.fakeLib(folder);
        System.setProperty("test.stage1.broken", "true");
        takeLog();
        run();
        assertTrue(logged("could not start stage 1"), lines.toString());
    }

    @Test
    void extractingReusesTheSameLib() throws IOException {
        byte[] lib = "lib one".getBytes(StandardCharsets.UTF_8);
        Path jar = Stage0.extract(new ByteArrayInputStream(lib), folder);
        assertEquals(folder.resolve("lib-" + Stage0.sha256(lib) + ".jar"), jar);
        assertArrayEquals(lib, Files.readAllBytes(jar));

        long modified = Files.getLastModifiedTime(jar).toMillis() - 10_000;
        Files.setLastModifiedTime(jar, java.nio.file.attribute.FileTime.fromMillis(modified));
        assertEquals(jar, Stage0.extract(new ByteArrayInputStream(lib), folder));
        assertEquals(modified, Files.getLastModifiedTime(jar).toMillis(), "not written again");

        byte[] other = "lib two".getBytes(StandardCharsets.UTF_8);
        Path otherJar = Stage0.extract(new ByteArrayInputStream(other), folder);
        assertFalse(otherJar.equals(jar), "another lib gets its own file");
        assertTrue(Files.exists(jar));
    }

    @Test
    void extractingReplacesADamagedCopy() throws IOException {
        byte[] lib = "lib one".getBytes(StandardCharsets.UTF_8);
        Path jar = Stage0.extract(new ByteArrayInputStream(lib), folder);
        TestSupport.write(jar, "damaged");
        assertEquals(jar, Stage0.extract(new ByteArrayInputStream(lib), folder));
        assertArrayEquals(lib, Files.readAllBytes(jar));
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
    void logLinesAreKeptUntilStage1TakesThem() {
        Stage0Log.info("hook %s", "fabric");
        Stage0Log.warn("then %d more", 2);
        Stage0Log.handOver().accept((level, message) -> lines.add(level + " " + message));
        assertEquals(java.util.Arrays.asList("INFO hook fabric", "WARN then 2 more"), lines);

        Stage0Log.info("after");
        assertEquals("INFO after", lines.get(2), "straight to stage 1");
    }

    @Test
    void logErrorsKeepTheStackTrace() {
        takeLog();
        Stage0Log.error("stage 1 failed", new IllegalStateException("broken"));
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
