package com.mrfdev.walktheplank.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PistonProtectionPolicyTest {
    @Test
    void protectsBaseHeadAndMovedCellsIndependently() {
        assertTrue(PistonProtectionPolicy.shouldCancel(true, false, false));
        assertTrue(PistonProtectionPolicy.shouldCancel(false, true, false));
        assertTrue(PistonProtectionPolicy.shouldCancel(false, false, true));
        assertFalse(PistonProtectionPolicy.shouldCancel(false, false, false));
    }
}
