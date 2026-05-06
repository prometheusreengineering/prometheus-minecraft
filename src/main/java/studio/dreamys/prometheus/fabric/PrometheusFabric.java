package studio.dreamys.prometheus.fabric;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import studio.dreamys.prometheus.Prometheus;

import java.nio.file.Path;

public class PrometheusFabric extends Prometheus implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        patch();
    }

    @Override
    protected void addToClasspath0(Path jar) {
        FabricLauncherBase.getLauncher().addToClassPath(jar);
    }
}