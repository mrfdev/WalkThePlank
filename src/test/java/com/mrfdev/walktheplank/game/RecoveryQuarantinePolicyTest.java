package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecoveryQuarantinePolicyTest {
    @Test
    void activeWriteAheadEvidenceDoesNotQuarantineRunnerMovement() {
        assertFalse(RecoveryQuarantinePolicy.requiresQuarantine(true, true, false));
    }

    @Test
    void orphanedEvidenceAndOwnershipLookupsRemainQuarantined() {
        assertTrue(RecoveryQuarantinePolicy.requiresQuarantine(false, true, false));
        assertTrue(RecoveryQuarantinePolicy.requiresQuarantine(false, false, true));
        assertTrue(RecoveryQuarantinePolicy.requiresQuarantine(false, true, true));
    }

    @Test
    void unaffectedPlayerIsNotQuarantined() {
        assertFalse(RecoveryQuarantinePolicy.requiresQuarantine(false, false, false));
    }
}
