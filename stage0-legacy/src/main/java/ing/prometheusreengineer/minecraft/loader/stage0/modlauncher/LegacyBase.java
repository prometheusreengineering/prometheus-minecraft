package ing.prometheusreengineer.minecraft.loader.stage0.modlauncher;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The ModLauncher 2-8 half (Forge 1.14-1.16) of {@link ModLauncherHook}, compiled against modlauncher 8.
 * <br>
 * {@code void beginScanning(IEnvironment)} has the same name and parameters as ModLauncher 9+'s
 * {@code List<Resource> beginScanning(IEnvironment)}, so the two can't be in one source file. They can be in one class
 * at runtime, because the JVM tells methods apart by their full descriptor. stage0 compiles ModLauncherHook against a
 * stub of this class without these two methods, and the build swaps this one in.
 */
@SuppressWarnings("JavadocReference") //unreachable link to other module
public abstract class LegacyBase implements ITransformationService {
    // abstract in ModLauncher 2-8, so it has to exist there, but 4-8 never call it (they use runScan)
    public void beginScanning(IEnvironment environment) {
    }

    // ModLauncher 4-8: jars returned here join the game's class loader
    public List<Map.Entry<String, Path>> runScan(IEnvironment environment) {
        return legacyScan();
    }

    protected abstract List<Map.Entry<String, Path>> legacyScan();
}
