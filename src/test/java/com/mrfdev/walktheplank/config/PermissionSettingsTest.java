package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class PermissionSettingsTest {
    @Test
    void acceptsDistinctPlayerAndPrivilegedPermissions() {
        PermissionSettings settings = settings(
                "infinityparkour.play",
                "infinityparkour.queue.join",
                "infinityparkour.admin.queue");

        assertEquals("infinityparkour.queue.join", settings.queueJoin());
        assertEquals("infinityparkour.admin.queue", settings.adminQueue());
        assertEquals("infinityparkour.admin.season", settings.adminSeason());
        assertEquals("infinityparkour.admin.export", settings.adminExport());
        assertEquals("infinityparkour.admin.reward", settings.adminReward());
        assertEquals("infinityparkour.admin.investigate", settings.adminInvestigate());
    }

    @Test
    void rejectsCaseInsensitivePlayerAndPrivilegedPermissionCollision() {
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(
                        "InfinityParkour.Admin.Queue",
                        "infinityparkour.queue.join",
                        "infinityparkour.admin.queue"));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(
                        "infinityparkour.play",
                        "InfinityParkour.Admin.Queue",
                        "infinityparkour.admin.queue"));
    }

    private static PermissionSettings settings(
            String playGame,
            String queueJoin,
            String adminQueue) {
        return new PermissionSettings(
                "infinityparkour.opengui",
                "infinityparkour.leavearena",
                playGame,
                queueJoin,
                "infinityparkour.reload",
                "infinityparkour.statscmd",
                "infinityparkour.topcmd",
                "infinityparkour.info",
                "infinityparkour.help",
                "infinityparkour.preferences",
                "infinityparkour.admin",
                "infinityparkour.admin.open",
                "infinityparkour.admin.debug",
                "infinityparkour.admin.stop",
                "infinityparkour.admin.recover",
                "infinityparkour.admin.validate",
                "infinityparkour.admin.arena",
                adminQueue,
                "infinityparkour.admin.season",
                "infinityparkour.admin.export",
                "infinityparkour.admin.reward",
                "infinityparkour.admin.investigate");
    }
}
