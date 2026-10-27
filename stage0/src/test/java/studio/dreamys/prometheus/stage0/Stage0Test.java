package studio.dreamys.prometheus.stage0;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Stage0Test {
    private Path folder;

    private final List<Path> classpath = new ArrayList<>();
    private final List<String> configs = new ArrayList<>();

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
        Stage0.run("test", Paths.get("game"), classpath::add, configs::add);
    }

    @Test
    void theFirstHookClaimsTheJvm() {
        assertTrue(Stage0.claim("modlauncher"));
        assertFalse(Stage0.claim("neoforge"));
        assertEquals("modlauncher", System.getProperty("prometheus.stage0.claimed"));
    }

    @Test
    void startsTheCoreWithTheContract() throws IOException {
        Path core = TestSupport.fakeCore(folder);
        System.setProperty("test.bootstrap.jars", core.toString());
        System.setProperty("test.bootstrap.configs", "a.mixins.json,b.mixins.json");

        run();

        Map<?, ?> context = (Map<?, ?>) System.getProperties().get("test.bootstrap.context");
        assertEquals(Stage0.CONTRACT, context.get("contract"));
        assertEquals("2.0.0-minecraft", context.get("stage0Version"));
        assertEquals("test", context.get("platform"));
        assertEquals(Paths.get("game"), context.get("gameDir"));
        assertEquals(core, context.get("coreJar"));
        assertEquals(1, classpath.size());
        assertEquals(2, configs.size());
    }

    @Test
    void noCoreMeansOff() {
        System.setProperty("prometheus.core.path", folder.resolve("missing.jar").toString());
        run();
        assertNull(System.getProperties().get("test.bootstrap.context"));
    }

    @Test
    void aFailingCoreDoesNotEscape() throws IOException {
        TestSupport.fakeCore(folder);
        System.setProperty("test.bootstrap.throw", "true");
        run();
        assertTrue(TestSupport.read(folder.resolve("logs").resolve("stage0.log")).contains("the core failed"));
    }

    @Test
    void aCoreThatCantLoadDoesNotEscape() throws IOException {
        TestSupport.fakeCore(folder);
        System.setProperty("test.bootstrap.broken", "true");
        run();
        assertTrue(TestSupport.read(folder.resolve("logs").resolve("stage0.log")).contains("could not start the core"));
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
