package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrfdev.walktheplank.database.RunCategoryScore;
import com.mrfdev.walktheplank.database.ScoreCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunOutcomePolicyTest {
    @Test
    void adminTestNeverProjectsScoreCategoriesOrCompletion() {
        RunOutcomePolicy.Projection projection = RunOutcomePolicy.project(
                true,
                SessionEndReason.FALL,
                112,
                List.of(
                        new RunCategoryScore(ScoreCategory.COMBO, 112),
                        new RunCategoryScore(ScoreCategory.FLAWLESS, 112)));

        assertEquals(0, projection.authoritativeScore());
        assertTrue(projection.categoryScores().isEmpty());
        assertFalse(projection.scoringEligible());
        assertFalse(projection.publicEventsEligible());
        assertEquals("ADMIN_TEST_FALL", projection.interruptedReason().orElseThrow());
    }

    @Test
    void movementModifiedNormalRunStillProjectsZero() {
        RunOutcomePolicy.Projection projection = RunOutcomePolicy.project(
                false,
                SessionEndReason.MOVEMENT_MODIFIED,
                42,
                List.of(new RunCategoryScore(ScoreCategory.COMBO, 9)));

        assertEquals(0, projection.authoritativeScore());
        assertTrue(projection.categoryScores().isEmpty());
        assertFalse(projection.scoringEligible());
        assertTrue(projection.publicEventsEligible());
        assertTrue(projection.interruptedReason().isEmpty());
    }

    @Test
    void eligibleNormalRunKeepsObservedProjection() {
        List<RunCategoryScore> categories =
                List.of(new RunCategoryScore(ScoreCategory.COMBO, 12));
        RunOutcomePolicy.Projection projection = RunOutcomePolicy.project(
                false,
                SessionEndReason.FALL,
                12,
                categories);

        assertEquals(12, projection.authoritativeScore());
        assertEquals(categories, projection.categoryScores());
        assertTrue(projection.scoringEligible());
        assertTrue(projection.publicEventsEligible());
        assertTrue(projection.interruptedReason().isEmpty());
    }
}
