package com.mrfdev.walktheplank.database;

import java.util.Objects;

/** Atomic result of the pre-dispatch marker; false suppresses duplicate or unsafe dispatch. */
public record RewardStepDispatchResult(RewardPlanRecord plan, boolean shouldDispatch) {
    public RewardStepDispatchResult {
        Objects.requireNonNull(plan, "plan");
    }
}
