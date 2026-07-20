package com.mrfdev.walktheplank.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class MainMenuLayoutTest {
    private static final Set<Integer> ACTION_SLOTS = Set.of(
            MainMenuLayout.TUTORIAL_SLOT,
            MainMenuLayout.PLAY_SLOT,
            MainMenuLayout.SCOREBOARD_SLOT,
            MainMenuLayout.PLAYER_STATS_SLOT,
            MainMenuLayout.BACK_SLOT,
            MainMenuLayout.CLOSE_SLOT);
    private static final Set<Integer> FRAME_ACTION_SLOTS = Set.of(
            MainMenuLayout.PLAYER_STATS_SLOT,
            MainMenuLayout.BACK_SLOT,
            MainMenuLayout.CLOSE_SLOT);

    @Test
    void usesFullHeightOneMbFrameWithOpenCenter() {
        assertEquals(54, MainMenuLayout.SIZE);
        assertEquals(26, MainMenuLayout.BORDER_SLOTS.size());
        assertEquals(
                MainMenuLayout.BORDER_SLOTS.size(),
                new HashSet<>(MainMenuLayout.BORDER_SLOTS).size());

        for (int slot = 0; slot < MainMenuLayout.SIZE; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            boolean expectedBorder = row == 0 || row == 5 || column == 0 || column == 8;
            assertEquals(
                    expectedBorder,
                    MainMenuLayout.BORDER_SLOTS.contains(slot),
                    "unexpected frame state at slot " + slot);
        }

        assertEquals(Set.of(20, 22, 24, 45, 52, 53), ACTION_SLOTS);
        assertTrue(FRAME_ACTION_SLOTS.stream().allMatch(MainMenuLayout.BORDER_SLOTS::contains));
        assertTrue(ACTION_SLOTS.stream()
                .filter(slot -> !FRAME_ACTION_SLOTS.contains(slot))
                .noneMatch(MainMenuLayout.BORDER_SLOTS::contains));
        assertEquals(25, openUnusedCenterSlots().size());
    }

    @Test
    void centerSlotsNotUsedByActionsRemainEmpty() {
        for (int slot : openUnusedCenterSlots()) {
            assertFalse(MainMenuLayout.BORDER_SLOTS.contains(slot));
            assertFalse(ACTION_SLOTS.contains(slot));
        }
    }

    private static List<Integer> openUnusedCenterSlots() {
        return java.util.stream.IntStream.range(0, MainMenuLayout.SIZE)
                .filter(slot -> !MainMenuLayout.BORDER_SLOTS.contains(slot))
                .filter(slot -> !ACTION_SLOTS.contains(slot))
                .boxed()
                .toList();
    }
}
