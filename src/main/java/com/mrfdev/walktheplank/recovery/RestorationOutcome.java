package com.mrfdev.walktheplank.recovery;

public enum RestorationOutcome {
    RESTORED,
    ALREADY_RESTORED,
    WORLD_MISSING,
    CONFLICT,
    FAILED,
    NO_LONGER_PENDING;

    public boolean completed() {
        return this == RESTORED || this == ALREADY_RESTORED || this == NO_LONGER_PENDING;
    }
}
