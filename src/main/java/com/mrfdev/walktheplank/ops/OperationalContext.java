package com.mrfdev.walktheplank.ops;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Shared, failure-contained operational telemetry used by runtime services. */
public final class OperationalContext {
    private final OperationalMetrics metrics;
    private final StructuredAuditLog auditLog;
    private final Logger logger;
    private final AtomicBoolean auditFailureReported = new AtomicBoolean();

    public OperationalContext(
            OperationalMetrics metrics,
            StructuredAuditLog auditLog,
            Logger logger) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public OperationalMetrics metrics() {
        return metrics;
    }

    /**
     * Appends a safe event. An audit-disk failure degrades diagnostics but does not mutate gameplay
     * state or recursively attempt another audit write.
     */
    public void audit(String event, UUID playerId, String arenaId, Map<String, ?> fields) {
        try {
            auditLog.record(event, playerId, arenaId, fields);
            auditFailureReported.set(false);
        } catch (IOException failure) {
            metrics.recordAuditFailure(failure);
            if (auditFailureReported.compareAndSet(false, true)) {
                logger.log(Level.SEVERE, "Could not append the WalkThePlank audit log", failure);
            }
        }
    }
}
