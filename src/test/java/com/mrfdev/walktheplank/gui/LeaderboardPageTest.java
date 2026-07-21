package com.mrfdev.walktheplank.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrfdev.walktheplank.database.ScoreEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class LeaderboardPageTest {
    @Test
    void clampsPagesAndKeepsDatabaseRanks() {
        List<ScoreEntry> scores = IntStream.rangeClosed(1, 60)
                .mapToObj(rank -> new ScoreEntry(
                        rank,
                        Optional.of(new UUID(0L, rank)),
                        "Player" + rank,
                        1000 - rank,
                        rank,
                        Optional.empty()))
                .toList();

        LeaderboardPage first = LeaderboardPage.of(LeaderboardKind.CLASSIC, scores, 0, 28);
        LeaderboardPage second = LeaderboardPage.of(LeaderboardKind.CLASSIC, scores, 2, 28);
        LeaderboardPage last = LeaderboardPage.of(LeaderboardKind.CLASSIC, scores, 99, 28);

        assertEquals(1, first.pageNumber());
        assertEquals(3, first.pageCount());
        assertEquals(28, first.entries().size());
        assertFalse(first.hasPrevious());
        assertTrue(first.hasNext());
        assertEquals(29, second.entries().getFirst().rank());
        assertEquals(3, last.pageNumber());
        assertEquals(4, last.entries().size());
        assertTrue(last.hasPrevious());
        assertFalse(last.hasNext());
    }

    @Test
    void representsAnEmptyBoardAsOneEmptyPage() {
        LeaderboardPage page = LeaderboardPage.of(LeaderboardKind.COMBO, List.of(), 1, 28);

        assertEquals(1, page.pageNumber());
        assertEquals(1, page.pageCount());
        assertEquals(0, page.totalEntries());
        assertTrue(page.entries().isEmpty());
    }

    @Test
    void rejectsInvalidPageSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LeaderboardPage.of(LeaderboardKind.CLASSIC, List.of(), 1, 0));
    }
}
