package com.mrfdev.walktheplank.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record PermissionSettings(
        String openGui,
        String leaveArena,
        String playGame,
        String queueJoin,
        String reload,
        String stats,
        String top,
        String info,
        String help,
        String preferences,
        String admin,
        String adminOpen,
        String adminDebug,
        String adminStop,
        String adminRecover,
        String adminValidate,
        String adminArena,
        String adminQueue,
        String adminSeason,
        String adminExport,
        String adminReward,
        String adminInvestigate) {
    public PermissionSettings(
            String openGui,
            String leaveArena,
            String playGame,
            String reload,
            String stats,
            String top,
            String info,
            String help,
            String preferences,
            String admin,
            String adminOpen,
            String adminDebug,
            String adminStop,
            String adminRecover,
            String adminValidate,
            String adminArena,
            String adminQueue,
            String adminSeason,
            String adminExport,
            String adminReward,
            String adminInvestigate) {
        this(
                openGui,
                leaveArena,
                playGame,
                "infinityparkour.queue.join",
                reload,
                stats,
                top,
                info,
                help,
                preferences,
                admin,
                adminOpen,
                adminDebug,
                adminStop,
                adminRecover,
                adminValidate,
                adminArena,
                adminQueue,
                adminSeason,
                adminExport,
                adminReward,
                adminInvestigate);
    }

    public PermissionSettings(
            String openGui,
            String leaveArena,
            String playGame,
            String reload,
            String stats,
            String top,
            String info,
            String help,
            String admin,
            String adminOpen,
            String adminDebug,
            String adminStop,
            String adminRecover,
            String adminValidate,
            String adminArena,
            String adminQueue,
            String adminSeason,
            String adminExport,
            String adminReward,
            String adminInvestigate) {
        this(
                openGui,
                leaveArena,
                playGame,
                "infinityparkour.queue.join",
                reload,
                stats,
                top,
                info,
                help,
                "infinityparkour.preferences",
                admin,
                adminOpen,
                adminDebug,
                adminStop,
                adminRecover,
                adminValidate,
                adminArena,
                adminQueue,
                adminSeason,
                adminExport,
                adminReward,
                adminInvestigate);
    }

    public PermissionSettings {
        openGui = validate(openGui, "openGui");
        leaveArena = validate(leaveArena, "leaveArena");
        playGame = validate(playGame, "playGame");
        queueJoin = validate(queueJoin, "queueJoin");
        reload = validate(reload, "reload");
        stats = validate(stats, "stats");
        top = validate(top, "top");
        info = validate(info, "info");
        help = validate(help, "help");
        preferences = validate(preferences, "preferences");
        admin = validate(admin, "admin");
        adminOpen = validate(adminOpen, "adminOpen");
        adminDebug = validate(adminDebug, "adminDebug");
        adminStop = validate(adminStop, "adminStop");
        adminRecover = validate(adminRecover, "adminRecover");
        adminValidate = validate(adminValidate, "adminValidate");
        adminArena = validate(adminArena, "adminArena");
        adminQueue = validate(adminQueue, "adminQueue");
        adminSeason = validate(adminSeason, "adminSeason");
        adminExport = validate(adminExport, "adminExport");
        adminReward = validate(adminReward, "adminReward");
        adminInvestigate = validate(adminInvestigate, "adminInvestigate");

        Map<String, String> playerPermissions = new LinkedHashMap<>();
        playerPermissions.put("openGui", openGui);
        playerPermissions.put("leaveArena", leaveArena);
        playerPermissions.put("playGame", playGame);
        playerPermissions.put("queueJoin", queueJoin);
        playerPermissions.put("stats", stats);
        playerPermissions.put("top", top);
        playerPermissions.put("info", info);
        playerPermissions.put("help", help);
        playerPermissions.put("preferences", preferences);

        Map<String, String> privilegedPermissions = new LinkedHashMap<>();
        privilegedPermissions.put("reload", reload);
        privilegedPermissions.put("admin", admin);
        privilegedPermissions.put("adminOpen", adminOpen);
        privilegedPermissions.put("adminDebug", adminDebug);
        privilegedPermissions.put("adminStop", adminStop);
        privilegedPermissions.put("adminRecover", adminRecover);
        privilegedPermissions.put("adminValidate", adminValidate);
        privilegedPermissions.put("adminArena", adminArena);
        privilegedPermissions.put("adminQueue", adminQueue);
        privilegedPermissions.put("adminSeason", adminSeason);
        privilegedPermissions.put("adminExport", adminExport);
        privilegedPermissions.put("adminReward", adminReward);
        privilegedPermissions.put("adminInvestigate", adminInvestigate);
        requirePrivilegeSeparation(playerPermissions, privilegedPermissions);
    }

    private static String validate(String permission, String name) {
        Objects.requireNonNull(permission, name);
        String normalized = permission.strip();
        if (normalized.isEmpty() || normalized.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Permission " + name + " must be a nonblank node without spaces");
        }
        return normalized;
    }

    private static void requirePrivilegeSeparation(
            Map<String, String> playerPermissions,
            Map<String, String> privilegedPermissions) {
        for (Map.Entry<String, String> privileged : privilegedPermissions.entrySet()) {
            String privilegedNode = privileged.getValue().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> player : playerPermissions.entrySet()) {
                if (privilegedNode.equals(player.getValue().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            "Privileged permission " + privileged.getKey()
                                    + " must not reuse player permission " + player.getKey());
                }
            }
        }
    }
}
