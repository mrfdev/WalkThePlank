package com.mrfdev.walktheplank.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PlayerPreferenceAndCategoryTest {
    @Test
    void particleModesApplyDeterministicAccessibleCounts() {
        assertEquals(20, ParticlePreference.FULL.apply(20));
        assertEquals(5, ParticlePreference.REDUCED.apply(20));
        assertEquals(1, ParticlePreference.REDUCED.apply(1));
        assertEquals(0, ParticlePreference.OFF.apply(20));
        assertThrows(
                IllegalArgumentException.class,
                () -> ParticlePreference.FULL.apply(-1));
    }

    @Test
    void runCategoryShapeIsUniqueAndCannotExceedClassicScore() {
        UUID runId = UUID.randomUUID();
        Instant completedAt = Instant.parse("2026-07-18T00:00:00Z");
        assertThrows(
                IllegalArgumentException.class,
                () -> new RunCompletion(
                        runId,
                        completedAt,
                        5,
                        "FALL",
                        List.of(
                                new RunCategoryScore(ScoreCategory.COMBO, 4),
                                new RunCategoryScore(ScoreCategory.COMBO, 3))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RunCompletion(
                        runId,
                        completedAt,
                        5,
                        "FALL",
                        List.of(new RunCategoryScore(ScoreCategory.FLAWLESS, 6))));
    }
}
