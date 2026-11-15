package azas.domain.analysis.service;

import static azas.domain.analysis.service.CandidateHistoryStore.*;

import azas.domain.analysis.dto.LaunchOptionsResponse;
import azas.domain.analysis.engine.LaunchOptionCalculator;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.domain.analysis.model.AnalysisType;
import azas.domain.analysis.model.ExecutionStatus;
import azas.domain.analysis.repository.AnalysisRunRepository;
import azas.domain.analysis.repository.LaunchSiteRepository;
import azas.global.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LaunchOptionService {
    private final AnalysisRunRepository runs;
    private final LaunchSiteRepository sites;
    private final JdbcTemplate jdbc;

    @Transactional
    public LaunchOptionsResponse get(UUID id) {
        // Serializes the one-time snapshot for pre-V6 runs; new runs capture on completion.
        var run = runs.lockById(id).orElseThrow(() -> new BusinessException(AnalysisErrorCode.RUN_NOT_FOUND));
        if (run.getAnalysisType() != AnalysisType.DATE_OPPORTUNITIES) throw new BusinessException(AnalysisErrorCode.TYPE_MISMATCH);
        if (run.getExecutionStatus() != ExecutionStatus.SUCCEEDED)
            return new LaunchOptionsResponse(id, run.getLandingSite().getId(), run.getExecutionStatus(), null,
                    LaunchOptionCalculator.MODEL, false, LaunchOptionCalculator.ASSUMPTIONS, List.of());
        if (jdbc.queryForObject("select count(*) from launch_option_batch where analysis_run_id = ?", Integer.class, id) == 0)
            capture(id, run.getAnalysisStartAt(), runs.findEarliestSatisfiedTouchdown(id).orElse(null));
        return jdbc.queryForObject("select * from launch_option_batch where analysis_run_id = ?", (rs, row) -> {
            if (rs.getString("status").equals("DATA_UNAVAILABLE")) throw new BusinessException(AnalysisErrorCode.LAUNCH_SITES_INCOMPLETE);
            var reports = jdbc.query("select * from launch_option_result where analysis_run_id = ? order by launch_site_id",
                    (site, n) -> new LaunchOptionsResponse.Site(site.getLong("launch_site_id"), site.getString("site_code"),
                            site.getDouble("latitude_degrees"), site.getDouble("longitude_degrees_east"),
                            site.getString("candidate_code") == null ? List.of() : List.of(new LaunchOptionsResponse.Candidate(
                                    site.getString("candidate_code"), site.getString("status"), instant(site, "launch_at"),
                                    instant(site, "tli_at"), instant(site, "lunar_arrival_at"), instant(site, "proposed_landing_at")))), id);
            return new LaunchOptionsResponse(id, run.getLandingSite().getId(), run.getExecutionStatus(), instant(rs, "touchdown_at"),
                    rs.getString("model"), false, JSON.readValue(rs.getString("assumptions"), LaunchOptionsResponse.Assumptions.class), reports,
                    instant(rs, "calculated_at"), rs.getString("engine_sha256"));
        }, id);
    }

    /** Caller holds the analysis row lock, so each run is frozen exactly once. */
    @Transactional
    public void capture(UUID id, Instant start, Instant touchdown) {
        var catalog = touchdown == null ? List.<azas.domain.analysis.entity.LaunchSite>of() : sites.findAllByOrderByIdAsc();
        jdbc.update("""
                insert into launch_option_batch (analysis_run_id, calculated_at, engine_sha256, model,
                        assumptions, touchdown_at, status)
                values (?, ?, ?, ?, ?::jsonb, ?, ?)
                """, id, time(Instant.now()), LaunchOptionCalculator.fingerprint(), LaunchOptionCalculator.MODEL,
                JSON.writeValueAsString(LaunchOptionCalculator.ASSUMPTIONS), time(touchdown),
                touchdown == null ? "NO_WINDOW" : catalog.isEmpty() ? "DATA_UNAVAILABLE" : "READY");
        for (var site : catalog) {
            var candidates = LaunchOptionCalculator.candidates(site.getSiteCode(), site.getLatitudeDegrees(), site.getLongitudeDegreesEast(), start, touchdown);
            var c = candidates.isEmpty() ? null : candidates.getFirst();
            jdbc.update("""
                    insert into launch_option_result (analysis_run_id, launch_site_id, site_code, latitude_degrees,
                            longitude_degrees_east, status, candidate_code, launch_at, tli_at, lunar_arrival_at, proposed_landing_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, site.getId(), site.getSiteCode(),
                    site.getLatitudeDegrees(), site.getLongitudeDegreesEast(), c == null ? "NO_CANDIDATE" : c.status(),
                    c == null ? null : c.candidateId(), c == null ? null : time(c.launchUtc()), c == null ? null : time(c.tliUtc()),
                    c == null ? null : time(c.lunarArrivalUtc()), c == null ? null : time(c.proposedLandingUtc()));
        }
    }
}
