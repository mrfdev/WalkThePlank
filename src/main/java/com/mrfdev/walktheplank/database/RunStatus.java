package com.mrfdev.walktheplank.database;

/** Durable lifecycle of a run, including interrupted states that require staff inspection. */
public enum RunStatus {
    STARTED,
    COMPLETED,
    ABORTED,
    UNKNOWN
}
