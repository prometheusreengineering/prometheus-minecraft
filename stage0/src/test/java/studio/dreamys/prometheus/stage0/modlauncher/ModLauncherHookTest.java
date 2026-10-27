package studio.dreamys.prometheus.stage0.modlauncher;

import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import studio.dreamys.prometheus.stage0.TestSupport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModLauncherHookTest {
    private final boolean modern = ModLauncherHook.modern;
    private Path folder;
    private Path patch;

    @BeforeEach
    void setUp() throws IOException {
        folder = TestSupport.folder();
        TestSupport.reset(folder);
        TestSupport.fakeCore(folder);
        patch = TestSupport.jar(folder.resolve("patch.jar"), "com/example/patch/A.class", "patch.mixins.json");
    }

    @AfterEach
    void tearDown() {
        ModLauncherHook.modern = modern;
        TestSupport.clear();
    }

    /**
     * A hook after ModLauncher's onLoad, with the fake core adding {@code patch} and its mixin config.
     */
    private ModLauncherHook loaded() {
        System.setProperty("test.bootstrap.jars", patch.toString());
        System.setProperty("test.bootstrap.configs", "patch.mixins.json");
        ModLauncherHook hook = new ModLauncherHook();
        hook.onLoad(null, Collections.<String>emptySet());
        return hook;
    }

    @Test
    void whatModLauncherAsksFirst() {
        ModLauncherHook hook = new ModLauncherHook();
        assertEquals("prometheus-minecraft", hook.name());
        hook.initialize(null);
        assertTrue(hook.transformers().isEmpty());
    }

    @Test
    void anotherHookAlreadyRan() {
        assertTrue(studio.dreamys.prometheus.stage0.Stage0.claim("neoforge"));
        ModLauncherHook hook = new ModLauncherHook();
        hook.onLoad(null, Collections.<String>emptySet());

        assertTrue(hook.beginScanning(null).isEmpty());
        assertTrue(hook.legacyScan().isEmpty());
        assertNull(hook.additionalClassesLocator());
        assertNull(hook.additionalResourcesLocator());
    }

    @Test
    void noPatchesNoMixins() {
        ModLauncherHook hook = new ModLauncherHook();
        hook.onLoad(null, Collections.<String>emptySet());
        assertTrue(hook.beginScanning(null).isEmpty());
        assertTrue(hook.legacyScan().isEmpty());
        assertNull(hook.additionalClassesLocator(), "no jars, no locator");
    }

    @Test
    void modernPutsPatchesInTheGameLayer() {
        ModLauncherHook.modern = true;
        List<ITransformationService.Resource> resources = loaded().beginScanning(null);
        assertEquals(1, resources.size());
        assertEquals(IModuleLayerManager.Layer.GAME, resources.get(0).target());
    }

    @Test
    void olderModLauncherUsesThePluginLayerAndRegistersMixins() {
        ModLauncherHook.modern = false;
        List<ITransformationService.Resource> resources = loaded().beginScanning(null);
        assertEquals(IModuleLayerManager.Layer.PLUGIN, resources.get(0).target());
    }

    @Test
    void olderModLauncherWithAPatchWithoutMixins() {
        ModLauncherHook.modern = false;
        System.setProperty("test.bootstrap.jars", patch.toString());
        ModLauncherHook hook = new ModLauncherHook();
        hook.onLoad(null, Collections.<String>emptySet());
        assertEquals(1, hook.beginScanning(null).size());
    }

    @Test
    void modLauncher4To8ScansAndServesTheJars() {
        ModLauncherHook hook = loaded();
        List<Map.Entry<String, Path>> scan = hook.legacyScan();
        assertEquals(1, scan.size());
        assertEquals("patch.jar", scan.get(0).getKey());
        assertEquals(patch, scan.get(0).getValue());

        assertNotNull(hook.additionalClassesLocator());
        assertNotNull(hook.additionalResourcesLocator());
    }

    @Test
    void java21Check() {
        assertTrue(ModLauncherHook.hasMethod(String.class, "isEmpty"));
        assertFalse(ModLauncherHook.hasMethod(String.class, "noSuchMethod"));
    }
}
