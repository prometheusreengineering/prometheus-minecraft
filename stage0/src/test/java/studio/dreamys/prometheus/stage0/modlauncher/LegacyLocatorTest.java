package studio.dreamys.prometheus.stage0.modlauncher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.dreamys.prometheus.stage0.TestSupport;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyLocatorTest {
    @TempDir
    Path folder;

    /**
     * Stands in for ModLauncher 4-8's TransformationServiceDecorator and its static set of claimed names.
     */
    static final Set<String> claimed = new HashSet<>();
    static final String notASet = "not a set";

    @BeforeEach
    void setUp() {
        TestSupport.reset(folder);
    }

    @AfterEach
    void tearDown() {
        claimed.clear();
        TestSupport.clear();
    }

    private LegacyLocator locator() throws IOException {
        Path patch = TestSupport.jar(folder.resolve("patch.jar"),
                "com/example/patch/", "META-INF/MANIFEST.MF", "/absolute.txt", "Root.class", ".hidden/C.class",
                "net/minecraft/client/Minecraft.class", "net/minecraftforge/common/Forge.class",
                "com/example/patch/A.class", "com/example/patch/sub/B.class",
                "patch.mixins.json", "pack.mcmeta", "fabric.mod.json", "assets/patch/icon.png");
        Path other = TestSupport.jar(folder.resolve("other.jar"), "com/example/patch/A.class", "other/C.class");
        Path broken = Files.write(folder.resolve("broken.jar"), new byte[]{1, 2, 3});
        return new LegacyLocator(Arrays.asList(patch, other, broken));
    }

    private static Optional<URL> find(Map.Entry<Set<String>, Supplier<Function<String, Optional<URL>>>> entry, String name) {
        return entry.getValue().get().apply(name);
    }

    @Test
    void servesOnlyWhatModLauncherAccepts() throws IOException {
        LegacyLocator locator = locator();
        Map.Entry<Set<String>, Supplier<Function<String, Optional<URL>>>> classes = locator.classes();
        Map.Entry<Set<String>, Supplier<Function<String, Optional<URL>>>> resources = locator.resources();

        assertEquals(new LinkedHashSet<>(Arrays.asList("com.example.patch.", "com.example.patch.sub.", "other.")), classes.getKey());
        assertEquals(new LinkedHashSet<>(Arrays.asList("patch.mixins.json", "assets/patch/icon.png")), resources.getKey());
    }

    @Test
    void theFirstJarWins() throws IOException {
        LegacyLocator locator = locator();
        Optional<URL> a = find(locator.classes(), "com/example/patch/A.class");
        assertTrue(a.isPresent());
        assertTrue(a.get().toString().contains("patch.jar!/com/example/patch/A.class"), a.get().toString());
        assertTrue(find(locator.resources(), "patch.mixins.json").isPresent());
        assertFalse(find(locator.classes(), "com/example/patch/Missing.class").isPresent());
    }

    @Test
    void nothingToServe() {
        LegacyLocator empty = new LegacyLocator(Collections.<Path>emptyList());
        assertNull(empty.classes());
        assertNull(empty.resources());
    }

    @Test
    void namesAnotherServiceClaimedAreLeftOut() {
        claimed.add("other.");
        claimed.add("somebody.else.");
        Set<String> ours = new LinkedHashSet<>(Arrays.asList("com.example.", "other."));
        assertEquals(Collections.singleton("com.example."), LegacyLocator.unclaimed(ours, LegacyLocatorTest.class.getName(), "claimed"));
        assertEquals(ours, LegacyLocator.unclaimed(ours, LegacyLocatorTest.class.getName(), "notASet"));
        assertEquals(ours, LegacyLocator.unclaimed(ours, LegacyLocatorTest.class.getName(), "missing"), "unreadable keeps all");
    }

    @Test
    void aBadUrlIsEmpty() {
        assertFalse(LegacyLocator.url("no-such-protocol:thing").isPresent());
    }
}
