package com.mrfdev.walktheplank.api.event;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a player lands on the target and their current run score advances. */
public final class WalkJumpEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String arenaId;
    private final int score;

    public WalkJumpEvent(Player player, String arenaId, int score) {
        if (score < 1) {
            throw new IllegalArgumentException("score must be positive");
        }
        this.player = Objects.requireNonNull(player, "player");
        this.arenaId = Objects.requireNonNull(arenaId, "arenaId");
        this.score = score;
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

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
