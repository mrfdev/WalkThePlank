package com.mrfdev.walktheplank.database;

import java.util.Objects;

/**
 * Result of idempotently preparing a reward plan.
 *
 * <p>{@code shouldDispatch} directs the caller to attempt atomic step claims. It can be true for
 * either a new plan or an exact, wholly PENDING retry; it never authorizes command replay by
 * itself.
 */
public record RewardPlanBeginResult(
        RewardPlanRecord plan,
        boolean created,
        boolean shouldDispatch) {

    public RewardPlanBeginResult {
        Objects.requireNonNull(plan, "plan");
        if (shouldDispatch
                && (plan.status() != RewardPlanStatus.PENDING
                        || plan.steps().isEmpty()
                        || plan.steps().stream()
                                .anyMatch(step -> step.status() != RewardStepStatus.PENDING))) {
            throw new IllegalArgumentException(
                    "Only a nonempty wholly pending plan may be dispatched");
        }
    }
}
