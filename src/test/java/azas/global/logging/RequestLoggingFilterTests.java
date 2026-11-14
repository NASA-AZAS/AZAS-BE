package azas.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import azas.support.LogCapture;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingFilterTests {

    private final AtomicLong clock = new AtomicLong();
    private final RequestLoggingFilter filter = new RequestLoggingFilter(new RequestLoggingProperties(1000), clock::get);
    private LogCapture capture;

    @BeforeEach
    void captureLogs() {
        MDC.clear();
        capture = new LogCapture(RequestLoggingFilter.class);
    }

    @AfterEach
    void restoreLogging() {
        capture.close();
        MDC.clear();
    }

    @Test
    void requestIdConnectsHeaderContextAndCompletedResponse() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/sample");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-ID"));
            clock.set(TimeUnit.MILLISECONDS.toNanos(25));
            res.getWriter().write("unchanged response");
        });
        String id = response.getHeader("X-Request-ID");
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(response.getContentAsString()).isEqualTo("unchanged response");
        assertThat(capture.events()).hasSize(1);
        ILoggingEvent event = capture.events().getFirst();
        assertThat(event.getMDCPropertyMap()).containsEntry("requestId", id);
        assertThat(fields(event)).containsAllEntriesOf(Map.of(
                "method", "GET", "path", "/api/v1/sample", "status", 200, "durationMs", 25L));
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void serverGeneratesIdsAndRestoresOnlyItsMdcEntry() throws Exception {
        MDC.put("requestId", "outer-context");
        MDC.put("otherContext", "preserve-me");
        var request = new MockHttpServletRequest("GET", "/openapi.json");
        request.addHeader("X-Request-ID", "caller-controlled-id");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get("requestId")).isNotEqualTo("outer-context").isNotEqualTo("caller-controlled-id"));
        assertThat(MDC.get("requestId")).isEqualTo("outer-context");
        assertThat(MDC.get("otherContext")).isEqualTo("preserve-me");
        assertThat(response.getHeader("X-Request-ID")).isNotEqualTo("caller-controlled-id");
    }

    @ParameterizedTest
    @CsvSource({"200,INFO", "302,INFO", "400,WARN", "404,WARN", "500,ERROR", "503,ERROR"})
    void responseStatusDeterminesSeverity(int status, String level) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/sample"), response,
                (req, res) -> response.setStatus(status));
        assertThat(capture.events()).hasSize(1);
        assertThat(capture.events().getFirst().getLevel()).isEqualTo(Level.valueOf(level));
        assertThat(fields(capture.events().getFirst())).containsEntry("status", status);
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/health,200,999,false",
            "HEAD,/health,200,1,false",
            "GET,/actuator/health,200,1,false",
            "GET,/actuator/health/readiness,200,1,false",
            "GET,/health,200,1000,true",
            "GET,/health,503,1,true",
            "GET,/health,302,1,true",
            "OPTIONS,/health,200,1,true",
            "GET,/healthy,200,1,true"
    })
    void onlyFastSuccessfulHealthChecksAreQuiet(String method, String path, int status,
            long durationMs, boolean shouldLog) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, path), response, (req, res) -> {
            clock.set(TimeUnit.MILLISECONDS.toNanos(durationMs));
            response.setStatus(status);
        });
        assertThat(response.getHeader("X-Request-ID")).isNotBlank();
        assertThat(capture.events()).hasSize(shouldLog ? 1 : 0);
        if (status == 200 && durationMs >= 1000) {
            assertThat(capture.events().getFirst().getLevel()).isEqualTo(Level.WARN);
        }
    }

    @Test
    void requestAndResponseContentAndHeadersAreNotCaptured() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/sample");
        request.setQueryString("token=query-secret");
        request.addHeader("Authorization", "Bearer auth-secret");
        request.addHeader("Cookie", "session=cookie-secret");
        request.setCookies(new Cookie("session", "cookie-secret"));
        request.setContent("{\"password\":\"body-secret\"}".getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            assertThat(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).contains("body-secret");
            res.getWriter().write("response-secret");
        });
        ILoggingEvent event = capture.events().getFirst();
        String rendered = event.getFormattedMessage() + fields(event) + event.getMDCPropertyMap();
        assertThat(rendered).doesNotContain("query-secret", "auth-secret", "cookie-secret", "body-secret", "response-secret");
        assertThat(fields(event)).containsEntry("path", "/api/v1/sample");
        assertThat(response.getContentAsString()).isEqualTo("response-secret");
    }

    @Test
    void escapingExceptionIsRethrownAndSummarizedWithoutAnotherStackTrace() {
        var request = new MockHttpServletRequest("GET", "/sample");
        var response = new MockHttpServletResponse();
        var failure = new ServletException("private-failure-message");
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> { throw failure; }))
                .isSameAs(failure);
        assertThat(capture.events()).hasSize(1);
        ILoggingEvent event = capture.events().getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(fields(event)).containsEntry("status", 500);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage()).doesNotContain("private-failure-message");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void pathsCannotForgeExtraLogLinesOrGrowWithoutABound() throws Exception {
        var request = new MockHttpServletRequest("GET", "/sample\r\nforged\u2028" + "x".repeat(1200));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        String path = (String) fields(capture.events().getFirst()).get("path");
        assertThat(path).doesNotContain("\r", "\n", "\u2028").endsWith("...[truncated]");
        assertThat(path.length()).isLessThan(550);
    }

    @Test
    void asyncCompletionRecordsFinalStatusAndDurationOnce() throws Exception {
        var request = new MockHttpServletRequest("GET", "/async");
        request.setAsyncSupported(true);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> req.startAsync(req, res));
        String id = response.getHeader("X-Request-ID");
        assertThat(capture.events()).isEmpty();
        assertThat(MDC.get("requestId")).isNull();
        clock.set(TimeUnit.MILLISECONDS.toNanos(250));
        response.setStatus(201);
        request.getAsyncContext().complete();
        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ERROR);
        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get("requestId")).isEqualTo(id));
        assertThat(capture.events()).hasSize(1);
        assertThat(capture.events().getFirst().getMDCPropertyMap()).containsEntry("requestId", id);
        assertThat(fields(capture.events().getFirst())).containsEntry("status", 201).containsEntry("durationMs", 250L);
        assertThat(response.getHeader("X-Request-ID")).isEqualTo(id);
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void asyncFailureIsLoggedOnCompletionWithTheFinalStatus() throws Exception {
        var request = new MockHttpServletRequest("GET", "/async");
        request.setAsyncSupported(true);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> req.startAsync(req, res));
        var context = (MockAsyncContext) request.getAsyncContext();
        for (var listener : context.getListeners()) {
            listener.onError(new AsyncEvent(context, request, response, new IllegalStateException("private-error")));
        }
        assertThat(capture.events()).isEmpty();
        response.setStatus(503);
        context.complete();
        assertThat(capture.events()).hasSize(1);
        assertThat(capture.events().getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(fields(capture.events().getFirst())).containsEntry("status", 503);
        assertThat(capture.events().getFirst().getThrowableProxy()).isNull();
    }

    @Test
    void concurrentRequestsKeepDistinctIdsAndCleanReusedThreads() throws Exception {
        var concurrentFilter = new RequestLoggingFilter(new RequestLoggingProperties(1000));
        var ready = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var futures = new ArrayList<Future<String>>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int index = 0; index < 12; index++) {
                futures.add(executor.submit(() -> {
                    var request = new MockHttpServletRequest("GET", "/concurrent");
                    var response = new MockHttpServletResponse();
                    concurrentFilter.doFilter(request, response, (req, res) -> {
                        String before = MDC.get("requestId");
                        ready.countDown();
                        try {
                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new ServletException(interrupted);
                        }
                        assertThat(MDC.get("requestId")).isEqualTo(before).isEqualTo(response.getHeader("X-Request-ID"));
                    });
                    assertThat(MDC.get("requestId")).isNull();
                    return response.getHeader("X-Request-ID");
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            var ids = new HashSet<String>();
            for (var future : futures) {
                ids.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(12);
            assertThat(capture.events()).hasSize(12);
            assertThat(capture.events().stream().map(event -> event.getMDCPropertyMap().get("requestId")).toList())
                    .containsExactlyInAnyOrderElementsOf(ids);
        } finally {
            release.countDown();
        }
    }

    private Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
