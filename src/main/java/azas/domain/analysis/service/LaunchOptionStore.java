package azas.domain.analysis.service;

import azas.domain.analysis.dto.LaunchOptionsResponse;
import azas.domain.analysis.dto.LaunchOptionsResponse.Site;
import azas.domain.analysis.engine.LaunchOptionCalculator;
import azas.domain.analysis.entity.AnalysisRun;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.domain.analysis.service.TouchdownSelections.Surface;
import azas.global.exception.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static azas.domain.analysis.service.CandidateHistoryStore.JSON;
import static azas.domain.analysis.service.CandidateHistoryStore.instant;
import static azas.domain.analysis.service.CandidateHistoryStore.time;

@Repository
@RequiredArgsConstructor
class LaunchOptionStore {
    static final String DEFAULT = "";
    private final JdbcTemplate jdbc;

    boolean exists(UUID id, String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from launch_option_batch where analysis_run_id = ? and selection_key = ?)",
                Boolean.class, id, key));
    }

    Optional<String> keyForDate(UUID id, LocalDate date) {
        return jdbc.queryForList("select selection_key from launch_touchdown_date where analysis_run_id = ? and touchdown_date = ?",
                String.class, id, date).stream().findFirst();
    }

    void rememberDate(UUID id, LocalDate date, String key) {
        jdbc.update("insert into launch_touchdown_date (analysis_run_id, touchdown_date, selection_key) values (?, ?, ?)", id, date, key);
    }

    Optional<String> keyForLaunch(UUID id, String launchId) {
        return jdbc.queryForList("select selection_key from launch_option_result where analysis_run_id = ? and candidate_code = ?",
                String.class, id, launchId).stream().findFirst();
    }

    void linkDefault(UUID id, Surface surface) {
        jdbc.update("""
                update launch_option_batch set surface_candidate_id = ?, surface_end_at = ?
                where analysis_run_id = ? and selection_key = ''
                """, surface.id(), time(surface.end()), id);
    }

    LaunchOptionsResponse read(AnalysisRun run, String key, boolean eligibleSitesOnly) {
        UUID id = run.getId();
        return jdbc.queryForObject("select * from launch_option_batch where analysis_run_id = ? and selection_key = ?", (rs, row) -> {
            if ("DATA_UNAVAILABLE".equals(rs.getString("status"))) throw new BusinessException(AnalysisErrorCode.LAUNCH_SITES_INCOMPLETE);
            var reports = jdbc.query("""
                    select * from launch_option_result where analysis_run_id = ? and selection_key = ?
                    and (not ? or candidate_code is not null) order by launch_site_id
                    """, (site, n) -> new Site(site.getLong("launch_site_id"), site.getString("site_code"),
                    site.getDouble("latitude_degrees"), site.getDouble("longitude_degrees_east"),
                    site.getString("candidate_code") == null ? List.of() : List.of(new LaunchOptionsResponse.Candidate(
                            site.getString("candidate_code"), site.getString("status"), instant(site, "launch_at"),
                            instant(site, "tli_at"), instant(site, "lunar_arrival_at"), instant(site, "proposed_landing_at")))),
                    id, key, eligibleSitesOnly);
            return new LaunchOptionsResponse(id, run.getLandingSite().getId(), run.getExecutionStatus(), instant(rs, "touchdown_at"),
                    rs.getString("model"), false, JSON.readValue(rs.getString("assumptions"), LaunchOptionsResponse.Assumptions.class),
                    reports, instant(rs, "calculated_at"), rs.getString("engine_sha256"), rs.getString("surface_candidate_id"));
        }, id, key);
    }

    void save(UUID id, String key, Instant start, Instant touchdown, Surface surface, List<Site> catalog) {
        jdbc.update("""
                insert into launch_option_batch (analysis_run_id, selection_key, calculated_at, engine_sha256, model,
                        assumptions, touchdown_at, status, surface_candidate_id, surface_end_at)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """, id, key, time(Instant.now()), LaunchOptionCalculator.fingerprint(), LaunchOptionCalculator.MODEL,
                JSON.writeValueAsString(LaunchOptionCalculator.ASSUMPTIONS), time(touchdown),
                touchdown == null ? "NO_WINDOW" : catalog.isEmpty() ? "DATA_UNAVAILABLE" : "READY",
                surface == null ? null : surface.id(), surface == null ? null : time(surface.end()));
        for (var site : catalog) {
            var candidates = LaunchOptionCalculator.candidates(site.launchSiteCode(), site.latitudeDeg(), site.longitudeDegEast(), start, touchdown);
            var candidate = candidates.isEmpty() ? null : candidates.getFirst();
            // Two touchdown selections can reuse a launch instant, but their trajectory inputs differ.
            String code = candidate == null ? null : key.isEmpty() ? candidate.candidateId() : "selected:" + key + ":" + site.launchSiteId();
            jdbc.update("""
                    insert into launch_option_result (analysis_run_id, selection_key, launch_site_id, site_code, latitude_degrees,
                            longitude_degrees_east, status, candidate_code, launch_at, tli_at, lunar_arrival_at, proposed_landing_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, key, site.launchSiteId(), site.launchSiteCode(), site.latitudeDeg(), site.longitudeDegEast(),
                    candidate == null ? "NO_CANDIDATE" : candidate.status(), code,
                    candidate == null ? null : time(candidate.launchUtc()), candidate == null ? null : time(candidate.tliUtc()),
                    candidate == null ? null : time(candidate.lunarArrivalUtc()), candidate == null ? null : time(candidate.proposedLandingUtc()));
        }
    }
}
