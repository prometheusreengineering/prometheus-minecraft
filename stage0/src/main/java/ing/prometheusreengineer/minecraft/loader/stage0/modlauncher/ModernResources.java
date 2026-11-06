package ing.prometheusreengineer.minecraft.loader.stage0.modlauncher;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The only class using ModLauncher 9+ types, so it is only ever loaded there.
 */
final class ModernResources {
    private ModernResources() {}

    /**
     * Java 21+ (Forge 1.21+) puts them in the GAME layer, older versions in PLUGIN, as the loader always did.
     */
    static @Unmodifiable List<ITransformationService.Resource> of(List<Path> jars, boolean modern) {
        IModuleLayerManager.Layer layer = modern ? IModuleLayerManager.Layer.GAME : IModuleLayerManager.Layer.PLUGIN;
        // JDK only: stage 0 can't use minecraft-lib, which isn't on its class path
        List<ITransformationService.Resource> resources = new ArrayList<>();
        for (Path jar : jars) {
            resources.add(new ITransformationService.Resource(layer, Collections.singletonList(SecureJar.from(jar))));
        }
        return Collections.unmodifiableList(resources);
    }
}
