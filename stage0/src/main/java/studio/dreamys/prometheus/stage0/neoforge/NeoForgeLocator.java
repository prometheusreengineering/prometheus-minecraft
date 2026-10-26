package studio.dreamys.prometheus.stage0.neoforge;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import studio.dreamys.prometheus.stage0.Stage0;

import java.nio.file.Paths;

/**
 * NeoForge's hook. FML 11+ (after 1.21.1) has no ModLauncher, and only accepts a jar in mods/ without a mod toml if it
 * declares one of FML's early services, like this one.
 * <br>
 * {@code findCandidates}, {@code IDiscoveryPipeline.addPath(Path, ...)}, {@code ModFileDiscoveryAttributes.DEFAULT} and
 * {@code IncompatibleFileReporting} are the same in FML 4, 11 and 12; compiled against 11.0.0. Tested in game on
 * NeoForge 26.3. On NeoForge 1.21.1 (FML 4) the ModLauncher service runs first and claims Prometheus, so this one
 * steps aside there.
 * <br>
 * Jars added here become mod files, so they need a {@code neoforge.mods.toml} (which also declares their mixins) or a
 * {@code FMLModType} manifest entry; there's no mixin registration to do.
 */
public class NeoForgeLocator implements IModFileCandidateLocator {
    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        if (!Stage0.claim("neoforge")) return;
        Stage0.run("neoforge", Paths.get("").toAbsolutePath(),
                jar -> pipeline.addPath(jar, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS),
                config -> { /* NeoForge reads mixins from the jar's neoforge.mods.toml */ });
    }

    @Override
    public String toString() {
        return "prometheus-minecraft";
    }
}
