package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GameManagerArenaAvailabilityContractTest {
    @Test
    void durableStartAbandonmentClearsItsMarkerBeforeAvailabilityIsRebuilt() {
        Set<String> unresolvedArenas = new HashSet<>(Set.of("main"));
        int[] availableArenas = {0};

        GameManager.publishAfterClearingPendingAbandonment(
                () -> unresolvedArenas.remove("main"),
                () -> availableArenas[0] =
                        unresolvedArenas.contains("main") ? 0 : 1);

        assertEquals(Set.of(), unresolvedArenas);
        assertEquals(1, availableArenas[0]);
    }
}
