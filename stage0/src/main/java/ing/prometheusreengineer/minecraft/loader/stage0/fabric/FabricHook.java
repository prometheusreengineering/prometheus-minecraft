package ing.prometheusreengineer.minecraft.loader.stage0.fabric;

import ing.prometheusreengineer.minecraft.loader.stage0.Loader;
import ing.prometheusreengineer.minecraft.loader.stage0.Stage0Log;
import net.fabricmc.loader.api.*;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.spongepowered.asm.mixin.Mixins;
import ing.prometheusreengineer.minecraft.loader.stage0.Stage0;

import java.util.ArrayList;
import java.util.List;

/**
 * Language adapter for early booting, and the {@code preLaunch} entrypoint that registers the patches' mixin configs.
 * <br>
 * Fabric sets up language adapters before it starts Mixin, so a config added then is refused ("you didn't call
 * MixinBootstrap.init()", checked on Fabric 1.21.4 with Loader 0.17.3). The patch jars go on the class path right
 * away, and their configs wait for {@code preLaunch}.
 */
public class FabricHook implements LanguageAdapter, PreLaunchEntrypoint {
    private static final List<String> pendingMixinConfigs = new ArrayList<>();

    public static void onLanguageAdapterInit() {
        Stage0.run(Loader.FABRIC, FabricLoader.getInstance().getGameDir(),
                FabricLauncherBase.getLauncher()::addToClassPath,
                pendingMixinConfigs::add);
    }

    @Override
    public void onPreLaunch() {
        pendingMixinConfigs.forEach(Mixins::addConfiguration);
        pendingMixinConfigs.clear();
    }

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        throw new LanguageAdapterException("Prometheus called as a language adapter!");
    }

    static {
        onLanguageAdapterInit();
    }
}
