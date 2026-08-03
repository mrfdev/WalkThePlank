package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdminTestFastForwardPolicyTest {
    @Test
    void acceptsOnlyCurrentAuthorizedHealthyTestState() {
        assertEquals(
                AdminTestFastForwardPolicy.Rejection.NONE,
                AdminTestFastForwardPolicy.evaluate(eligible()));
        assertTrue(GameManager.adminTestPermissionGranted(true, false));
        assertTrue(GameManager.adminTestPermissionGranted(false, true));
        assertFalse(GameManager.adminTestPermissionGranted(false, false));
    }

    @Test
    void rejectsRevokedPermissionBeforeWorldMutation() {
        AdminTestFastForwardPolicy.State eligible = eligible();
        assertEquals(
                AdminTestFastForwardPolicy.Rejection.ADMIN_PERMISSION_REVOKED,
                AdminTestFastForwardPolicy.evaluate(new AdminTestFastForwardPolicy.State(
                        eligible.pluginEnabled(),
                        eligible.shuttingDown(),
                        eligible.adminTestingEnabled(),
                        eligible.targetScore(),
                        eligible.maximumTargetScore(),
                        eligible.controlCurrent(),
                        eligible.sessionCurrent(),
                        eligible.deadlineExceeded(),
                        eligible.adminTest(),
                        eligible.playerOnline(),
                        eligible.playerDead(),
                        false,
                        eligible.playPermission(),
                        eligible.supportedGameMode(),
                        eligible.arenaLeaseOwned())));
    }

    @Test
    void rejectsTargetWhenReloadLowersConfiguredCap() {
        AdminTestFastForwardPolicy.State eligible = eligible();
        assertEquals(
                AdminTestFastForwardPolicy.Rejection.TARGET_OUT_OF_RANGE,
                AdminTestFastForwardPolicy.evaluate(new AdminTestFastForwardPolicy.State(
                        eligible.pluginEnabled(),
                        eligible.shuttingDown(),
                        eligible.adminTestingEnabled(),
                        eligible.targetScore(),
                        100,
                        eligible.controlCurrent(),
                        eligible.sessionCurrent(),
                        eligible.deadlineExceeded(),
                        eligible.adminTest(),
                        eligible.playerOnline(),
                        eligible.playerDead(),
                        eligible.adminPermission(),
                        eligible.playPermission(),
                        eligible.supportedGameMode(),
                        eligible.arenaLeaseOwned())));
    }

    @Test
    void rejectsAStalledReplayAtItsMonotonicDeadline() {
        AdminTestFastForwardPolicy.State eligible = eligible();
        assertEquals(
                AdminTestFastForwardPolicy.Rejection.TIMEOUT,
                AdminTestFastForwardPolicy.evaluate(new AdminTestFastForwardPolicy.State(
                        eligible.pluginEnabled(),
                        eligible.shuttingDown(),
                        eligible.adminTestingEnabled(),
                        eligible.targetScore(),
                        eligible.maximumTargetScore(),
                        eligible.controlCurrent(),
                        eligible.sessionCurrent(),
                        true,
                        eligible.adminTest(),
                        eligible.playerOnline(),
                        eligible.playerDead(),
                        eligible.adminPermission(),
                        eligible.playPermission(),
                        eligible.supportedGameMode(),
                        eligible.arenaLeaseOwned())));
    }

    private static AdminTestFastForwardPolicy.State eligible() {
        return new AdminTestFastForwardPolicy.State(
                true,
                false,
                true,
                110,
                500,
                true,
                true,
                false,
                true,
                true,
                false,
                true,
                true,
                true,
                true);
    }
}
