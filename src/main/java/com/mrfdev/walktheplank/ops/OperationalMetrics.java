package com.mrfdev.walktheplank.ops;

import com.mrfdev.walktheplank.game.SessionEndReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-local operational counters for diagnostics and health reporting.
 *
 * <p>The metrics deliberately contain no player names, UUIDs, commands, or other personal data.
 * They reset whenever the plugin is restarted.</p>
 */
public final class OperationalMetrics {
    private static final int MAX_FAILURE_SUMMARY_LENGTH = 240;

    private final Clock clock;
    private final Instant startedAt;
    private final LongAdder sessionsStarted = new LongAdder();
    private final LongAdder jumpsCompleted = new LongAdder();
    private final LongAdder queueJoins = new LongAdder();
    private final LongAdder restorationsRecovered = new LongAdder();
    private final LongAdder restorationFailures = new LongAdder();
    private final LongAdder rewardStepsCompleted = new LongAdder();
    private final LongAdder rewardFailures = new LongAdder();
    private final LongAdder repositoryFailures = new LongAdder();
    private final LongAdder auditFailures = new LongAdder();
    private final Map<SessionEndReason, LongAdder> sessionEnds = new ConcurrentHashMap<>();
    private final AtomicReference<Failure> lastFailure = new AtomicReference<>();

    public OperationalMetrics() {
        this(Clock.systemUTC());
    }

    OperationalMetrics(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAt = clock.instant();
        for (SessionEndReason reason : SessionEndReason.values()) {
            sessionEnds.put(reason, new LongAdder());
        }
    }

    public void recordSessionStarted() {
        sessionsStarted.increment();
    }

    public void recordJump() {
        jumpsCompleted.increment();
    }

    public void recordSessionEnded(SessionEndReason reason) {
        Objects.requireNonNull(reason, "reason");
        sessionEnds.get(reason).increment();
    }

    public void recordQueueJoin() {
        queueJoins.increment();
    }

    public void recordRestorationRecovered() {
        restorationsRecovered.increment();
    }

    public void recordRestorationsRecovered(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        restorationsRecovered.add(count);
    }

    public void recordRestorationFailure(Throwable failure) {
        restorationFailures.increment();
        recordFailure("restoration", failure);
    }

    public void recordRestorationFailures(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        if (count == 0) {
            return;
        }
        restorationFailures.add(count);
        lastFailure.set(new Failure(
                clock.instant(),
                "restoration",
                count + " restoration record(s) failed during retry"));
    }

    public void recordRewardStepCompleted() {
        rewardStepsCompleted.increment();
    }

    public void recordRewardFailure(Throwable failure) {
        rewardFailures.increment();
        recordFailure("reward", failure);
    }

    public void recordRewardFailure() {
        rewardFailures.increment();
    }

    public void recordRepositoryFailure(Throwable failure) {
        repositoryFailures.increment();
        recordFailure("repository", failure);
    }

    public void recordAuditFailure(Throwable failure) {
        auditFailures.increment();
        recordFailure("audit", failure);
    }

    public void recordFailure(String subsystem, Throwable failure) {
        Objects.requireNonNull(subsystem, "subsystem");
        Objects.requireNonNull(failure, "failure");
        // Throwable messages can contain SQL, paths, player data, or trusted command text.
        // Full details belong in the normal server log, not copy/paste-safe health output.
        String summary = failure.getClass().getSimpleName();
        lastFailure.set(new Failure(
                clock.instant(),
                sanitize(subsystem, 48),
                sanitize(summary, MAX_FAILURE_SUMMARY_LENGTH)));
    }

    public Snapshot snapshot() {
        EnumMap<SessionEndReason, Long> ended = new EnumMap<>(SessionEndReason.class);
        sessionEnds.forEach((reason, counter) -> ended.put(reason, counter.sum()));
        return new Snapshot(
                startedAt,
                Duration.between(startedAt, clock.instant()),
                sessionsStarted.sum(),
                jumpsCompleted.sum(),
                queueJoins.sum(),
                restorationsRecovered.sum(),
                restorationFailures.sum(),
                rewardStepsCompleted.sum(),
                rewardFailures.sum(),
                repositoryFailures.sum(),
                auditFailures.sum(),
                Collections.unmodifiableMap(ended),
                lastFailure.get());
    }

    private static String sanitize(String input, int maximumLength) {
        String sanitized = input
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .strip();
        if (sanitized.length() <= maximumLength) {
            return sanitized;
        }
        return sanitized.substring(0, maximumLength - 1) + "\u2026";
    }

    public record Failure(Instant occurredAt, String subsystem, String summary) {
        public Failure {
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(subsystem, "subsystem");
            Objects.requireNonNull(summary, "summary");
        }
    }

    public record Snapshot(
            Instant startedAt,
            Duration uptime,
            long sessionsStarted,
            long jumpsCompleted,
            long queueJoins,
            long restorationsRecovered,
            long restorationFailures,
            long rewardStepsCompleted,
            long rewardFailures,
            long repositoryFailures,
            long auditFailures,
            Map<SessionEndReason, Long> sessionEnds,
            Failure lastFailure) {
        public Snapshot {
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(uptime, "uptime");
            sessionEnds = Map.copyOf(sessionEnds);
        }

        public long sessionsEnded() {
            return sessionEnds.values().stream().mapToLong(Long::longValue).sum();
        }

        public boolean degraded() {
            return restorationFailures > 0
                    || rewardFailures > 0
                    || repositoryFailures > 0
                    || auditFailures > 0;
        }
    }
}
