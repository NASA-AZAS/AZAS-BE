package azas.domain.analysis.service;

import static azas.domain.analysis.service.CandidateHistoryStore.*;

import azas.domain.analysis.dto.LaunchOptionsResponse;
import azas.domain.analysis.dto.LaunchTrajectoryResponse;
import azas.domain.analysis.engine.AnalysisEngine;
import azas.domain.analysis.engine.LunarTrajectoryCalculator;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.global.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class TrajectoryHistoryStore {
    private final JdbcTemplate jdbc;
    public record Input(UUID analysisRunId, long landingSiteId, LaunchOptionsResponse.Site site,
            LaunchOptionsResponse.Candidate candidate, LaunchOptionsResponse.Assumptions assumptions, String surfaceCandidateId) {}
    public record Prepared(UUID id, Instant expiresAt, Input input) {}

    @Transactional(readOnly = true)
    public List<LaunchTrajectoryResponse.Summary> list(UUID runId) {
        if (jdbc.queryForObject("select count(*) from analysis_run where analysis_run_id = ?", Integer.class, runId) == 0)
            throw new BusinessException(AnalysisErrorCode.RUN_NOT_FOUND);
        return jdbc.query("select * from trajectory_run where analysis_run_id = ? order by created_at desc, trajectory_run_id desc limit 100",
                (rs, row) -> new LaunchTrajectoryResponse.Summary(rs.getObject("trajectory_run_id", UUID.class), rs.getString("candidate_code"),
                        rs.getString("execution_status"), rs.getString("status"), instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"), rs.getString("reason")), runId);
    }

    @Transactional
    public Prepared create(LaunchOptionsResponse options, LaunchOptionsResponse.Site site, LaunchOptionsResponse.Candidate candidate) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(), expires = now.plusSeconds(AnalysisStore.TIMEOUT_SECONDS);
        var input = new Input(options.analysisRunId(), options.landingSiteId(), site, candidate,
                options.assumptions(), options.surfaceCandidateId());
        jdbc.update("""
                insert into trajectory_run (trajectory_run_id, analysis_run_id, launch_site_id, candidate_code,
                    execution_status, created_at, expires_at, engine_sha256, input_snapshot)
                values (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?::jsonb)
                """, id, options.analysisRunId(), site.launchSiteId(), candidate.candidateId(), time(now), time(expires),
                LunarTrajectoryCalculator.fingerprint(), JSON.writeValueAsString(input));
        return new Prepared(id, expires, input);
    }

    @Transactional
    public boolean start(Prepared prepared) {
        return jdbc.update("""
                update trajectory_run set execution_status = 'RUNNING', started_at = clock_timestamp()
                where trajectory_run_id = ? and execution_status = 'PENDING' and expires_at > clock_timestamp()
                """, prepared.id()) == 1;
    }

    @Transactional
    public void complete(Prepared prepared, LaunchTrajectoryResponse result) {
        // Lock before samples: late/cancelled workers cannot overwrite a terminal result.
        var active = jdbc.queryForObject("""
                select execution_status = 'RUNNING' and expires_at > clock_timestamp()
                from trajectory_run where trajectory_run_id = ? for update
                """, Boolean.class, prepared.id());
        if (!Boolean.TRUE.equals(active)) { fail(prepared.id(), "ANALYSIS_TIMEOUT"); return; }
        jdbc.batchUpdate("""
                insert into trajectory_sample (trajectory_run_id, sample_index, observed_at, elapsed_seconds,
                        position_x_km, position_y_km, position_z_km, velocity_x_km_s, velocity_y_km_s, velocity_z_km_s)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, result.samples(), 1000,
                new org.springframework.jdbc.core.ParameterizedPreparedStatementSetter<LaunchTrajectoryResponse.Sample>() {
                    int index;
                    public void setValues(java.sql.PreparedStatement ps, LaunchTrajectoryResponse.Sample sample) throws java.sql.SQLException {
                        ps.setObject(1, prepared.id()); ps.setInt(2, index++); ps.setTimestamp(3, time(sample.utc()));
                        ps.setLong(4, sample.elapsedSeconds());
                        for (int axis = 0; axis < 3; axis++) {
                            ps.setDouble(5 + axis, sample.positionKm()[axis]); ps.setDouble(8 + axis, sample.velocityKmS()[axis]);
                        }
                    }
                });
        int saved = jdbc.update("""
                update trajectory_run set execution_status = ?, finished_at = clock_timestamp(), status = ?, reason = ?,
                    solver_status = ?, model = ?, frame = ?, origin = ?, segment = ?, arrival_target = ?,
                    nominal_sample_step_seconds = ?, position_residual_km = ?, tli_delta_v_km_s = ?, source_records = ?::jsonb
                where trajectory_run_id = ? and expires_at > clock_timestamp()
                """, "FAILED".equals(result.status()) ? "FAILED" : "SUCCEEDED", result.status(), result.reason(), result.lambertStatus(),
                result.model(), result.frame(), result.origin(), result.segment(), result.arrivalTarget(), result.nominalSampleStepSeconds(),
                result.positionResidualKm(), result.tliDeltaVKmS(), JSON.writeValueAsString(result.sourceRecords()), prepared.id());
        if (saved == 0) throw new azas.domain.analysis.engine.AnalysisEngineException("ANALYSIS_TIMEOUT", null);
    }

    @Transactional
    public void fail(UUID id, String reason) {
        jdbc.update("""
                update trajectory_run set execution_status = 'FAILED', status = 'FAILED', reason = ?, finished_at = clock_timestamp()
                where trajectory_run_id = ? and execution_status in ('PENDING', 'RUNNING')
                """, reason, id);
    }

    @Transactional
    public void expire() {
        jdbc.update("""
                update trajectory_run set execution_status = 'FAILED', status = 'FAILED', reason = 'ANALYSIS_TIMEOUT',
                    finished_at = clock_timestamp() where execution_status in ('PENDING', 'RUNNING') and expires_at <= clock_timestamp()
                """);
    }

    @Transactional(readOnly = true)
    public LaunchTrajectoryResponse get(UUID analysisId, UUID trajectoryId) {
        var rows = jdbc.query("select * from trajectory_run where trajectory_run_id = ? and analysis_run_id = ?", (rs, row) -> {
            Input input = JSON.readValue(rs.getString("input_snapshot"), Input.class);
            var c = input.candidate();
            String state = rs.getString("execution_status");
            // Samples commit with SUCCEEDED. An earlier snapshot must not pick them up in a second query.
            List<LaunchTrajectoryResponse.Sample> samples = "SUCCEEDED".equals(state) ? samples(trajectoryId) : List.of();
            return LaunchTrajectoryResponse.builder().analysisRunId(analysisId).landingSiteId(input.landingSiteId())
                    .launchSiteId(input.site().launchSiteId()).launchSiteCode(input.site().launchSiteCode()).candidateId(c.candidateId())
                    .surfaceCandidateId(input.surfaceCandidateId())
                    .status(rs.getString("status")).reason(rs.getString("reason")).lambertStatus(rs.getString("solver_status"))
                    .model(rs.getString("model")).frame(rs.getString("frame")).origin(rs.getString("origin"))
                    .segment(rs.getString("segment")).arrivalTarget(rs.getString("arrival_target")).assumptions(input.assumptions())
                    .launchUtc(c.launchUtc()).tliUtc(c.tliUtc()).lunarArrivalUtc(c.lunarArrivalUtc()).proposedLandingUtc(c.proposedLandingUtc())
                    .nominalSampleStepSeconds(rs.getInt("nominal_sample_step_seconds")).positionResidualKm((Double) rs.getObject("position_residual_km"))
                    .tliDeltaVKmS((Double) rs.getObject("tli_delta_v_km_s")).samples(samples)
                    .sourceRecords(List.of(JSON.readValue(rs.getString("source_records"), AnalysisEngine.Source[].class)))
                    .trajectoryRunId(trajectoryId).executionStatus(state).createdAt(instant(rs, "created_at"))
                    .startedAt(instant(rs, "started_at"))
                    .finishedAt(instant(rs, "finished_at")).engineSha256(rs.getString("engine_sha256")).build();
        }, trajectoryId, analysisId);
        if (rows.isEmpty()) throw new BusinessException(AnalysisErrorCode.TRAJECTORY_NOT_FOUND);
        return rows.getFirst();
    }

    private List<LaunchTrajectoryResponse.Sample> samples(UUID trajectoryId) {
        return jdbc.query("select * from trajectory_sample where trajectory_run_id = ? order by sample_index", (rs, n) ->
                new LaunchTrajectoryResponse.Sample(instant(rs, "observed_at"), rs.getLong("elapsed_seconds"),
                        new double[]{rs.getDouble("position_x_km"), rs.getDouble("position_y_km"), rs.getDouble("position_z_km")},
                        new double[]{rs.getDouble("velocity_x_km_s"), rs.getDouble("velocity_y_km_s"), rs.getDouble("velocity_z_km_s")}), trajectoryId);
    }
}
