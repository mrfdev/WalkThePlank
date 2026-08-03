package com.mrfdev.walktheplank.command;

import com.mrfdev.walktheplank.game.GameManager.AdminTestStartResult;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Explicitly armed, self-only administrative gameplay reproduction controls. */
final class TestCommands {
    private final CommandSupport support;

    TestCommands(CommandSupport support) {
        this.support = support;
    }

    void help(CommandSender sender) {
        if (!support.requireAdministrativePermission(
                sender, support.permissions().adminTest())) {
            return;
        }
        var testing = support.settings.get().adminTesting();
        support.sendHeader(sender, "Administrative gameplay test");
        support.sendField(sender, "Enabled", Boolean.toString(testing.enabled()));
        support.sendField(
                sender,
                "Maximum target",
                Integer.toString(testing.maximumTargetScore()));
        support.sendCommandHelp(
                sender,
                "/walk admin test fast-forward <target-score>",
                "Fast-forward your score-zero run without scores or rewards");
    }

    void fastForward(CommandSender sender, int targetScore) {
        if (!support.requireAdministrativePermission(
                sender, support.permissions().adminTest())) {
            return;
        }
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return;
        }

        AdminTestStartResult result =
                support.games.startAdminTestFastForward(player, targetScore);
        switch (result) {
            case STARTED -> support.sendLine(
                    sender,
                    "&aStarted a non-scoring fast-forward toward score &f{{score}}&a. "
                            + "Wait for its completion message before jumping.",
                    Map.of("score", targetScore));
            case DISABLED -> support.sendLine(
                    sender,
                    "&eAdministrative gameplay testing is disabled in config.");
            case NO_ACTIVE_RUN -> support.sendLine(
                    sender,
                    "&eStart a WalkThePlank run before using fast-forward.");
            case SCORE_NOT_ZERO -> support.sendLine(
                    sender,
                    "&eFast-forward is accepted only while the active run is still at score 0.");
            case ALREADY_RUNNING -> support.sendLine(
                    sender,
                    "&eAn administrative fast-forward is already in progress.");
            case TARGET_OUT_OF_RANGE -> support.sendLine(
                    sender,
                    "&eThe target must be between 1 and the configured maximum of &f{{maximum}}&e.",
                    Map.of(
                            "maximum",
                            support.settings.get()
                                    .adminTesting()
                                    .maximumTargetScore()));
        }
    }
}
