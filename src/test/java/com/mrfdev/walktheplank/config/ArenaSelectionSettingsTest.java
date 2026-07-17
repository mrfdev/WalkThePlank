package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mrfdev.walktheplank.game.ArenaSelectionPolicy;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArenaSelectionSettingsTest {
    @Test
    void pinnedPolicyRequiresAnArena() {
        assertThrows(IllegalArgumentException.class, () -> new ArenaSelectionSettings(
                ArenaSelectionPolicy.PINNED, Optional.empty()));
        assertEquals(
                "summer",
                new ArenaSelectionSettings(ArenaSelectionPolicy.PINNED, Optional.of(" summer "))
                        .pinnedArenaId()
                        .orElseThrow());
    }
}
