package ing.prometheusreengineer.minecraft.loader.stage0;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * What every platform hook calls: claim the run, unpack the minecraft-lib jar this jar carries, start its stage 1.
 * <br>
 * Stage 0 is the jar users install, so it should almost never need to change: no dependencies beyond the JDK and the
 * platform APIs its hooks implement, and everything else lives in the lib and the core. The lib is a whole jar inside
 * this one ({@code minecraft-lib.jar} next to this class), not unpacked into it: stage 1 puts the newest lib on the
 * game's class path, and ModLauncher 9+ refuses two jars with one package. Stage 1 updates the lib, then downloads and
 * starts the core, so a stage 0 jar never goes out of date (the EssentialLoader approach).
 * <br>
 * Stage 1 is called through {@code ing.prometheusreengineer.minecraft.boot.Stage1.run(Map)} with only JDK types, so it
 * doesn't matter which class loader either side lives in. Nothing here may use the lib: it isn't on stage 0's class
 * path, and a missing class would fail while a hook links this one, outside any try.
 */
public final class Stage0 {
    private Stage0() {}

    /**
     * What the map handed to stage 1 contains. Only ever add keys, and bump this when you do, so a newer lib can tell
     * what an older stage 0 provides.
     * <ul>
     *     <li>{@code contract} (Integer): this number</li>
     *     <li>{@code stage0Version} (String)</li>
     *     <li>{@code platform} (String): {@code fabric}, {@code launchwrapper}, {@code modlauncher} or {@code neoforge}</li>
     *     <li>{@code gameDir} (Path)</li>
     *     <li>{@code libJar} (Path): the lib this jar carries, unpacked</li>
     *     <li>{@code addToClasspath} (Consumer&lt;Path&gt;): makes a jar visible to the game</li>
     *     <li>{@code addMixinConfig} (Consumer&lt;String&gt;): registers a mixin config, where the platform needs it</li>
     *     <li>{@code stage0Log} (Consumer&lt;BiConsumer&lt;String, String&gt;&gt;): stage 1 calls it with where stage 0's
     *     log lines go (level, message)</li>
     * </ul>
     * 1 and 2 were never released: stage 0 started a core directly (2 carried it, with {@code coreJar}).
     */
    public static final int CONTRACT = 3;

    private static final String CLAIM = "prometheus.stage0.claimed";
    private static final String EMBEDDED_LIB = "minecraft-lib.jar";
    private static final String STAGE1 = "ing.prometheusreengineer.minecraft.boot.Stage1";

    /**
     * True for the first hook in this JVM, which then runs Prometheus; false for any other.
     * <br>
     * A system property rather than a static field: NeoForge 1.21.1 calls both our ModLauncher service and our locator,
     * and loads them with different class loaders, so each would see its own static.
     */
    private static boolean claim(String hook) {
        Object first = System.getProperties().putIfAbsent(CLAIM, hook);
        if (first == null) return true;
        Stage0Log.info("%s hook skipped, %s already started Prometheus", hook, first);
        return false;
    }

    /**
     * Never throws: a broken lib means no patches this launch, not a crashed game.
     */
    public static void run(@NotNull Loader platform, Path gameDir, Consumer<Path> addToClasspath, Consumer<String> addMixinConfig) {
        if (!claim(platform.id)) return;
        run(platform, gameDir, addToClasspath, addMixinConfig, Stage0.class.getResourceAsStream(EMBEDDED_LIB));
    }

    /**
     * @param embedded the lib this jar carries, null if it has none
     */
    @VisibleForTesting
    static void run(@NotNull Loader platform, Path gameDir, Consumer<Path> addToClasspath, Consumer<String> addMixinConfig, @Nullable InputStream embedded) {
        try {
            Stage0Log.info("stage0 %s on %s, java %s", version(), platform, System.getProperty("java.version"));
            if (embedded == null) {
                Stage0Log.warn("this jar carries no lib, Prometheus is off for this launch");
                return;
            }
            Path lib = extract(embedded, Paths.get(System.getProperty("java.io.tmpdir"), "prometheus"));

            Map<String, Object> context = new HashMap<>();
            context.put("contract", CONTRACT);
            context.put("stage0Version", version());
            context.put("platform", platform.id);
            context.put("gameDir", gameDir);
            context.put("libJar", lib);
            context.put("addToClasspath", addToClasspath);
            context.put("addMixinConfig", addMixinConfig);
            context.put("stage0Log", Stage0Log.handOver());

            ClassLoader loader = new ChildFirstClassLoader(lib, Stage0.class.getClassLoader());
            Class.forName(STAGE1, true, loader)
                    .getMethod("run", Map.class)
                    .invoke(null, context);
        } catch (InvocationTargetException e) {
            Stage0Log.error("stage 1 failed", e.getCause());
        } catch (Throwable t) {
            Stage0Log.error("could not start stage 1", t);
        }
    }

    /**
     * Into {@code dir/lib-<sha256>.jar}. Named by content, so a jar another running game has open is never written
     * over: the same lib reuses its file, another lib gets its own. Only a damaged copy is replaced.
     */
    static @NotNull Path extract(InputStream embedded, Path dir) throws IOException {
        byte[] bytes;
        try (InputStream in = embedded; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
            bytes = out.toByteArray();
        }
        String sha256 = sha256(bytes);
        Path jar = dir.resolve("lib-" + sha256 + ".jar");
        if (Files.isRegularFile(jar) && sha256.equals(sha256(Files.readAllBytes(jar)))) return jar;

        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "lib-", ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return jar;
    }

    static @NotNull String sha256(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : digest("SHA-256").digest(bytes)) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is required of every JRE", e);
        }
    }

    static String version() {
        return version(Stage0.class.getResourceAsStream("stage0.properties"));
    }

    /**
     * The {@code version} in a stage0.properties, or "unknown" if there's none or it can't be read.
     */
    static String version(InputStream properties) {
        try (InputStream in = properties) {
            Properties read = new Properties();
            if (in != null) read.load(in);
            return read.getProperty("version", "unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
