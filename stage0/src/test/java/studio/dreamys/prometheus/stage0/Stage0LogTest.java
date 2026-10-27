package studio.dreamys.prometheus.stage0;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Stage0LogTest {
    @TempDir
    Path folder;

    @BeforeEach
    void setUp() {
        TestSupport.reset(folder);
    }

    @AfterEach
    void tearDown() {
        TestSupport.clear();
    }

    private String log() throws IOException {
        return TestSupport.read(folder.resolve("logs").resolve("stage0.log"));
    }

    @Test
    void firstLineOfALaunchStartsTheFileOver() throws IOException {
        TestSupport.write(folder.resolve("logs").resolve("stage0.log"), "last launch\n");
        Stage0Log.info("hook %s", "fabric");
        Stage0Log.warn("then %d more", 2);

        String log = log();
        assertFalse(log.contains("last launch"), log);
        assertTrue(log.contains("[Prometheus/stage0] (INFO) hook fabric"), log);
        assertTrue(log.contains("[Prometheus/stage0] (WARN) then 2 more"), log);
    }

    @Test
    void errorsKeepTheStackTrace() throws IOException {
        Stage0Log.error("the core failed", new IllegalStateException("broken"));
        assertTrue(log().contains("java.lang.IllegalStateException: broken"), log());
    }

    @Test
    void noArgsAndBadPatternsAreKept() throws IOException {
        Stage0Log.info("100% literal");
        Stage0Log.info("bad %d", "not a number");
        assertTrue(log().contains("100% literal"), log());
        assertTrue(log().contains("bad %d"), log());
    }

    @Test
    void anUnwritableFolderOnlyLosesTheFile() throws IOException {
        Path notAFolder = Files.createFile(folder.resolve("file"));
        System.setProperty("prometheus.folder", notAFolder.toString());
        assertDoesNotThrow(() -> Stage0Log.warn("still on stdout"));
    }
}
