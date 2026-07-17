package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class RewardCompletionBarrierTest {
    private static final UUID PLAYER = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID FIRST_PLAN = UUID.fromString("70000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_PLAN = UUID.fromString("70000000-0000-0000-0000-000000000003");

    @Test
    void exactPlanOwnsBarrierUntilExactTerminalClear() {
        RewardCompletionBarrier barrier = new RewardCompletionBarrier();

        assertTrue(barrier.begin(PLAYER, FIRST_PLAN));
        assertTrue(barrier.begin(PLAYER, FIRST_PLAN));
        assertTrue(barrier.isBlocked(PLAYER));
        assertFalse(barrier.begin(PLAYER, OTHER_PLAN));
        assertFalse(barrier.clear(PLAYER, OTHER_PLAN));
        assertTrue(barrier.isBlocked(PLAYER));
        assertTrue(barrier.clear(PLAYER, FIRST_PLAN));
        assertFalse(barrier.isBlocked(PLAYER));
    }

    @Test
    void newProcessStateStartsWithoutStrandedBarriers() {
        RewardCompletionBarrier previousProcess = new RewardCompletionBarrier();
        assertTrue(previousProcess.begin(PLAYER, FIRST_PLAN));

        RewardCompletionBarrier restartedProcess = new RewardCompletionBarrier();
        assertFalse(restartedProcess.isBlocked(PLAYER));
    }
}
