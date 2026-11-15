package azas.domain.analysis.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Stable entry/return contract. Controllers and persistence never depend on LLSN result shapes. */
public interface AnalysisEngine {
    Identity identity();
    Output analyze(Input input);

    record Identity(String version, String sha256) {}
    record Input(String siteCode, double latitude, double longitude, double surfaceElevation,
                 double observerHeight, String frame, int sphereRadius, boolean distantTerrainVerified,
                 String datasetSha256, List<Double> horizon, Instant start, Instant end, int stepSeconds) {
        public Input { horizon = List.copyOf(horizon); }
    }

    record Metrics(Double visibleRatio, double knownRatio, Double longestOccultationHours) {}
    record Window(String body, Instant start, Instant end, int precisionSeconds) {}
    record Day(LocalDate date, Instant start, Instant end, String resultStatus, String terrainCoverage,
               List<String> qualityFlags, Metrics sun, Metrics earth, Metrics joint, List<Window> windows) {
        public Day { windows = List.copyOf(windows); qualityFlags = List.copyOf(qualityFlags); }
    }

    record Source(int target, String url, String responseSha256, Instant retrievedAt) {}
    record Output(String resultStatus, List<Day> days, List<Source> sources, List<String> diagnostics) {
        public Output {
            days = List.copyOf(days);
            sources = List.copyOf(sources);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
