package com.mrfdev.walktheplank.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mrfdev.walktheplank.database.RewardPlanStatus;
import com.mrfdev.walktheplank.database.RewardStepStatus;
import com.mrfdev.walktheplank.database.RunStatus;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/** Exact Paper 26.2 Brigadier command grammar. */
final class WalkCommandTree {
    static final List<String> ALIASES =
            List.of("walk", "infinityparkour", "infp");
    private static final int DEFAULT_LIMIT = 20;
    private static final SimpleCommandExceptionType EXACT_PLAYER_ONLY =
            new SimpleCommandExceptionType(new com.mojang.brigadier.LiteralMessage(
                    "WalkThePlank requires one exact online player name."));

    private WalkCommandTree() {
    }

    static LiteralCommandNode<CommandSourceStack> create(
            WalkCommand command) {
        LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("walktheplank")
                        .executes(context -> command.execute(
                                context.getSource(),
                                command.players::openRoot));

        addPlayerCommands(root, command);
        root.then(adminNode(command));
        return root.build();
    }

    private static void addPlayerCommands(
            LiteralArgumentBuilder<CommandSourceStack> root,
            WalkCommand command) {
        root.then(Commands.literal("play")
                .requires(source -> command.playerPermission(
                        source,
                        command.support.permissions().playGame()))
                .executes(context -> command.execute(
                        context.getSource(), command.players::play)));
        root.then(Commands.literal("leave")
                .requires(source -> source.getSender() instanceof Player player
                        && (command.support.games.isPlaying(player)
                                || command.playerPermission(
                                        source,
                                        command.support.permissions()
                                                .leaveArena())))
                .executes(context -> command.execute(
                        context.getSource(), command.players::leave)));
        root.then(Commands.literal("stats")
                .requires(source -> command.playerPermission(
                        source,
                        command.support.permissions().stats()))
                .executes(context -> command.execute(
                        context.getSource(), command.players::stats)));

        LiteralArgumentBuilder<CommandSourceStack> top =
                Commands.literal("top")
                        .requires(source -> command.playerPermission(
                                source,
                                command.support.permissions().top()))
                        .executes(context -> command.execute(
                                context.getSource(),
                                sender -> command.players.top(sender, false)));
        top.then(Commands.literal("all-time")
                .executes(context -> command.execute(
                        context.getSource(),
                        sender -> command.players.top(sender, false))));
        top.then(Commands.literal("season")
                .executes(context -> command.execute(
                        context.getSource(),
                        sender -> command.players.top(sender, true))));
        root.then(top);

        root.then(Commands.literal("info")
                .requires(source -> command.playerPermission(
                        source,
                        command.support.permissions().info()))
                .executes(context -> command.execute(
                        context.getSource(), command.players::info)));
        root.then(Commands.literal("version")
                .requires(source -> command.playerPermission(
                        source,
                        command.support.permissions().info()))
                .executes(context -> command.execute(
                        context.getSource(), command.players::info)));
        root.then(Commands.literal("help")
                .requires(source -> command.playerPermission(
                        source,
                        command.support.permissions().help()))
                .executes(context -> command.execute(
                        context.getSource(), command.players::help)));
        root.then(playerQueueNode(command));

        root.then(Commands.literal("reload")
                .requires(source -> command.adminPermission(
                        source,
                        command.support.permissions().reload()))
                .executes(context -> command.execute(
                        context.getSource(), command::reload)));
        root.then(playerTargetNode(
                "open",
                command,
                command.support.permissions().adminOpen(),
                (sender, player) -> command.players.openOther(sender, player)));
        root.then(Commands.argument(
                        "legacy-player",
                        ArgumentTypes.player())
                .requires(source -> command.adminPermission(
                        source,
                        command.support.permissions().adminOpen()))
                .executes(context -> {
                    Player player = player(context, "legacy-player");
                    return command.execute(
                            context.getSource(),
                            sender -> command.players.openOther(sender, player));
                }));
        root.then(debugNode(command));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> playerQueueNode(
            WalkCommand command) {
        LiteralArgumentBuilder<CommandSourceStack> queue =
                Commands.literal("queue")
                        .requires(source -> command.playerPermission(
                                source,
                                command.support.permissions().playGame()))
                        .executes(context -> command.execute(
                                context.getSource(),
                                sender -> command.queue.player(
                                        sender,
                                        QueueCommands.PlayerAction.STATUS)));
        for (QueueCommands.PlayerAction action :
                QueueCommands.PlayerAction.values()) {
            queue.then(Commands.literal(
                            action.name().toLowerCase(Locale.ROOT))
                    .executes(context -> command.execute(
                            context.getSource(),
                            sender -> command.queue.player(sender, action))));
        }
        return queue;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> adminNode(
            WalkCommand command) {
        LiteralArgumentBuilder<CommandSourceStack> admin =
                Commands.literal("admin")
                        .requires(command::anyAdminPermission)
                        .executes(context -> command.execute(
                                context.getSource(), command::adminHelp));
        admin.then(Commands.literal("help")
                .executes(context -> command.execute(
                        context.getSource(), command::adminHelp)));
        admin.then(playerTargetNode(
                "open",
                command,
                command.support.permissions().adminOpen(),
                (sender, player) -> command.players.openOther(sender, player)));
        admin.then(Commands.literal("reload")
                .requires(source -> command.adminPermission(
                        source,
                        command.support.permissions().reload()))
                .executes(context -> command.execute(
                        context.getSource(), command::reload)));
        admin.then(playerTargetNode(
                "stop",
                command,
                command.support.permissions().adminStop(),
                (sender, player) -> command.players.stop(sender, player)));
        admin.then(Commands.literal("recover")
                .requires(source -> command.adminPermission(
                        source,
                        command.support.permissions().adminRecover()))
                .executes(context -> command.execute(
                        context.getSource(), command.players::recover)));
        admin.then(Commands.literal("validate")
                .requires(source -> command.adminPermission(
                        source,
                        command.support.permissions().adminValidate()))
                .executes(context -> command.execute(
                        context.getSource(), command.arenas::validateAll)));
        admin.then(arenaNode(command));
        admin.then(adminQueueNode(command));
        admin.then(seasonNode(command));
        admin.then(exportNode(command));
        admin.then(rewardNode(command));
        admin.then(investigationNode(command));

        LiteralArgumentBuilder<CommandSourceStack> status =
                Commands.literal("status")
                        .requires(source -> command.adminPermission(
                                source,
                                command.support.permissions().adminDebug()))
                        .executes(context -> command.execute(
                                context.getSource(), command.database::health));
        status.then(Commands.argument("player", ArgumentTypes.player())
                .executes(context -> {
                    Player player = player(context, "player");
                    return command.execute(
                            context.getSource(),
                            sender -> command.players.status(sender, player));
                }));
        admin.then(status);
        admin.then(adminDebugNode(command));
        admin.then(Commands.literal("doctor")
                .requires(source -> command.adminPermission(
                        source,
                        command.support.permissions().adminDebug()))
                .executes(context -> command.execute(
                        context.getSource(), command.database::doctor)));
        return admin;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> arenaNode(
            WalkCommand command) {
        LiteralArgumentBuilder<CommandSourceStack> arena =
                Commands.literal("arena")
                        .requires(source -> command.adminPermission(
                                source,
                                command.support.permissions().adminArena()))
                        .executes(context -> command.execute(
                                context.getSource(), command.arenas::help));
        arena.then(Commands.literal("help")
                .executes(context -> command.execute(
                        context.getSource(), command.arenas::help)));
        arena.then(Commands.literal("list")
                .executes(context -> command.execute(
                        context.getSource(), command.arenas::list)));
        arena.then(arenaEditNode(
                "create", command, ArenaCommands.LocationEdit.CREATE));
        arena.then(arenaEditNode(
                "setstart", command, ArenaCommands.LocationEdit.SET_START));
        arena.then(arenaEditNode(
                "setexit", command, ArenaCommands.LocationEdit.SET_EXIT));
        arena.then(Commands.literal("clear-exit")
                .then(arenaIdArgument(command)
                        .executes(context -> {
                            String id = StringArgumentType.getString(
                                    context, "arena");
                            return command.execute(
                                    context.getSource(),
                                    sender -> command.arenas.clearExit(
                                            sender, id));
                        })));

        LiteralArgumentBuilder<CommandSourceStack> validate =
                Commands.literal("validate")
                        .executes(context -> command.execute(
                                context.getSource(),
                                command.arenas::validateArenas));
        validate.then(arenaIdArgument(command)
                .executes(context -> {
                    String id = StringArgumentType.getString(
                            context, "arena");
                    return command.execute(
                            context.getSource(),
                            sender -> command.arenas.validateArena(sender, id));
                }));
        arena.then(validate);

        arena.then(Commands.literal("remove")
                .then(arenaIdArgument(command)
                        .then(Commands.literal("confirm")
                                .executes(context -> {
                                    String id = StringArgumentType.getString(
                                            context, "arena");
                                    return command.execute(
                                            context.getSource(),
                                            sender -> command.arenas.remove(
                                                    sender, id));
                                }))));
        return arena;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> arenaEditNode(
            String literal,
            WalkCommand command,
            ArenaCommands.LocationEdit edit) {
        return Commands.literal(literal)
                .then(arenaIdArgument(command)
                        .executes(context -> {
                            String id = StringArgumentType.getString(
                                    context, "arena");
                            return command.execute(
                                    context.getSource(),
                                    sender -> command.arenas.locationEdit(
                                            sender, id, edit));
                        }));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
                    CommandSourceStack, String>
            arenaIdArgument(WalkCommand command) {
        return Commands.argument("arena", StringArgumentType.word())
                .suggests((context, builder) -> suggest(
                        builder, command.support.configuredArenaIds()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> adminQueueNode(
            WalkCommand command) {
        LiteralArgumentBuilder<CommandSourceStack> queue =
                Commands.literal("queue")
                        .requires(source -> command.adminPermission(
                                source,
                                command.support.permissions().adminQueue()))
                        .executes(context -> command.execute(
                                context.getSource(),
                                sender -> command.queue.admin(
                                        sender,
                                        QueueCommands.AdminAction.STATUS)));
        for (QueueCommands.AdminAction action :
                QueueCommands.AdminAction.values()) {
            queue.then(Commands.literal(
                            action.name().toLowerCase(Locale.ROOT))
                    .executes(context -> command.execute(
                            context.getSource(),
                            sender -> command.queue.admin(sender, action))));
        }
        return queue;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> seasonNode(
            WalkCommand command) {
        LiteralArgumentBuilder<CommandSourceStack> season =
                Commands.literal("season")
                        .requires(source -> command.adminPermission(
                                source,
                                command.support.permissions().adminSeason()))
                        .executes(context -> command.execute(
                                context.getSource(), command.seasons::list));
        season.then(Commands.literal("list")
                .executes(context -> command.execute(
                        context.getSource(), command.seasons::list)));
        season.then(Commands.literal("create")
                .then(Commands.argument(
                                "name",
                                StringArgumentType.greedyString())
                        .executes(context -> {
                            String name = StringArgumentType.getString(
                                    context, "name");
                            return command.execute(
                                    context.getSource(),
                                    sender -> command.seasons.create(
                                            sender, name));
                        })));
        for (SeasonCommands.Transition transition :
                SeasonCommands.Transition.values()) {
            season.then(Commands.literal(
                            transition.name().toLowerCase(Locale.ROOT))
                    .then(seasonIdArgument(command)
                            .executes(context -> {
                                UUID id = context.getArgument(
                                        "season", UUID.class);
                                return command.execute(
                                        context.getSource(),
                                        sender -> command.seasons.transition(
                                                sender,
                                                transition,
                                                id));
                            })));
        }
        return season;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> exportNode(
            WalkCommand command) {
        LiteralArgumentBuilder<CommandSourceStack> export =
                Commands.literal("export")
                        .requires(source -> command.adminPermission(
                                source,
                                command.support.permissions().adminExport()));
        export.then(Commands.literal("all-time")
                .executes(context -> command.execute(
                        context.getSource(),
                        command.seasons::exportAllTime)));
        export.then(Commands.literal("current-season")
                .executes(context -> command.execute(
                        context.getSource(),
                        command.seasons::exportCurrentSeason)));
        export.then(Commands.literal("season")
                .then(seasonIdArgument(command)
                        .executes(context -> {
                            UUID id = context.getArgument(
                                    "season", UUID.class);
                            return command.execute(
                                    context.getSource(),
                                    sender -> command.seasons.exportSeason(
                                            sender, id));
                        })));
        return export;
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
                    CommandSourceStack, UUID>
            seasonIdArgument(WalkCommand command) {
        return Commands.argument("season", ArgumentTypes.uuid())
                .suggests((context, builder) -> suggest(
                        builder,
                        command.support.scores.seasons().stream()
                                .map(season -> season.id().toString())
                                .toList()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rewardNode(
            WalkCommand command) {
        LiteralArgumentBuilder<CommandSourceStack> reward =
                Commands.literal("reward")
                        .requires(source -> command.adminPermission(
                                source,
                                command.support.permissions().adminReward()))
                        .executes(context -> command.execute(
                                context.getSource(), command.rewards::help));
        reward.then(Commands.literal("help")
                .executes(context -> command.execute(
                        context.getSource(), command.rewards::help)));

        LiteralArgumentBuilder<CommandSourceStack> list =
                Commands.literal("list")
                        .executes(context -> command.execute(
                                context.getSource(),
                                sender -> command.rewards.list(
                                        sender,
                                        RewardPlanStatus.UNKNOWN,
                                        DEFAULT_LIMIT)));
        for (RewardPlanStatus status : RewardPlanStatus.values()) {
            LiteralArgumentBuilder<CommandSourceStack> statusNode =
                    Commands.literal(status.name().toLowerCase(Locale.ROOT))
                            .executes(context -> command.execute(
                                    context.getSource(),
                                    sender -> command.rewards.list(
                                            sender,
                                            status,
                                            DEFAULT_LIMIT)));
            statusNode.then(Commands.argument(
                            "limit",
                            IntegerArgumentType.integer(1, 100))
                    .executes(context -> {
                        int limit = IntegerArgumentType.getInteger(
                                context, "limit");
                        return command.execute(
                                context.getSource(),
                                sender -> command.rewards.list(
                                        sender, status, limit));
                    }));
            list.then(statusNode);
        }
        reward.then(list);

        reward.then(Commands.literal("inspect")
                .then(Commands.argument("plan", ArgumentTypes.uuid())
                        .executes(context -> {
                            UUID plan = context.getArgument(
                                    "plan", UUID.class);
                            return command.execute(
                                    context.getSource(),
                                    sender -> command.rewards.inspect(
                                            sender, plan));
                        })));

        LiteralArgumentBuilder<CommandSourceStack> resolve =
                Commands.literal("resolve");
        var plan = Commands.argument("plan", ArgumentTypes.uuid());
        var step = Commands.argument(
                "step", IntegerArgumentType.integer(0));
        for (RewardStepStatus resolution : List.of(
                RewardStepStatus.SUCCEEDED,
                RewardStepStatus.FAILED,
                RewardStepStatus.SKIPPED)) {
            step.then(Commands.literal(
                            resolution.name().toLowerCase(Locale.ROOT))
                    .then(Commands.literal("confirm")
                            .executes(context -> {
                                UUID planId = context.getArgument(
                                        "plan", UUID.class);
                                int stepIndex =
                                        IntegerArgumentType.getInteger(
                                                context, "step");
                                return command.execute(
                                        context.getSource(),
                                        sender -> command.rewards.resolve(
                                                sender,
                                                planId,
                                                stepIndex,
                                                resolution));
                            })));
        }
        plan.then(step);
        resolve.then(plan);
        reward.then(resolve);

        reward.then(Commands.literal("abandon")
                .then(Commands.argument("plan", ArgumentTypes.uuid())
                        .then(Commands.literal("confirm")
                                .executes(context -> {
                                    UUID planId = context.getArgument(
                                            "plan", UUID.class);
                                    return command.execute(
                                            context.getSource(),
                                            sender -> command.rewards.abandon(
                                                    sender, planId));
                                }))));
        return reward;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> investigationNode(
            WalkCommand command) {
        LiteralArgumentBuilder<CommandSourceStack> run =
                Commands.literal("run")
                        .requires(source -> command.adminPermission(
                                source,
                                command.support.permissions()
                                        .adminInvestigate()))
                        .executes(context -> command.execute(
                                context.getSource(),
                                command.investigations::help));
        run.then(Commands.literal("help")
                .executes(context -> command.execute(
                        context.getSource(),
                        command.investigations::help)));

        LiteralArgumentBuilder<CommandSourceStack> list =
                Commands.literal("list")
                        .executes(context -> command.execute(
                                context.getSource(),
                                sender -> command.investigations.list(
                                        sender,
                                        Optional.empty(),
                                        DEFAULT_LIMIT)));
        list.then(investigationFilterNode(
                command, "all", Optional.empty()));
        for (RunStatus status : RunStatus.values()) {
            list.then(investigationFilterNode(
                    command,
                    status.name().toLowerCase(Locale.ROOT),
                    Optional.of(status)));
        }
        run.then(list);

        run.then(Commands.literal("inspect")
                .then(Commands.argument("run", ArgumentTypes.uuid())
                        .executes(context -> {
                            UUID runId = context.getArgument(
                                    "run", UUID.class);
                            return command.execute(
                                    context.getSource(),
                                    sender -> command.investigations.inspect(
                                            sender, runId));
                        })));
        run.then(uuidInvestigationNode(command, "player"));
        run.then(arenaInvestigationNode(command));
        run.then(uuidInvestigationNode(command, "season"));
        return run;
    }

    private static LiteralArgumentBuilder<CommandSourceStack>
            investigationFilterNode(
                    WalkCommand command,
                    String literal,
                    Optional<RunStatus> status) {
        LiteralArgumentBuilder<CommandSourceStack> node =
                Commands.literal(literal)
                        .executes(context -> command.execute(
                                context.getSource(),
                                sender -> command.investigations.list(
                                        sender, status, DEFAULT_LIMIT)));
        node.then(Commands.argument(
                        "limit",
                        IntegerArgumentType.integer(1, 100))
                .executes(context -> {
                    int limit = IntegerArgumentType.getInteger(
                            context, "limit");
                    return command.execute(
                            context.getSource(),
                            sender -> command.investigations.list(
                                    sender, status, limit));
                }));
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack>
            uuidInvestigationNode(
                    WalkCommand command,
                    String kind) {
        LiteralArgumentBuilder<CommandSourceStack> node =
                Commands.literal(kind);
        var identifier = Commands.argument(kind, ArgumentTypes.uuid())
                .executes(context -> executeUuidInvestigation(
                        command, context, kind, DEFAULT_LIMIT));
        identifier.then(Commands.argument(
                        "limit",
                        IntegerArgumentType.integer(1, 100))
                .executes(context -> executeUuidInvestigation(
                        command,
                        context,
                        kind,
                        IntegerArgumentType.getInteger(context, "limit"))));
        node.then(identifier);
        return node;
    }

    private static int executeUuidInvestigation(
            WalkCommand command,
            CommandContext<CommandSourceStack> context,
            String kind,
            int limit) {
        UUID identifier = context.getArgument(kind, UUID.class);
        return command.execute(context.getSource(), sender -> {
            if (kind.equals("player")) {
                command.investigations.player(
                        sender, identifier, limit);
            } else {
                command.investigations.season(
                        sender, identifier, limit);
            }
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack>
            arenaInvestigationNode(WalkCommand command) {
        LiteralArgumentBuilder<CommandSourceStack> node =
                Commands.literal("arena");
        var identifier = arenaIdArgument(command)
                .executes(context -> {
                    String arena = StringArgumentType.getString(
                            context, "arena");
                    return command.execute(
                            context.getSource(),
                            sender -> command.investigations.arena(
                                    sender, arena, DEFAULT_LIMIT));
                });
        identifier.then(Commands.argument(
                        "limit",
                        IntegerArgumentType.integer(1, 100))
                .executes(context -> {
                    String arena = StringArgumentType.getString(
                            context, "arena");
                    int limit = IntegerArgumentType.getInteger(
                            context, "limit");
                    return command.execute(
                            context.getSource(),
                            sender -> command.investigations.arena(
                                    sender, arena, limit));
                }));
        node.then(identifier);
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> debugNode(
            WalkCommand command) {
        return buildDebugNode(command, "debug");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> adminDebugNode(
            WalkCommand command) {
        return buildDebugNode(command, "debug");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildDebugNode(
            WalkCommand command,
            String literal) {
        LiteralArgumentBuilder<CommandSourceStack> debug =
                Commands.literal(literal)
                        .requires(source -> command.adminPermission(
                                source,
                                command.support.permissions().adminDebug()))
                        .executes(context -> command.execute(
                                context.getSource(),
                                sender -> command.database.debug(
                                        sender, "overview")));
        for (String page : CommandSupport.DEBUG_PAGES) {
            debug.then(Commands.literal(page)
                    .executes(context -> command.execute(
                            context.getSource(),
                            sender -> command.database.debug(sender, page))));
        }
        return debug;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> playerTargetNode(
            String literal,
            WalkCommand command,
            String permission,
            PlayerAction action) {
        return Commands.literal(literal)
                .requires(source -> command.adminPermission(
                        source, permission))
                .then(Commands.argument("player", ArgumentTypes.player())
                        .executes(context -> {
                            Player player = player(context, "player");
                            return command.execute(
                                    context.getSource(),
                                    sender -> action.execute(sender, player));
                        }));
    }

    private static Player player(
            CommandContext<CommandSourceStack> context,
            String argument)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String token = context.getNodes().stream()
                .filter(parsed -> parsed.getNode().getName().equals(argument))
                .map(parsed -> parsed.getRange().get(context.getInput()))
                .reduce((first, second) -> second)
                .orElse("");
        if (token.startsWith("@")) {
            throw EXACT_PLAYER_ONLY.create();
        }
        PlayerSelectorArgumentResolver resolver = context.getArgument(
                argument, PlayerSelectorArgumentResolver.class);
        return resolver.resolve(context.getSource()).getFirst();
    }

    private static CompletableFuture<Suggestions> suggest(
            SuggestionsBuilder builder,
            Collection<String> choices) {
        String remaining = builder.getRemainingLowerCase();
        choices.stream()
                .filter(choice -> choice.toLowerCase(Locale.ROOT)
                        .startsWith(remaining))
                .distinct()
                .sorted()
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    @FunctionalInterface
    private interface PlayerAction {
        void execute(
                org.bukkit.command.CommandSender sender,
                Player player);
    }
}
