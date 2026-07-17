package com.mrfdev.walktheplank.command;

import com.mrfdev.walktheplank.game.GameManager.QueueStatus;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Fair player queue and queue administration commands. */
final class QueueCommands {
    enum PlayerAction {
        JOIN,
        LEAVE,
        STATUS,
        READY
    }

    enum AdminAction {
        STATUS,
        PAUSE,
        RESUME,
        DRAIN
    }

    private final CommandSupport support;

    QueueCommands(CommandSupport support) {
        this.support = support;
    }

    void player(CommandSender sender, PlayerAction action) {
        Player player = support.requirePlayer(sender);
        if (player == null
                || !support.requirePermission(sender, support.permissions().playGame())) {
            return;
        }
        switch (action) {
            case JOIN -> support.games.joinQueue(player);
            case LEAVE -> support.games.leaveQueue(player);
            case READY -> support.games.startReady(player);
            case STATUS -> showStatus(
                    sender, support.games.queueStatus(player.getUniqueId()));
        }
    }

    void admin(CommandSender sender, AdminAction action) {
        if (!support.requireAdministrativePermission(
                sender, support.permissions().adminQueue())) {
            return;
        }
        switch (action) {
            case STATUS -> showStatus(
                    sender, support.games.queueStatus(new UUID(0L, 0L)));
            case PAUSE -> {
                support.games.setQueuePaused(true, CommandSupport.operatorId(sender));
                support.sendLine(
                        sender,
                        "&aThe arena queue is paused; existing positions are preserved.");
            }
            case RESUME -> {
                support.games.setQueuePaused(false, CommandSupport.operatorId(sender));
                support.sendLine(
                        sender,
                        "&aThe arena queue is accepting readiness assignments again.");
            }
            case DRAIN -> support.sendLine(
                    sender,
                    "&aRemoved &f{{players}}&a player(s) from the arena queue.",
                    Map.of(
                            "players",
                            support.games.drainQueue(CommandSupport.operatorId(sender))));
        }
    }

    void showStatus(CommandSender sender, QueueStatus status) {
        support.sendHeader(sender, "Arena queue");
        support.sendField(sender, "Enabled", Boolean.toString(status.enabled()));
        support.sendField(sender, "State", status.paused() ? "paused" : "running");
        support.sendField(
                sender,
                "Players",
                status.total() + " total / "
                        + status.waiting() + " waiting / "
                        + status.ready() + " ready");
        if (status.playerPosition() > 0) {
            support.sendField(
                    sender,
                    "Your position",
                    Integer.toString(status.playerPosition()));
        }
        status.playerReadyUntil().ifPresent(readyUntil ->
                support.sendField(
                        sender,
                        "Reserved until",
                        CommandSupport.SNAPSHOT_TIME.format(readyUntil)));
    }
}
