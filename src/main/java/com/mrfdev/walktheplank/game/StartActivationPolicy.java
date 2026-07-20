package com.mrfdev.walktheplank.game;

/** Pure admission rule for activating the exact pending start after its durable write completes. */
final class StartActivationPolicy {
    private StartActivationPolicy() {
    }

    static Rejection evaluate(
            boolean shuttingDown,
            boolean exactPendingStart,
            boolean alreadyActive,
            boolean arenaReserved,
            boolean notCancelled,
            boolean online,
            boolean hasPlayPermission,
            boolean supportedGameMode,
            boolean movementStateEligible,
            boolean hasDisallowedMovementEffect) {
        if (shuttingDown) {
            return Rejection.SHUTTING_DOWN;
        }
        if (!exactPendingStart) {
            return Rejection.PENDING_START_REPLACED;
        }
        if (alreadyActive) {
            return Rejection.SESSION_ALREADY_ACTIVE;
        }
        if (!arenaReserved) {
            return Rejection.ARENA_LEASE_LOST;
        }
        if (!notCancelled) {
            return Rejection.PENDING_START_CANCELLED;
        }
        if (!online) {
            return Rejection.PLAYER_OFFLINE;
        }
        if (!hasPlayPermission) {
            return Rejection.PLAY_PERMISSION_REVOKED;
        }
        if (!supportedGameMode) {
            return Rejection.UNSUPPORTED_GAME_MODE;
        }
        if (!movementStateEligible) {
            return Rejection.MOVEMENT_STATE_CHANGED;
        }
        if (hasDisallowedMovementEffect) {
            return Rejection.MOVEMENT_EFFECT_ADDED;
        }
        return Rejection.NONE;
    }

    static boolean mayActivate(
            boolean shuttingDown,
            boolean exactPendingStart,
            boolean alreadyActive,
            boolean arenaReserved,
            boolean notCancelled,
            boolean online,
            boolean hasPlayPermission,
            boolean supportedGameMode,
            boolean movementStateEligible,
            boolean hasDisallowedMovementEffect) {
        return evaluate(
                shuttingDown,
                exactPendingStart,
                alreadyActive,
                arenaReserved,
                notCancelled,
                online,
                hasPlayPermission,
                supportedGameMode,
                movementStateEligible,
                hasDisallowedMovementEffect) == Rejection.NONE;
    }

    enum Rejection {
        NONE,
        SHUTTING_DOWN,
        PENDING_START_REPLACED,
        SESSION_ALREADY_ACTIVE,
        ARENA_LEASE_LOST,
        PENDING_START_CANCELLED,
        PLAYER_OFFLINE,
        PLAY_PERMISSION_REVOKED,
        UNSUPPORTED_GAME_MODE,
        MOVEMENT_STATE_CHANGED,
        MOVEMENT_EFFECT_ADDED
    }
}
