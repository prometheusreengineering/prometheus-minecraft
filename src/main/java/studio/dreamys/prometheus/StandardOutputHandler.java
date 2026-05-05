package studio.dreamys.prometheus;

import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

public class StandardOutputHandler extends Handler {
    public StandardOutputHandler() {
        setFormatter(new SimpleFormatter());
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) return;

        System.out.print(getFormatter().format(record));
    }

    @Override
    public void flush() {
        System.out.flush();
    }

    @Override
    public void close() {}
}
