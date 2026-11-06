package ing.prometheusreengineer.minecraft.loader.core;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A stand-in for the core's Bootstrap, so stage 0 can be tested without the core. Stage0Test jars this class into a
 * fake core. It's loaded by stage 0's child-first class loader, so it talks to the tests through system properties:
 * <ul>
 *     <li>{@code test.bootstrap.broken}: fail to load at all</li>
 *     <li>{@code test.bootstrap.throw}: throw instead of running</li>
 *     <li>{@code test.bootstrap.jars}: paths (File.pathSeparator separated) to pass to addToClasspath</li>
 *     <li>{@code test.bootstrap.configs}: mixin configs (comma separated) to pass to addMixinConfig</li>
 * </ul>
 * The context it got is stored as {@code test.bootstrap.context}.
 */
public final class Bootstrap {
    static {
        if (System.getProperty("test.bootstrap.broken") != null) throw new IllegalStateException("a core that can't load");
    }

    private Bootstrap() {}

    @SuppressWarnings("unchecked")
    public static void run(Map<String, Object> context) {
        if (System.getProperty("test.bootstrap.throw") != null) throw new IllegalStateException("broken core");
        System.getProperties().put("test.bootstrap.context", context);

        String jars = System.getProperty("test.bootstrap.jars");
        if (jars != null) {
            Consumer<Path> addToClasspath = (Consumer<Path>) context.get("addToClasspath");
            for (String jar : jars.split(File.pathSeparator)) addToClasspath.accept(Paths.get(jar));
        }
        String configs = System.getProperty("test.bootstrap.configs");
        if (configs != null) {
            Consumer<String> addMixinConfig = (Consumer<String>) context.get("addMixinConfig");
            for (String config : configs.split(",")) addMixinConfig.accept(config);
        }
    }
}
