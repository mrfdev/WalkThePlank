package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
