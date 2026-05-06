package studio.dreamys.prometheus.loader.forge;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import org.spongepowered.asm.mixin.MixinEnvironment;
import studio.dreamys.prometheus.loader.Prometheus;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class PrometheusForgeModLauncher extends Prometheus implements ITransformationService {
    private List<Resource> resources = new ArrayList<>();

    @Override
    protected void patch0(Path jarPath) {
        //we don't need to add mixins manually due to the environment hacking we do
        addToClasspath(jarPath);
    }

    @Override
    protected void addToClasspath0(Path jarPath) {
        //inject jar into classpath (will be permanently visible after mixin stage)
        resources.add(new Resource(IModuleLayerManager.Layer.GAME, Collections.singletonList(SecureJar.from(jarPath))));
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
        patch();

        //backup original classloader
        ClassLoader backupClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            //extend classpath with injected jars (will be temporarily visible before and during mixin stage)
            URLClassLoader urlClassLoader = new URLClassLoader(resources.stream().map(resource -> {
                Path jarPath = resource.resources().getFirst().getPrimaryPath();
                try {
                    return jarPath.toUri().toURL();
                } catch (MalformedURLException e) {
                    log(String.format("Failed to convert %s to URL", jarPath), e);
                    throw new RuntimeException(e);
                }
            }).toArray(URL[]::new), backupClassLoader);
            Thread.currentThread().setContextClassLoader(urlClassLoader);

            //expose the mixin environment to this new classloader/classpath so that mixins can be loaded
            MixinEnvironment.getDefaultEnvironment();
        } catch (Exception e) {
            log("Exception was thrown during MixinEnvironment hacking. " +
                    "If the issue is: 'Environment conflict, mismatched versions or you didn't call MixinBootstrap.init()', you can ignore it. " +
                    "It is not fatal and is a side effect of the environment hacking.", e);
        } finally {
            //restore original classloader
            Thread.currentThread().setContextClassLoader(backupClassLoader);
        }
    }

    @Override
    public List<Resource> beginScanning(IEnvironment environment) {
        return resources;
    }

    @Override
    @SuppressWarnings({"NullableProblems", "rawtypes"})
    public List<ITransformer> transformers() {
        return Collections.emptyList();
    }
}