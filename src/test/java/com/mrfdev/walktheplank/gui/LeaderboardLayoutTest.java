package com.mrfdev.walktheplank.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class LeaderboardLayoutTest {
    @Test
    void reservesFourOpenRowsForTwentyEightEntries() {
        assertEquals(54, LeaderboardLayout.SIZE);
        assertEquals(LeaderboardLayout.PAGE_SIZE, LeaderboardLayout.ENTRY_SLOTS.size());
        assertEquals(
                LeaderboardLayout.ENTRY_SLOTS.size(),
                new HashSet<>(LeaderboardLayout.ENTRY_SLOTS).size());
        assertTrue(LeaderboardLayout.ENTRY_SLOTS.stream()
                .noneMatch(LeaderboardLayout.BORDER_SLOTS::contains));
    }

    @Test
    void navigationAndSelectorsStayOnTheBottomFrame() {
        Set<Integer> controls = Set.of(
                LeaderboardLayout.BACK_SLOT,
                LeaderboardLayout.PREVIOUS_SLOT,
                LeaderboardLayout.CLASSIC_SLOT,
                LeaderboardLayout.SEASON_SLOT,
                LeaderboardLayout.COMBO_SLOT,
                LeaderboardLayout.FLAWLESS_SLOT,
                LeaderboardLayout.NEXT_SLOT,
                LeaderboardLayout.CLOSE_SLOT);

        assertEquals(8, controls.size());
        assertTrue(controls.stream().allMatch(LeaderboardLayout.BORDER_SLOTS::contains));
        assertFalse(controls.stream().anyMatch(LeaderboardLayout.ENTRY_SLOTS::contains));
    }
}
