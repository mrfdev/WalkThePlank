package com.mrfdev.walktheplank.game;

import java.util.Objects;

/** Pure per-step revalidation for the privileged, permanently non-scoring fast-forward. */
final class AdminTestFastForwardPolicy {
    private AdminTestFastForwardPolicy() {
    }

    static Rejection evaluate(State state) {
        Objects.requireNonNull(state, "state");
        if (!state.pluginEnabled() || state.shuttingDown()) {
            return Rejection.RUNTIME_CLOSING;
        }
        if (!state.adminTestingEnabled()) {
            return Rejection.DISABLED;
        }
        if (state.targetScore() < 1 || state.targetScore() > state.maximumTargetScore()) {
            return Rejection.TARGET_OUT_OF_RANGE;
        }
        if (!state.controlCurrent()) {
            return Rejection.CONTROL_REPLACED;
        }
        if (!state.sessionCurrent()) {
            return Rejection.SESSION_REPLACED;
        }
        if (state.deadlineExceeded()) {
            return Rejection.TIMEOUT;
        }
        if (!state.adminTest()) {
            return Rejection.SESSION_NOT_TEST;
        }
        if (!state.playerOnline()) {
            return Rejection.PLAYER_OFFLINE;
        }
        if (state.playerDead()) {
            return Rejection.PLAYER_DEAD;
        }
        if (!state.adminPermission()) {
            return Rejection.ADMIN_PERMISSION_REVOKED;
        }
        if (!state.playPermission()) {
            return Rejection.PLAY_PERMISSION_REVOKED;
        }
        if (!state.supportedGameMode()) {
            return Rejection.UNSUPPORTED_GAME_MODE;
        }
        if (!state.arenaLeaseOwned()) {
            return Rejection.ARENA_LEASE_LOST;
        }
        return Rejection.NONE;
    }

    record State(
            boolean pluginEnabled,
            boolean shuttingDown,
            boolean adminTestingEnabled,
            int targetScore,
            int maximumTargetScore,
            boolean controlCurrent,
            boolean sessionCurrent,
            boolean deadlineExceeded,
            boolean adminTest,
            boolean playerOnline,
            boolean playerDead,
            boolean adminPermission,
            boolean playPermission,
            boolean supportedGameMode,
            boolean arenaLeaseOwned) {
    }

    enum Rejection {
        NONE,
        RUNTIME_CLOSING,
        DISABLED,
        TARGET_OUT_OF_RANGE,
        CONTROL_REPLACED,
        SESSION_REPLACED,
        TIMEOUT,
        SESSION_NOT_TEST,
        PLAYER_OFFLINE,
        PLAYER_DEAD,
        ADMIN_PERMISSION_REVOKED,
        PLAY_PERMISSION_REVOKED,
        UNSUPPORTED_GAME_MODE,
        ARENA_LEASE_LOST
    }
}
