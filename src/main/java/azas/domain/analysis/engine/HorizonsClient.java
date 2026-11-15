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
import java.util.function.BiFunction;
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
    public record Position(double[] km, AnalysisEngine.Source source) {}
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
        return fetch(target, query, (text, source) -> {
            validateHeader(text, target, input, longitude);
            var rows = dataRows(text);
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

            return new Series(angles, source);
        });
    }

    public Position position(int target, Instant epoch) {
        Map<String, String> query = new LinkedHashMap<>();
        query.putAll(Map.of("format", "json", "COMMAND", quote(target), "EPHEM_TYPE", "'VECTORS'", "CENTER", "'500@0'"));
        query.putAll(Map.of("REF_SYSTEM", "'ICRF'", "REF_PLANE", "'FRAME'", "VEC_CORR", "'NONE'", "VEC_TABLE", "'2'",
                "OUT_UNITS", "'KM-S'", "CSV_FORMAT", "'YES'", "TIME_TYPE", "'UT'", "CAL_TYPE", "'GREGORIAN'",
                "OBJ_DATA", "'NO'", "TLIST", quote(REQUEST_TIME.format(epoch))));
        return fetch(target, query, (text, source) -> {
            groups(text, "Target body name:[^\\r\\n]*\\(" + target + "\\)");
            groups(text, "Center body name:[^\\r\\n]*\\(0\\)");
            groups(text, "Calendar Date \\(UT\\s*\\)");
            require(text.contains("Reference frame : ICRF") && text.contains("Output units    : KM-S")
                    && text.contains("GEOMETRIC cartesian states"), "Incompatible Horizons vector frame or units");
            var rows = dataRows(text);
            require(rows.size() == 1, "Expected one vector");
            String[] fields = rows.getFirst().split(",", -1);
            require(fields.length >= 8, "Invalid vector row");
            Instant observed = LocalDateTime.parse(fields[1].replace("A.D.", "").strip(), RESPONSE_TIME).toInstant(ZoneOffset.UTC);
            double expectedJulianDate = 2440587.5 + epoch.getEpochSecond() / 86400.0;
            require(observed.equals(epoch) && Math.abs(Double.parseDouble(fields[0]) - expectedJulianDate) < 1e-8,
                    "Unexpected Horizons vector epoch");
            double[] position = new double[3];
            for (int i = 0; i < 6; i++) {
                double value = Double.parseDouble(fields[i + 2]);
                require(Double.isFinite(value), "Invalid vector value");
                if (i < 3) position[i] = value;
            }
            return new Position(position, source);
        });
    }

    private static List<String> dataRows(String text) {
        int start = text.indexOf("$$SOE"), end = text.indexOf("$$EOE");
        require(start >= 0 && end > start && start == text.lastIndexOf("$$SOE") && end == text.lastIndexOf("$$EOE"),
                "Invalid Horizons delimiters");
        return text.substring(start + 5, end).strip().lines().toList();
    }

    private <T> T fetch(int target, Map<String, String> query, BiFunction<String, AnalysisEngine.Source, T> parse) {
        URI uri = URI.create(endpoint + "?" + String.join("&", query.entrySet().stream().map(entry ->
                entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).toList()));
        try {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new AnalysisEngineException("HORIZONS_UNAVAILABLE", null);
            }
            require(response.body().length <= 2_000_000, "Oversized Horizons response");
            var wrapper = JSON.readTree(response.body());
            require(!wrapper.has("error") && wrapper.path("result").isString(), "Invalid Horizons wrapper");
            return parse.apply(wrapper.path("result").asString(),
                    new AnalysisEngine.Source(target, uri.toString(), sha256(response.body()), Instant.now()));
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
