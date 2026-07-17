package com.mrfdev.walktheplank.database;

/**
 * Privacy-safe result of a read-only SQLite health probe.
 *
 * <p>The report deliberately contains no filesystem paths, SQL text, player data, reward
 * commands, or exception messages so it can be copied into a support request.</p>
 */
public record DatabaseDoctorReport(
        boolean quickCheckPassed,
        long databaseBytes,
        long walBytes,
        int migrationBackupCount,
        long latencyMillis) {

    public DatabaseDoctorReport {
        if (databaseBytes < 0L || walBytes < 0L) {
            throw new IllegalArgumentException("Database sizes must not be negative");
        }
        if (migrationBackupCount < 0) {
            throw new IllegalArgumentException("Migration backup count must not be negative");
        }
        if (latencyMillis < 0L) {
            throw new IllegalArgumentException("Database probe latency must not be negative");
        }
    }
}
