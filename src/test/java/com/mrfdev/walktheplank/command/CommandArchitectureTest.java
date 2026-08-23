package com.mrfdev.walktheplank.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.junit.jupiter.api.Test;

class CommandArchitectureTest {
    private static final Path PROJECT = Path.of(
            System.getProperty("user.dir")).toAbsolutePath().normalize();
    private static final Path COMMANDS = PROJECT.resolve(
            "src/main/java/com/mrfdev/walktheplank/command");

    @Test
    void coordinatorNoLongerUsesLegacyBukkitCommandInterfaces()
            throws IOException {
        assertFalse(CommandExecutor.class.isAssignableFrom(WalkCommand.class));
        assertFalse(TabCompleter.class.isAssignableFrom(WalkCommand.class));

        String coordinator = Files.readString(
                COMMANDS.resolve("WalkCommand.java"));
        assertTrue(coordinator.lines().count() < 500L);
        assertFalse(coordinator.contains("PluginCommand"));
        assertFalse(coordinator.contains("setExecutor"));
        assertFalse(coordinator.contains("setTabCompleter"));
    }

    @Test
    void lifecycleTreeUsesTypedArgumentsAndExplicitConfirmations()
            throws IOException {
        String plugin = Files.readString(PROJECT.resolve(
                "src/main/java/com/mrfdev/walktheplank/WalkThePlankPlugin.java"));
        String tree = Files.readString(
                COMMANDS.resolve("WalkCommandTree.java"));
        String tests = Files.readString(
                COMMANDS.resolve("TestCommands.java"));
        String descriptor = Files.readString(
                PROJECT.resolve("src/main/resources/plugin.yml"));

        assertTrue(plugin.contains("getLifecycleManager().registerEventHandler"));
        assertTrue(plugin.contains("LifecycleEvents.COMMANDS"));
        assertTrue(tree.contains("ArgumentTypes.player()"));
        assertTrue(tree.contains("ArgumentTypes.uuid()"));
        assertTrue(tree.contains("IntegerArgumentType.integer(1, 100)"));
        assertTrue(tree.contains("IntegerArgumentType.integer(1, 500)"));
        assertTrue(tree.contains("Commands.literal(\"confirm\")"));
        assertTrue(tree.contains("Commands.literal(\"event\")"));
        assertTrue(tree.contains("Commands.literal(\"enabled\")"));
        assertTrue(tree.contains("Commands.literal(\"test\")"));
        assertTrue(tree.contains("Commands.literal(\"fast-forward\")"));
        assertTrue(tree.contains("Commands.literal(\"release\")"));
        assertTrue(tree.contains("Commands.literal(\"started\")"));
        assertTrue(tree.contains("Commands.literal(\"export\")"));
        assertTrue(tree.contains("new InstantArgument()"));
        assertTrue(tree.contains("StringArgumentType.string()"));
        assertTrue(tree.contains(".adminInvestigate())"));
        assertTrue(tree.contains(".adminExport())"));
        assertTrue(tree.contains("context.getSource(), command.tests::help"));
        assertTrue(tests.contains(
                "Started a non-scoring fast-forward toward score"));
        assertFalse(tests.contains("Fast-forwarded this test run to score"));
        assertTrue(tree.contains(
                "List.of(\"walk\", \"infinityparkour\", \"infp\")"));
        assertTrue(descriptor.contains("softdepend: [Multiverse-Core,"));
        assertFalse(descriptor.contains("\ncommands:"));
    }

    @Test
    void everyRequestedCommandDomainHasItsOwnModule() {
        for (String module : List.of(
                "PlayerCommands.java",
                "QueueCommands.java",
                "ArenaCommands.java",
                "EventCommands.java",
                "SeasonCommands.java",
                "RewardCommands.java",
                "InvestigationCommands.java",
                "InstantArgument.java",
                "TestCommands.java",
                "DatabaseCommands.java")) {
            assertTrue(Files.isRegularFile(COMMANDS.resolve(module)), module);
        }
    }

    @Test
    void experimentalPaperApisAndExternalEconomyDatabaseApisStayOut()
            throws IOException {
        String productionSources;
        try (var files = Files.walk(PROJECT.resolve("src/main/java"))) {
            productionSources = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    })
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        assertFalse(productionSources.contains("io.papermc.paper.dialog"));
        assertFalse(productionSources.contains("io.papermc.paper.datacomponent"));
        assertFalse(productionSources.contains("net.milkbowl.vault"));
        assertFalse(productionSources.contains("com.Zrips"));
        assertFalse(productionSources.contains("com.mysql"));
    }
}
