package com.mrfdev.walktheplank.api.event;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired immediately before WalkThePlank allocates an arena and starts a run. */
public final class WalkRunStartEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String arenaId;
    private boolean cancelled;

    public WalkRunStartEvent(Player player, String arenaId) {
        this.player = Objects.requireNonNull(player, "player");
        this.arenaId = Objects.requireNonNull(arenaId, "arenaId");
    }

    public Player player() {
        return player;
    }

    public String arenaId() {
        return arenaId;
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
