package studio.dreamys.prometheus.stage0.neoforge;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import studio.dreamys.prometheus.stage0.Stage0;
import studio.dreamys.prometheus.stage0.TestSupport;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeLocatorTest {
    private Path folder;
    private Path patch;
    private final List<Path> added = new ArrayList<>();
    private IDiscoveryPipeline pipeline;

    @BeforeEach
    void setUp() throws IOException {
        folder = TestSupport.folder();
        TestSupport.reset(folder);
        TestSupport.fakeCore(folder);
        patch = TestSupport.jar(folder.resolve("patch.jar"), "META-INF/neoforge.mods.toml");
        pipeline = (IDiscoveryPipeline) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IDiscoveryPipeline.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addPath")) added.add((Path) args[0]);
                    return null;
                });
    }

    @AfterEach
    void tearDown() {
        TestSupport.clear();
    }

    @Test
    void addsPatchesAsModFiles() {
        System.setProperty("test.bootstrap.jars", patch.toString());
        System.setProperty("test.bootstrap.configs", "patch.mixins.json");
        new NeoForgeLocator().findCandidates((ILaunchContext) null, pipeline);
        assertEquals(1, added.size());
        assertEquals(patch, added.get(0));
        assertTrue(System.getProperties().get("test.bootstrap.context") != null);
    }

    @Test
    void anotherHookAlreadyRan() {
        Stage0.claim("modlauncher");
        new NeoForgeLocator().findCandidates((ILaunchContext) null, pipeline);
        assertTrue(added.isEmpty());
        assertNull(System.getProperties().get("test.bootstrap.context"));
    }

    @Test
    void name() {
        assertEquals("prometheus-minecraft", new NeoForgeLocator().toString());
    }
}
