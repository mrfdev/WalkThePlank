package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LandingPolicyTest {
    @Test
    void requiresGroundSupportAndNonAscendingVelocity() {
        assertTrue(LandingPolicy.isGroundedAndNotAscending(true, 0.0));
        assertTrue(LandingPolicy.isGroundedAndNotAscending(true, -0.08));
        assertFalse(LandingPolicy.isGroundedAndNotAscending(false, -0.08));
        assertFalse(LandingPolicy.isGroundedAndNotAscending(true, 0.01));
        assertFalse(LandingPolicy.isGroundedAndNotAscending(true, Double.NaN));
    }
}
