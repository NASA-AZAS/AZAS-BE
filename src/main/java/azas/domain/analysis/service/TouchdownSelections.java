package azas.domain.analysis.service;

import azas.domain.analysis.dto.AnalysisResponses.TouchdownWindow;
import azas.domain.analysis.dto.LaunchOptionsResponse.Site;
import azas.domain.analysis.engine.DateOpportunityEngine;
import azas.domain.analysis.engine.LaunchOptionCalculator;
import azas.domain.analysis.entity.AnalysisRun;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.global.exception.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static azas.domain.analysis.service.CandidateHistoryStore.instant;
import static azas.domain.analysis.service.CandidateHistoryStore.time;

@Component
@RequiredArgsConstructor
class TouchdownSelections {
    record Surface(String id, Instant touchdown, Instant end) {}

    private final AnalysisQueryService results;
    private final CandidateHistoryStore history;
    private final JdbcTemplate jdbc;

    Surface byId(UUID runId, String id, boolean hasStoredCandidates) {
        var frozen = jdbc.query("""
                select touchdown_at, surface_end_at from launch_option_batch
                where analysis_run_id = ? and surface_candidate_id = ?
                """, (rs, row) -> new Surface(id, instant(rs, "touchdown_at"), instant(rs, "surface_end_at")),
                runId, id).stream().findFirst();
        if (frozen.isPresent()) return frozen.get();

        if (hasStoredCandidates) {
            return history.findPeriod(runId, id).map(p -> new Surface(id, p.touchdown(), p.end()))
                    .orElseThrow(() -> new BusinessException(AnalysisErrorCode.SURFACE_CANDIDATE_NOT_FOUND));
        }
        return results.legacyCandidates(runId).stream().filter(c -> c.candidateId().equals(id))
                .map(c -> new Surface(id, c.proposedLandingUtc(), c.surfaceFinishUtc())).findFirst()
                .orElseThrow(() -> new BusinessException(AnalysisErrorCode.SURFACE_CANDIDATE_NOT_FOUND));
    }

    Surface at(AnalysisRun run, Instant touchdown, TouchdownWindow window) {
        Optional<Surface> stored;
        if (CandidateHistoryStore.JSON.readTree(run.getAnalysisParameters()).has("request")) {
            stored = jdbc.query("""
                    select candidate_code, surface_finished_at from surface_candidate_result
                    where analysis_run_id = ? and proposed_landing_at = ? and surface_status = 'SATISFIED'
                    order by candidate_index limit 1
                    """, (rs, row) -> new Surface(rs.getString("candidate_code"), touchdown, instant(rs, "surface_finished_at")),
                    run.getId(), time(touchdown)).stream().findFirst();
        } else {
            stored = results.legacyCandidates(run.getId()).stream().filter(c -> touchdown.equals(c.proposedLandingUtc()))
                    .map(c -> new Surface(c.candidateId(), touchdown, c.surfaceFinishUtc())).findFirst();
        }
        return stored.orElseGet(() -> new Surface(run.getId() + "-T" + touchdown,
                touchdown, touchdown.plus(Duration.between(window.endUtc(), window.supportEndUtc()))));
    }

    TouchdownWindow windowAt(UUID runId, Instant touchdown) {
        return results.touchdownWindows(runId).stream()
                .filter(w -> "SATISFIED".equals(w.surfaceStatus()) && !touchdown.isBefore(w.startUtc()) && !touchdown.isAfter(w.endUtc()))
                .findFirst().orElseThrow(() -> new BusinessException(AnalysisErrorCode.TOUCHDOWN_DATE_UNAVAILABLE));
    }

    private long stepSeconds(AnalysisRun run) {
        return CandidateHistoryStore.JSON.readTree(run.getAnalysisParameters()).path("request")
                .path("timeStepMinutes").asInt(DateOpportunityEngine.STEP_SECONDS / 60) * 60L;
    }

    Surface onGrid(AnalysisRun run, Instant time) {
        var window = windowAt(run.getId(), time);
        var offset = Duration.between(window.startUtc(), time);
        if (!time.equals(window.endUtc()) && (offset.getNano() != 0 || offset.getSeconds() % stepSeconds(run) != 0)) {
            throw new BusinessException(AnalysisErrorCode.TOUCHDOWN_DATE_UNAVAILABLE);
        }
        return at(run, time, window);
    }

    Surface onDate(AnalysisRun run, LocalDate date, List<Site> catalog) {
        Instant midnight = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant nextDay = midnight.plus(Duration.ofDays(1));
        long step = stepSeconds(run);
        TouchdownWindow firstWindow = null;
        Instant firstTime = null;
        for (var window : results.touchdownWindows(run.getId())) {
            if (!"SATISFIED".equals(window.surfaceStatus()) || window.endUtc().isBefore(midnight)
                    || !window.startUtc().isBefore(nextDay)) continue;

            // LLSN Deadline.emitGrid starts at the window boundary and includes its exact end.
            long offset = Math.max(0, Duration.between(window.startUtc(), midnight).getSeconds());
            Instant gridStart = window.startUtc().plusSeconds(Math.ceilDiv(offset, step) * step);
            Instant time = gridStart.isAfter(window.endUtc()) ? window.endUtc() : gridStart;
            if (firstWindow == null) {
                firstWindow = window;
                firstTime = time;
            }
            while (time.isBefore(nextDay)) {
                if (hasLaunch(run.getAnalysisStartAt(), time, catalog)) return at(run, time, window);
                if (time.equals(window.endUtc())) break;
                Instant next = time.plusSeconds(step);
                time = next.isAfter(window.endUtc()) ? window.endUtc() : next;
            }
        }
        if (firstWindow == null) throw new BusinessException(AnalysisErrorCode.TOUCHDOWN_DATE_UNAVAILABLE);
        return at(run, firstTime, firstWindow);
    }

    private static boolean hasLaunch(Instant analysisStart, Instant touchdown, List<Site> catalog) {
        return catalog.stream().anyMatch(site -> !LaunchOptionCalculator.candidates(site.launchSiteCode(),
                site.latitudeDeg(), site.longitudeDegEast(), analysisStart, touchdown).isEmpty());
    }
}
