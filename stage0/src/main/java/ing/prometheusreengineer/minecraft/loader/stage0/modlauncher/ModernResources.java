package ing.prometheusreengineer.minecraft.loader.stage0.modlauncher;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;

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
     * The GAME layer, with the mods. In PLUGIN, as the loader before stage 0 did on Java 17, a patch's module can't read
     * the mod it patches ("module prometheus.essential does not read module essential", Forge 1.18.2).
     */
    static List<ITransformationService.Resource> of(List<Path> jars) {
        List<ITransformationService.Resource> resources = new ArrayList<>();
        for (Path jar : jars) {
            resources.add(new ITransformationService.Resource(IModuleLayerManager.Layer.GAME, Collections.singletonList(SecureJar.from(jar))));
        }
        return resources;
    }
}
