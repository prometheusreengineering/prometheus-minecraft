package studio.dreamys.prometheus.forge;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import studio.dreamys.prometheus.Prometheus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class PrometheusForgeModLauncher extends Prometheus implements ITransformationService {
    List<Resource> resources = new ArrayList<>();

    @Override
    protected void addToClasspath0(Path jar) {
        SecureJar secureJar = SecureJar.from(jar);
        resources.add(new Resource(IModuleLayerManager.Layer.BOOT, Collections.singletonList(secureJar)));
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public String name() {
        return "prometheus-minecraft";
    }

    @Override
    public void initialize(IEnvironment iEnvironment) {

    }

    @Override
    public void onLoad(IEnvironment iEnvironment, Set<String> set) {

    }

    @Override
    public List<Resource> beginScanning(IEnvironment environment) {
        patch();
        return resources;
    }

    @Override
    @SuppressWarnings({"NullableProblems", "rawtypes"})
    public List<ITransformer> transformers() {
        return Collections.emptyList();
    }
}
