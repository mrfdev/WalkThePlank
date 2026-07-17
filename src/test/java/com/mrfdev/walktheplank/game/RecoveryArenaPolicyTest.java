package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoveryArenaPolicyTest {
    @Test
    void pendingOwnershipBlocksOnlyItsArenas() {
        assertEquals(
                Set.of("main"),
                RecoveryArenaPolicy.unavailableArenaIds(
                        List.of("main", "second"), List.of("main"), 0));
    }

    @Test
    void unreadableOwnershipFailsClosedForEveryArena() {
        assertEquals(
                Set.of("main", "second"),
                RecoveryArenaPolicy.unavailableArenaIds(
                        List.of("main", "second"), List.of(), 1));
    }
}
