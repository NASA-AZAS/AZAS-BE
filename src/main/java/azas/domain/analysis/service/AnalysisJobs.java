package azas.domain.analysis.service;

import azas.domain.analysis.dto.AnalysisRequest;
import azas.domain.analysis.dto.DateOpportunityRequest;
import azas.domain.analysis.engine.AnalysisEngine;
import azas.domain.analysis.engine.AnalysisEngineException;
import azas.domain.analysis.engine.DateOpportunityEngine;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.global.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class AnalysisJobs {
    private static final Logger LOG = LoggerFactory.getLogger(AnalysisJobs.class);
    private final AnalysisStore store;
    private final AnalysisEngine engine;
    private final DateOpportunityEngine opportunities;
    private final Semaphore admission = new Semaphore(3);
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), Thread.ofPlatform().name("analysis-worker-", 0).factory());
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("analysis-deadlines").factory());

    public AnalysisJobs(AnalysisStore store, AnalysisEngine engine, DateOpportunityEngine opportunities) {
        this.store = store; this.engine = engine;
        this.opportunities = opportunities;
        timer.setRemoveOnCancelPolicy(true);
    }

    @PostConstruct
    void startExpirySweep() {
        timer.scheduleWithFixedDelay(() -> {
            try {
                store.expire();
            } catch (RuntimeException exception) {
                LOG.error("Analysis expiry sweep failed", exception);
            }
        }, 0, 15, TimeUnit.SECONDS);
    }

    public UUID submit(long siteId, AnalysisRequest request) {
        return schedule(() -> store.create(siteId, request, engine.identity()),
                prepared -> store.complete(prepared, engine.analyze(prepared.input())));
    }

    public UUID submitOpportunities(long siteId, DateOpportunityRequest request) {
        return schedule(() -> store.createOpportunities(siteId, request, opportunities.identity()),
                prepared -> store.completeOpportunities(prepared,
                        opportunities.analyze(siteId, prepared.input(), request.durationSeconds())));
    }

    private UUID schedule(Supplier<AnalysisStore.Prepared> prepare, Predicate<AnalysisStore.Prepared> calculateAndSave) {
        if (!admission.tryAcquire()) {
            throw new BusinessException(AnalysisErrorCode.BUSY);
        }

        AnalysisStore.Prepared prepared;

        try {
            prepared = prepare.get();
        } catch (RuntimeException exception) {
            admission.release();
            throw exception;
        }
        String requestId = MDC.get("requestId");
        AtomicReference<ScheduledFuture<?>> deadlineReference = new AtomicReference<>();
        var task = new FutureTask<Void>(() -> {
            try (var ignored = MDC.putCloseable("requestId", requestId)) {
                execute(prepared, calculateAndSave);
            }

            return null;
        }) {
            @Override protected void done() {
                admission.release();
                worker.remove(this);
                var deadline = deadlineReference.get();

                if (deadline != null) {
                    deadline.cancel(false);
                }
            }
        };

        try {
            worker.execute(task);
        } catch (RejectedExecutionException exception) {
            task.cancel(false);
            store.fail(prepared, "ANALYSIS_CAPACITY_EXCEEDED");
            throw new BusinessException(AnalysisErrorCode.BUSY);
        }

        var deadline = timer.schedule(() -> {
            task.cancel(true);

            try {
                store.fail(prepared, "ANALYSIS_TIMEOUT");
            } catch (RuntimeException exception) {
                LOG.error("Analysis deadline persistence failed", exception);
            }
        }, Math.max(0, Duration.between(Instant.now(), prepared.expiresAt()).toMillis()) + 1, TimeUnit.MILLISECONDS);
        deadlineReference.set(deadline);

        if (task.isDone()) {
            deadline.cancel(false);
        }

        return prepared.id();
    }

    private void execute(AnalysisStore.Prepared prepared, Predicate<AnalysisStore.Prepared> calculateAndSave) {
        try {
            if (!store.start(prepared)) return;
            LOG.atInfo().addKeyValue("analysisRunId", prepared.id()).log("Analysis started");
            boolean saved = calculateAndSave.test(prepared);
            LOG.atInfo().addKeyValue("analysisRunId", prepared.id()).addKeyValue("saved", saved).log("Analysis finished");
        } catch (RuntimeException exception) {
            String code = exception instanceof AnalysisEngineException failure ? failure.code() : "ANALYSIS_FAILED";

            if (Thread.currentThread().isInterrupted()) {
                code = "ANALYSIS_INTERRUPTED";
            }

            // JDBC must be able to acquire a connection to record interruption before restoring the flag.
            boolean interrupted = Thread.interrupted();

            try {
                store.fail(prepared, code);
            } catch (RuntimeException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            LOG.atError().addKeyValue("analysisRunId", prepared.id()).addKeyValue("failureCode", code)
                    .setCause(exception).log("Analysis failed");
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
        timer.shutdownNow();

        try {
            worker.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
