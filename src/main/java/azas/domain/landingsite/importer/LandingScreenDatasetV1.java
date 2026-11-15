package azas.domain.landingsite.importer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/** Immutable parser for the producer's 2026-11-14 dataset. Keep applied imports unchanged. */
public final class LandingScreenDatasetV1 {

    public static final String CODE = "landing-screen";
    public static final String VERSION = "2026-11-14-v1";
    public static final int SITE_COUNT = 27;
    public static final int SAMPLES_PER_SITE = 3600;
    public static final String RESOURCE_ROOT = "terrain/landing-screen/v1/";
    private static final String DIRECTORY = "data/imported/landing-screen/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LandingScreenDatasetV1() {
    }

    @FunctionalInterface
    public interface ResourceReader {
        byte[] read(String path) throws IOException;
    }

    public record Site(String code, double latitude, double longitude, double elevation,
            double observerHeight, double squareSide, String screeningStatus, double[][] samples) {
    }

    public record Dataset(String sha256, String sourceMetadata, List<Site> sites) {
    }

    public static byte[] readResource(String path) throws IOException {
        try (InputStream stream = LandingScreenDatasetV1.class.getClassLoader()
                .getResourceAsStream(RESOURCE_ROOT + path)) {
            if (stream == null) {
                throw new IOException("Missing terrain resource: " + path);
            }

            return stream.readAllBytes();
        }
    }

    public static Dataset load() throws IOException {
        return load(LandingScreenDatasetV1::readResource);
    }

    public static Dataset load(ResourceReader reader) throws IOException {
        byte[] registryBytes = reader.read("data/product_registry.json");
        Map<String, Object> registry = json(registryBytes);
        require(CODE.equals(registry.get("productId")), "Unsupported terrain dataset");
        require((DIRECTORY + "metadata.json").equals(registry.get("metadataPath")), "Invalid metadata path");
        require(number(registry.get("profileCount")) == SITE_COUNT, "Expected 27 sites");
        require(number(registry.get("rowsPerProfile")) == SAMPLES_PER_SITE, "Expected 3600 samples per site");

        Map<String, byte[]> files = new TreeMap<>();

        for (Object item : list(registry.get("files"))) {
            Map<String, Object> entry = map(item);
            String path = string(entry.get("path"));
            require(path.matches("data/imported/landing-screen/[A-Za-z0-9_.-]+"), "Invalid source path");
            require(!files.containsKey(path), "Duplicate source path");
            byte[] bytes = reader.read(path);
            require(bytes.length == number(entry.get("bytes")) && sha256(bytes).equals(entry.get("sha256")),
                    "Source integrity failure: " + path);
            files.put(path, bytes);
        }

        byte[] metadataBytes = reader.read(DIRECTORY + "metadata.json");
        Map<String, Object> metadata = json(metadataBytes);
        admitMetadata(metadata);
        Map<String, Double> heights = new HashMap<>();

        for (Object item : list(metadata.get("sites"))) {
            Map<String, Object> entry = map(item);
            String code = string(entry.get("siteId"));
            double height = number(entry.get("observerHeightAboveSurfaceM"));
            require(height == 1 && heights.putIfAbsent(code, height) == null,
                    "Invalid or duplicate observer height: " + code);
        }

        Set<String> expectedFiles = new HashSet<>(Set.of(DIRECTORY + "candidates.csv",
                DIRECTORY + "Site01_mmgis.geojson", DIRECTORY + "HORIZON_METADATA.md"));
        List<Site> sites = new ArrayList<>();
        Set<String> codes = new HashSet<>();

        try (BufferedReader csv = csv(files.get(DIRECTORY + "candidates.csv"))) {
            require("site,latitude_deg,longitude_deg,elevation_5m_m,square_side_m,status".equals(csv.readLine()),
                    "Invalid candidate header");
            String line;

            while ((line = csv.readLine()) != null) {
                String[] fields = line.split(",", -1);
                require(fields.length == 6 && fields[0].matches("[A-Za-z0-9_-]+"), "Invalid candidate row");
                String code = fields[0];
                require(codes.add(code) && heights.containsKey(code), "Duplicate or unregistered site: " + code);
                double latitude = finite(fields[1]), longitude = finite(fields[2]), elevation = finite(fields[3]);
                double side = finite(fields[4]);
                require(Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180 && side >= 100
                        && fields[5].equals("screened"), "Invalid candidate coordinates or screening");
                String profile = DIRECTORY + code + "_horizon.csv";
                expectedFiles.add(profile);
                sites.add(new Site(code, latitude, longitude, elevation, heights.get(code), side, fields[5],
                        profile(files.get(profile))));
            }
        }

        require(sites.size() == SITE_COUNT && codes.equals(heights.keySet()), "Candidate and metadata sites differ");
        require(files.keySet().equals(expectedFiles), "Manifest and expected source files differ");
        verifyGeoJson(files.get(DIRECTORY + "Site01_mmgis.geojson"), sites);

        // Path + content hash prevents ambiguous concatenation and includes metadata absent from the producer manifest.
        Map<String, String> digests = new TreeMap<>();
        files.forEach((path, bytes) -> digests.put(path, sha256(bytes)));
        digests.put("data/product_registry.json", sha256(registryBytes));
        digests.put(DIRECTORY + "metadata.json", sha256(metadataBytes));
        String bundle = sha256(JSON.writeValueAsBytes(digests));
        String provenance = JSON.writeValueAsString(Map.of("metadata", metadata, "productRegistry", registry));

        return new Dataset(bundle, provenance, List.copyOf(sites));
    }

    public static int checksum() {
        try {
            return ByteBuffer.wrap(HexFormat.of().parseHex(load().sha256())).getInt();
        } catch (IOException exception) {
            throw new IllegalStateException("Terrain import resources are unavailable", exception);
        }
    }

    private static void admitMetadata(Map<String, Object> metadata) {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("schemaVersion", "1.0");
        expected.put("datasetId", CODE);
        expected.put("inputType", "HORIZON_CSV");
        expected.put("metadataStatus", "READY");
        expected.put("coordinateSystem", "MOON_ME_DE421_PLANETOCENTRIC_EAST");
        expected.put("verticalDatum", "ELEVATION_M_ABOVE_1737400_M_SPHERE");
        expected.put("surfaceElevationMeaning", "elevation_5m_m_IS_SURFACE_ELEVATION");
        expected.put("azimuthConvention", "NORTH_CLOCKWISE_DEGREES");
        expected.put("elevationConvention", "LOCAL_HORIZONTAL_DEGREES");
        expected.put("noDataPolicy", "ALL_ROWS_VALID");
        expected.put("interpolationPolicy", "LINEAR_NO_SMOOTHING");
        expected.put("distantTerrainVerified", false);
        expected.forEach((key, value) -> require(value.equals(metadata.get(key)), "Unsupported metadata: " + key));

        for (String key : List.of("producer", "parentProducts", "qualityDescription")) {
            string(metadata.get(key));
        }
    }

    private static double[][] profile(byte[] bytes) throws IOException {
        double[][] result = new double[SAMPLES_PER_SITE][4];

        try (BufferedReader csv = csv(bytes)) {
            require("azimuth_deg,horizon_deg,blocker_horizontal_distance_m,searched_ground_distance_m"
                    .equals(csv.readLine()), "Invalid horizon header");

            for (int index = 0; index < SAMPLES_PER_SITE; index++) {
                String line = csv.readLine();
                require(line != null, "Missing horizon sample");
                String[] fields = line.split(",", -1);
                require(fields.length == 4, "Invalid horizon sample");

                for (int column = 0; column < 4; column++) {
                    result[index][column] = finite(fields[column]);
                }

                double[] sample = result[index];
                require(Math.abs(sample[0] - index / 10.0) < 1e-8 && Math.abs(sample[1]) <= 90
                        && sample[2] >= 0 && sample[3] > 0 && sample[3] <= 1_000_000,
                        "Invalid horizon grid, angle or distance");
            }

            require(csv.readLine() == null, "Extra horizon samples");
        }

        return result;
    }

    private static void verifyGeoJson(byte[] bytes, List<Site> sites) {
        List<?> features = list(json(bytes).get("features"));
        require(features.size() == 1, "Invalid Site01 GeoJSON");
        Map<String, Object> feature = map(features.getFirst());
        Map<String, Object> geometry = map(feature.get("geometry"));
        Map<String, Object> properties = map(feature.get("properties"));
        List<?> coordinates = list(geometry.get("coordinates"));
        Site site = sites.stream().filter(value -> value.code().equals("Site01")).findFirst().orElseThrow();
        require("Point".equals(geometry.get("type")) && coordinates.size() == 2
                && "Site01".equals(properties.get("site"))
                && Math.abs(number(coordinates.get(0)) - site.longitude()) < 1e-8
                && Math.abs(number(coordinates.get(1)) - site.latitude()) < 1e-8
                && Math.abs(number(properties.get("elevation_m")) - site.elevation()) < 1e-8
                && Math.abs(number(properties.get("observer_height_m")) - site.observerHeight()) < 1e-8,
                "Site01 GeoJSON and candidate metadata differ");
    }

    private static BufferedReader csv(byte[] bytes) {
        require(bytes != null, "Unregistered CSV file");

        return new BufferedReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
    }

    private static double finite(String value) {
        double number = Double.parseDouble(value);
        require(Double.isFinite(number), "Non-finite CSV value");

        return number;
    }

    private static double number(Object value) {
        require(value instanceof Number && Double.isFinite(((Number) value).doubleValue()), "Invalid metadata number");

        return ((Number) value).doubleValue();
    }

    private static String string(Object value) {
        require(value instanceof String && !((String) value).isBlank(), "Missing metadata string");

        return (String) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        require(value instanceof Map, "Invalid JSON object");

        return (Map<String, Object>) value;
    }

    private static List<?> list(Object value) {
        require(value instanceof List, "Invalid JSON list");

        return (List<?>) value;
    }

    private static Map<String, Object> json(byte[] bytes) {
        require(bytes != null, "Missing source JSON");

        return map(JSON.readValue(bytes, Map.class));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
