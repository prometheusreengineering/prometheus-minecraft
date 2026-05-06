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
    private final List<Resource> resources = new ArrayList<>();
    private final Object lock = new Object();
    private boolean scanningFinished;

    @Override
    protected void addToClasspath0(Path jar) {
        SecureJar secureJar = SecureJar.from(jar);
        resources.add(new Resource(IModuleLayerManager.Layer.BOOT, Collections.singletonList(secureJar)));

        synchronized (lock) {
            while (!scanningFinished) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    log("Interrupted while waiting for patch to be added to classpath", e);
                    throw new RuntimeException(e);
                }
            }
        }
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
        synchronized (lock) {
            scanningFinished = true;
            lock.notify();
        }

        return Collections.emptyList();
    }
}
