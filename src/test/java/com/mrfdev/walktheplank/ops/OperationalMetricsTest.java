package com.mrfdev.walktheplank.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrfdev.walktheplank.game.SessionEndReason;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OperationalMetricsTest {
    @Test
    void reportsCountersWithoutPlayerData() {
        OperationalMetrics metrics = new OperationalMetrics(
                Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC));

        metrics.recordSessionStarted();
        metrics.recordJump();
        metrics.recordJump();
        metrics.recordQueueJoin();
        metrics.recordSessionEnded(SessionEndReason.LEAVE);
        metrics.recordRewardStepCompleted();

        OperationalMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(1, snapshot.sessionsStarted());
        assertEquals(2, snapshot.jumpsCompleted());
        assertEquals(1, snapshot.queueJoins());
        assertEquals(1, snapshot.sessionsEnded());
        assertEquals(1, snapshot.sessionEnds().get(SessionEndReason.LEAVE));
        assertEquals(1, snapshot.rewardStepsCompleted());
        assertEquals(0, snapshot.auditFailures());
        assertFalse(snapshot.degraded());
    }

    @Test
    void recordsSafeBoundedFailureCategoryWithoutThrowableMessage() {
        OperationalMetrics metrics = new OperationalMetrics(
                Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC));

        metrics.recordRestorationFailure(new IllegalStateException("line one\n" + "x".repeat(400)));

        OperationalMetrics.Snapshot snapshot = metrics.snapshot();
        assertTrue(snapshot.degraded());
        assertEquals(1, snapshot.restorationFailures());
        assertEquals("restoration", snapshot.lastFailure().subsystem());
        assertEquals("IllegalStateException", snapshot.lastFailure().summary());
        assertFalse(snapshot.lastFailure().summary().contains("line one"));
        assertTrue(snapshot.lastFailure().summary().length() <= 240);
    }

    @Test
    void recordsAggregateRestorationRetryOutcomes() {
        OperationalMetrics metrics = new OperationalMetrics(
                Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC));

        metrics.recordRestorationsRecovered(4);
        metrics.recordRestorationFailures(2);

        OperationalMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(4, snapshot.restorationsRecovered());
        assertEquals(2, snapshot.restorationFailures());
        assertTrue(snapshot.degraded());
        assertEquals("restoration", snapshot.lastFailure().subsystem());
        assertEquals("2 restoration record(s) failed during retry", snapshot.lastFailure().summary());
        assertThrows(IllegalArgumentException.class, () -> metrics.recordRestorationsRecovered(-1));
        assertThrows(IllegalArgumentException.class, () -> metrics.recordRestorationFailures(-1));
    }
}
