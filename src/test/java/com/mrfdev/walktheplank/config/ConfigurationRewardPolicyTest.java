package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ConfigurationRewardPolicyTest {
    @Test
    void countsInclusiveOverlappingRewardRanges() {
        long maximum = ConfigurationShapePolicy.maximumMatchingRewardSteps(List.of(
                new ConfigurationShapePolicy.RewardStepRange(Integer.MIN_VALUE, 10, 60),
                new ConfigurationShapePolicy.RewardStepRange(5, Integer.MAX_VALUE, 41)));

        assertEquals(101L, maximum);
    }

    @Test
    void acceptsAdjacentRangesAtTheHundredStepBoundary() {
        long maximum = ConfigurationShapePolicy.maximumMatchingRewardSteps(List.of(
                new ConfigurationShapePolicy.RewardStepRange(0, 4, 100),
                new ConfigurationShapePolicy.RewardStepRange(5, 10, 100),
                new ConfigurationShapePolicy.RewardStepRange(
                        Integer.MAX_VALUE, Integer.MAX_VALUE, 40)));

        assertEquals(100L, maximum);
    }

    @Test
    void treatsUnknownNestedKeysAsStrictErrorsOrLegacyWarnings() {
        Map<String, Object> values = Map.of("x", 1, "typo", true);
        Set<String> allowed = Set.of("x");
        ConfigurationShapePolicy.UnknownKeysProblem strict =
                ConfigurationShapePolicy.unknownKeys(
                                values, allowed, "startPositions[0]", true)
                        .orElseThrow();
        ConfigurationShapePolicy.UnknownKeysProblem legacy =
                ConfigurationShapePolicy.unknownKeys(
                                values, allowed, "startPositions[0]", false)
                        .orElseThrow();

        assertTrue(strict.error());
        assertTrue(strict.message().contains("startPositions[0] contains 1 unknown key"));
        assertTrue(!legacy.error());
        assertTrue(legacy.message().contains(
                "startPositions[0] contains 1 legacy/unknown key"));
    }
}
