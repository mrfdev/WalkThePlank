package com.mrfdev.walktheplank.game;

import com.mrfdev.walktheplank.config.ThemeSoundCue;
import java.util.Optional;

/** Deterministic cue precedence keeps milestone/combo landings from stacking sounds. */
final class ThemeSoundPolicy {
    private static final int COMBO_INTERVAL = 5;

    private ThemeSoundPolicy() {
    }

    static ThemeSoundCue landing(boolean milestone, int combo) {
        if (milestone) {
            return ThemeSoundCue.MILESTONE;
        }
        if (combo >= COMBO_INTERVAL && combo % COMBO_INTERVAL == 0) {
            return ThemeSoundCue.COMBO;
        }
        return ThemeSoundCue.LANDING;
    }

    static Optional<ThemeSoundCue> ending(SessionEndReason reason) {
        return switch (reason) {
            case LEAVE -> Optional.of(ThemeSoundCue.FINISH);
            case FALL, TIMEOUT, MOVEMENT_MODIFIED, ERROR ->
                    Optional.of(ThemeSoundCue.FAILURE);
            default -> Optional.empty();
        };
    }
}
