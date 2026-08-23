package com.mrfdev.walktheplank.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Shared, failure-contained operational telemetry used by runtime services. */
public final class OperationalContext {
    private final OperationalMetrics metrics;
    private final StructuredAuditLog auditLog;
    private final OperationsIoWorker ioWorker;
    private final Logger logger;
    private final AtomicBoolean auditFailureReported = new AtomicBoolean();

    public OperationalContext(
            OperationalMetrics metrics,
            StructuredAuditLog auditLog,
            Logger logger) {
        this(metrics, auditLog, new OperationsIoWorker(), logger);
    }

    /**
     * Acquires exclusive operations-writer ownership before opening or inspecting the audit path.
     */
    public static OperationalContext open(
            Path pluginDataDirectory,
            String releaseIdentity,
            Logger logger) throws IOException {
        Path checkedDirectory =
                Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        OperationsIoWorker worker = new OperationsIoWorker(checkedDirectory);
        try {
            StructuredAuditLog auditLog =
                    StructuredAuditLog.open(checkedDirectory, releaseIdentity);
            return new OperationalContext(
                    new OperationalMetrics(),
                    auditLog,
                    worker,
                    logger);
        } catch (IOException | RuntimeException | LinkageError failure) {
            if (!worker.close(Duration.ofSeconds(5L))) {
                failure.addSuppressed(new IllegalStateException(
                        "Operations writer did not terminate after audit initialization failed"));
            }
            throw failure;
        }
    }

    OperationalContext(
            OperationalMetrics metrics,
            StructuredAuditLog auditLog,
            OperationsIoWorker ioWorker,
            Logger logger) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.ioWorker = Objects.requireNonNull(ioWorker, "ioWorker");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public OperationalMetrics metrics() {
        return metrics;
    }

    /**
     * Enqueues a safe immutable event without performing file I/O on the calling thread.
     *
     * <p>An audit-disk or queue failure degrades diagnostics but does not mutate gameplay state,
     * run the write on the caller, or recursively attempt another audit write.</p>
     */
    public void audit(String event, UUID playerId, String arenaId, Map<String, ?> fields) {
        StructuredAuditLog.PreparedRecord prepared = auditLog.prepare(
                event, playerId, arenaId, fields);
        if (!ioWorker.submitBestEffort(() -> appendAudit(prepared))) {
            reportAuditFailure(new RejectedExecutionException(
                    "WalkThePlank operations queue is full or closing"));
        }
    }

    /**
     * Durably appends an audit event as part of required work already running on the operations
     * writer.
     *
     * <p>This is intentionally rejected on every other thread so callers cannot turn audit I/O
     * into a main-thread fallback.</p>
     */
    public void auditRequiredOnWorker(
            String event,
            UUID playerId,
            String arenaId,
            Map<String, ?> fields) throws IOException {
        if (!ioWorker.ownsCurrentThread()) {
            throw new IllegalStateException(
                    "Required audit append must run on the operations writer");
        }
        try {
            auditLog.record(event, playerId, arenaId, fields);
            auditFailureReported.set(false);
        } catch (IOException | RuntimeException failure) {
            reportAuditFailure(failure);
            throw failure;
        }
    }

    /** Enqueues required operational I/O without blocking or falling back to the caller. */
    public <T> CompletableFuture<T> submitRequired(Callable<T> operation) {
        return ioWorker.submitRequired(operation);
    }

    public OperationsIoWorker.Status ioStatus() {
        return ioWorker.status();
    }

    public StructuredAuditLog.AuditHealth auditHealth() {
        return auditLog.health();
    }

    /** Stops acceptance and drains accepted audit and required operations. */
    public boolean close(Duration timeout) {
        return ioWorker.close(timeout);
    }

    private void appendAudit(StructuredAuditLog.PreparedRecord prepared) {
        try {
            auditLog.append(prepared);
            auditFailureReported.set(false);
        } catch (IOException | RuntimeException failure) {
            reportAuditFailure(failure);
        }
    }

    private void reportAuditFailure(Throwable failure) {
        metrics.recordAuditFailure(failure);
        if (auditFailureReported.compareAndSet(false, true)) {
            logger.log(Level.SEVERE, "Could not append the WalkThePlank audit log", failure);
        }
    }
}
