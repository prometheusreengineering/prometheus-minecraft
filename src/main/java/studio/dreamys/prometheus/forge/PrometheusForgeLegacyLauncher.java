package studio.dreamys.prometheus.forge;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import studio.dreamys.prometheus.Prometheus;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

public class PrometheusForgeLegacyLauncher extends Prometheus implements ITweaker {
    private LaunchClassLoader launchClassLoader;

    @Override
    protected void addToClasspath0(Path jar) {
        try {
            launchClassLoader.addURL(jar.toUri().toURL());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to add patch to classpath", e);
        }
    }

    @Override
    public void acceptOptions(List<String> list, File file, File file1, String s) {

    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader launchClassLoader) {
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
