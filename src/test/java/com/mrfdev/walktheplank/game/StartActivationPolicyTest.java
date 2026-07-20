package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class StartActivationPolicyTest {
    @Test
    void permitsTheExactEligiblePendingStart() {
        assertTrue(StartActivationPolicy.mayActivate(
                false, true, false, true, true, true, true, true, true, false));
    }

    @Test
    void rejectsStalePendingRecordsAndDynamicEligibilityChanges() {
        assertFalse(StartActivationPolicy.mayActivate(
                false, false, false, true, true, true, true, true, true, false));
        assertFalse(StartActivationPolicy.mayActivate(
                false, true, false, true, true, true, false, true, true, false));
        assertFalse(StartActivationPolicy.mayActivate(
                false, true, false, true, true, true, true, false, true, false));
        assertFalse(StartActivationPolicy.mayActivate(
                false, true, false, true, true, true, true, true, true, true));
        assertFalse(StartActivationPolicy.mayActivate(
                true, true, false, true, true, true, true, true, true, false));
        assertFalse(StartActivationPolicy.mayActivate(
                false, true, false, false, true, true, true, true, true, false));
        assertFalse(StartActivationPolicy.mayActivate(
                false, true, false, true, true, true, true, true, false, false));
        assertFalse(StartActivationPolicy.mayActivate(
                false, true, false, true, false, true, true, true, true, false));
    }

    @Test
    void identifiesTheExactFailedActivationCondition() {
        assertEquals(
                StartActivationPolicy.Rejection.SHUTTING_DOWN,
                StartActivationPolicy.evaluate(
                        true, true, false, true, true, true, true, true, true, false));
        assertEquals(
                StartActivationPolicy.Rejection.PENDING_START_REPLACED,
                StartActivationPolicy.evaluate(
                        false, false, false, true, true, true, true, true, true, false));
        assertEquals(
                StartActivationPolicy.Rejection.SESSION_ALREADY_ACTIVE,
                StartActivationPolicy.evaluate(
                        false, true, true, true, true, true, true, true, true, false));
        assertEquals(
                StartActivationPolicy.Rejection.ARENA_LEASE_LOST,
                StartActivationPolicy.evaluate(
                        false, true, false, false, true, true, true, true, true, false));
        assertEquals(
                StartActivationPolicy.Rejection.PENDING_START_CANCELLED,
                StartActivationPolicy.evaluate(
                        false, true, false, true, false, true, true, true, true, false));
        assertEquals(
                StartActivationPolicy.Rejection.PLAYER_OFFLINE,
                StartActivationPolicy.evaluate(
                        false, true, false, true, true, false, true, true, true, false));
        assertEquals(
                StartActivationPolicy.Rejection.PLAY_PERMISSION_REVOKED,
                StartActivationPolicy.evaluate(
                        false, true, false, true, true, true, false, true, true, false));
        assertEquals(
                StartActivationPolicy.Rejection.UNSUPPORTED_GAME_MODE,
                StartActivationPolicy.evaluate(
                        false, true, false, true, true, true, true, false, true, false));
        assertEquals(
                StartActivationPolicy.Rejection.MOVEMENT_STATE_CHANGED,
                StartActivationPolicy.evaluate(
                        false, true, false, true, true, true, true, true, false, false));
        assertEquals(
                StartActivationPolicy.Rejection.MOVEMENT_EFFECT_ADDED,
                StartActivationPolicy.evaluate(
                        false, true, false, true, true, true, true, true, true, true));
        assertEquals(
                StartActivationPolicy.Rejection.NONE,
                StartActivationPolicy.evaluate(
                        false, true, false, true, true, true, true, true, true, false));
    }
}
