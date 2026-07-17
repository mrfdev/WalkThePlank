package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameManagerExternalTeleportAttemptTest {
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ATTEMPT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID WORLD_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_WORLD_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");

    @Test
    void refusesCompletionUntilMonitorObservedTheEvent() {
        GameManager.PendingExternalTeleport attempt = attempt();

        assertAll(
                () -> assertFalse(attempt.reached(true, WORLD_ID, 8.0, 70.0, -4.0)),
                () -> assertEquals("monitor_not_observed", attempt.notCommittedReason(true)));
    }

    @Test
    void cancelledAndInvalidOutcomesFailClosed() {
        GameManager.PendingExternalTeleport cancelled = attempt().observe(
                false, WORLD_ID, 8.0, 70.0, -4.0);
        GameManager.PendingExternalTeleport invalid = attempt().observe(
                true, null, Double.NaN, 70.0, -4.0);

        assertAll(
                () -> assertFalse(cancelled.reached(true, WORLD_ID, 8.0, 70.0, -4.0)),
                () -> assertEquals("event_cancelled", cancelled.notCommittedReason(true)),
                () -> assertFalse(invalid.reached(true, WORLD_ID, 8.0, 70.0, -4.0)),
                () -> assertEquals("invalid_destination", invalid.notCommittedReason(true)));
    }

    @Test
    void verifiesTheFinalRedirectedDestinationInsteadOfTheOriginalDestination() {
        GameManager.PendingExternalTeleport redirected = attempt().observe(
                true, OTHER_WORLD_ID, 120.5, 80.0, 30.5);

        assertAll(
                () -> assertFalse(redirected.reached(true, WORLD_ID, 8.0, 70.0, -4.0)),
                () -> assertTrue(redirected.reached(true, OTHER_WORLD_ID, 120.5, 80.0, 30.5)),
                () -> assertFalse(redirected.reached(false, OTHER_WORLD_ID, 120.5, 80.0, 30.5)),
                () -> assertEquals("player_offline", redirected.notCommittedReason(false)));
    }

    @Test
    void attemptTokenPreventsAStaleEventFromObservingAnotherTeleport() {
        GameManager.PendingExternalTeleport attempt = attempt();

        assertAll(
                () -> assertTrue(attempt.matches(ATTEMPT_ID)),
                () -> assertFalse(attempt.matches(UUID.fromString(
                        "50000000-0000-0000-0000-000000000005"))));
    }

    private static GameManager.PendingExternalTeleport attempt() {
        return GameManager.PendingExternalTeleport.awaiting(RUN_ID, ATTEMPT_ID);
    }
}
