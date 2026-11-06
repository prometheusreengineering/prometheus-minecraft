package ing.prometheusreengineer.minecraft.boot;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A stand-in for minecraft-lib's stage 1, so stage 0 can be tested without the lib. The tests jar this class into a
 * fake lib. It's loaded by stage 0's child-first class loader, so it talks to the tests through system properties:
 * <ul>
 *     <li>{@code test.stage1.broken}: fail to load at all</li>
 *     <li>{@code test.stage1.throw}: throw instead of running</li>
 *     <li>{@code test.stage1.jars}: paths (File.pathSeparator separated) to pass to addToClasspath</li>
 *     <li>{@code test.stage1.configs}: mixin configs (comma separated) to pass to addMixinConfig</li>
 * </ul>
 * The context it got is stored as {@code test.stage1.context}.
 */
public final class Stage1 {
    static {
        if (System.getProperty("test.stage1.broken") != null) throw new IllegalStateException("a lib that can't load");
    }

    private Stage1() {}

    @SuppressWarnings("unchecked")
    public static void run(Map<String, Object> context) {
        if (System.getProperty("test.stage1.throw") != null) throw new IllegalStateException("broken lib");
        System.getProperties().put("test.stage1.context", context);

        String jars = System.getProperty("test.stage1.jars");
        if (jars != null) {
            Consumer<Path> addToClasspath = (Consumer<Path>) context.get("addToClasspath");
            for (String jar : jars.split(File.pathSeparator)) addToClasspath.accept(Paths.get(jar));
        }
        String configs = System.getProperty("test.stage1.configs");
        if (configs != null) {
            Consumer<String> addMixinConfig = (Consumer<String>) context.get("addMixinConfig");
            for (String config : configs.split(",")) addMixinConfig.accept(config);
        }
    }
}
