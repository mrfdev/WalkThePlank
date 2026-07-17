package com.mrfdev.walktheplank.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Pure structural configuration rules that do not require a running Bukkit server. */
final class ConfigurationShapePolicy {
    private ConfigurationShapePolicy() {
    }

    static Optional<UnknownKeysProblem> unknownKeys(
            Map<?, ?> values,
            Set<String> allowedKeys,
            String description,
            boolean strict) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(allowedKeys, "allowedKeys");
        Objects.requireNonNull(description, "description");
        long unknown = values.keySet().stream()
                .filter(key -> !(key instanceof String name) || !allowedKeys.contains(name))
                .count();
        if (unknown == 0L) {
            return Optional.empty();
        }
        String message = strict
                ? description + " contains " + unknown
                        + " unknown key(s); remove typos or obsolete settings"
                : description + " contains " + unknown
                        + " legacy/unknown key(s) and they are ignored";
        return Optional.of(new UnknownKeysProblem(strict, message));
    }

    static long maximumMatchingRewardSteps(List<RewardStepRange> rewardStepRanges) {
        Objects.requireNonNull(rewardStepRanges, "rewardStepRanges");
        Map<Long, Long> rewardStepDeltas = new TreeMap<>();
        for (RewardStepRange range : rewardStepRanges) {
            rewardStepDeltas.merge(
                    (long) range.minimumScore(), (long) range.steps(), Long::sum);
            rewardStepDeltas.merge(
                    (long) range.maximumScore() + 1L, -(long) range.steps(), Long::sum);
        }
        long matchingSteps = 0L;
        long maximumMatchingSteps = 0L;
        for (long delta : rewardStepDeltas.values()) {
            matchingSteps += delta;
            maximumMatchingSteps = Math.max(maximumMatchingSteps, matchingSteps);
        }
        return maximumMatchingSteps;
    }

    record UnknownKeysProblem(boolean error, String message) {
        UnknownKeysProblem {
            Objects.requireNonNull(message, "message");
        }
    }

    record RewardStepRange(int minimumScore, int maximumScore, int steps) {
        RewardStepRange {
            if (minimumScore > maximumScore) {
                throw new IllegalArgumentException("minimumScore must not exceed maximumScore");
            }
            if (steps < 0) {
                throw new IllegalArgumentException("steps must not be negative");
            }
        }
    }
}
