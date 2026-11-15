package azas.domain.landingsite.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LandingScreenDatasetV1Tests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DIRECTORY = "data/imported/landing-screen/";
    private static final String PROFILE = DIRECTORY + "Site01_horizon.csv";

    @Test
    void validatesTheRealBundleAndPreservesThePublishedSite01Checkpoint() throws Exception {
        var dataset = LandingScreenDatasetV1.load();
        assertThat(dataset.sites()).hasSize(27);
        assertThat(dataset.sites()).allSatisfy(site -> assertThat(site.samples().length).isEqualTo(3600));
        var site = dataset.sites().stream().filter(value -> value.code().equals("Site01")).findFirst().orElseThrow();
        assertThat(site.longitude()).isEqualTo(-129.9644390);
        assertThat(site.latitude()).isEqualTo(-89.6307206);
        assertThat(site.elevation()).isEqualTo(863.783);
        assertThat(site.observerHeight()).isEqualTo(1);
        assertThat(site.samples()[2200]).containsExactly(220, 8.1972760, 5730.6, 311785);
        assertThat(dataset.sites().stream().flatMapToDouble(value -> Arrays.stream(value.samples())
                .mapToDouble(sample -> sample[1])).filter(angle -> angle < 0).count()).isEqualTo(11341);
        assertThat(dataset.sha256()).hasSize(64).isEqualTo(LandingScreenDatasetV1.load().sha256());
        assertThat(dataset.sourceMetadata()).contains("distantTerrainVerified", "blocker_horizontal_distance_m");
    }

    @Test
    void rejectsChangedSourceBytesBeforeParsing() throws Exception {
        Map<String, byte[]> overrides = new HashMap<>();
        overrides.put(PROFILE, "changed".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> load(overrides)).hasMessageContaining("Source integrity failure");
    }

    @Test
    void rejectsNonFiniteValuesEvenWithAnUpdatedManifest() throws Exception {
        for (String invalid : List.of("NaN", "Infinity", "-Infinity")) {
            Map<String, byte[]> overrides = new HashMap<>();
            String[] lines = text(PROFILE).split("\\R");
            String[] fields = lines[2201].split(",");
            fields[1] = invalid;
            lines[2201] = String.join(",", fields);
            replaceRegistered(overrides, PROFILE, String.join("\n", lines) + "\n");
            assertThatThrownBy(() -> load(overrides)).hasMessageContaining("Non-finite");
        }
    }

    @Test
    void rejectsMissingAndExtraProfileRows() throws Exception {
        String original = text(PROFILE);
        String[] lines = original.split("\\R");
        Map<String, byte[]> missing = new HashMap<>();
        replaceRegistered(missing, PROFILE, String.join("\n", Arrays.copyOf(lines, lines.length - 1)) + "\n");
        assertThatThrownBy(() -> load(missing)).hasMessageContaining("Missing horizon sample");
        Map<String, byte[]> extra = new HashMap<>();
        replaceRegistered(extra, PROFILE, original.stripTrailing() + "\n359.9,0,1,1\n");
        assertThatThrownBy(() -> load(extra)).hasMessageContaining("Extra horizon samples");
    }

    @Test
    void rejectsWrongAzimuthGridAndHeader() throws Exception {
        Map<String, byte[]> grid = new HashMap<>();
        String[] lines = text(PROFILE).split("\\R");
        lines[1] = lines[1].replaceFirst("^0\\.0,", "0.1,");
        replaceRegistered(grid, PROFILE, String.join("\n", lines) + "\n");
        assertThatThrownBy(() -> load(grid)).hasMessageContaining("Invalid horizon grid");
        Map<String, byte[]> header = new HashMap<>();
        replaceRegistered(header, PROFILE, text(PROFILE).replace("azimuth_deg,", "wrong_header,"));
        assertThatThrownBy(() -> load(header)).hasMessageContaining("Invalid horizon header");
    }

    @Test
    void rejectsDuplicateCandidateCodes() throws Exception {
        Map<String, byte[]> overrides = new HashMap<>();
        replaceRegistered(overrides, DIRECTORY + "candidates.csv",
                text(DIRECTORY + "candidates.csv").replace("\nDM2,", "\nDM1,"));
        assertThatThrownBy(() -> load(overrides)).hasMessageContaining("Duplicate or unregistered site");
    }

    @Test
    void rejectsIncompleteOrIncompatibleMetadataAndObserverHeights() throws Exception {
        for (String field : List.of("metadataStatus", "coordinateSystem", "verticalDatum", "interpolationPolicy")) {
            Map<String, byte[]> overrides = new HashMap<>();
            Map<String, Object> metadata = readMap(DIRECTORY + "metadata.json");
            metadata.put(field, "UNSUPPORTED");
            overrides.put(DIRECTORY + "metadata.json", JSON.writeValueAsBytes(metadata));
            assertThatThrownBy(() -> load(overrides)).hasMessageContaining("Unsupported metadata");
        }
        Map<String, byte[]> overrides = new HashMap<>();
        overrides.put(DIRECTORY + "metadata.json", text(DIRECTORY + "metadata.json")
                .replace("\"observerHeightAboveSurfaceM\": 1", "\"observerHeightAboveSurfaceM\": 2")
                .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> load(overrides)).hasMessageContaining("observer height");
    }

    @Test
    void rejectsDisagreementWithSite01GeoJson() throws Exception {
        Map<String, byte[]> overrides = new HashMap<>();
        replaceRegistered(overrides, DIRECTORY + "Site01_mmgis.geojson",
                text(DIRECTORY + "Site01_mmgis.geojson").replace("863.783", "864.783"));
        assertThatThrownBy(() -> load(overrides)).hasMessageContaining("GeoJSON");
    }

    @Test
    void includesProducerMetadataInTheBundleFingerprint() throws Exception {
        Map<String, byte[]> overrides = new HashMap<>();
        Map<String, Object> metadata = readMap(DIRECTORY + "metadata.json");
        metadata.put("qualityDescription", "Changed producer declaration");
        overrides.put(DIRECTORY + "metadata.json", JSON.writeValueAsBytes(metadata));
        assertThat(load(overrides).sha256()).isNotEqualTo(LandingScreenDatasetV1.load().sha256());
    }

    private static LandingScreenDatasetV1.Dataset load(Map<String, byte[]> overrides) throws Exception {
        return LandingScreenDatasetV1.load(path -> overrides.containsKey(path)
                ? overrides.get(path) : LandingScreenDatasetV1.readResource(path));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(String path) throws Exception {
        return JSON.readValue(LandingScreenDatasetV1.readResource(path), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static void replaceRegistered(Map<String, byte[]> overrides, String path, String replacement) throws Exception {
        byte[] bytes = replacement.getBytes(StandardCharsets.UTF_8);
        overrides.put(path, bytes);
        Map<String, Object> registry = readMap("data/product_registry.json");
        for (Object item : (List<?>) registry.get("files")) {
            Map<String, Object> entry = (Map<String, Object>) item;
            if (path.equals(entry.get("path"))) {
                entry.put("bytes", bytes.length);
                entry.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            }
        }
        overrides.put("data/product_registry.json", JSON.writeValueAsBytes(registry));
    }

    private static String text(String path) throws Exception {
        return new String(LandingScreenDatasetV1.readResource(path), StandardCharsets.UTF_8);
    }
}
