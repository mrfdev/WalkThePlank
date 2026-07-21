package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrfdev.walktheplank.config.ThemeSoundCue;
import org.junit.jupiter.api.Test;

final class ThemeSoundPolicyTest {
    @Test
    void milestoneWinsAndComboUsesNonStackingFiveJumpCadence() {
        assertEquals(ThemeSoundCue.MILESTONE, ThemeSoundPolicy.landing(true, 5));
        assertEquals(ThemeSoundCue.LANDING, ThemeSoundPolicy.landing(false, 4));
        assertEquals(ThemeSoundCue.COMBO, ThemeSoundPolicy.landing(false, 5));
        assertEquals(ThemeSoundCue.COMBO, ThemeSoundPolicy.landing(false, 15));
        assertEquals(ThemeSoundCue.LANDING, ThemeSoundPolicy.landing(false, 16));
    }

    @Test
    void onlyPlayerFacingTerminalOutcomesProduceCues() {
        assertEquals(
                ThemeSoundCue.FINISH,
                ThemeSoundPolicy.ending(SessionEndReason.LEAVE).orElseThrow());
        assertEquals(
                ThemeSoundCue.FAILURE,
                ThemeSoundPolicy.ending(SessionEndReason.FALL).orElseThrow());
        assertEquals(
                ThemeSoundCue.FAILURE,
                ThemeSoundPolicy.ending(SessionEndReason.MOVEMENT_MODIFIED).orElseThrow());
        assertTrue(ThemeSoundPolicy.ending(SessionEndReason.SHUTDOWN).isEmpty());
        assertTrue(ThemeSoundPolicy.ending(SessionEndReason.ADMIN).isEmpty());
    }
}
