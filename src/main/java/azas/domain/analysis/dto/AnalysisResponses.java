package azas.domain.analysis.dto;

import azas.domain.analysis.entity.AnalysisRun;
import azas.domain.analysis.entity.DailyAnalysisResult;
import azas.domain.analysis.entity.VisibilityWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class AnalysisResponses {
    private AnalysisResponses() {}
    public record Accepted(UUID analysisRunId, String executionStatus) {}
    public record TouchdownWindow(Instant startUtc, Instant endUtc, Instant supportEndUtc, String surfaceStatus) {}
    public record SurfaceCandidate(String candidateId, Instant proposedLandingUtc, Instant surfaceFinishUtc) {}
    public record Opportunities(UUID analysisRunId, Instant earliestTouchdownUtc, List<TouchdownWindow> windows,
            List<SurfaceCandidate> candidates) {}
    public record Run(UUID analysisRunId, Long landingSiteId, String analysisType, String executionStatus,
            String overallResultStatus, Instant analysisStartAt, Instant analysisEndAt, int sampleIntervalMinutes,
            String engineVersion, String engineSha256, Instant createdAt, Instant executionStartedAt,
            Instant executionFinishedAt, Instant executionExpiresAt, String failureCode, List<String> diagnostics) {
        public static Run from(AnalysisRun run) {
            return new Run(run.getId(), run.getLandingSite().getId(), run.getAnalysisType().name(),
                    run.getExecutionStatus().name(), run.getOverallResultStatus() == null ? null : run.getOverallResultStatus().name(),
                    run.getAnalysisStartAt(), run.getAnalysisEndAt(), run.getSampleIntervalMinutes(), run.getEngineVersion(),
                    run.getEngineSha256(), run.getCreatedAt(), run.getExecutionStartedAt(), run.getExecutionFinishedAt(),
                    run.getExecutionExpiresAt(), run.getFailureCode(), strings(run.getDiagnosticMessages()));
        }
    }

    public record Window(String celestialBody, Instant windowStartAt, Instant windowEndAt, int boundaryPrecisionSeconds) {
        public static Window from(VisibilityWindow window) {
            return new Window(window.getCelestialBody().name(), window.getWindowStartAt(), window.getWindowEndAt(),
                    window.getBoundaryPrecisionSeconds());
        }
    }

    public record Day(Long dailyAnalysisResultId, LocalDate analysisDate, String resultStatus,
            Instant evaluatedPeriodStartAt, Instant evaluatedPeriodEndAt, Double sunVisibleTimeRatio,
            Double earthVisibleTimeRatio, Double jointVisibleTimeRatio, double sunKnownTimeRatio,
            double earthKnownTimeRatio, double jointKnownTimeRatio, double jointUnknownTimeRatio,
            Double longestSunOccultationHours, Double longestEarthOccultationHours, String terrainCoverageStatus,
            List<String> qualityFlags, List<Window> visibilityWindows) {
        public static Day from(DailyAnalysisResult day, List<VisibilityWindow> windows) {
            return new Day(day.getId(), day.getAnalysisDate(), day.getResultStatus().name(),
                    day.getEvaluatedPeriodStartAt(), day.getEvaluatedPeriodEndAt(), day.getSunVisibleTimeRatio(),
                    day.getEarthVisibleTimeRatio(), day.getJointVisibleTimeRatio(), day.getSunKnownTimeRatio(),
                    day.getEarthKnownTimeRatio(), day.getJointKnownTimeRatio(), day.getJointUnknownTimeRatio(),
                    day.getLongestSunOccultationHours(), day.getLongestEarthOccultationHours(), day.getTerrainCoverageStatus(),
                    strings(day.getQualityFlags()), windows.stream().map(Window::from).toList());
        }
    }

    private static List<String> strings(String[] values) { return values == null ? List.of() : List.copyOf(Arrays.asList(values)); }
}
