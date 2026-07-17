package com.mrfdev.walktheplank.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConfigurationOperationGuardTest {
    @Test
    void arenaEditRequiresMatchingGenerationAuthorizationAndIdleState() {
        assertTrue(WalkCommand.arenaEditStateCurrent(7L, 7L, true, true));
        assertFalse(WalkCommand.arenaEditStateCurrent(7L, 8L, true, true));
        assertFalse(WalkCommand.arenaEditStateCurrent(7L, 7L, false, true));
        assertFalse(WalkCommand.arenaEditStateCurrent(7L, 7L, true, false));
    }
}
