package com.mrfdev.walktheplank.reward;

import com.mrfdev.walktheplank.config.RewardTier;
import com.mrfdev.walktheplank.config.RuntimeSettings;
import com.mrfdev.walktheplank.database.RewardStepSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

public final class RewardService {
    private final Supplier<RuntimeSettings> settings;
    private final Logger logger;
    private final CommandGateway commandGateway;

    public RewardService(Supplier<RuntimeSettings> settings, Logger logger) {
        this(settings, logger, new BukkitCommandGateway());
    }

    RewardService(
            Supplier<RuntimeSettings> settings,
            Logger logger,
            CommandGateway commandGateway) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.commandGateway = Objects.requireNonNull(commandGateway, "commandGateway");
    }

    public RewardPlan planFor(int score) {
        RuntimeSettings current = settings.get();
        if (!current.finishCommandsEnabled()) {
            return RewardPlan.none();
        }
        List<String> commands = new ArrayList<>();
        for (RewardTier tier : current.rewardTiers()) {
            if (tier.contains(score)) {
                commands.addAll(tier.commands());
            }
        }
        return new RewardPlan(
                current.rewardsOnlyOnPersonalBest(),
                commands,
                current.allowedRewardCommandRoots());
    }

    public void execute(
            RewardPlan plan,
            String playerName,
            UUID playerId,
            int score,
            boolean personalBest) {
        PreparedRewardPlan prepared = prepare(
                plan, playerName, playerId, score, personalBest);
        if (!prepared.executable()) {
            return;
        }
        for (PreparedRewardStep step : prepared.steps()) {
            if (!dispatch(step)) {
                logger.warning(
                        "Finish reward command " + (step.index() + 1) + '/'
                                + prepared.steps().size()
                                + " was not handled (root: " + step.commandRoot() + ')');
            }
        }
    }

    /**
     * Expands and preflights a plan without dispatching it. The returned raw commands remain
     * process-local; {@link PreparedRewardPlan#ledgerSteps()} exposes only roots and hashes.
     */
    public PreparedRewardPlan prepare(
            RewardPlan plan,
            String playerName,
            UUID playerId,
            int score,
            boolean personalBest) {
        if (!commandGateway.isPrimaryThread()) {
            throw new IllegalStateException("Reward commands must run on the server thread");
        }
        boolean skippedForPersonalBest = plan.onlyOnPersonalBest() && !personalBest;

        List<PreparedRewardStep> commands = new ArrayList<>();
        for (String configuredCommand : plan.commands()) {
            String command = configuredCommand
                    .replace("{{playerName}}", playerName)
                    .replace("{{playerUuid}}", playerId.toString())
                    .replace("{{score}}", Integer.toString(score))
                    .strip();
            if (command.startsWith("/")) {
                command = command.substring(1).stripLeading();
            }
            if (command.isBlank()) {
                continue;
            }
            if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
                logger.severe(
                        "Aborted finish reward plan before execution because a command contains a line break");
                return new PreparedRewardPlan(
                        RewardPreparationStatus.ABORTED_INVALID_COMMAND, List.of(), Set.of());
            }
            String root = commandRoot(command);
            commands.add(new PreparedRewardStep(
                    commands.size(), command, root, hashCommand(command)));
        }

        if (skippedForPersonalBest) {
            // Retain redacted root/hash evidence in the durable ledger without requiring or
            // dispatching an external command for an ineligible run.
            return new PreparedRewardPlan(
                    RewardPreparationStatus.SKIPPED_PERSONAL_BEST, commands, Set.of());
        }

        Set<String> disallowedRoots = new LinkedHashSet<>();
        for (PreparedRewardStep command : commands) {
            if (!plan.allowedCommandRoots().contains(command.commandRoot())) {
                disallowedRoots.add(command.commandRoot());
            }
        }
        if (!disallowedRoots.isEmpty()) {
            logger.severe(
                    "Aborted finish reward plan before execution; command roots are outside "
                            + "the configured allow-list: " + String.join(", ", disallowedRoots));
            return new PreparedRewardPlan(
                    RewardPreparationStatus.ABORTED_DISALLOWED_ROOT,
                    commands,
                    disallowedRoots);
        }

        Set<String> missingRoots = new LinkedHashSet<>();
        try {
            for (PreparedRewardStep command : commands) {
                if (!commandGateway.commandExists(command.commandRoot())) {
                    missingRoots.add(command.commandRoot());
                }
            }
        } catch (RuntimeException failure) {
            logger.log(
                    Level.SEVERE,
                    "Could not preflight finish reward commands; no commands were executed",
                    failure);
            return new PreparedRewardPlan(
                    RewardPreparationStatus.ABORTED_PREFLIGHT_FAILURE,
                    commands,
                    Set.of());
        }
        if (!missingRoots.isEmpty()) {
            logger.severe(
                    "Aborted finish reward plan before execution; unavailable command roots: "
                            + String.join(", ", missingRoots));
            return new PreparedRewardPlan(
                    RewardPreparationStatus.ABORTED_MISSING_ROOT,
                    commands,
                    missingRoots);
        }
        return new PreparedRewardPlan(RewardPreparationStatus.READY, commands, Set.of());
    }

    /** Dispatches one already-preflighted step; durable callers must claim it first. */
    public boolean dispatch(PreparedRewardStep step) {
        Objects.requireNonNull(step, "step");
        if (!commandGateway.isPrimaryThread()) {
            throw new IllegalStateException("Reward commands must run on the server thread");
        }
        return commandGateway.dispatch(step.command());
    }

    private static String commandRoot(String command) {
        int end = 0;
        while (end < command.length() && !Character.isWhitespace(command.charAt(end))) {
            end++;
        }
        return command.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static String hashCommand(String command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(command.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    interface CommandGateway {
        boolean isPrimaryThread();

        boolean commandExists(String root);

        boolean dispatch(String command);
    }

    private static final class BukkitCommandGateway implements CommandGateway {
        @Override
        public boolean isPrimaryThread() {
            return Bukkit.isPrimaryThread();
        }

        @Override
        public boolean commandExists(String root) {
            return Bukkit.getServer().getCommandMap().getCommand(root) != null;
        }

        @Override
        public boolean dispatch(String command) {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    public enum RewardPreparationStatus {
        READY,
        SKIPPED_PERSONAL_BEST,
        ABORTED_INVALID_COMMAND,
        ABORTED_DISALLOWED_ROOT,
        ABORTED_MISSING_ROOT,
        ABORTED_PREFLIGHT_FAILURE
    }

    public record PreparedRewardPlan(
            RewardPreparationStatus status,
            List<PreparedRewardStep> steps,
            Set<String> unavailableRoots) {

        public PreparedRewardPlan {
            Objects.requireNonNull(status, "status");
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            unavailableRoots = Set.copyOf(
                    Objects.requireNonNull(unavailableRoots, "unavailableRoots"));
        }

        public boolean executable() {
            return status == RewardPreparationStatus.READY;
        }

        public List<RewardStepSpec> ledgerSteps() {
            return steps.stream()
                    .map(step -> new RewardStepSpec(
                            step.commandRoot(), step.commandHash()))
                    .toList();
        }
    }

    public record PreparedRewardStep(
            int index,
            String command,
            String commandRoot,
            String commandHash) {

        public PreparedRewardStep {
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative");
            }
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(commandRoot, "commandRoot");
            Objects.requireNonNull(commandHash, "commandHash");
        }

        @Override
        public String toString() {
            return "PreparedRewardStep[index=" + index
                    + ", command=<redacted>, commandRoot=" + commandRoot
                    + ", commandHash=" + commandHash + ']';
        }
    }

    public record RewardPlan(
            boolean onlyOnPersonalBest,
            List<String> commands,
            Set<String> allowedCommandRoots) {
        private static final RewardPlan NONE =
                new RewardPlan(false, List.of(), Set.of());

        public RewardPlan {
            commands = List.copyOf(commands);
            allowedCommandRoots = Set.copyOf(allowedCommandRoots);
        }

        /** Convenience constructor for trusted programmatic plans and focused tests. */
        public RewardPlan(boolean onlyOnPersonalBest, List<String> commands) {
            this(onlyOnPersonalBest, commands, commandRoots(commands));
        }

        public static RewardPlan none() {
            return NONE;
        }

        private static Set<String> commandRoots(List<String> commands) {
            Set<String> roots = new LinkedHashSet<>();
            for (String configured : commands) {
                String command = Objects.requireNonNull(configured, "configured command").strip();
                if (command.startsWith("/")) {
                    command = command.substring(1).stripLeading();
                }
                if (!command.isBlank()) {
                    roots.add(commandRoot(command));
                }
            }
            return Set.copyOf(roots);
        }
    }
}
