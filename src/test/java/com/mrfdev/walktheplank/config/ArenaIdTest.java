package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ArenaIdTest {
    @Test
    void acceptsStableLowercaseIdentifiers() {
        assertEquals("summer_2026-main", ArenaId.requireValid("summer_2026-main"));
        assertTrue(ArenaId.isValid("0"));
        assertTrue(ArenaId.isValid("arena-32_character_identifier"));
    }

    @Test
    void rejectsPathsWhitespaceCaseAndOversizedIdentifiers() {
        for (String unsafe : new String[] {
                "../main", "arena.main", "Arena", "two words", "", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }) {
            assertFalse(ArenaId.isValid(unsafe));
            assertThrows(IllegalArgumentException.class, () -> ArenaId.requireValid(unsafe));
        }
    }
}
