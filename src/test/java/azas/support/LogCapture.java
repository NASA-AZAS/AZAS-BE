package azas.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

public final class LogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {

    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    private final Map<Logger, LoggerState> loggers = new LinkedHashMap<>();

    public LogCapture(Class<?>... types) {
        start();
        for (Class<?> type : types) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            loggers.put(logger, new LoggerState(logger.getLevel(), logger.isAdditive()));
            logger.setLevel(Level.INFO);
            logger.setAdditive(false);
            logger.addAppender(this);
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        events.add(event);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(events);
    }

    @Override
    public void close() {
        loggers.forEach((logger, state) -> {
            logger.detachAppender(this);
            logger.setLevel(state.level());
            logger.setAdditive(state.additive());
        });
        stop();
    }

    private record LoggerState(Level level, boolean additive) {
    }
}
