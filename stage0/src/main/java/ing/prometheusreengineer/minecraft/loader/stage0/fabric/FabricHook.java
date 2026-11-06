package ing.prometheusreengineer.minecraft.loader.stage0.fabric;

import ing.prometheusreengineer.minecraft.loader.stage0.Loader;
import net.fabricmc.loader.api.*;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.spongepowered.asm.mixin.Mixins;
import ing.prometheusreengineer.minecraft.loader.stage0.Stage0;

/**
 * Language adapter for early booting.
 */
public class FabricHook implements LanguageAdapter {
    @SuppressWarnings("Convert2MethodRef")
    public static void onLanguageAdapterInit() {
        Stage0.run(Loader.FABRIC, FabricLoader.getInstance().getGameDir(),
                FabricLauncherBase.getLauncher()::addToClassPath,
                // a lambda, not Mixins::addConfiguration, so Mixins is only resolved when a config is added
                config -> Mixins.addConfiguration(config));
    }

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        throw new LanguageAdapterException("Prometheus called as a language adapter!");
    }

    static {
        onLanguageAdapterInit();
    }
}
