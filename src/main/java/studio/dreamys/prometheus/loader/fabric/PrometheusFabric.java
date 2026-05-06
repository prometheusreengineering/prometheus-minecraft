package studio.dreamys.prometheus.loader.fabric;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import studio.dreamys.prometheus.loader.Prometheus;

import java.nio.file.Path;

public class PrometheusFabric extends Prometheus implements PreLaunchEntrypoint {
    @Override
    protected void addToClasspath0(Path jar) {
        //inject jar into classpath
        FabricLauncherBase.getLauncher().addToClassPath(jar);
    }

    @Override
    public void onPreLaunch() {
        patch();
    }
}