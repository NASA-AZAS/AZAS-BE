package azas.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import azas.domain.analysis.dto.AnalysisRequest;
import azas.domain.analysis.engine.AnalysisEngine;
import azas.domain.analysis.engine.AnalysisEngineException;
import azas.domain.analysis.engine.DateOpportunityEngine;
import azas.global.exception.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AnalysisJobsTests {
    private final AnalysisStore store = mock(AnalysisStore.class);
    private final AnalysisEngine engine = mock(AnalysisEngine.class);
    private final DateOpportunityEngine opportunities = mock(DateOpportunityEngine.class);
    private final AnalysisRequest request = new AnalysisRequest(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1));

    @Test void allowsOneWorkerAndTwoWaitingJobsAndRejectsBeforeLoadingMoreTerrain() throws Exception {
        when(store.create(anyLong(), any(), any())).thenAnswer(call -> prepared(120));
        when(store.start(any())).thenReturn(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(engine.analyze(any())).thenAnswer(call -> {
            entered.countDown();

            try { release.await(); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AnalysisEngineException("ANALYSIS_INTERRUPTED", exception);
            }

            return new AnalysisEngine.Output("PARTIAL", List.of(), List.of(), List.of());
        });
        var jobs = new AnalysisJobs(store, engine, opportunities);

        try {
            jobs.submit(1, request);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            jobs.submit(1, request);
            jobs.submit(1, request);
            assertThatThrownBy(() -> jobs.submit(1, request)).isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).errorCode().code()).isEqualTo("ANALYSIS_CAPACITY_EXCEEDED"));
            verify(store, times(3)).create(anyLong(), any(), any());
            verify(engine, times(1)).analyze(any());
        } finally { release.countDown(); jobs.stop(); }
    }

    @Test void deadlineInterruptsABlockedCallAndRecordsTimeout() throws Exception {
        when(store.create(anyLong(), any(), any())).thenReturn(prepared(2));
        when(store.start(any())).thenReturn(true);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(engine.analyze(any())).thenAnswer(call -> {
            entered.countDown();

            try { new CountDownLatch(1).await(); }
            catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new AnalysisEngineException("ANALYSIS_INTERRUPTED", exception);
            }

            throw new AssertionError("Expected deadline cancellation");
        });
        var jobs = new AnalysisJobs(store, engine, opportunities);

        try {
            jobs.submit(1, request);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            verify(store, timeout(3000)).fail(any(), eq("ANALYSIS_TIMEOUT"));
            verify(store, never()).complete(any(), any());
        } finally { jobs.stop(); }
    }

    @Test void failedValidationReleasesAdmissionForLaterRequests() {
        when(store.create(anyLong(), any(), any())).thenThrow(new IllegalArgumentException("bad input"));
        var jobs = new AnalysisJobs(store, engine, opportunities);

        try {
            for (int i = 0; i < 5; i++) assertThatThrownBy(() -> jobs.submit(1, request)).hasMessage("bad input");
            verify(store, times(5)).create(anyLong(), any(), any());
        } finally { jobs.stop(); }
    }

    @Test void trajectoryRequestsShareAdmissionAndTimeoutWithoutStartingMoreWorkers() throws Exception {
        when(store.create(anyLong(), any(), any())).thenAnswer(call -> prepared(120));
        when(store.start(any())).thenReturn(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(engine.analyze(any())).thenAnswer(call -> {
            entered.countDown(); release.await();
            return new AnalysisEngine.Output("PARTIAL", List.of(), List.of(), List.of());
        });
        var jobs = new AnalysisJobs(store, engine, opportunities);
        try {
            jobs.submit(1, request);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            jobs.submit(1, request); jobs.submit(1, request);
            var prepare = mock(java.util.function.Supplier.class);
            assertThatThrownBy(() -> jobs.compute(prepare)).isInstanceOf(BusinessException.class);
            verifyNoInteractions(prepare);
        } finally { release.countDown(); jobs.stop(); }

        var timedJobs = new AnalysisJobs(store, engine, opportunities);
        var interrupted = new CountDownLatch(1);
        var reason = new java.util.concurrent.atomic.AtomicReference<String>();
        try {
            assertThatThrownBy(() -> timedJobs.compute(() -> new AnalysisJobs.Work<>(Instant.now().plusMillis(200), () -> {
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException exception) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return false;
            }, reason::set))).isInstanceOf(AnalysisEngineException.class).hasMessage("ANALYSIS_TIMEOUT");
            assertThat(reason.get()).isEqualTo("ANALYSIS_TIMEOUT");
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(timedJobs.compute(() -> new AnalysisJobs.Work<>(Instant.now().plusSeconds(5), () -> true, reason::set))).isTrue();
        } finally { timedJobs.stop(); }
    }

    private AnalysisStore.Prepared prepared(int expiresInSeconds) {
        return new AnalysisStore.Prepared(UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(expiresInSeconds),
                new AnalysisEngine.Input("test", 0, 0, 0, 1, "test", 1737400, false, "a".repeat(64),
                        List.of(), Instant.EPOCH, Instant.EPOCH.plusSeconds(86400), 3600));
    }
}
