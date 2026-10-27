package studio.dreamys.prometheus.core;

import ing.prometheusreengineer.minecraft.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseResolverTest {
    private static final String REPO = "minecraft-test";
    private static final byte[] PATCH_1 = "patch one".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PATCH_2 = "patch two".getBytes(StandardCharsets.UTF_8);

    private Path folder;
    private FakeGitHub github;

    @BeforeEach
    void setUp() throws IOException {
        folder = TestSupport.folder();
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
        return folder.resolve("cache").resolve("patches").resolve(REPO);
    }

    private Path firstLaunch() {
        github.release(REPO, "v1", "minecraft-test-1.jar", PATCH_1, "sha256:" + Hashing.sha256(PATCH_1));
        Path jar = ReleaseResolver.resolve(REPO);
        assertNotNull(jar);
        return jar;
    }

    private void due() {
        System.setProperty("prometheus.update.interval", "0");
    }

    @Test
    void localJarForDevelopment() throws IOException {
        Path jar = TestSupport.jar(folder.resolve("dev.jar"), "a.txt");
        System.setProperty("prometheus.patch.minecraft-test.path", jar.toString());
        assertEquals(jar.toAbsolutePath(), ReleaseResolver.resolve(REPO));

        System.setProperty("prometheus.patch.minecraft-test.path", folder.resolve("missing.jar").toString());
        assertNull(ReleaseResolver.resolve(REPO));
        assertEquals(0, github.releaseCalls.get());
    }

    @Test
    void downloadsVerifiesAndCaches() throws IOException {
        Path jar = firstLaunch();
        assertEquals(cache().resolve("minecraft-test-1.jar"), jar);
        assertTrue(Arrays.equals(PATCH_1, TestSupport.bytes(jar)));
        Properties state = ReleaseResolver.load(cache().resolve("release.properties"));
        assertEquals("v1", state.getProperty("tag"));
        assertEquals(Hashing.sha256(PATCH_1), state.getProperty("sha256"));
    }

    @Test
    void noGithubCallWithinTheInterval() {
        Path jar = firstLaunch();
        assertEquals(jar, ReleaseResolver.resolve(REPO));
        assertEquals(1, github.releaseCalls.get());
    }

    @Test
    void dueButUpToDate() {
        Path jar = firstLaunch();
        due();
        assertEquals(jar, ReleaseResolver.resolve(REPO));
        assertEquals(2, github.releaseCalls.get());
    }

    @Test
    void aNewReleaseReplacesTheOldJar() {
        Path old = firstLaunch();
        due();
        github.release(REPO, "v2", "minecraft-test-2.jar", PATCH_2, "sha256:" + Hashing.sha256(PATCH_2));
        assertEquals(cache().resolve("minecraft-test-2.jar"), ReleaseResolver.resolve(REPO));
        assertFalse(Files.exists(old));
    }

    @Test
    void sameTagNewAssetName() {
        firstLaunch();
        due();
        github.release(REPO, "v1", "minecraft-test-1b.jar", PATCH_2, null);
        assertEquals(cache().resolve("minecraft-test-1b.jar"), ReleaseResolver.resolve(REPO), "no digest is trusted");
    }

    @Test
    void aNullDigestIsNoDigest() {
        github.releaseJson(REPO, "{\"tag_name\":\"v1\",\"assets\":[{\"name\":\"minecraft-test-1.jar\",\"digest\":null,"
                + "\"browser_download_url\":\"" + github.api() + "/download/minecraft-test-1.jar\"}]}");
        github.release("unused", "v1", "minecraft-test-1.jar", PATCH_1, null);
        assertEquals(cache().resolve("minecraft-test-1.jar"), ReleaseResolver.resolve(REPO));
    }

    @Test
    void aWrongDigestKeepsTheCache() {
        Path jar = firstLaunch();
        due();
        github.release(REPO, "v2", "minecraft-test-2.jar", PATCH_2, "sha256:" + Hashing.sha256(PATCH_1));
        assertEquals(jar, ReleaseResolver.resolve(REPO));
    }

    @Test
    void aChangedCacheIsDownloadedUnderANewName() throws IOException {
        Path jar = firstLaunch();
        Files.write(jar, "tampered".getBytes(StandardCharsets.UTF_8));
        Path fresh = ReleaseResolver.resolve(REPO);
        assertNotNull(fresh);
        assertNotEquals(jar, fresh);
        assertTrue(fresh.getFileName().toString().startsWith("minecraft-test-1-"), fresh.toString());
    }

    @Test
    void aMissingCacheIsDownloadedAgain() throws IOException {
        Path jar = firstLaunch();
        Files.delete(jar);
        assertEquals(jar, ReleaseResolver.resolve(REPO));
        assertTrue(Files.exists(jar));
    }

    @Test
    void offline() {
        System.setProperty("prometheus.offline", "true");
        assertNull(ReleaseResolver.resolve(REPO), "nothing cached yet");

        System.clearProperty("prometheus.offline");
        Path jar = firstLaunch();
        System.setProperty("prometheus.offline", "true");
        due();
        assertEquals(jar, ReleaseResolver.resolve(REPO));
        assertEquals(1, github.releaseCalls.get());
    }

    @Test
    void githubDown() {
        github.releaseStatus = 503;
        assertNull(ReleaseResolver.resolve(REPO));

        github.releaseStatus = 200;
        Path jar = firstLaunch();
        due();
        github.releaseStatus = 503;
        assertEquals(jar, ReleaseResolver.resolve(REPO));
    }

    @Test
    void releasesWithoutAJar() {
        github.releaseJson(REPO, "{\"tag_name\":\"v1\"}");
        assertNull(ReleaseResolver.resolve(REPO));
        github.releaseJson(REPO, "{\"tag_name\":\"v1\",\"assets\":[{\"size\":1},{\"name\":\"sources.zip\"}]}");
        assertNull(ReleaseResolver.resolve(REPO));
    }

    @Test
    void cutShortDownloads() {
        github.release(REPO, "v1", "minecraft-test-1.jar", PATCH_1, null, "truncated");
        assertNull(ReleaseResolver.resolve(REPO));
        assertFalse(Files.exists(cache().resolve("minecraft-test-1.jar")));
    }

    @Test
    void aBadStateFileIsIgnored() throws IOException {
        Files.createDirectories(cache());
        Files.write(cache().resolve("release.properties"), "checkedAt=soon\nfile=minecraft-test-1.jar\n".getBytes(StandardCharsets.UTF_8));
        assertNotNull(firstLaunch());

        Files.write(cache().resolve("release.properties"), "tag=\\u12\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(ReleaseResolver.load(cache().resolve("release.properties")).isEmpty());
    }

    @Test
    void anUnsavableStateIsOnlyLogged() throws IOException {
        Path notAFolder = Files.createFile(folder.resolve("file"));
        assertDoesNotThrow(() -> ReleaseResolver.save(notAFolder.resolve("release.properties"), new Properties()));
    }

    @Test
    void oldJarsInUseStay() throws IOException {
        Path keep = Files.createDirectories(cache()).resolve("minecraft-test-2.jar");
        Files.write(keep, PATCH_2);
        Path inUse = Files.createDirectories(cache().resolve("minecraft-test-1.jar").resolve("held"));
        ReleaseResolver.deleteOthers(cache(), keep);
        assertTrue(Files.exists(keep));
        assertTrue(Files.exists(inUse));

        assertDoesNotThrow(() -> ReleaseResolver.deleteOthers(folder.resolve("missing"), keep));
    }
}
