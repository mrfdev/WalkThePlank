package com.mrfdev.walktheplank.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Atomic, validated editing of the manual event participation switch. */
public final class EventConfigurationEditor {
    private final ConfigurationManager validator;
    private final AtomicConfigFile atomicFile;

    public EventConfigurationEditor(JavaPlugin plugin, ConfigurationManager validator) {
        Objects.requireNonNull(plugin, "plugin");
        this.validator = Objects.requireNonNull(validator, "validator");
        Path configFile = plugin.getDataFolder().toPath().resolve("config.yml");
        atomicFile = new AtomicConfigFile(configFile);
    }

    public EditPreparation prepare(
            ConfigurationManager.ConfigurationFiles files,
            long expectedGeneration,
            boolean enabled) throws IOException {
        ConfigurationManager.ConfigurationFiles checked = Objects.requireNonNull(files, "files");
        if (expectedGeneration < 0L) {
            throw new IllegalArgumentException("expectedGeneration cannot be negative");
        }
        YamlConfiguration candidate = validator.editableConfig(checked);
        applyEnabled(candidate, enabled);
        ConfigurationValidationReport validation = validator.validateCandidate(candidate, checked);
        if (!validation.valid()) {
            return new EditPreparation(validation, Optional.empty());
        }
        ConfigurationManager.ConfigurationSnapshot snapshot =
                validator.prepareCandidate(candidate, checked);
        return new EditPreparation(
                validation,
                Optional.of(new PreparedEdit(
                        checked,
                        candidate.saveToString(),
                        snapshot,
                        validation,
                        expectedGeneration,
                        enabled)));
    }

    static void applyEnabled(YamlConfiguration candidate, boolean enabled) {
        Objects.requireNonNull(candidate, "candidate").set("event.enabled", enabled);
    }

    /** Worker-only compare-and-swap persistence. */
    public synchronized CommittedEdit commit(PreparedEdit prepared) throws IOException {
        PreparedEdit checked = Objects.requireNonNull(prepared, "prepared");
        validator.requireFilesUnchanged(checked.source());
        validator.requireDatabaseStorageSafe(checked.snapshot().databaseSettings());
        AtomicConfigFile.CommitToken token = atomicFile.replaceWithBackup(
                checked.source().configBytes(), checked.contents());
        try {
            validator.requireTranslationsUnchanged(checked.source());
        } catch (IOException failure) {
            try {
                atomicFile.restoreBackup(token);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        return new CommittedEdit(checked, token);
    }

    public synchronized void rollback(CommittedEdit committed) throws IOException {
        atomicFile.restoreBackup(Objects.requireNonNull(committed, "committed").commitToken);
    }

    public synchronized void requireCommittedUnchanged(CommittedEdit committed)
            throws IOException {
        CommittedEdit checked = Objects.requireNonNull(committed, "committed");
        atomicFile.requireCommittedUnchanged(checked.commitToken);
        validator.requireTranslationsUnchanged(checked.prepared().source());
    }

    public record EditPreparation(
            ConfigurationValidationReport validation,
            Optional<PreparedEdit> prepared) {
        public EditPreparation {
            Objects.requireNonNull(validation, "validation");
            prepared = Objects.requireNonNull(prepared, "prepared");
            if (validation.valid() != prepared.isPresent()) {
                throw new IllegalArgumentException(
                        "A valid event edit must have exactly one prepared candidate");
            }
        }
    }

    public record PreparedEdit(
            ConfigurationManager.ConfigurationFiles source,
            String contents,
            ConfigurationManager.ConfigurationSnapshot snapshot,
            ConfigurationValidationReport validation,
            long expectedGeneration,
            boolean enabled) {
        public PreparedEdit {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(contents, "contents");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(validation, "validation");
            if (!validation.valid() || expectedGeneration < 0L) {
                throw new IllegalArgumentException("Prepared event edit is invalid");
            }
        }
    }

    public static final class CommittedEdit {
        private final PreparedEdit prepared;
        private final AtomicConfigFile.CommitToken commitToken;

        private CommittedEdit(
                PreparedEdit prepared,
                AtomicConfigFile.CommitToken commitToken) {
            this.prepared = Objects.requireNonNull(prepared, "prepared");
            this.commitToken = Objects.requireNonNull(commitToken, "commitToken");
        }

        public PreparedEdit prepared() {
            return prepared;
        }
    }
}
