package ing.prometheusreengineer.minecraft.loader.stage0;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreResolverTest {
    private static final byte[] CORE_1 = "core one".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CORE_2 = "core two".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path folder;

    private FakeGitHub github;

    @BeforeEach
    void setUp() throws IOException {
        TestSupport.reset(folder);
        github = new FakeGitHub();
        System.setProperty("prometheus.core.url", github.url());
    }

    @AfterEach
    void tearDown() {
        github.close();
        TestSupport.clear();
    }

    private Path cache() {
        return folder.resolve("cache").resolve("core");
    }

    private static String sha256(byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile("sha", ".bin");
        try {
            Files.write(tmp, bytes);
            return CoreResolver.sha256(tmp);
        } finally {
            Files.delete(tmp);
        }
    }

    private Path expected(byte[] core) throws IOException {
        return cache().resolve("prometheus-core-" + sha256(core) + ".jar");
    }

    private Path firstLaunch() throws IOException {
        github.release(CORE_1, sha256(CORE_1));
        Path core = CoreResolver.resolve();
        assertNotNull(core);
        return core;
    }

    private void due() {
        System.setProperty("prometheus.update.interval", "0");
    }

    @Test
    void corePathWins() throws IOException {
        Path jar = TestSupport.jar(folder.resolve("dev.jar"), "a.txt");
        System.setProperty("prometheus.core.path", jar.toString());
        assertEquals(jar.toAbsolutePath(), CoreResolver.resolve());

        System.setProperty("prometheus.core.path", folder.resolve("missing.jar").toString());
        assertNull(CoreResolver.resolve());
        assertEquals(0, github.hashCalls.get());
    }

    @Test
    void downloadsVerifiesAndCaches() throws IOException {
        Path core = firstLaunch();
        assertEquals(expected(CORE_1), core);
        assertTrue(Arrays.equals(CORE_1, Files.readAllBytes(core)));
        Properties state = CoreResolver.load(cache().resolve("core.properties"));
        assertEquals(sha256(CORE_1), state.getProperty("sha256"));
        assertEquals(core.getFileName().toString(), state.getProperty("file"));
    }

    @Test
    void takesSha256sumOutputAndUppercase() throws IOException {
        github.release(CORE_1, sha256(CORE_1).toUpperCase() + "  prometheus-core.jar\n");
        assertEquals(expected(CORE_1), CoreResolver.resolve());
    }

    @Test
    void noGithubCallWithinTheInterval() throws IOException {
        Path core = firstLaunch();
        assertEquals(core, CoreResolver.resolve());
        assertEquals(1, github.hashCalls.get());
    }

    @Test
    void dueButUpToDateOnlyReadsTheHash() throws IOException {
        Path core = firstLaunch();
        due();
        assertEquals(core, CoreResolver.resolve());
        assertEquals(2, github.hashCalls.get());
        assertEquals(1, github.jarCalls.get(), "the jar isn't downloaded again");
    }

    @Test
    void aNewReleaseReplacesTheOldCore() throws IOException {
        Path old = firstLaunch();
        due();
        github.release(CORE_2, sha256(CORE_2));
        assertEquals(expected(CORE_2), CoreResolver.resolve());
        assertFalse(Files.exists(old), "old core deleted");
    }

    @Test
    void aWrongHashKeepsTheCache() throws IOException {
        Path core = firstLaunch();
        due();
        github.release(CORE_2, sha256("something else".getBytes(StandardCharsets.UTF_8)));
        assertEquals(core, CoreResolver.resolve());
        assertEquals(1, Files.list(cache()).filter(file -> file.toString().endsWith(".jar")).count());
    }

    @Test
    void aMissingOrBadHashFileMeansNoDownload() {
        github.release(CORE_1, "<html>not a hash</html>");
        assertNull(CoreResolver.resolve());
        github.release(CORE_1, "");
        assertNull(CoreResolver.resolve());
        assertEquals(0, github.jarCalls.get());
    }

    @Test
    void aChangedCacheIsDownloadedUnderANewName() throws IOException {
        Path core = firstLaunch();
        Files.write(core, "tampered".getBytes(StandardCharsets.UTF_8));
        Path fresh = CoreResolver.resolve();
        assertNotNull(fresh);
        assertNotEquals(core, fresh, "never written over: another game may have it open");
        assertTrue(fresh.getFileName().toString().startsWith("prometheus-core-" + sha256(CORE_1) + "-"), fresh.toString());
        assertTrue(Arrays.equals(CORE_1, Files.readAllBytes(fresh)));
    }

    @Test
    void aMissingCacheIsDownloadedAgain() throws IOException {
        Path core = firstLaunch();
        Files.delete(core);
        assertEquals(core, CoreResolver.resolve());
        assertTrue(Files.exists(core));
    }

    @Test
    void offline() throws IOException {
        System.setProperty("prometheus.offline", "true");
        assertNull(CoreResolver.resolve(), "nothing cached yet");

        System.clearProperty("prometheus.offline");
        Path core = firstLaunch();
        System.setProperty("prometheus.offline", "true");
        due();
        assertEquals(core, CoreResolver.resolve());
        assertEquals(1, github.hashCalls.get());
    }

    @Test
    void githubDown() throws IOException {
        github.status = 503;
        assertNull(CoreResolver.resolve());

        github.status = 200;
        Path core = firstLaunch();
        due();
        github.status = 503;
        assertEquals(core, CoreResolver.resolve());
    }

    @Test
    void cutShortDownloads() throws IOException {
        github.release(CORE_1, sha256(CORE_1));
        github.route = "truncated";
        assertNull(CoreResolver.resolve());
        assertFalse(Files.exists(expected(CORE_1)));
    }

    @Test
    void downloadsWithoutALength() throws IOException {
        github.release(CORE_1, sha256(CORE_1));
        github.route = "chunked";
        Path core = CoreResolver.resolve();
        assertNotNull(core);
        assertTrue(Arrays.equals(CORE_1, Files.readAllBytes(core)));
    }

    @Test
    void aBadStateFileIsIgnored() throws IOException {
        String noHash = "checkedAt=soon\nfile=prometheus-core-1.jar\n";
        TestSupport.write(cache().resolve("core.properties"), noHash);
        assertNotNull(firstLaunch(), "no sha256 means no verified cache, and soon means never checked");

        TestSupport.write(cache().resolve("core.properties"), "sha256=abc\n");
        assertNotNull(CoreResolver.resolve(), "no file means no verified cache");

        TestSupport.write(cache().resolve("core.properties"), "file=x\\u12\n");
        assertTrue(CoreResolver.load(cache().resolve("core.properties")).isEmpty());
    }

    @Test
    void anUnsavableStateIsOnlyLogged() throws IOException {
        Path notAFolder = Files.createFile(folder.resolve("file"));
        assertDoesNotThrow(() -> CoreResolver.save(notAFolder.resolve("core.properties"), new Properties()));
    }

    @Test
    void oldCoresInUseStay() throws IOException {
        Path keep = Files.createDirectories(cache()).resolve("prometheus-core-2.jar");
        Files.write(keep, CORE_2);
        // a folder can't be deleted while it has something in it, like a jar another game holds open
        Path inUse = Files.createDirectories(cache().resolve("prometheus-core-1.jar").resolve("held"));
        CoreResolver.deleteOtherCores(cache(), keep);
        assertTrue(Files.exists(keep));
        assertTrue(Files.exists(inUse));

        assertDoesNotThrow(() -> CoreResolver.deleteOtherCores(folder.resolve("missing"), keep));
    }

    @Test
    void plainMoveWhereAtomicIsNotSupported() throws IOException {
        Path tmp = Files.write(folder.resolve("tmp"), CORE_1);
        Path target = folder.resolve("target.jar");
        CoreResolver.moveIntoPlace(tmp, target, (source, to, options) -> {
            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) throw new AtomicMoveNotSupportedException(source.toString(), to.toString(), "test");
            Files.move(source, to, options);
        });
        assertTrue(Arrays.equals(CORE_1, Files.readAllBytes(target)));
    }

    @Test
    void unknownAlgorithm() {
        assertThrows(IllegalStateException.class, () -> CoreResolver.digest("NOT-A-HASH"));
    }
}
