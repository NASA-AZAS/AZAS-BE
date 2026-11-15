package azas.domain.analysis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HorizonsClientTests {
    private HttpServer server;
    private HorizonsClient client;
    private byte[] body;
    private int status;
    private final CopyOnWriteArrayList<Map<String, String>> queries = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        body = getClass().getResourceAsStream("/analysis/sun-observer.json").readAllBytes();
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/horizons", exchange -> {
            queries.add(java.util.Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                    .map(pair -> pair.split("=", 2)).collect(Collectors.toMap(pair -> pair[0],
                            pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);

            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        client = new HorizonsClient(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/horizons"));
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void validatesTheOriginalObserverResponseAndRequestsCoordinatesHeightAndPeriod() {
        var result = client.angles(10, input());
        assertThat(result.angles()).hasSize(289);
        assertThat(result.angles().getFirst().azimuth()).isEqualTo(50.036093);
        assertThat(result.source().responseSha256()).isEqualTo(HorizonsClient.sha256(body));
        assertThat(queries.getFirst()).containsEntry("COMMAND", "'10'").containsEntry("CENTER", "'coord@301'")
                .containsEntry("QUANTITIES", "'4'").containsEntry("STEP_SIZE", "'10 m'")
                .containsEntry("APPARENT", "'AIRLESS'").containsEntry("TIME_ZONE", "'+00:00'");
        assertThat(queries.getFirst().get("SITE_COORD")).isEqualTo("'230.035561000000,-89.630720600000,0.864783000000'");
    }

    @Test void everyCallUsesHttpEvenForIdenticalInput() {
        client.angles(10, input());
        client.angles(10, input());
        assertThat(queries).hasSize(2);
    }

    @Test void vectorRequestUsesBarycentricGeometricCoordinatesAndChecksTheEpochAndFrame() throws Exception {
        Instant epoch = Instant.parse("2027-01-18T22:10:00Z");
        try (var input = getClass().getResourceAsStream("/analysis/earth-vector.json")) {
            body = input.readAllBytes();
        }
        var position = client.position(399, epoch);
        assertThat(position.km()[0]).isEqualTo(-6.966751080513786e7);
        assertThat(queries.getFirst()).containsEntry("COMMAND", "'399'").containsEntry("CENTER", "'500@0'")
                .containsEntry("EPHEM_TYPE", "'VECTORS'").containsEntry("REF_SYSTEM", "'ICRF'")
                .containsEntry("REF_PLANE", "'FRAME'").containsEntry("VEC_CORR", "'NONE'")
                .containsEntry("VEC_TABLE", "'2'").containsEntry("TIME_TYPE", "'UT'")
                .containsEntry("OUT_UNITS", "'KM-S'").containsEntry("TLIST", "'2027-01-18 22:10:00'");
        assertThatThrownBy(() -> client.position(301, epoch)).hasMessage("HORIZONS_INVALID_RESPONSE");
        assertThatThrownBy(() -> client.position(399, epoch.plusSeconds(1))).hasMessage("HORIZONS_INVALID_RESPONSE");
        var mapper = JsonMapper.builder().build();
        String text = mapper.readTree(body).path("result").asString();
        for (String invalid : new String[]{text.replace("Output units    : KM-S", "Output units    : AU-D"),
                text.replace("Reference frame : ICRF", "Reference frame : ECLIPTIC"),
                text.replace("2461424.423611111", "NaN"), text.replace("-6.966751080513786E+07", "Infinity")}) {
            body = mapper.writeValueAsBytes(Map.of("result", invalid));
            assertThatThrownBy(() -> client.position(399, epoch)).hasMessage("HORIZONS_INVALID_RESPONSE");
        }
    }

    @Test void rejectsWrongTargetFrameOrEpochAndMissingRows() {
        byte[] original = body;
        var mapper = JsonMapper.builder().build();
        String text = mapper.readTree(body).path("result").asString();

        for (String invalid : new String[]{text.replace("MEAN_ME (high precision)", "ICRF").replace("MOON_ME", "ICRF"),
                text.replace("2026-Dec-01 00:00:00", "2026-Dec-01 00:01:00"),
                text.replaceFirst("(?m)^.*2026-Dec-01 00:10:00.*\\R", "")}) {
            body = mapper.writeValueAsBytes(Map.of("result", invalid));
            assertThatThrownBy(() -> client.angles(10, input())).isInstanceOf(AnalysisEngineException.class)
                    .hasMessage("HORIZONS_INVALID_RESPONSE");
        }

        body = original;
        assertThatThrownBy(() -> client.angles(399, input())).hasMessage("HORIZONS_INVALID_RESPONSE");
    }

    @Test void rejectsApiErrorsAndHttpFailuresWithSafeCodes() {
        body = "{\"error\":\"private upstream details\"}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client.angles(10, input())).hasMessage("HORIZONS_INVALID_RESPONSE");
        status = 503;
        assertThatThrownBy(() -> client.angles(10, input())).hasMessage("HORIZONS_UNAVAILABLE");
    }

    @Test void interruptionIsPreservedAndClassified() {
        Thread.currentThread().interrupt();

        try {
            assertThatThrownBy(() -> client.angles(10, input())).hasMessage("ANALYSIS_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    private AnalysisEngine.Input input() {
        return new AnalysisEngine.Input("Site01", -89.6307206, -129.964439, 863.783, 1,
                "MOON_ME_DE421_PLANETOCENTRIC_EAST", 1737400, false, "a".repeat(64),
                Collections.nCopies(3600, 0.0), Instant.parse("2026-12-01T00:00:00Z"),
                Instant.parse("2026-12-03T00:00:00Z"), 600);
    }
}
