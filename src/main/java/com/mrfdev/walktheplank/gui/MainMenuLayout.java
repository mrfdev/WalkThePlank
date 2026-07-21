package com.mrfdev.walktheplank.gui;

import java.util.List;

final class MainMenuLayout {
    static final int SIZE = 54;
    static final int TUTORIAL_SLOT = 20;
    static final int PLAY_SLOT = 22;
    static final int SCOREBOARD_SLOT = 24;
    static final int STATUS_SLOT = 31;
    static final int PLAYER_STATS_SLOT = 45;
    static final int BACK_SLOT = 52;
    static final int CLOSE_SLOT = 53;
    static final List<Integer> BORDER_SLOTS = List.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 17,
            18, 26,
            27, 35,
            36, 44,
            45, 46, 47, 48, 49, 50, 51, 52, 53);

    private MainMenuLayout() {
    }
}
