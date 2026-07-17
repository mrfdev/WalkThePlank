package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class QueueSettingsTest {
    @Test
    void defaultsUseAnExplicitReadinessWindow() {
        QueueSettings defaults = QueueSettings.defaults();
        assertEquals(Duration.ofSeconds(20), defaults.readinessWindow());
        assertEquals(Duration.ofSeconds(5), defaults.reminderInterval());
    }

    @Test
    void rejectsReminderSlowerThanReadinessWindow() {
        assertThrows(IllegalArgumentException.class, () -> new QueueSettings(
                true,
                Duration.ZERO,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10)));
    }
}
