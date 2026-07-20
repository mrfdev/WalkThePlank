package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class PermissionDescriptorTest {
    @Test
    void preferencesRequireAnExplicitGrantOrTheAdminParent() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/plugin.yml")) {
            assertTrue(input != null, "processed plugin.yml must be available to tests");
            YamlConfiguration descriptor = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));

            assertFalse(descriptor.contains(
                    "permissions.infinityparkour.player.children.infinityparkour.preferences"));
            assertTrue(descriptor.getBoolean(
                    "permissions.infinityparkour.admin.children.infinityparkour.preferences"));
            assertFalse(descriptor.getBoolean(
                    "permissions.infinityparkour.preferences.default"));

            assertFalse(descriptor.contains(
                    "permissions.infinityparkour.player.children.infinityparkour.queue.join"));
            assertTrue(descriptor.getBoolean(
                    "permissions.infinityparkour.admin.children.infinityparkour.queue.join"));
            assertFalse(descriptor.getBoolean(
                    "permissions.infinityparkour.queue.join.default"));
        }
    }
}
