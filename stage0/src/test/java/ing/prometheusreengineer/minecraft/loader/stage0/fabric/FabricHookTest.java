package ing.prometheusreengineer.minecraft.loader.stage0.fabric;

import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ing.prometheusreengineer.minecraft.loader.stage0.TestSupport;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricHookTest {
    private Path folder;
    private Path patch;
    private final List<Path> added = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        folder = TestSupport.folder();
        TestSupport.reset(folder);
        TestSupport.fakeCore(folder);
        patch = TestSupport.jar(folder.resolve("patch.jar"), "com/example/patch/A.class");

        // what Fabric has set up by the time it loads language adapters: the game dir and its launcher
        Field gameDir = FabricLoaderImpl.class.getDeclaredField("gameDir");
        gameDir.setAccessible(true);
        gameDir.set(FabricLoaderImpl.INSTANCE, folder);
        launcher((FabricLauncher) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FabricLauncher.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addToClassPath")) added.add((Path) args[0]);
                    return null;
                }));
    }

    @AfterEach
    void tearDown() throws Exception {
        launcher(null);
        TestSupport.clear();
    }

    /**
     * FabricLauncherBase.setLauncher only allows one call per JVM.
     */
    private static void launcher(FabricLauncher launcher) throws Exception {
        Field field = FabricLauncherBase.class.getDeclaredField("launcher");
        field.setAccessible(true);
        field.set(null, launcher);
    }

    private static Map<?, ?> context() {
        return (Map<?, ?>) System.getProperties().get("test.bootstrap.context");
    }

    @Test
    void addsPatchesThroughFabricsLauncher() throws IOException {
        System.setProperty("test.bootstrap.jars", patch.toString());
        FabricHook.onLanguageAdapterInit();
        assertEquals(folder, context().get("gameDir"));
        assertEquals(1, added.size());
        assertEquals(patch, added.get(0));
    }

    @Test
    void mixinConfigsGoToMixin() {
        // without a running game Mixin can't take the config, which only the fake core sees
        System.setProperty("test.bootstrap.configs", "patch.mixins.json");
        FabricHook.onLanguageAdapterInit();
        assertTrue(context() != null);
    }

    @Test
    void anotherHookAlreadyRan() {
        // what Stage0.claim leaves behind
        System.setProperty("prometheus.stage0.claimed", "modlauncher");
        FabricHook.onLanguageAdapterInit();
        assertNull(context());
    }

    @Test
    void neverCreatesAnything() {
        // stage 0 is only a language adapter to run early, no mod uses it
        assertThrows(LanguageAdapterException.class, () -> new FabricHook().create(null, "", Object.class));
    }
}
