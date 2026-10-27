package studio.dreamys.prometheus.stage0;

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
        System.setProperty("prometheus.github.api", github.api());
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

    private Path firstLaunch() throws IOException {
        github.release("v1", "prometheus-core-1.jar", CORE_1, "sha256:" + sha256(CORE_1));
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
        assertEquals(0, github.releaseCalls.get());
    }

    @Test
    void downloadsVerifiesAndCaches() throws IOException {
        Path core = firstLaunch();
        assertEquals(cache().resolve("prometheus-core-1.jar"), core);
        assertTrue(Arrays.equals(CORE_1, Files.readAllBytes(core)));
        Properties state = CoreResolver.load(cache().resolve("core.properties"));
        assertEquals("v1", state.getProperty("tag"));
        assertEquals(sha256(CORE_1), state.getProperty("sha256"));
    }

    @Test
    void noGithubCallWithinTheInterval() throws IOException {
        Path core = firstLaunch();
        assertEquals(core, CoreResolver.resolve());
        assertEquals(1, github.releaseCalls.get());
    }

    @Test
    void dueButUpToDate() throws IOException {
        Path core = firstLaunch();
        due();
        assertEquals(core, CoreResolver.resolve());
        assertEquals(2, github.releaseCalls.get());
    }

    @Test
    void aNewReleaseReplacesTheOldCore() throws IOException {
        Path old = firstLaunch();
        due();
        github.release("v2", "prometheus-core-2.jar", CORE_2, "sha256:" + sha256(CORE_2));
        Path core = CoreResolver.resolve();
        assertEquals(cache().resolve("prometheus-core-2.jar"), core);
        assertFalse(Files.exists(old), "old core deleted");
    }

    @Test
    void sameTagNewAssetName() throws IOException {
        firstLaunch();
        due();
        github.release("v1", "prometheus-core-1b.jar", CORE_2, null);
        assertEquals(cache().resolve("prometheus-core-1b.jar"), CoreResolver.resolve(), "no digest is trusted");
    }

    @Test
    void aWrongDigestKeepsTheCache() throws IOException {
        Path core = firstLaunch();
        due();
        github.release("v2", "prometheus-core-2.jar", CORE_2, "sha256:" + sha256(CORE_1));
        assertEquals(core, CoreResolver.resolve());
        assertFalse(Files.exists(cache().resolve("prometheus-core-2.jar")));
    }

    @Test
    void aChangedCacheIsDownloadedUnderANewName() throws IOException {
        Path core = firstLaunch();
        Files.write(core, "tampered".getBytes(StandardCharsets.UTF_8));
        Path fresh = CoreResolver.resolve();
        assertNotNull(fresh);
        assertNotEquals(core, fresh, "never written over: another game may have it open");
        assertTrue(fresh.getFileName().toString().startsWith("prometheus-core-1-"), fresh.toString());
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
        assertEquals(1, github.releaseCalls.get());
    }

    @Test
    void githubDown() throws IOException {
        github.releaseStatus = 503;
        assertNull(CoreResolver.resolve());

        github.releaseStatus = 200;
        Path core = firstLaunch();
        due();
        github.releaseStatus = 503;
        assertEquals(core, CoreResolver.resolve());
    }

    @Test
    void releasesWithoutACore() {
        github.releaseJson = "{\"tag_name\":\"v1\"}";
        assertNull(CoreResolver.resolve());
        github.releaseJson = "{\"tag_name\":\"v1\",\"assets\":[\"not an object\",{\"name\":7},{\"name\":\"prometheus-core-1.zip\"},"
                + "{\"name\":\"other-1.jar\"}]}";
        assertNull(CoreResolver.resolve());
    }

    @Test
    void cutShortDownloads() {
        github.release("v1", "prometheus-core-1.jar", CORE_1, null, "truncated");
        assertNull(CoreResolver.resolve());
        assertFalse(Files.exists(cache().resolve("prometheus-core-1.jar")));
    }

    @Test
    void downloadsWithoutALength() throws IOException {
        github.release("v1", "prometheus-core-1.jar", CORE_1, "sha256:" + sha256(CORE_1), "chunked");
        Path core = CoreResolver.resolve();
        assertNotNull(core);
        assertTrue(Arrays.equals(CORE_1, Files.readAllBytes(core)));
    }

    @Test
    void aBadStateFileIsIgnored() throws IOException {
        TestSupport.write(cache().resolve("core.properties"), "checkedAt=soon\nfile=prometheus-core-1.jar\n");
        assertNotNull(firstLaunch(), "no sha256 means no verified cache, and soon means never checked");

        TestSupport.write(cache().resolve("core.properties"), "tag=\\u12\n");
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
