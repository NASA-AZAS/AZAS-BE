package azas.global.logging;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(RequestLoggingFilter.ORDER)
public class RequestLoggingFilter extends OncePerRequestFilter {

    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    private static final String STATE_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".state";
    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private final long slowRequestMs;
    private final LongSupplier clock;

    @Autowired
    public RequestLoggingFilter(RequestLoggingProperties properties) {
        this(properties, System::nanoTime);
    }

    RequestLoggingFilter(RequestLoggingProperties properties, LongSupplier clock) {
        this.slowRequestMs = properties.slowRequestMs();
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        doFilterInternal(request, response, chain);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RequestState state = (RequestState) request.getAttribute(STATE_ATTRIBUTE);

        if (state == null) {
            state = new RequestState(request, clock.getAsLong());
            request.setAttribute(STATE_ATTRIBUTE, state);
        }

        response.setHeader(REQUEST_ID_HEADER, state.requestId);
        String previousId = MDC.get(REQUEST_ID_MDC_KEY);
        MDC.put(REQUEST_ID_MDC_KEY, state.requestId);

        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            state.failed = true;
            throw failure;
        } finally {
            try {
                if (request.isAsyncStarted()) {
                    listenForCompletion(state, request, response);
                } else {
                    logCompletion(state, response);
                }
            } finally {
                restoreRequestId(previousId);
            }
        }
    }

    private void listenForCompletion(RequestState state, HttpServletRequest request, HttpServletResponse response) {
        if (!state.listening.compareAndSet(false, true)) {
            return;
        }

        try {
            request.getAsyncContext().addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    logCompletion(state, response);
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    state.failed = true;
                }

                @Override
                public void onError(AsyncEvent event) {
                    state.failed = true;
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                    event.getAsyncContext().addListener(this, request, response);
                }
            }, request, response);
        } catch (IllegalStateException alreadyCompleted) {
            // Completion can race with listener registration on another thread.
            logCompletion(state, response);
        }
    }

    private void logCompletion(RequestState state, HttpServletResponse response) {
        if (!state.logged.compareAndSet(false, true)) {
            return;
        }

        long durationMs = TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - state.startedAt);
        int status = response.getStatus();

        if (state.failed && status < 400 && !response.isCommitted()) {
            // The container has not rendered an escaping exception yet.
            status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }

        boolean slow = durationMs >= slowRequestMs;

        if (state.healthCheck && status >= 200 && status < 300 && !slow) {
            return;
        }

        Level level = status >= 500 ? Level.ERROR : status >= 400 || slow ? Level.WARN : Level.INFO;
        String previousId = MDC.get(REQUEST_ID_MDC_KEY);
        MDC.put(REQUEST_ID_MDC_KEY, state.requestId);

        try {
            LOG.atLevel(level)
                    .addKeyValue("method", state.method)
                    .addKeyValue("path", state.path)
                    .addKeyValue("status", status)
                    .addKeyValue("durationMs", durationMs)
                    .log("HTTP request completed");
        } finally {
            restoreRequestId(previousId);
        }
    }

    private static void restoreRequestId(@Nullable String previousId) {
        if (previousId == null) {
            MDC.remove(REQUEST_ID_MDC_KEY);
        } else {
            MDC.put(REQUEST_ID_MDC_KEY, previousId);
        }
    }

    private static String safeValue(String value, int limit) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), limit));

        for (int index = 0; index < value.length() && index < limit; index++) {
            char character = value.charAt(index);
            result.append(Character.isISOControl(character) || character == '\u2028' || character == '\u2029'
                    ? '_' : character);
        }

        if (value.length() > limit) {
            result.append("...[truncated]");
        }

        return result.toString();
    }

    private static final class RequestState {
        private final String requestId = UUID.randomUUID().toString();
        private final long startedAt;
        private final String method;
        private final String path;
        private final boolean healthCheck;
        private final AtomicBoolean logged = new AtomicBoolean();
        private final AtomicBoolean listening = new AtomicBoolean();
        private volatile boolean failed;

        private RequestState(HttpServletRequest request, long startedAt) {
            this.startedAt = startedAt;
            this.method = safeValue(request.getMethod(), 16);
            this.path = safeValue(request.getRequestURI(), 512);
            String applicationPath = request.getRequestURI().substring(request.getContextPath().length());
            this.healthCheck = (method.equals("GET") || method.equals("HEAD"))
                    && (applicationPath.equals("/health") || applicationPath.equals("/actuator/health")
                    || applicationPath.startsWith("/actuator/health/"));
        }
    }
}
