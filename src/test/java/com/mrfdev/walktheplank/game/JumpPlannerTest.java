package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class JumpPlannerTest {
    @Test
    void generatedJumpsStayReachableAndInsideArenaBounds() {
        JumpPlanner planner = new JumpPlanner(6);
        GridPoint center = new GridPoint(0, 100, 0);
        GridPoint current = center;
        SplittableRandom random = new SplittableRandom(456L);

        for (int score = 0; score < 1_000; score++) {
            GridPoint next = planner.next(center, current, score, random, ignored -> true);
            int horizontalDistance = Math.abs(next.x() - current.x()) + Math.abs(next.z() - current.z());
            assertTrue(horizontalDistance >= 2 && horizontalDistance <= 5);
            assertTrue(Math.abs(next.y() - current.y()) <= 1);
            assertTrue(Math.abs(next.x() - center.x()) <= 6);
            assertTrue(Math.abs(next.z() - center.z()) <= 6);
            assertTrue(Math.abs(next.y() - center.y()) <= 6);
            current = next;
        }
    }

    @Test
    void plannerUsesOnlyLocationsAcceptedBySafetyPredicate() {
        JumpPlanner planner = new JumpPlanner(6);
        GridPoint center = new GridPoint(0, 64, 0);
        GridPoint expected = new GridPoint(2, 64, 0);

        GridPoint actual = planner.next(
                center,
                center,
                0,
                new SplittableRandom(1L),
                expected::equals);

        assertEquals(expected, actual);
    }

    @Test
    void plannerFailsCleanlyWhenArenaHasNoSafeSpace() {
        JumpPlanner planner = new JumpPlanner(6);
        GridPoint center = new GridPoint(0, 64, 0);

        assertThrows(
                IllegalStateException.class,
                () -> planner.next(center, center, 0, new SplittableRandom(1L), ignored -> false));
    }
}
