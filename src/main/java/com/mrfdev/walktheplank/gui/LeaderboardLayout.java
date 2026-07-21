package com.mrfdev.walktheplank.gui;

import java.util.List;

final class LeaderboardLayout {
    static final int SIZE = 54;
    static final int PAGE_SIZE = 28;
    static final int BACK_SLOT = 45;
    static final int PREVIOUS_SLOT = 46;
    static final int CLASSIC_SLOT = 48;
    static final int SEASON_SLOT = 49;
    static final int COMBO_SLOT = 50;
    static final int FLAWLESS_SLOT = 51;
    static final int NEXT_SLOT = 52;
    static final int CLOSE_SLOT = 53;
    static final List<Integer> ENTRY_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43);
    static final List<Integer> BORDER_SLOTS = MainMenuLayout.BORDER_SLOTS;

    private LeaderboardLayout() {
    }
}
