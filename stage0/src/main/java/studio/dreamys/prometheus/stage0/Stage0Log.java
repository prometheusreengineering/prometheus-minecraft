package studio.dreamys.prometheus.stage0;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * stdout plus {@code <prometheus folder>/logs/stage0.log}, overwritten once per launch. The core logs to
 * {@code loader.log} with minecraft-lib's {@code Log}; this is only what happens before (or instead of) it.
 * Never throws.
 */
public final class Stage0Log {
    private Stage0Log() {}

    /**
     * A system property, so the two hooks NeoForge 1.21.1 loads with different class loaders share one file.
     */
    private static final String STARTED = "prometheus.stage0.log.started";

    public static void info(String format, Object... args) {
        write("INFO", format(format, args), null);
    }

    public static void warn(String format, Object... args) {
        write("WARN", format(format, args), null);
    }

    public static void error(String message, Throwable t) {
        write("ERROR", message, t);
    }

    private static synchronized void write(String level, String message, Throwable t) {
        String line = "[Prometheus/stage0] (" + level + ") " + message;
        System.out.println(line);
        if (t != null) t.printStackTrace(System.out);

        try {
            Path file = Settings.configFolder().resolve("logs").resolve("stage0.log");
            Files.createDirectories(file.getParent());
            boolean first = System.getProperties().putIfAbsent(STARTED, "true") == null;
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, first ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND)) {
                out.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()) + " " + line + System.lineSeparator());
                if (t != null) out.write(stackTrace(t));
            }
        } catch (IOException | RuntimeException ignored) {
            // stdout already has it
        }
    }

    private static String format(String format, Object[] args) {
        if (args.length == 0) return format;
        try {
            return String.format(Locale.ROOT, format, args);
        } catch (RuntimeException e) {
            return format;
        }
    }

    private static String stackTrace(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
