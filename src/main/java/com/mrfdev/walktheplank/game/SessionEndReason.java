package com.mrfdev.walktheplank.game;

public enum SessionEndReason {
    FALL(true, true),
    LEAVE(true, true),
    TELEPORT(false, false),
    TIMEOUT(false, true),
    QUIT(false, true),
    DEATH(false, false),
    GAME_MODE_CHANGE(false, true),
    RELOAD(false, true),
    SHUTDOWN(false, true),
    PERMISSION_REVOKED(false, true),
    MOVEMENT_MODIFIED(false, true),
    ADMIN(false, true),
    ERROR(false, true);

    private final boolean rewardsEligible;
    private final boolean returnPlayer;

    SessionEndReason(boolean rewardsEligible, boolean returnPlayer) {
        this.rewardsEligible = rewardsEligible;
        this.returnPlayer = returnPlayer;
    }

    public boolean rewardsEligible() {
        return rewardsEligible;
    }

    public boolean returnPlayer() {
        return returnPlayer;
    }
}
