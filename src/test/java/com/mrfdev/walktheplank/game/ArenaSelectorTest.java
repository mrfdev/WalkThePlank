package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ArenaSelectorTest {
    @Test
    void roundRobinAndLeastRecentlyUsedRemainFair() {
        ArenaSelector selector = new ArenaSelector(new Random(1L));
        List<String> arenas = List.of("a", "b", "c");

        assertEquals("a", selector.select(arenas, ArenaSelectionPolicy.ROUND_ROBIN, null));
        assertEquals("b", selector.select(arenas, ArenaSelectionPolicy.ROUND_ROBIN, null));
        assertEquals("c", selector.select(arenas, ArenaSelectionPolicy.ROUND_ROBIN, null));
        assertEquals("a", selector.select(List.of("c", "a"), ArenaSelectionPolicy.ROUND_ROBIN, null));

        ArenaSelector lru = new ArenaSelector(new Random(1L));
        assertEquals("a", lru.select(arenas, ArenaSelectionPolicy.LEAST_RECENTLY_USED, null));
        assertEquals("b", lru.select(arenas, ArenaSelectionPolicy.LEAST_RECENTLY_USED, null));
        assertEquals("c", lru.select(arenas, ArenaSelectionPolicy.LEAST_RECENTLY_USED, null));
        assertEquals("a", lru.select(arenas, ArenaSelectionPolicy.LEAST_RECENTLY_USED, null));
    }

    @Test
    void pinnedFallsBackSafelyAndPolicyParsingIsStrict() {
        ArenaSelector selector = new ArenaSelector(new Random(2L));
        List<String> arenas = List.of("north", "south");

        assertEquals("south", selector.select(arenas, ArenaSelectionPolicy.PINNED, "south"));
        assertEquals("north", selector.select(arenas, ArenaSelectionPolicy.PINNED, "missing"));
        assertEquals(ArenaSelectionPolicy.LEAST_RECENTLY_USED,
                ArenaSelectionPolicy.parse("least-recently-used"));
        assertThrows(IllegalArgumentException.class, () -> ArenaSelectionPolicy.parse("fastest"));
    }

    @Test
    void duplicateAvailableArenaIdsAreRejected() {
        ArenaSelector selector = new ArenaSelector(new Random(3L));
        assertThrows(
                IllegalArgumentException.class,
                () -> selector.select(
                        List.of("main", "main"),
                        ArenaSelectionPolicy.RANDOM,
                        null));
    }
}
