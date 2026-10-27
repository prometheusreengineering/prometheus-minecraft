package studio.dreamys.prometheus.stage0;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * What every platform hook calls: claim the run, find the core ({@link CoreResolver}), start it.
 * <br>
 * Stage 0 is the jar users install, so it should almost never need to change: no dependencies beyond the JDK and the
 * platform APIs its hooks implement, and all the actual loading lives in the core it downloads. The core is called
 * through {@code studio.dreamys.prometheus.core.Bootstrap.run(Map)} with only JDK types, so it doesn't matter which
 * class loader either side lives in.
 */
public final class Stage0 {
    private Stage0() {}

    /**
     * What the map handed to the core contains. Only ever add keys, and bump this when you do, so a newer core can
     * tell what an older stage 0 provides.
     * <ul>
     *     <li>{@code contract} (Integer): this number</li>
     *     <li>{@code stage0Version} (String)</li>
     *     <li>{@code platform} (String): {@code fabric}, {@code launchwrapper}, {@code modlauncher} or {@code neoforge}</li>
     *     <li>{@code gameDir} (Path)</li>
     *     <li>{@code coreJar} (Path): the core jar, so it can put itself on the game's class path for patches</li>
     *     <li>{@code addToClasspath} (Consumer&lt;Path&gt;): makes a jar visible to the game</li>
     *     <li>{@code addMixinConfig} (Consumer&lt;String&gt;): registers a mixin config, where the platform needs it</li>
     * </ul>
     */
    public static final int CONTRACT = 1;

    private static final String CLAIM = "prometheus.stage0.claimed";
    private static final String BOOTSTRAP = "studio.dreamys.prometheus.core.Bootstrap";

    /**
     * True for the first hook in this JVM, which then runs Prometheus; false for any other.
     * <br>
     * A system property rather than a static field: NeoForge 1.21.1 calls both our ModLauncher service and our locator,
     * and loads them with different class loaders, so each would see its own static.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted") //intellij overthinking it
    public static boolean claim(String hook) {
        Object first = System.getProperties().putIfAbsent(CLAIM, hook);
        if (first == null) return true;
        Stage0Log.info("%s hook skipped, %s already started Prometheus", hook, first);
        return false;
    }

    /**
     * Never throws: a broken core means no patches this launch, not a crashed game.
     */
    public static void run(String platform, Path gameDir, Consumer<Path> addToClasspath, Consumer<String> addMixinConfig) {
        try {
            Stage0Log.info("stage0 %s on %s, java %s", version(), platform, System.getProperty("java.version"));
            Path core = CoreResolver.resolve();
            if (core == null) {
                Stage0Log.warn("Prometheus is off for this launch");
                return;
            }

            Map<String, Object> context = new HashMap<>();
            context.put("contract", CONTRACT);
            context.put("stage0Version", version());
            context.put("platform", platform);
            context.put("gameDir", gameDir);
            context.put("coreJar", core);
            context.put("addToClasspath", addToClasspath);
            context.put("addMixinConfig", addMixinConfig);

            // never closed: the core's classes stay in use for the whole game
            ClassLoader loader = new ChildFirstClassLoader(core, Stage0.class.getClassLoader());
            Class.forName(BOOTSTRAP, true, loader).getMethod("run", Map.class).invoke(null, context);
        } catch (InvocationTargetException e) {
            Stage0Log.error("the core failed", e.getCause());
        } catch (Throwable t) {
            Stage0Log.error("could not start the core", t);
        }
    }

    static String version() {
        try (InputStream in = Stage0.class.getResourceAsStream("stage0.properties")) {
            Properties properties = new Properties();
            if (in != null) properties.load(in);
            return properties.getProperty("version", "unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
