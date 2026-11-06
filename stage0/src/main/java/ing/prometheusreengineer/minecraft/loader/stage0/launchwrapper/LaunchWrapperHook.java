package ing.prometheusreengineer.minecraft.loader.stage0.launchwrapper;

import ing.prometheusreengineer.minecraft.loader.stage0.Loader;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixins;
import ing.prometheusreengineer.minecraft.loader.stage0.Stage0;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Forge 1.8-1.12 (and Lunar on them): the {@code TweakClass} in MANIFEST.MF.
 */
public class LaunchWrapperHook implements ITweaker {
    private Path gameDir;

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        this.gameDir = gameDir != null ? gameDir.toPath().toAbsolutePath() : Paths.get("").toAbsolutePath();
    }

    @SuppressWarnings("Convert2MethodRef") //explained below
    @Override
    public void injectIntoClassLoader(LaunchClassLoader launchClassLoader) {
        Path dir = gameDir != null ? gameDir : Paths.get("").toAbsolutePath();
        Stage0.run(Loader.LAUNCHWRAPPER, dir,
                jar -> launchClassLoader.addURL(url(jar.toUri())),
                // a lambda, not Mixins::addConfiguration: Forge 1.8.9 has no Mixin unless a mod brings it
                config -> Mixins.addConfiguration(config));
    }

    static @NotNull URL url(@NotNull URI uri) {
        try {
            return uri.toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("not a valid jar path: " + uri, e);
        }
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
