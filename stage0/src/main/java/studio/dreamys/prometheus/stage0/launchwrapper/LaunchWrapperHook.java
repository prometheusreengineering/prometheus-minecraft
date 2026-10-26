package studio.dreamys.prometheus.stage0.launchwrapper;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.spongepowered.asm.mixin.Mixins;
import studio.dreamys.prometheus.stage0.Stage0;

import java.io.File;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Forge 1.8-1.12 (and Lunar on them): the {@code TweakClass} in MANIFEST.MF. */
public class LaunchWrapperHook implements ITweaker {
    private Path gameDir;

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        this.gameDir = gameDir != null ? gameDir.toPath().toAbsolutePath() : Paths.get("").toAbsolutePath();
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader launchClassLoader) {
        if (!Stage0.claim("launchwrapper")) return;
        Path dir = gameDir != null ? gameDir : Paths.get("").toAbsolutePath();
        Stage0.run("launchwrapper", dir,
                jar -> {
                    try {
                        launchClassLoader.addURL(jar.toUri().toURL());
                    } catch (MalformedURLException e) {
                        throw new IllegalArgumentException("not a valid jar path: " + jar, e);
                    }
                },
                // a lambda, not Mixins::addConfiguration: Forge 1.8.9 has no Mixin unless a mod brings it
                config -> Mixins.addConfiguration(config));
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
