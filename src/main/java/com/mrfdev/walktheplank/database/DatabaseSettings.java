package com.mrfdev.walktheplank.database;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Connection settings for the standalone SQLite score database. */
public record DatabaseSettings(
        Path databasePath,
        Duration busyTimeout,
        int migrationBackupRetention) {

    public static final int DEFAULT_MIGRATION_BACKUP_RETENTION = 5;
    public static final int MINIMUM_MIGRATION_BACKUP_RETENTION = 2;
    public static final int MAXIMUM_MIGRATION_BACKUP_RETENTION = 100;

    public DatabaseSettings {
        Objects.requireNonNull(databasePath, "databasePath");
        Objects.requireNonNull(busyTimeout, "busyTimeout");
        if (busyTimeout.isNegative() || busyTimeout.isZero()) {
            throw new IllegalArgumentException("busyTimeout must be positive");
        }
        if (busyTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("busyTimeout must not exceed ten minutes");
        }
        if (migrationBackupRetention < MINIMUM_MIGRATION_BACKUP_RETENTION
                || migrationBackupRetention > MAXIMUM_MIGRATION_BACKUP_RETENTION) {
            throw new IllegalArgumentException(
                    "migrationBackupRetention must be between "
                            + MINIMUM_MIGRATION_BACKUP_RETENTION + " and "
                            + MAXIMUM_MIGRATION_BACKUP_RETENTION);
        }
        databasePath = databasePath.toAbsolutePath().normalize();
    }

    /** Creates SQLite settings with a five-second busy timeout. */
    public static DatabaseSettings sqlite(Path databasePath) {
        return new DatabaseSettings(
                databasePath,
                Duration.ofSeconds(5),
                DEFAULT_MIGRATION_BACKUP_RETENTION);
    }

    /** Creates SQLite settings with a caller-selected busy timeout. */
    public static DatabaseSettings sqlite(Path databasePath, Duration busyTimeout) {
        return new DatabaseSettings(
                databasePath,
                busyTimeout,
                DEFAULT_MIGRATION_BACKUP_RETENTION);
    }

    /** Creates SQLite settings with an explicit bounded migration-backup policy. */
    public static DatabaseSettings sqlite(
            Path databasePath,
            Duration busyTimeout,
            int migrationBackupRetention) {
        return new DatabaseSettings(databasePath, busyTimeout, migrationBackupRetention);
    }
}
