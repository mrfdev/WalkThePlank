package com.mrfdev.walktheplank.database;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable season metadata. */
public record Season(
        UUID id,
        String name,
        SeasonStatus status,
        Instant createdAt,
        Instant transitionedAt,
        Optional<Instant> activatedAt,
        Optional<Instant> closedAt,
        Optional<Instant> archivedAt) {

    public Season {
        Objects.requireNonNull(id, "id");
        name = PersistenceValidation.text(name, "name", 80);
        Objects.requireNonNull(status, "status");
        createdAt = PersistenceValidation.instant(createdAt, "createdAt");
        transitionedAt = PersistenceValidation.instant(transitionedAt, "transitionedAt");
        activatedAt = PersistenceValidation.optionalInstant(activatedAt, "activatedAt");
        closedAt = PersistenceValidation.optionalInstant(closedAt, "closedAt");
        archivedAt = PersistenceValidation.optionalInstant(archivedAt, "archivedAt");
        requireNotBefore(transitionedAt, createdAt, "transitionedAt");
        if (activatedAt.isPresent()) {
            requireNotBefore(activatedAt.orElseThrow(), createdAt, "activatedAt");
        }
        if (closedAt.isPresent()) {
            requireNotBefore(closedAt.orElseThrow(), createdAt, "closedAt");
        }
        if (archivedAt.isPresent()) {
            requireNotBefore(archivedAt.orElseThrow(), createdAt, "archivedAt");
        }
        if (status == SeasonStatus.PLANNED
                && (activatedAt.isPresent() || closedAt.isPresent() || archivedAt.isPresent())) {
            throw new IllegalArgumentException("A planned season cannot have lifecycle timestamps");
        }
        if (status == SeasonStatus.ACTIVE
                && (activatedAt.isEmpty() || closedAt.isPresent() || archivedAt.isPresent())) {
            throw new IllegalArgumentException(
                    "An active season requires activatedAt and cannot have closedAt");
        }
        if (status == SeasonStatus.CLOSED
                && (closedAt.isEmpty() || archivedAt.isPresent())) {
            throw new IllegalArgumentException(
                    "A closed season requires closedAt and cannot have archivedAt");
        }
        if (status == SeasonStatus.ARCHIVED
                && (closedAt.isEmpty() || archivedAt.isEmpty())) {
            throw new IllegalArgumentException(
                    "An archived season requires closedAt and archivedAt");
        }
        if (activatedAt.isPresent()
                && closedAt.isPresent()
                && closedAt.orElseThrow().isBefore(activatedAt.orElseThrow())) {
            throw new IllegalArgumentException("closedAt must not be before activatedAt");
        }
        if (closedAt.isPresent()
                && archivedAt.isPresent()
                && archivedAt.orElseThrow().isBefore(closedAt.orElseThrow())) {
            throw new IllegalArgumentException("archivedAt must not be before closedAt");
        }
    }

    private static void requireNotBefore(Instant value, Instant lowerBound, String name) {
        if (value.isBefore(lowerBound)) {
            throw new IllegalArgumentException(name + " must not be before createdAt");
        }
    }
}
