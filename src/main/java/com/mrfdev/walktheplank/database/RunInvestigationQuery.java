package com.mrfdev.walktheplank.database;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded, UUID-owned filter for retained run evidence.
 *
 * <p>Player names are deliberately absent: they are mutable display metadata and are never an
 * ownership key. A time range is accepted only as a complete, bounded half-open interval.</p>
 */
public record RunInvestigationQuery(
        Optional<RunStatus> status,
        Optional<UUID> playerId,
        Optional<UUID> runId,
        Optional<String> arenaId,
        Optional<UUID> seasonId,
        Optional<String> release,
        Optional<Instant> startedAtInclusive,
        Optional<Instant> startedBeforeExclusive,
        int limit) {
    public static final int MAXIMUM_RESULTS = 100;
    public static final Duration MAXIMUM_TIME_WINDOW = Duration.ofDays(366);

    public RunInvestigationQuery {
        status = Objects.requireNonNull(status, "status");
        playerId = Objects.requireNonNull(playerId, "playerId");
        runId = Objects.requireNonNull(runId, "runId");
        arenaId = Objects.requireNonNull(arenaId, "arenaId")
                .map(value -> PersistenceValidation.text(value, "arenaId", 128));
        seasonId = Objects.requireNonNull(seasonId, "seasonId");
        release = Objects.requireNonNull(release, "release")
                .map(value -> PersistenceValidation.text(value, "release", 128));
        startedAtInclusive = Objects.requireNonNull(startedAtInclusive, "startedAtInclusive")
                .map(value -> PersistenceValidation.instant(value, "startedAtInclusive"));
        startedBeforeExclusive = Objects.requireNonNull(
                        startedBeforeExclusive, "startedBeforeExclusive")
                .map(value -> PersistenceValidation.instant(value, "startedBeforeExclusive"));
        if (limit < 1 || limit > MAXIMUM_RESULTS) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAXIMUM_RESULTS);
        }
        if (startedAtInclusive.isPresent() != startedBeforeExclusive.isPresent()) {
            throw new IllegalArgumentException(
                    "A start-time filter requires both inclusive start and exclusive end");
        }
        if (startedAtInclusive.isPresent()) {
            Instant start = startedAtInclusive.orElseThrow();
            Instant end = startedBeforeExclusive.orElseThrow();
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException(
                        "Start-time filter end must be after its start");
            }
            if (Duration.between(start, end).compareTo(MAXIMUM_TIME_WINDOW) > 0) {
                throw new IllegalArgumentException(
                        "Start-time filter must not exceed "
                                + MAXIMUM_TIME_WINDOW.toDays() + " days");
            }
        }
    }

    public static RunInvestigationQuery all(int limit) {
        return filtered(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                limit);
    }

    public static RunInvestigationQuery forStatus(RunStatus status, int limit) {
        return filtered(
                Optional.of(Objects.requireNonNull(status, "status")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                limit);
    }

    public static RunInvestigationQuery forPlayer(UUID playerId, int limit) {
        return filtered(
                Optional.empty(),
                Optional.of(Objects.requireNonNull(playerId, "playerId")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                limit);
    }

    public static RunInvestigationQuery forRun(UUID runId) {
        return filtered(
                Optional.empty(),
                Optional.empty(),
                Optional.of(Objects.requireNonNull(runId, "runId")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                1);
    }

    public static RunInvestigationQuery forArena(String arenaId, int limit) {
        return filtered(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(arenaId),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                limit);
    }

    public static RunInvestigationQuery forSeason(UUID seasonId, int limit) {
        return filtered(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Objects.requireNonNull(seasonId, "seasonId")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                limit);
    }

    /** Filters by the exact release identity captured when each run started. */
    public static RunInvestigationQuery forRelease(String release, int limit) {
        return filtered(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(release),
                Optional.empty(),
                Optional.empty(),
                limit);
    }

    /** Filters run starts in the complete half-open interval {@code [start, before)}. */
    public static RunInvestigationQuery forStartWindow(
            Instant start,
            Instant before,
            int limit) {
        return filtered(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Objects.requireNonNull(start, "start")),
                Optional.of(Objects.requireNonNull(before, "before")),
                limit);
    }

    public static RunInvestigationQuery filtered(
            Optional<RunStatus> status,
            Optional<UUID> playerId,
            Optional<UUID> runId,
            Optional<String> arenaId,
            Optional<UUID> seasonId,
            Optional<String> release,
            Optional<Instant> startedAtInclusive,
            Optional<Instant> startedBeforeExclusive,
            int limit) {
        return new RunInvestigationQuery(
                status,
                playerId,
                runId,
                arenaId,
                seasonId,
                release,
                startedAtInclusive,
                startedBeforeExclusive,
                limit);
    }
}
