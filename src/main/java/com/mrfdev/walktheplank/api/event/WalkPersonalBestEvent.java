package com.mrfdev.walktheplank.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the primary server thread after a persisted score becomes a new personal best. */
public final class WalkPersonalBestEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String lastKnownName;
    private final int previousBest;
    private final int newBest;

    public WalkPersonalBestEvent(UUID playerId, String lastKnownName, int previousBest, int newBest) {
        if (previousBest < 0 || newBest <= previousBest) {
            throw new IllegalArgumentException("newBest must be greater than the non-negative previousBest");
        }
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.lastKnownName = Objects.requireNonNull(lastKnownName, "lastKnownName");
        this.previousBest = previousBest;
        this.newBest = newBest;
    }

    public UUID playerId() {
        return playerId;
    }

    public String lastKnownName() {
        return lastKnownName;
    }

    public int previousBest() {
        return previousBest;
    }

    public int newBest() {
        return newBest;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
