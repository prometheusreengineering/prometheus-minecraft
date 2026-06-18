package studio.dreamys.prometheus.loader.forge;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import org.spongepowered.asm.launch.MixinBootstrap;
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
        addToClasspath(jarPath);
        //add mixins later
    }

    @Override
    protected void addToClasspath0(Path jarPath) {
        //inject jar into classpath (Layer.PLUGIN is not filtered by FML)
        resources.add(new Resource(IModuleLayerManager.Layer.PLUGIN, Collections.singletonList(SecureJar.from(jarPath))));
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
            //extend classpath with injected jars so that mixin can read the mixins.json file out of it
            URLClassLoader urlClassLoader = new URLClassLoader(resources.stream().map(resource -> {
                Path jarPath = resource.resources().get(0).getPrimaryPath();
                try {
                    return jarPath.toUri().toURL();
                } catch (MalformedURLException e) {
                    log(String.format("Failed to convert %s to URL", jarPath), e);
                    throw new RuntimeException(e);
                }
            }).toArray(URL[]::new), backupClassLoader);
            Thread.currentThread().setContextClassLoader(urlClassLoader);

            try {
                //expose the mixin environment to this new classloader/classpath so that mixins can be loaded
                MixinBootstrap.init();
                MixinEnvironment.getDefaultEnvironment();
            } catch (Exception ignored) {} //can throw non-fatal issue if another mod initialized it, no biggie

            //load mixin configs from the added jars while the URLClassLoader is still context
            for (Resource resource : resources) {
                Path jar = resource.resources().get(0).getPrimaryPath();
                addMixinConfigs(jar);
            }
        } catch (Exception e) {
            log("Exception was thrown during Mixin configuration loading", e);
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