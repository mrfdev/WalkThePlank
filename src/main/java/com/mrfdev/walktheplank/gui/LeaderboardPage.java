package com.mrfdev.walktheplank.gui;

import com.mrfdev.walktheplank.database.ScoreEntry;
import java.util.List;
import java.util.Objects;

record LeaderboardPage(
        LeaderboardKind kind,
        int pageNumber,
        int pageCount,
        int totalEntries,
        List<ScoreEntry> entries) {

    LeaderboardPage {
        Objects.requireNonNull(kind, "kind");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (pageNumber < 1 || pageCount < 1 || pageNumber > pageCount || totalEntries < 0) {
            throw new IllegalArgumentException("Invalid leaderboard page bounds");
        }
    }

    static LeaderboardPage of(
            LeaderboardKind kind,
            List<ScoreEntry> orderedScores,
            int requestedPage,
            int pageSize) {
        Objects.requireNonNull(orderedScores, "orderedScores");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        int pageCount = Math.max(1, (orderedScores.size() + pageSize - 1) / pageSize);
        int pageNumber = Math.max(1, Math.min(requestedPage, pageCount));
        int from = Math.min((pageNumber - 1) * pageSize, orderedScores.size());
        int to = Math.min(from + pageSize, orderedScores.size());
        return new LeaderboardPage(
                kind,
                pageNumber,
                pageCount,
                orderedScores.size(),
                orderedScores.subList(from, to));
    }

    boolean hasPrevious() {
        return pageNumber > 1;
    }

    boolean hasNext() {
        return pageNumber < pageCount;
    }
}
