package com.mrfdev.walktheplank.database;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Connection settings for the standalone SQLite score database. */
public record DatabaseSettings(Path databasePath, Duration busyTimeout) {
    public DatabaseSettings {
        Objects.requireNonNull(databasePath, "databasePath");
        Objects.requireNonNull(busyTimeout, "busyTimeout");
        if (busyTimeout.isNegative() || busyTimeout.isZero()) {
            throw new IllegalArgumentException("busyTimeout must be positive");
        }
        if (busyTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("busyTimeout must not exceed ten minutes");
        }
        databasePath = databasePath.toAbsolutePath().normalize();
    }

    /** Creates SQLite settings with a five-second busy timeout. */
    public static DatabaseSettings sqlite(Path databasePath) {
        return new DatabaseSettings(databasePath, Duration.ofSeconds(5));
    }

    /** Creates SQLite settings with a caller-selected busy timeout. */
    public static DatabaseSettings sqlite(Path databasePath, Duration busyTimeout) {
        return new DatabaseSettings(databasePath, busyTimeout);
    }
}
