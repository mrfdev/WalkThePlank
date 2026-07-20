package com.mrfdev.walktheplank.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Builds a deterministic SHA-256 fingerprint from an allow-listed, redacted projection. */
final class SafeConfigFingerprint {
    private static final List<String> SCALAR_PATHS = List.of(
            "configVersion",
            "gameplay.fallDistance",
            "gameplay.horizontalRadius",
            "gameplay.maximumRunSeconds",
            "gameplay.idleTimeoutSeconds",
            "gameplay.onlyReplaceAir",
            "particle.show",
            "particle.type",
            "particle.count",
            "theme.active",
            "runFinishCommands",
            "rewards.onlyOnPersonalBest",
            "queue.enabled",
            "queue.joinCooldownSeconds",
            "queue.readinessWindowSeconds",
            "queue.reminderIntervalSeconds",
            "arenaSelection.policy",
            "arenaSelection.pinnedArena",
            "permissions.openGui",
            "permissions.leaveArena",
            "permissions.playGame",
            "permissions.reload",
            "permissions.statsCmd",
            "permissions.topCmd",
            "permissions.info",
            "permissions.help",
            "permissions.admin",
            "permissions.adminOpen",
            "permissions.adminDebug",
            "permissions.adminStop",
            "permissions.adminRecover",
            "permissions.adminValidate",
            "permissions.adminArena",
            "permissions.adminQueue",
            "permissions.adminSeason",
            "permissions.adminExport",
            "permissions.adminReward",
            "database.type",
            "database.sqlite.busyTimeoutMillis");

    private SafeConfigFingerprint() {
    }

    static String fingerprint(YamlConfiguration source) {
        StringBuilder projection = new StringBuilder("walktheplank-config-v1\n");
        for (String path : SCALAR_PATHS) {
            append(projection, path, source.get(path));
        }

        List<String> blocks = source.getStringList("parkourBlocks");
        for (int index = 0; index < blocks.size(); index++) {
            append(projection, "parkourBlocks[" + index + ']', blocks.get(index));
        }

        ConfigurationSection presets =
                source.getConfigurationSection("theme-presets");
        if (presets == null) {
            append(projection, "theme-presets.type", typeOf(source.get("theme-presets")));
        } else {
            List<String> presetNames =
                    new ArrayList<>(presets.getKeys(false));
            Collections.sort(presetNames);
            for (String presetName : presetNames) {
                String path = "theme-presets." + presetName;
                List<String> presetBlocks =
                        source.getStringList(path + ".parkourBlocks");
                for (int index = 0; index < presetBlocks.size(); index++) {
                    append(
                            projection,
                            path + ".parkourBlocks[" + index + ']',
                            presetBlocks.get(index));
                }
                append(
                        projection,
                        path + ".particle.show",
                        source.get(path + ".particle.show"));
                append(
                        projection,
                        path + ".particle.type",
                        source.get(path + ".particle.type"));
                append(
                        projection,
                        path + ".particle.count",
                        source.get(path + ".particle.count"));
            }
        }

        List<String> rewardRoots = source.getStringList("rewards.allowedCommandRoots");
        for (int index = 0; index < rewardRoots.size(); index++) {
            append(projection, "rewards.allowedCommandRoots[" + index + ']', rewardRoots.get(index));
        }

        Object rawArenas = source.get("startPositions");
        if (rawArenas instanceof List<?> arenas) {
            for (int index = 0; index < arenas.size(); index++) {
                Object rawArena = arenas.get(index);
                if (rawArena instanceof Map<?, ?> arena) {
                    appendLocation(projection, "startPositions[" + index + "]", arena);
                    append(projection, "startPositions[" + index + "].id", arena.get("id"));
                    append(projection, "startPositions[" + index + "].useCustomEndPosition",
                            arena.get("useCustomEndPosition"));
                    Object rawExit = arena.get("endPos");
                    if (rawExit instanceof Map<?, ?> exit) {
                        appendLocation(projection, "startPositions[" + index + "].endPos", exit);
                    }
                } else {
                    append(projection, "startPositions[" + index + "].type", typeOf(rawArena));
                }
            }
        } else {
            append(projection, "startPositions.type", typeOf(rawArenas));
        }

        Object rawTiers = source.get("finishCommands");
        if (rawTiers instanceof List<?> tiers) {
            for (int tierIndex = 0; tierIndex < tiers.size(); tierIndex++) {
                Object rawTier = tiers.get(tierIndex);
                if (!(rawTier instanceof Map<?, ?> tier)) {
                    append(projection, "finishCommands[" + tierIndex + "].type", typeOf(rawTier));
                    continue;
                }
                append(projection, "finishCommands[" + tierIndex + "].minScore", tier.get("minScore"));
                append(projection, "finishCommands[" + tierIndex + "].maxScore", tier.get("maxScore"));
                Object rawCommands = tier.get("commands");
                if (rawCommands instanceof List<?> commands) {
                    append(projection, "finishCommands[" + tierIndex + "].commandCount", commands.size());
                    for (int commandIndex = 0; commandIndex < commands.size(); commandIndex++) {
                        String command = String.valueOf(commands.get(commandIndex));
                        append(projection,
                                "finishCommands[" + tierIndex + "].commandDigest[" + commandIndex + ']',
                                digest(command));
                    }
                } else {
                    append(projection, "finishCommands[" + tierIndex + "].commands.type", typeOf(rawCommands));
                }
            }
        }

        // The database location affects behavior but must never be exposed in diagnostics.
        append(projection, "database.sqlite.fileDigest",
                digest(String.valueOf(source.get("database.sqlite.file", "database.db"))));

        // Unknown key names are safe to include; their possibly secret values are deliberately excluded.
        List<String> keyNames = new ArrayList<>(source.getKeys(true));
        Collections.sort(keyNames);
        for (String key : keyNames) {
            append(projection, "known-key", key);
        }
        return digest(projection.toString());
    }

    private static void appendLocation(StringBuilder target, String path, Map<?, ?> values) {
        for (String key : List.of("world", "x", "y", "z", "yaw", "pitch")) {
            append(target, path + '.' + key, values.get(key));
        }
    }

    private static void append(StringBuilder target, String key, Object value) {
        target.append(key)
                .append('=')
                .append(typeOf(value))
                .append(':')
                .append(String.valueOf(value))
                .append('\n');
    }

    private static String typeOf(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
