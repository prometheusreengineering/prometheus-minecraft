package ing.prometheusreengineer.minecraft.loader.stage0.modlauncher;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyBaseTest {
    /**
     * Stands in for ModLauncherHook, whose 9+ half needs ModLauncher 10.
     */
    static final class Hook extends LegacyBase {
        static final List<Map.Entry<String, Path>> SCAN = Collections.<Map.Entry<String, Path>>singletonList(
                new AbstractMap.SimpleImmutableEntry<>("patch.jar", Paths.get("patch.jar")));

        @Override
        protected List<Map.Entry<String, Path>> legacyScan() {
            return SCAN;
        }

        @Override
        public String name() {
            return "test";
        }

        @Override
        public void initialize(IEnvironment environment) {}

        @Override
        public void onLoad(IEnvironment environment, Set<String> otherServices) {}

        @Override
        @SuppressWarnings("rawtypes")
        public List<ITransformer> transformers() {
            return Collections.emptyList();
        }
    }

    @Test
    void runScanIsTheLegacyScan() {
        assertEquals(Hook.SCAN, new Hook().runScan(null));
    }

    @Test
    void beginScanningDoesNothing() {
        assertDoesNotThrow(() -> new Hook().beginScanning(null));
    }
}
