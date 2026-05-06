package studio.dreamys.prometheus.loader.forge;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import studio.dreamys.prometheus.loader.Prometheus;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class PrometheusForgeLegacyLauncher extends Prometheus implements ITweaker {
    private LaunchClassLoader launchClassLoader;

    @Override
    protected void addToClasspath0(Path jar) {
        try {
            //inject jar into classpath
            launchClassLoader.addURL(jar.toUri().toURL());
        } catch (Exception e) {
            log("Failed to add patch to classpath", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void acceptOptions(List<String> list, File file, File file1, String s) {

    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader launchClassLoader) {
        //get the right classloader to inject into
        this.launchClassLoader = launchClassLoader;

        patch();
    }

    @Override
    public String getLaunchTarget() {
        return null;
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}