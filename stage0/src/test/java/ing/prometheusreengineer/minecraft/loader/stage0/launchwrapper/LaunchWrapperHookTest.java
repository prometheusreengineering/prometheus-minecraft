package ing.prometheusreengineer.minecraft.loader.stage0.launchwrapper;

import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ing.prometheusreengineer.minecraft.loader.stage0.Stage0;
import ing.prometheusreengineer.minecraft.loader.stage0.TestSupport;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchWrapperHookTest {
    private Path folder;
    private Path patch;

    @BeforeEach
    void setUp() throws IOException {
        folder = TestSupport.folder();
        TestSupport.reset(folder);
        TestSupport.fakeCore(folder);
        patch = TestSupport.jar(folder.resolve("patch.jar"), "com/example/patch/A.class");
        System.setProperty("test.bootstrap.jars", patch.toString());
    }

    @AfterEach
    void tearDown() {
        TestSupport.clear();
    }

    private static Map<?, ?> context() {
        return (Map<?, ?>) System.getProperties().get("test.bootstrap.context");
    }

    @Test
    void addsPatchesToTheLaunchClassLoader() throws IOException {
        LaunchWrapperHook hook = new LaunchWrapperHook();
        hook.acceptOptions(Collections.<String>emptyList(), folder.toFile(), null, "profile");
        try (LaunchClassLoader loader = new LaunchClassLoader(new URL[0])) {
            hook.injectIntoClassLoader(loader);
            assertTrue(loader.getSources().contains(patch.toUri().toURL()), loader.getSources().toString());
        }
        assertEquals(folder.toAbsolutePath(), context().get("gameDir"));
        assertNull(hook.getLaunchTarget());
        assertEquals(0, hook.getLaunchArguments().length);
    }

    @Test
    void withoutAGameDirItsTheWorkingDirectory() throws IOException {
        LaunchWrapperHook hook = new LaunchWrapperHook();
        try (LaunchClassLoader loader = new LaunchClassLoader(new URL[0])) {
            hook.injectIntoClassLoader(loader);
        }
        assertEquals(Paths.get("").toAbsolutePath(), context().get("gameDir"), "acceptOptions never called");

        TestSupport.reset(folder);
        TestSupport.fakeCore(folder);
        hook.acceptOptions(Collections.<String>emptyList(), null, null, null);
        try (LaunchClassLoader loader = new LaunchClassLoader(new URL[0])) {
            hook.injectIntoClassLoader(loader);
        }
        assertEquals(Paths.get("").toAbsolutePath(), context().get("gameDir"), "no game dir given");
    }

    @Test
    void mixinConfigsGoToMixin() throws IOException {
        // without a running game Mixin can't take the config, which only the fake core sees
        System.setProperty("test.bootstrap.configs", "patch.mixins.json");
        try (LaunchClassLoader loader = new LaunchClassLoader(new URL[0])) {
            new LaunchWrapperHook().injectIntoClassLoader(loader);
        }
        assertTrue(context() != null);
    }

    @Test
    void anotherHookAlreadyRan() throws IOException {
        Stage0.claim("fabric");
        try (LaunchClassLoader loader = new LaunchClassLoader(new URL[0])) {
            new LaunchWrapperHook().injectIntoClassLoader(loader);
            assertTrue(loader.getSources().isEmpty());
        }
        assertNull(context());
    }

    @Test
    void notAUrl() {
        assertThrows(IllegalArgumentException.class, () -> LaunchWrapperHook.url(URI.create("no-such-protocol:thing")));
    }
}
