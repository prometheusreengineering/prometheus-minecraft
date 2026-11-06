package ing.prometheusreengineer.minecraft.loader.stage0.fabric;

import ing.prometheusreengineer.minecraft.loader.stage0.Loader;
import ing.prometheusreengineer.minecraft.loader.stage0.Stage0;
import ing.prometheusreengineer.minecraft.loader.stage0.Stage0Log;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.spongepowered.asm.mixin.Mixins;

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

    /**
     * Never throws: it runs from the static block below, where anything thrown would crash the launch.
     */
    public static void onLanguageAdapterInit() {
        try {
            Stage0.run(Loader.FABRIC, FabricLoader.getInstance().getGameDir(),
                    jar -> FabricLauncherBase.getLauncher().addToClassPath(jar),
                    pendingMixinConfigs::add);
        } catch (Throwable t) {
            Stage0Log.error("could not start on Fabric", t);
        }
    }

    @Override
    public void onPreLaunch() {
        for (String config : pendingMixinConfigs) {
            try {
                Mixins.addConfiguration(config);
            } catch (Throwable t) {
                Stage0Log.error("could not register mixin config " + config, t);
            }
        }
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
