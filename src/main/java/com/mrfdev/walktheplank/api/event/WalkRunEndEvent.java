package com.mrfdev.walktheplank.api.event;

import com.mrfdev.walktheplank.game.SessionEndReason;
import java.time.Duration;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a run is removed from the active-session registry. */
public final class WalkRunEndEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String arenaId;
    private final int score;
    private final Duration duration;
    private final SessionEndReason reason;
    private final boolean cleanupComplete;

    public WalkRunEndEvent(
            Player player,
            String arenaId,
            int score,
            Duration duration,
            SessionEndReason reason,
            boolean cleanupComplete) {
        if (score < 0) {
            throw new IllegalArgumentException("score cannot be negative");
        }
        this.player = Objects.requireNonNull(player, "player");
        this.arenaId = Objects.requireNonNull(arenaId, "arenaId");
        this.score = score;
        this.duration = Objects.requireNonNull(duration, "duration");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.cleanupComplete = cleanupComplete;
    }

    public Player player() {
        return player;
    }

    public String arenaId() {
        return arenaId;
    }

    public int score() {
        return score;
    }

    public Duration duration() {
        return duration;
    }

    public SessionEndReason reason() {
        return reason;
    }

    public boolean cleanupComplete() {
        return cleanupComplete;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
