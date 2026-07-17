package com.mrfdev.walktheplank.config;

import java.util.List;

public record RewardTier(int minimumScore, int maximumScore, List<String> commands) {
    public RewardTier {
        if (minimumScore > maximumScore) {
            throw new IllegalArgumentException("minimumScore cannot exceed maximumScore");
        }
        commands = List.copyOf(commands);
    }

    public boolean contains(int score) {
        return score >= minimumScore && score <= maximumScore;
    }
}
