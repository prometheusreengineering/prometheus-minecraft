package ing.prometheusreengineer.minecraft.loader.stage0;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Stage 0's log lines, which end up in the core's {@code loader.log}: kept until the core takes them through
 * {@link #handOver}, then passed straight on. Until then each line also goes to stdout, which is in the game's log, so a
 * core that never starts still leaves a trace. Stage 0 can't write a file of its own: finding the prometheus folder
 * needs minecraft-lib's settings. Never throws.
 */
public final class Stage0Log {
    private Stage0Log() {}

    private static final List<String[]> kept = new ArrayList<>();
    private static BiConsumer<String, String> core;

    public static void info(String format, Object... args) {
        write("INFO", format(format, args), null);
    }

    public static void warn(String format, Object... args) {
        write("WARN", format(format, args), null);
    }

    public static void error(String message, Throwable t) {
        write("ERROR", message, t);
    }

    /**
     * What the core gets as {@code stage0Log}: it calls this with where the lines go (level, message), and gets the
     * kept ones right away.
     */
    static synchronized void handOver(BiConsumer<String, String> sink) {
        core = sink;
        for (String[] line : kept) send(line[0], line[1]);
        kept.clear();
    }

    static Consumer<BiConsumer<String, String>> handOver() {
        return Stage0Log::handOver;
    }

    private static synchronized void write(String level, String message, Throwable t) {
        if (t != null) message += System.lineSeparator() + stackTrace(t);
        if (core != null) {
            send(level, message);
            return;
        }
        System.out.println("[Prometheus/stage0] (" + level + ") " + message);
        kept.add(new String[]{level, message});
    }

    private static void send(String level, String message) {
        try {
            core.accept(level, message);
        } catch (Throwable t) {
            System.out.println("[Prometheus/stage0] (" + level + ") " + message);
        }
    }

    /**
     * Forgets the core and every kept line, for tests.
     */
    static synchronized void reset() {
        core = null;
        kept.clear();
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
        return out.toString().trim();
    }
}
