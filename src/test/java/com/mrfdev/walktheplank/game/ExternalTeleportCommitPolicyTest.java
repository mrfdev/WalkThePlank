package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExternalTeleportCommitPolicyTest {
    private static final UUID WORLD = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void acceptsCommittedDestinationWithinOneCentimeter() {
        assertTrue(ExternalTeleportCommitPolicy.reached(
                WORLD, 10.0, 64.0, -5.0, WORLD, 10.005, 64.0, -5.0));
    }

    @Test
    void rejectsCancelledModifiedCrossWorldAndNonFiniteDestinations() {
        assertFalse(ExternalTeleportCommitPolicy.reached(
                WORLD, 10.0, 64.0, -5.0, WORLD, 10.02, 64.0, -5.0));
        assertFalse(ExternalTeleportCommitPolicy.reached(
                WORLD,
                10.0,
                64.0,
                -5.0,
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                10.0,
                64.0,
                -5.0));
        assertFalse(ExternalTeleportCommitPolicy.reached(
                WORLD, Double.NaN, 64.0, -5.0, WORLD, 10.0, 64.0, -5.0));
    }
}
