package com.mrfdev.walktheplank.database;

import java.util.Objects;
import java.util.Optional;

/** Atomic result of completing a run and persisting its optional redacted reward intent. */
public record CompletedRunWithRewardPlanResult(
        CompletedRunResult completion,
        Optional<RewardPlanBeginResult> rewardPlan) {

    public CompletedRunWithRewardPlanResult {
        Objects.requireNonNull(completion, "completion");
        rewardPlan = Objects.requireNonNull(rewardPlan, "rewardPlan");
    }
}
