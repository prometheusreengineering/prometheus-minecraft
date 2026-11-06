package ing.prometheusreengineer.minecraft.loader.stage0.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.spongepowered.asm.mixin.Mixins;
import ing.prometheusreengineer.minecraft.loader.stage0.Stage0;

/**
 * Fabric: the {@code preLaunch} entrypoint in fabric.mod.json.
 */
public class FabricHook implements PreLaunchEntrypoint {
    @SuppressWarnings("Convert2MethodRef") //explained below
    @Override
    public void onPreLaunch() {
        if (!Stage0.claim("fabric")) return;
        Stage0.run("fabric", FabricLoader.getInstance().getGameDir(),
                jar -> FabricLauncherBase.getLauncher().addToClassPath(jar),
                // a lambda, not Mixins::addConfiguration, so Mixins is only resolved when a config is added
                config -> Mixins.addConfiguration(config));
    }
}
