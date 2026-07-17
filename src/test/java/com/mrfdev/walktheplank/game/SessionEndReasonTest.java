package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionEndReasonTest {
    @Test
    void onlyNormalPlayerCompletionIsRewardEligible() {
        assertAll(
                () -> assertTrue(SessionEndReason.FALL.rewardsEligible()),
                () -> assertTrue(SessionEndReason.LEAVE.rewardsEligible()),
                () -> assertFalse(SessionEndReason.TELEPORT.rewardsEligible()),
                () -> assertFalse(SessionEndReason.TIMEOUT.rewardsEligible()),
                () -> assertFalse(SessionEndReason.QUIT.rewardsEligible()),
                () -> assertFalse(SessionEndReason.DEATH.rewardsEligible()),
                () -> assertFalse(SessionEndReason.GAME_MODE_CHANGE.rewardsEligible()),
                () -> assertFalse(SessionEndReason.RELOAD.rewardsEligible()),
                () -> assertFalse(SessionEndReason.SHUTDOWN.rewardsEligible()),
                () -> assertFalse(SessionEndReason.PERMISSION_REVOKED.rewardsEligible()),
                () -> assertFalse(SessionEndReason.MOVEMENT_MODIFIED.rewardsEligible()),
                () -> assertFalse(SessionEndReason.ADMIN.rewardsEligible()),
                () -> assertFalse(SessionEndReason.ERROR.rewardsEligible()));
    }

    @Test
    void deathDoesNotTeleportButGameModeCleanupReturnsThePlayer() {
        assertAll(
                () -> assertFalse(SessionEndReason.DEATH.returnPlayer()),
                () -> assertTrue(SessionEndReason.GAME_MODE_CHANGE.returnPlayer()),
                () -> assertTrue(SessionEndReason.MOVEMENT_MODIFIED.returnPlayer()),
                () -> assertTrue(SessionEndReason.ADMIN.returnPlayer()));
    }
}
