package com.mrfdev.walktheplank.database;

/** Aggregate status of a durable reward plan. */
public enum RewardPlanStatus {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    PARTIAL,
    UNKNOWN,
    ABANDONED
}
