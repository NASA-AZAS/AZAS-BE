package azas.domain.analysis;

import static org.assertj.core.api.Assertions.*;

import azas.domain.analysis.dto.AnalysisRequest;
import azas.domain.analysis.engine.AnalysisEngine;
import azas.domain.analysis.service.AnalysisStore;
import azas.support.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AnalysisExecutionTests extends PostgresIntegrationTest {
    @Autowired AnalysisStore store;
    @Autowired JdbcTemplate jdbc;

    @Test void onlyTheOwnerCanStartOrCompleteAndCompletedRunsCannotBeOverwritten() {
        var prepared = prepare(1);
        var wrong = new AnalysisStore.Prepared(prepared.id(), UUID.randomUUID(), prepared.expiresAt(), prepared.input());
        assertThat(store.start(wrong)).isFalse();
        assertThat(store.start(prepared)).isTrue();
        assertThat(store.start(prepared)).isFalse();
        assertThat(store.complete(wrong, output(prepared, false))).isFalse();
        assertThat(store.complete(prepared, output(prepared, false))).isTrue();
        store.fail(prepared, "SHOULD_NOT_OVERWRITE");
        assertThat(status(prepared)).isEqualTo("SUCCEEDED");
        assertThat(dayCount(prepared)).isEqualTo(1);
    }

    @Test void expiresInterruptedJobsWithoutFailingAnotherUnexpiredOwnersWork() {
        var abandoned = prepare(1);
        var active = prepare(1);
        assertThat(store.start(abandoned)).isTrue();
        assertThat(store.start(active)).isTrue();
        jdbc.update("""
                update analysis_run set created_at = now() - interval '3 minutes',
                    execution_expires_at = now() - interval '1 minute' where analysis_run_id = ?
                """, abandoned.id());
        store.expire();
        assertThat(status(abandoned)).isEqualTo("FAILED");
        assertThat(status(active)).isEqualTo("RUNNING");
        assertThat(store.complete(abandoned, output(abandoned, false))).isFalse();
        assertThat(dayCount(abandoned)).isZero();
        assertThat(store.complete(active, output(active, false))).isTrue();
    }

    @Test void failureDuringTheSecondDayRollsBackTheFirstDayAndItsWindows() {
        var prepared = prepare(2);
        store.start(prepared);
        assertThatThrownBy(() -> store.complete(prepared, output(prepared, true)))
                .isInstanceOf(RuntimeException.class);
        assertThat(dayCount(prepared)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from visibility_window w join daily_analysis_result d using (daily_analysis_result_id)
                where d.analysis_run_id = ?
                """, Long.class, prepared.id())).isZero();
        store.fail(prepared, "ANALYSIS_INVALID_RESULT");
        assertThat(status(prepared)).isEqualTo("FAILED");
    }

    @Test void savesMappedWindowsWithTheirDailyParentAndRejectsCrossDayWindows() {
        var prepared = prepare(1);
        store.start(prepared);
        assertThat(store.complete(prepared, output(prepared, false))).isTrue();
        assertThat(jdbc.queryForObject("""
                select count(*) from visibility_window w join daily_analysis_result d using (daily_analysis_result_id)
                where d.analysis_run_id = ? and w.celestial_body = 'SUN'
                """, Long.class, prepared.id())).isEqualTo(1);
        var bad = prepare(1);
        store.start(bad);
        var valid = output(bad, false);
        var day = valid.days().getFirst();
        var cross = new AnalysisEngine.Day(day.date(), day.start(), day.end(), day.resultStatus(), day.terrainCoverage(),
                day.qualityFlags(), day.sun(), day.earth(), day.joint(), List.of(new AnalysisEngine.Window("SUN",
                day.start(), day.end().plusSeconds(1), 3600)));
        assertThatThrownBy(() -> store.complete(bad, new AnalysisEngine.Output("PARTIAL", List.of(cross), List.of(), List.of())))
                .hasMessage("ANALYSIS_INVALID_RESULT");
        assertThat(dayCount(bad)).isZero();
        store.fail(bad, "ANALYSIS_INVALID_RESULT");
    }

    private AnalysisStore.Prepared prepare(int days) {
        long site = jdbc.queryForObject("select landing_site_id from landing_site where site_code='Site01'", Long.class);

        return store.create(site, new AnalysisRequest(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, days)),
                new AnalysisEngine.Identity("test-only", "a".repeat(64)));
    }

    private AnalysisEngine.Output output(AnalysisStore.Prepared prepared, boolean invalidSecondDay) {
        var first = prepared.input().start();
        var metrics = new AnalysisEngine.Metrics(0.5, 1, 12.0);
        var days = new java.util.ArrayList<AnalysisEngine.Day>();

        for (var cursor = first; cursor.isBefore(prepared.input().end()); cursor = cursor.plusSeconds(86400)) {
            var chosen = invalidSecondDay && !cursor.equals(first) ? new AnalysisEngine.Metrics(2.0, 1, 12.0) : metrics;
            days.add(new AnalysisEngine.Day(cursor.atOffset(java.time.ZoneOffset.UTC).toLocalDate(), cursor, cursor.plusSeconds(86400),
                    "PARTIAL", "LOCAL_HORIZON_ONLY", List.of("TEST_ONLY"), chosen, metrics, metrics,
                    List.of(new AnalysisEngine.Window("SUN", cursor, cursor.plusSeconds(3600), 3600))));
        }

        return new AnalysisEngine.Output("PARTIAL", days, List.of(), List.of("TEST_ONLY"));
    }

    private String status(AnalysisStore.Prepared prepared) {
        return jdbc.queryForObject("select execution_status::text from analysis_run where analysis_run_id = ?", String.class, prepared.id());
    }

    private Long dayCount(AnalysisStore.Prepared prepared) {
        return jdbc.queryForObject("select count(*) from daily_analysis_result where analysis_run_id = ?", Long.class, prepared.id());
    }
}
