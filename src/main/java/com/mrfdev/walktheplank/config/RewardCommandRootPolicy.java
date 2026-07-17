package com.mrfdev.walktheplank.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict allow-list policy for trusted console reward command roots. */
final class RewardCommandRootPolicy {
    static final int MAXIMUM_ROOTS = 32;
    private static final Pattern SAFE_ROOT =
            Pattern.compile("[a-z0-9][a-z0-9:_-]{0,127}");

    private RewardCommandRootPolicy() {
    }

    static Set<String> parse(Object configuredRoots) {
        if (!(configuredRoots instanceof List<?> roots)) {
            throw new IllegalArgumentException(
                    "rewards.allowedCommandRoots must be a list");
        }
        if (roots.isEmpty() || roots.size() > MAXIMUM_ROOTS) {
            throw new IllegalArgumentException(
                    "rewards.allowedCommandRoots must contain 1 through "
                            + MAXIMUM_ROOTS + " roots");
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object rawRoot : roots) {
            if (!(rawRoot instanceof String configuredRoot)) {
                throw new IllegalArgumentException(
                        "Every rewards.allowedCommandRoots entry must be a string");
            }
            String root = normalize(configuredRoot);
            if (!result.add(root)) {
                throw new IllegalArgumentException(
                        "rewards.allowedCommandRoots must not contain duplicates");
            }
        }
        return Set.copyOf(result);
    }

    static String normalize(String configuredRoot) {
        String root = Objects.requireNonNull(configuredRoot, "configuredRoot")
                .strip()
                .toLowerCase(Locale.ROOT);
        if (!SAFE_ROOT.matcher(root).matches()) {
            throw new IllegalArgumentException(
                    "Reward command roots must use only safe command-key characters");
        }
        return root;
    }

    static String rootOf(String configuredCommand) {
        String command = Objects.requireNonNull(configuredCommand, "configuredCommand");
        command = command.startsWith("/")
                ? command.substring(1).stripLeading()
                : command;
        int separator = 0;
        while (separator < command.length()
                && !Character.isWhitespace(command.charAt(separator))) {
            separator++;
        }
        return normalize(command.substring(0, separator));
    }
}
