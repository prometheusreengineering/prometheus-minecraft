package ing.prometheusreengineer.minecraft.loader.stage0.modlauncher;

import cpw.mods.modlauncher.api.ITransformationService;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * STUB, only so ModLauncherHook compiles. The stage0 build replaces this class with the real one from the
 * stage0-legacy module (maven-dependency-plugin, prepare-package), which adds ModLauncher 2-8's
 * {@code void beginScanning} and {@code runScan}. javac would reject that {@code void beginScanning} next to
 * ModLauncherHook's {@code List} one, so it can't be here.
 * <br>
 * Keep its members (apart from those two) the same as stage0-legacy's LegacyBase.
 */
public abstract class LegacyBase implements ITransformationService {
    @SuppressWarnings("unused") //used by ModLauncherHook
    protected abstract List<Map.Entry<String, Path>> legacyScan();
}
