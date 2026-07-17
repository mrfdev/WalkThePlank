package com.mrfdev.walktheplank.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before an eligible durable reward plan is dispatched.
 *
 * <p>The event exposes counts and identifiers, never configured command text.</p>
 */
public final class WalkRewardPlanEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID planId;
    private final UUID playerId;
    private final int score;
    private final int stepCount;
    private boolean cancelled;

    public WalkRewardPlanEvent(UUID planId, UUID playerId, int score, int stepCount) {
        if (score < 1 || stepCount < 1) {
            throw new IllegalArgumentException("score and stepCount must be positive");
        }
        this.planId = Objects.requireNonNull(planId, "planId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.score = score;
        this.stepCount = stepCount;
    }

    public UUID planId() {
        return planId;
    }

    public UUID playerId() {
        return playerId;
    }

    public int score() {
        return score;
    }

    public int stepCount() {
        return stepCount;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
