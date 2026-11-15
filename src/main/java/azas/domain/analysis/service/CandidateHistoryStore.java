package azas.domain.analysis.service;

import azas.domain.analysis.dto.AnalysisResponses.SurfaceCandidate;
import azas.domain.analysis.engine.TransferSearch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class CandidateHistoryStore {
    static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public void save(UUID run, List<TransferSearch.Candidate> candidates) {
        int index = 0;
        for (var c : candidates) {
            int number = ++index;
            jdbc.update("""
                    insert into surface_candidate_result (analysis_run_id, candidate_index, candidate_code,
                        proposed_landing_at, surface_finished_at, window_start_at, window_end_at, status, surface_status,
                        launch_at, tli_at, lunar_arrival_at, transfer_duration_hours, solver_status, position_residual_km,
                        model, reasons, solver_details)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                    """, run, number, String.format(Locale.ROOT, "%s-%05d", run, number), time(c.landingUtc()),
                    time(c.surfaceFinishUtc()), time(c.windowStartUtc()), time(c.windowEndUtc()), c.status(), c.surfaceStatus(),
                    time(c.launchUtc()), time(c.tliUtc()), time(c.lunarArrivalUtc()), c.transferDurationHours(), c.lambertStatus(),
                    c.positionResidualKm(), c.modelLevel(), JSON.writeValueAsString(c.reasons()), JSON.writeValueAsString(c.lambert()));
        }
    }

    public List<SurfaceCandidate> get(UUID run) {
        return jdbc.query("select * from surface_candidate_result where analysis_run_id = ? order by candidate_index",
                (rs, row) -> new SurfaceCandidate(rs.getString("candidate_code"), instant(rs, "proposed_landing_at"),
                        instant(rs, "surface_finished_at"), rs.getString("status"), rs.getString("surface_status"),
                        instant(rs, "launch_at"), instant(rs, "tli_at"), instant(rs, "lunar_arrival_at"),
                        (Double) rs.getObject("transfer_duration_hours"), rs.getString("solver_status"),
                        (Double) rs.getObject("position_residual_km"), rs.getString("model"), false,
                        List.of(JSON.readValue(rs.getString("reasons"), String[].class)),
                        JSON.readValue(rs.getString("solver_details"), JSON.getTypeFactory().constructMapType(Map.class, String.class, Object.class))), run);
    }

    static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }
    static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
}
