package com.mrfdev.walktheplank.config;

/** Rejects database modes that could otherwise redirect score writes away from SQLite. */
final class DatabaseBackendPolicy {
    private DatabaseBackendPolicy() {
    }

    static void requireSQLite(String configuredType, boolean legacyMySqlEnabled) {
        if (legacyMySqlEnabled || "MYSQL".equalsIgnoreCase(configuredType)) {
            throw new IllegalArgumentException(
                    "MySQL score storage is not supported by this standalone build; "
                            + "set mysql.enabled to false, set database.type to SQLITE, "
                            + "and migrate scores into database.sqlite.file before starting");
        }
        if (configuredType != null && !"SQLITE".equalsIgnoreCase(configuredType)) {
            throw new IllegalArgumentException(
                    "database.type must be SQLITE; unsupported value '" + configuredType + "'");
        }
    }
}
