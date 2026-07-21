package com.mrfdev.walktheplank.command;

import com.mrfdev.walktheplank.config.PermissionSettings;
import com.mrfdev.walktheplank.database.ParticlePreference;
import com.mrfdev.walktheplank.database.PlayerPreferences;
import com.mrfdev.walktheplank.database.ScoreCategory;
import com.mrfdev.walktheplank.database.ScoreEntry;
import com.mrfdev.walktheplank.database.ScoreSnapshot;
import com.mrfdev.walktheplank.database.Season;
import com.mrfdev.walktheplank.game.GameManager.SessionStatus;
import com.mrfdev.walktheplank.game.SessionEndReason;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Player-facing commands plus player-targeted staff controls. */
final class PlayerCommands {
    private final CommandSupport support;

    PlayerCommands(CommandSupport support) {
        this.support = support;
    }

    void openRoot(CommandSender sender) {
        if (sender instanceof Player player) {
            openMenu(sender, player);
        } else {
            help(sender);
        }
    }

    void play(CommandSender sender) {
        Player player = support.requirePlayer(sender);
        if (player == null
                || !support.requirePermission(sender, support.permissions().playGame())) {
            return;
        }
        support.games.start(player);
    }

    void leave(CommandSender sender) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (!support.games.isPlaying(player)
                && !support.requirePermission(sender, support.permissions().leaveArena())) {
            return;
        }
        support.games.end(player, SessionEndReason.LEAVE);
    }

    void stats(CommandSender sender) {
        Player player = support.requirePlayer(sender);
        if (player == null
                || !support.requirePermission(sender, support.permissions().stats())) {
            return;
        }
        support.menus.sendStats(player);
    }

    void categoryStats(CommandSender sender, ScoreCategory category) {
        Player player = support.requirePlayer(sender);
        if (player == null
                || !support.requirePermission(sender, support.permissions().stats())) {
            return;
        }
        var stats = support.scores.categoryStats(category, player.getUniqueId());
        support.sendHeader(sender, title(category) + " statistics");
        if (stats.isEmpty()) {
            support.sendLine(sender, "&7No score has been recorded in this category yet.");
            return;
        }
        var value = stats.orElseThrow();
        support.sendLine(
                sender,
                "&7Best: &f{{score}}&7, rank: &f{{rank}}&7/&f{{total}}&7.",
                Map.of(
                        "score", value.bestScore(),
                        "rank", value.rank(),
                        "total", value.totalEntries()));
    }

    void top(CommandSender sender, boolean seasonRequested) {
        if (!support.requirePermission(sender, support.permissions().top())) {
            return;
        }
        ScoreSnapshot selected = seasonRequested
                ? support.scores.activeSeasonScores().orElse(null)
                : support.scores.snapshot();
        if (selected == null) {
            support.sendLine(sender, "&eThere is no active WalkThePlank season.");
            return;
        }
        if (seasonRequested) {
            support.sendLine(sender, "&7Season: &f{{seasonName}}", Map.of(
                    "seasonName",
                    support.scores.activeSeason().map(Season::name).orElse("active season")));
        }
        for (String line : support.messages.rawList("scoreboardRecordInChat.prefix")) {
            support.sendLine(sender, line);
        }
        String format = support.messages.raw(
                "scoreboardRecordInChat.record",
                "&f{{rank}}. &9{{playerName}} &7({{score}})");
        List<ScoreEntry> top = selected.top();
        if (top.isEmpty()) {
            support.sendLine(sender, "&7No scores have been recorded yet.");
        } else {
            int index = 1;
            for (ScoreEntry entry : top) {
                support.sendLine(sender, format, Map.of(
                        "index", index,
                        "rank", entry.rank(),
                        "playerName", entry.username(),
                        "score", entry.score()));
                index++;
            }
        }
        for (String line : support.messages.rawList("scoreboardRecordInChat.suffix")) {
            support.sendLine(sender, line);
        }
    }

    void categoryTop(CommandSender sender, ScoreCategory category) {
        if (!support.requirePermission(sender, support.permissions().top())) {
            return;
        }
        support.sendHeader(sender, title(category) + " top 10");
        List<ScoreEntry> top = support.scores.categoryTop(category, 10);
        if (top.isEmpty()) {
            support.sendLine(sender, "&7No scores have been recorded in this category yet.");
            return;
        }
        for (ScoreEntry entry : top) {
            support.sendLine(
                    sender,
                    "&f{{rank}}. &9{{playerName}} &7({{score}})",
                    Map.of(
                            "rank", entry.rank(),
                            "playerName", entry.username(),
                            "score", entry.score()));
        }
    }

    void preferences(CommandSender sender) {
        Player player = support.requirePlayer(sender);
        if (player == null
                || !support.requirePermission(sender, support.permissions().preferences())) {
            return;
        }
        PlayerPreferences preferences =
                support.scores.preferences(player.getUniqueId());
        support.sendHeader(sender, "Accessibility preferences");
        support.sendField(sender, "Particles", preferences.particles().name().toLowerCase());
        support.sendField(sender, "Sounds", onOff(preferences.soundsEnabled()));
        support.sendField(sender, "Titles", onOff(preferences.titlesEnabled()));
        support.sendLine(
                sender,
                "&7Change these with &f/walk settings particles|sounds|titles&7.");
    }

    void setParticles(CommandSender sender, ParticlePreference preference) {
        Player player = preferencePlayer(sender);
        if (player == null) {
            return;
        }
        persistPreferences(
                sender,
                support.scores.updateParticlePreference(
                        player.getUniqueId(), preference, Instant.now()));
    }

    void setSounds(CommandSender sender, boolean enabled) {
        Player player = preferencePlayer(sender);
        if (player == null) {
            return;
        }
        persistPreferences(
                sender,
                support.scores.updateSoundPreference(
                        player.getUniqueId(), enabled, Instant.now()));
    }

    void setTitles(CommandSender sender, boolean enabled) {
        Player player = preferencePlayer(sender);
        if (player == null) {
            return;
        }
        persistPreferences(
                sender,
                support.scores.updateTitlePreference(
                        player.getUniqueId(), enabled, Instant.now()));
    }

    private Player preferencePlayer(CommandSender sender) {
        Player player = support.requirePlayer(sender);
        if (player == null
                || !support.requirePermission(sender, support.permissions().preferences())) {
            return null;
        }
        return player;
    }

    private void persistPreferences(
            CommandSender sender,
            CompletableFuture<PlayerPreferences> update) {
        support.completeOnMainThread(
                update,
                saved -> support.sendLine(
                        sender,
                        "&aAccessibility preferences saved: particles &f{{particles}}&a, "
                                + "sounds &f{{sounds}}&a, titles &f{{titles}}&a.",
                        Map.of(
                                "particles", saved.particles().name().toLowerCase(),
                                "sounds", onOff(saved.soundsEnabled()),
                                "titles", onOff(saved.titlesEnabled()))),
                "accessibility preference update",
                sender);
    }

    private static String title(ScoreCategory category) {
        return switch (category) {
            case COMBO -> "Combo";
            case FLAWLESS -> "Flawless";
        };
    }

    private static String onOff(boolean enabled) {
        return enabled ? "on" : "off";
    }

    void info(CommandSender sender) {
        if (!support.requirePermission(sender, support.permissions().info())) {
            return;
        }
        support.sendHeader(sender, "Plugin information");
        support.sendField(sender, "Release", support.buildInfo.releaseLabel());
        support.sendField(sender, "Artifact", support.buildInfo.artifactFile());
        support.sendField(sender, "Source", support.buildInfo.sourceLabel());
        support.sendField(
                sender,
                "Platform",
                "Java " + support.buildInfo.javaTarget()
                        + " / Paper " + support.buildInfo.paperTarget());
        support.sendField(
                sender,
                "Scores",
                Integer.toString(support.scores.snapshot().totalEntries()));
        support.sendField(
                sender,
                "Arenas",
                support.games.activeSessions() + " active, "
                        + support.games.availableArenas() + " available, "
                        + support.games.totalArenas() + " configured");
        support.sendField(sender, "Documentation", CommandSupport.DOCS_URL);
    }

    void help(CommandSender sender) {
        if (!support.requirePermission(sender, support.permissions().help())) {
            return;
        }
        PermissionSettings permissions = support.permissions();
        support.sendHeader(sender, "Command help");
        support.addHelp(sender, permissions.openGui(), "/walk", "Open the menu");
        support.addHelp(sender, permissions.playGame(), "/walk play", "Start a run");
        support.addHelp(
                sender,
                permissions.playGame(),
                "/walk queue <status|leave>",
                "Inspect or leave the optional queue");
        support.addHelp(
                sender,
                permissions.queueJoin(),
                "/walk queue <join|ready>",
                "Reserve the next free arena fairly");
        if (sender instanceof Player player
                && (support.games.isPlaying(player)
                        || support.has(sender, permissions.leaveArena()))) {
            support.sendCommandHelp(sender, "/walk leave", "End your run safely");
        }
        support.addHelp(
                sender,
                permissions.stats(),
                "/walk stats",
                "Show your personal best and rank");
        support.addHelp(sender, permissions.top(), "/walk top", "Show the top ten");
        support.addHelp(
                sender,
                permissions.preferences(),
                "/walk settings",
                "Change particles, sounds, and titles");
        support.addHelp(
                sender,
                permissions.info(),
                "/walk info",
                "Show build and platform information");
        if (support.hasAnyAdminPermission(sender)) {
            support.sendCommandHelp(
                    sender,
                    "/walk admin",
                    "Show permission-filtered staff commands");
        }
        support.addAdminHelp(
                sender,
                permissions.adminDebug(),
                "/walk debug [page]",
                "Show safe diagnostics");
    }

    void openOther(CommandSender sender, Player target) {
        if (!support.requireAdministrativePermission(
                sender, support.permissions().adminOpen())) {
            return;
        }
        support.menus.open(target);
        support.operations.audit(
                "admin.open",
                CommandSupport.operatorId(sender),
                null,
                Map.of(
                        "actor", CommandSupport.operatorKind(sender),
                        "target_player_id", target.getUniqueId(),
                        "result", "opened"));
        support.sendLine(
                sender,
                "&aOpened the WalkThePlank menu for &f{{playerName}}&a.",
                Map.of("playerName", target.getName()));
    }

    void stop(CommandSender sender, Player target) {
        if (!support.requireAdministrativePermission(
                sender, support.permissions().adminStop())) {
            return;
        }
        if (!support.games.end(target, SessionEndReason.ADMIN)) {
            support.operations.audit(
                    "admin.stop",
                    CommandSupport.operatorId(sender),
                    null,
                    Map.of(
                            "actor", CommandSupport.operatorKind(sender),
                            "target_player_id", target.getUniqueId(),
                            "result", "not_active"));
            support.sendLine(
                    sender,
                    "&e{{playerName}} is not in a WalkThePlank run.",
                    Map.of("playerName", target.getName()));
            return;
        }
        support.operations.audit(
                "admin.stop",
                CommandSupport.operatorId(sender),
                null,
                Map.of(
                        "actor", CommandSupport.operatorKind(sender),
                        "target_player_id", target.getUniqueId(),
                        "result", "stopped"));
        support.sendLine(
                sender,
                "&aStopped &f{{playerName}}&a's run without rewards.",
                Map.of("playerName", target.getName()));
    }

    void recover(CommandSender sender) {
        if (!support.requireAdministrativePermission(
                sender, support.permissions().adminRecover())) {
            return;
        }
        support.completeOnMainThread(
                support.games.retryQuarantinedArenas(CommandSupport.operatorId(sender)),
                recovered -> support.sendLine(
                        sender,
                        "&aRestoration retry complete: &f{{recovered}}&a arena(s) recovered, "
                                + "&f{{quarantined}}&a still quarantined.",
                        Map.of(
                                "recovered", recovered,
                                "quarantined", support.games.quarantinedArenas())),
                "arena restoration retry",
                sender);
    }

    void status(CommandSender sender, Player target) {
        if (!support.requireAdministrativePermission(
                sender, support.permissions().adminDebug())) {
            return;
        }
        Optional<SessionStatus> status = support.games.sessionStatus(target.getUniqueId());
        if (status.isEmpty()) {
            support.sendLine(
                    sender,
                    "&e{{playerName}} is not in a WalkThePlank run.",
                    Map.of("playerName", target.getName()));
            return;
        }
        SessionStatus session = status.orElseThrow();
        support.sendHeader(sender, "Run status: " + target.getName());
        support.sendField(sender, "Arena", session.arenaId());
        support.sendField(sender, "Score", Integer.toString(session.score()));
        support.sendField(sender, "Elapsed", session.elapsedSeconds() + " seconds");
        support.sendField(sender, "Idle", session.idleSeconds() + " seconds");
    }

    private void openMenu(CommandSender sender, Player player) {
        if (!support.requirePermission(sender, support.permissions().openGui())) {
            return;
        }
        if (!support.games.isEventEnabled()) {
            support.messages.send(player, "chat.eventDisabled");
            return;
        }
        support.menus.open(player);
    }
}
