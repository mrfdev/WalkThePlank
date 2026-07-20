package com.mrfdev.walktheplank.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PlayerStatsTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("74e6a8f9-7d62-43eb-a473-55e4f783733e");

    @Test
    void reportsTheSameBoundedPercentileUsedByChatAndTheMenu() {
        assertEquals(1, stats(1, 100).percentile());
        assertEquals(34, stats(1, 3).percentile());
        assertEquals(50, stats(50, 100).percentile());
        assertEquals(100, stats(100, 100).percentile());
    }

    private static PlayerStats stats(int rank, int total) {
        return new PlayerStats(1L, PLAYER_ID, "Tester", 42, rank, total);
    }
}
