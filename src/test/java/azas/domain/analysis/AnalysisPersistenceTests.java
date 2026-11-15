package azas.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import azas.domain.analysis.entity.AnalysisRun;
import azas.domain.analysis.entity.DailyAnalysisResult;
import azas.domain.analysis.entity.LandingOpportunityResult;
import azas.domain.analysis.entity.VisibilityWindow;
import azas.domain.analysis.model.AnalysisType;
import azas.domain.analysis.model.CelestialBody;
import azas.domain.analysis.model.ExecutionStatus;
import azas.domain.analysis.model.OpportunityStatus;
import azas.domain.analysis.model.ResultStatus;
import azas.domain.landingsite.entity.LandingSite;
import azas.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AnalysisPersistenceTests extends PostgresIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 14);
    private static final Instant START = Instant.parse("2026-11-14T00:00:00Z");
    private static final Instant END = START.plusSeconds(86400);

    @Autowired EntityManager entityManager;

    @Test
    void savesTypedResultsWithoutTurningUnknownValuesIntoZeroOrFalse() {
        var run = persistRun(AnalysisType.DATE_OPPORTUNITIES, true);
        var daily = daily(run);
        entityManager.persist(daily);
        var window = VisibilityWindow.builder().dailyAnalysisResult(daily).celestialBody(CelestialBody.JOINT)
                .windowStartAt(START.plusSeconds(3600)).windowEndAt(START.plusSeconds(7200))
                .boundaryPrecisionSeconds(3600).build();
        entityManager.persist(window);
        var opportunity = LandingOpportunityResult.builder().analysisRun(run).opportunityCode("example-1")
                .opportunityStatus(OpportunityStatus.UNKNOWN).surfaceConditionStatus("UNKNOWN")
                .terrainCoverageStatus("PARTIAL").landingWindowStartAt(START).landingWindowEndAt(END)
                .proposedLandingAt(START.plusSeconds(3600)).surfaceActivityFinishedAt(START.plusSeconds(7200))
                .deadlineSatisfied(true).trajectorySolverStatus("NOT_RUN").trajectoryVerified(false)
                .calculationModelLevel("ENVIRONMENT_ONLY").decisionReasons(new String[]{"TERRAIN_INCOMPLETE"})
                .provenanceId("a".repeat(64)).trajectoryCalculationDetails("{\"solver\":\"not-run\"}").build();
        entityManager.persist(opportunity);
        entityManager.flush();
        entityManager.clear();

        var storedRun = entityManager.find(AnalysisRun.class, run.getId());
        assertThat(storedRun.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(storedRun.getOverallResultStatus()).isEqualTo(ResultStatus.PARTIAL);
        assertThat(storedRun.getMissionDueDate()).isEqualTo(DATE);
        assertThat(storedRun.getMissionDeadlineAt()).isEqualTo(END);
        assertThat(storedRun.getAnalysisParameters()).contains("observerHeight", "1");
        assertThat(storedRun.getDiagnosticMessages()).containsExactly("DEM coverage incomplete");
        assertThat(storedRun.getSourceProvenance()).contains("MOON_ME");
        var storedDaily = entityManager.find(DailyAnalysisResult.class, daily.getId());
        assertThat(storedDaily.getAnalysisDate()).isEqualTo(DATE);
        assertThat(storedDaily.getSunVisibleTimeRatio()).isNull();
        assertThat(storedDaily.getLongestSunOccultationHours()).isNull();
        assertThat(storedDaily.getJointUnknownTimeRatio()).isEqualTo(1.0);
        assertThat(storedDaily.getQualityFlags()).containsExactly("TERRAIN_INCOMPLETE");
        assertThat(entityManager.find(VisibilityWindow.class, window.getId()).getCelestialBody())
                .isEqualTo(CelestialBody.JOINT);
        var storedOpportunity = entityManager.find(LandingOpportunityResult.class, opportunity.getId());
        assertThat(storedOpportunity.getOpportunityStatus()).isEqualTo(OpportunityStatus.UNKNOWN);
        assertThat(storedOpportunity.getLaunchAt()).isNull();
        assertThat(storedOpportunity.getArrivalPositionErrorKilometers()).isNull();
        assertThat(storedOpportunity.getDecisionReasons()).containsExactly("TERRAIN_INCOMPLETE");
        assertThat(storedOpportunity.getTrajectoryCalculationDetails()).contains("not-run");
    }

    @Test
    void identicalParametersStillCreateIndependentRunsAndDailyResults() {
        var first = persistRun(AnalysisType.VISIBILITY, false);
        var second = persistRun(AnalysisType.VISIBILITY, false);
        entityManager.persist(daily(first));
        entityManager.persist(daily(second));
        entityManager.flush();
        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getCalculationParametersSha256()).isEqualTo(second.getCalculationParametersSha256());
    }

    @Test
    void rejectsTwoSummariesForTheSameRunAndDate() {
        var run = persistRun(AnalysisType.VISIBILITY, false);
        entityManager.persist(daily(run));
        assertThatThrownBy(() -> {
            entityManager.persist(daily(run));
            entityManager.flush();
        }).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void dateOpportunitiesRequireBothTheSelectedDueDateAndAppliedDeadline() {
        assertThatThrownBy(() -> persistRun(AnalysisType.DATE_OPPORTUNITIES, false))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void rejectsAnInvalidDailyRatio() {
        var run = persistRun(AnalysisType.VISIBILITY, false);
        var invalid = DailyAnalysisResult.builder().analysisRun(run).analysisDate(DATE)
                .resultStatus(ResultStatus.PARTIAL).evaluatedPeriodStartAt(START).evaluatedPeriodEndAt(END)
                .sunKnownTimeRatio(2.0).earthKnownTimeRatio(0.0).jointKnownTimeRatio(0.0)
                .jointUnknownTimeRatio(1.0).terrainCoverageStatus("PARTIAL").build();
        assertThatThrownBy(() -> {
            entityManager.persist(invalid);
            entityManager.flush();
        }).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void aDailyResultWithWindowsCannotBeDeleted() {
        var run = persistRun(AnalysisType.VISIBILITY, false);
        var daily = daily(run);
        entityManager.persist(daily);
        entityManager.persist(VisibilityWindow.builder().dailyAnalysisResult(daily).celestialBody(CelestialBody.SUN)
                .windowStartAt(START).windowEndAt(END).boundaryPrecisionSeconds(3600).build());
        entityManager.flush();
        var dailyId = daily.getId();
        entityManager.clear();
        assertThatThrownBy(() -> {
            entityManager.remove(entityManager.find(DailyAnalysisResult.class, dailyId));
            entityManager.flush();
        }).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    private AnalysisRun persistRun(AnalysisType type, boolean withDeadline) {
        var site = entityManager.createQuery("from LandingSite where siteCode = :code", LandingSite.class)
                .setParameter("code", "Site01").getSingleResult();
        var run = AnalysisRun.builder().landingSite(site).analysisType(type)
                .executionStatus(ExecutionStatus.SUCCEEDED).overallResultStatus(ResultStatus.PARTIAL)
                .resultSchemaVersion("1").analysisStartAt(START).analysisEndAt(END).sampleIntervalMinutes(60)
                .missionDueDate(withDeadline ? DATE : null).missionDeadlineAt(withDeadline ? END : null)
                .analysisParameters("{\"observerHeight\":1}").calculationParametersSha256("b".repeat(64))
                .engineVersion("test-only").engineSha256("c".repeat(64))
                .diagnosticMessages(new String[]{"DEM coverage incomplete"})
                .sourceProvenance("{\"frame\":\"MOON_ME\"}").build();
        entityManager.persist(run);
        entityManager.flush();
        return run;
    }

    private DailyAnalysisResult daily(AnalysisRun run) {
        return DailyAnalysisResult.builder().analysisRun(run).analysisDate(DATE)
                .resultStatus(ResultStatus.PARTIAL).evaluatedPeriodStartAt(START).evaluatedPeriodEndAt(END)
                .sunKnownTimeRatio(0.0).earthKnownTimeRatio(0.0).jointKnownTimeRatio(0.0)
                .jointUnknownTimeRatio(1.0).terrainCoverageStatus("PARTIAL")
                .qualityFlags(new String[]{"TERRAIN_INCOMPLETE"}).build();
    }
}
