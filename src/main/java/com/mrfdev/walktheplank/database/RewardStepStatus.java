package com.mrfdev.walktheplank.database;

/** Durable state for one reward command without storing its sensitive arguments. */
public enum RewardStepStatus {
    PENDING,
    DISPATCHING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    SKIPPED
}
