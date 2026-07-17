package com.mrfdev.walktheplank.database;

import java.time.Instant;

/** Process-local failure metadata that deliberately excludes SQL, paths, and exception messages. */
public record SanitizedDatabaseFailure(
        Instant occurredAt,
        String category,
        String exceptionType) {

    public SanitizedDatabaseFailure {
        occurredAt = PersistenceValidation.instant(occurredAt, "occurredAt");
        category = PersistenceValidation.text(category, "category", 32);
        exceptionType = PersistenceValidation.text(exceptionType, "exceptionType", 128);
    }
}
