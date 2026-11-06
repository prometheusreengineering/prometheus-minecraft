package ing.prometheusreengineer.minecraft.loader.stage0.modlauncher;

import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ing.prometheusreengineer.minecraft.loader.stage0.TestSupport;

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
    private final boolean layered = ModLauncherHook.layered;
    private Path folder;
    private Path patch;

    @BeforeEach
    void setUp() throws IOException {
        folder = TestSupport.folder();
        TestSupport.reset(folder);
        TestSupport.fakeLib(folder);
        patch = TestSupport.jar(folder.resolve("patch.jar"), "com/example/patch/A.class", "patch.mixins.json");
    }

    @AfterEach
    void tearDown() {
        ModLauncherHook.layered = layered;
        TestSupport.clear();
    }

    /**
     * A hook after ModLauncher's onLoad, with the fake stage 1 adding {@code patch} and its mixin config.
     */
    private ModLauncherHook loaded() {
        System.setProperty("test.stage1.jars", patch.toString());
        System.setProperty("test.stage1.configs", "patch.mixins.json");
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
        // what Stage0.claim leaves behind
        System.setProperty("prometheus.stage0.claimed", "neoforge");
        ModLauncherHook hook = new ModLauncherHook();
        hook.onLoad(null, Collections.<String>emptySet());

        assertTrue(hook.completeScan(null).isEmpty());
        assertTrue(hook.legacyScan().isEmpty());
        assertNull(hook.additionalClassesLocator());
        assertNull(hook.additionalResourcesLocator());
    }

    @Test
    void scannedBeforeOnLoad() {
        ModLauncherHook hook = new ModLauncherHook();
        assertTrue(hook.completeScan(null).isEmpty());
        assertTrue(hook.legacyScan().isEmpty());
    }

    @Test
    void noPatchesNoMixins() {
        ModLauncherHook hook = new ModLauncherHook();
        hook.onLoad(null, Collections.<String>emptySet());
        assertTrue(hook.completeScan(null).isEmpty());
        assertTrue(hook.legacyScan().isEmpty());
        assertNull(hook.additionalClassesLocator(), "no jars, no locator");
    }

    @Test
    void modLauncher9PutsPatchesInTheGameLayerAfterTheScan() {
        ModLauncherHook.layered = true;
        ModLauncherHook hook = loaded();
        // 9-11 drop GAME resources from beginScanning
        assertTrue(hook.beginScanning(null).isEmpty());
        // without a running game Mixin refuses the config, which is only logged
        List<ITransformationService.Resource> resources = hook.completeScan(null);
        assertEquals(1, resources.size());
        assertEquals(IModuleLayerManager.Layer.GAME, resources.get(0).target());
    }

    @Test
    void olderModLauncherRegistersMixinsOnLoad() {
        ModLauncherHook.layered = false;
        assertEquals(1, loaded().legacyScan().size());
    }

    @Test
    void olderModLauncherWithAPatchWithoutMixins() {
        ModLauncherHook.layered = false;
        System.setProperty("test.stage1.jars", patch.toString());
        ModLauncherHook hook = new ModLauncherHook();
        hook.onLoad(null, Collections.<String>emptySet());
        assertEquals(1, hook.legacyScan().size());
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
    void modLauncher9Check() {
        assertTrue(ModLauncherHook.hasClass("cpw.mods.modlauncher.api.IModuleLayerManager"));
        assertFalse(ModLauncherHook.hasClass("cpw.mods.modlauncher.NoSuchClass"));
    }
}
