package azas.domain.analysis.engine;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HorizonsClient {
    private static final URI ENDPOINT = URI.create("https://ssd.jpl.nasa.gov/api/horizons.api");
    private static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter RESPONSE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MMM-dd HH:mm:ss").appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter(Locale.US).withResolverStyle(ResolverStyle.STRICT);
    private static final String XYZ = "([\\d.Ee+\\-]+),\\s*([\\d.Ee+\\-]+),\\s*([\\d.Ee+\\-]+)";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient client;
    private final URI endpoint;

    public HorizonsClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), ENDPOINT);
    }

    HorizonsClient(HttpClient client, URI endpoint) { this.client = client; this.endpoint = endpoint; }

    public record Angle(double azimuth, double elevation) {}
    public record Series(List<Angle> angles, AnalysisEngine.Source source) {
        public Series { angles = List.copyOf(angles); }
    }

    public Series angles(int target, AnalysisEngine.Input input) {
        Map<String, String> query = new LinkedHashMap<>();
        query.putAll(Map.of("format", "json", "COMMAND", quote(target), "EPHEM_TYPE", "'OBSERVER'",
                "CENTER", "'coord@301'", "COORD_TYPE", "'GEODETIC'"));
        double longitude = (input.longitude() % 360 + 360) % 360;
        query.put("SITE_COORD", String.format(Locale.ROOT, "'%.12f,%.12f,%.12f'", longitude, input.latitude(),
                (input.surfaceElevation() + input.observerHeight()) / 1000));
        query.put("START_TIME", quote(REQUEST_TIME.format(input.start())));
        query.put("STOP_TIME", quote(REQUEST_TIME.format(input.end())));
        query.put("STEP_SIZE", quote(input.stepSeconds() / 60 + " m"));
        query.putAll(Map.of("QUANTITIES", "'4'", "CSV_FORMAT", "'YES'", "TIME_TYPE", "'UT'", "TIME_ZONE", "'+00:00'",
                "OBJ_DATA", "'NO'", "TIME_DIGITS", "'SECONDS'", "ANG_FORMAT", "'DEG'", "APPARENT", "'AIRLESS'",
                "CAL_FORMAT", "'CAL'", "CAL_TYPE", "'GREGORIAN'"));
        URI uri = URI.create(endpoint + "?" + String.join("&", query.entrySet().stream().map(entry ->
                entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).toList()));

        try {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new AnalysisEngineException("HORIZONS_UNAVAILABLE", null);
            }

            if (response.body().length > 2_000_000) {
                throw new IllegalArgumentException("Oversized Horizons response");
            }

            var wrapper = JSON.readTree(response.body());
            require(!wrapper.has("error") && wrapper.path("result").isString(), "Invalid Horizons wrapper");
            String text = wrapper.path("result").asString();
            validateHeader(text, target, input, longitude);
            int start = text.indexOf("$$SOE"), end = text.indexOf("$$EOE");
            require(start >= 0 && end > start && start == text.lastIndexOf("$$SOE") && end == text.lastIndexOf("$$EOE"),
                    "Invalid Horizons delimiters");
            var rows = text.substring(start + 5, end).strip().lines().toList();
            require(rows.size() == Duration.between(input.start(), input.end()).toSeconds() / input.stepSeconds() + 1,
                    "Horizons row count mismatch");
            List<Angle> angles = new ArrayList<>();

            for (String row : rows) {
                String[] fields = row.split(",", -1);
                require(fields.length >= 5, "Invalid Horizons row");
                Instant epoch = LocalDateTime.parse(fields[0].replace("A.D.", "").strip(), RESPONSE_TIME)
                        .toInstant(ZoneOffset.UTC);
                require(epoch.equals(input.start().plusSeconds((long) input.stepSeconds() * angles.size())),
                        "Horizons epoch mismatch");
                double az = Double.parseDouble(fields[3]), el = Double.parseDouble(fields[4]);
                require(Double.isFinite(az) && az >= 0 && az <= 360 && Double.isFinite(el) && Math.abs(el) <= 90,
                        "Invalid Horizons angles");
                angles.add(new Angle(az % 360, el));
            }

            return new Series(angles, new AnalysisEngine.Source(target, uri.toString(), sha256(response.body()), Instant.now()));
        } catch (AnalysisEngineException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new AnalysisEngineException("HORIZONS_TIMEOUT", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AnalysisEngineException("ANALYSIS_INTERRUPTED", exception);
        } catch (java.io.IOException exception) {
            throw new AnalysisEngineException("HORIZONS_UNAVAILABLE", exception);
        } catch (RuntimeException exception) {
            throw new AnalysisEngineException("HORIZONS_INVALID_RESPONSE", exception);
        }
    }

    private static void validateHeader(String text, int target, AnalysisEngine.Input input, double longitude) {
        groups(text, "Target body name:[^\\r\\n]*\\(" + target + "\\)[^\\r\\n]*\\{source:");
        groups(text, "Center body name:[^\\r\\n]*Moon \\(301\\)[^\\r\\n]*\\{source:");
        groups(text, "Center pole/equ[ \\t]*:[ \\t]*(?:MEAN_ME \\(high precision\\)|MOON_ME)[ \\t]+\\{East-longitude positive\\}[ \\t]*\\R");
        require(text.contains("NO (AIRLESS)") && text.contains("Azi_(a-app)") && text.contains("Elev_(a-app)"),
                "Incompatible observer quantities");

        for (String radius : groups(text, "Center radii\\s*:\\s*" + XYZ + "\\s+km")) {
            require(Math.abs(Double.parseDouble(radius) - 1737.4) < 1e-9, "Incompatible lunar sphere");
        }

        String[] site = groups(text, "Center geodetic\\s*:\\s*" + XYZ);
        require(Math.abs(Double.parseDouble(site[0]) - longitude) < 1e-4
                && Math.abs(Double.parseDouble(site[1]) - input.latitude()) < 1e-4
                && Math.abs(Double.parseDouble(site[2]) - (input.surfaceElevation() + input.observerHeight()) / 1000) < 1e-4,
                "Horizons observer mismatch");
    }

    private static String[] groups(String text, String regex) {
        var matcher = Pattern.compile(regex).matcher(text);
        require(matcher.find(), "Incompatible Horizons header");
        String[] groups = new String[matcher.groupCount()];

        for (int i = 0; i < groups.length; i++) groups[i] = matcher.group(i + 1);

        return groups;
    }

    private static String quote(Object value) { return "'" + value + "'"; }
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
