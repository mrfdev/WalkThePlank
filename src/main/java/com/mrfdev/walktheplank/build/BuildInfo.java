package com.mrfdev.walktheplank.build;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import org.bukkit.plugin.java.JavaPlugin;

/** Immutable identity embedded into every 1MB release artifact. */
public record BuildInfo(
        String pluginVersion,
        String buildNumber,
        String artifactFile,
        String javaTarget,
        String paperTarget,
        String paperApiVersion,
        String placeholderApiVersion,
        String sourceCommit,
        boolean sourceDirty) {
    private static final String RESOURCE = "build-info.properties";

    public BuildInfo {
        pluginVersion = requireValue(pluginVersion, "pluginVersion");
        buildNumber = requireValue(buildNumber, "buildNumber");
        artifactFile = requireValue(artifactFile, "artifactFile");
        javaTarget = requireValue(javaTarget, "javaTarget");
        paperTarget = requireValue(paperTarget, "paperTarget");
        paperApiVersion = requireValue(paperApiVersion, "paperApiVersion");
        placeholderApiVersion = requireValue(placeholderApiVersion, "placeholderApiVersion");
        sourceCommit = requireValue(sourceCommit, "sourceCommit");
        if (!buildNumber.matches("\\d{3}")) {
            throw new IllegalArgumentException("buildNumber must contain exactly three digits");
        }
        if (!sourceCommit.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                    "sourceCommit must be a full lowercase 40-character Git commit");
        }
    }

    public static BuildInfo load(JavaPlugin plugin) throws IOException {
        Objects.requireNonNull(plugin, "plugin");
        try (InputStream stream = plugin.getResource(RESOURCE)) {
            if (stream == null) {
                throw new IOException("Missing bundled " + RESOURCE);
            }
            return loadProperties(stream);
        }
    }

    static BuildInfo loadProperties(InputStream stream) throws IOException {
        Properties properties = new Properties();
        properties.load(Objects.requireNonNull(stream, "stream"));
        return new BuildInfo(
                properties.getProperty("pluginVersion"),
                properties.getProperty("buildNumber"),
                properties.getProperty("artifactFile"),
                properties.getProperty("javaTarget"),
                properties.getProperty("paperTarget"),
                properties.getProperty("paperApiVersion"),
                properties.getProperty("placeholderApiVersion"),
                properties.getProperty("sourceCommit"),
                requireBoolean(properties.getProperty("sourceDirty"), "sourceDirty"));
    }

    public String releaseLabel() {
        return "v" + pluginVersion + " build " + buildNumber;
    }

    public String sourceLabel() {
        return sourceCommit.substring(0, 12) + (sourceDirty ? "-dirty" : "-clean");
    }

    private static String requireValue(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.contains("${")) {
            throw new IllegalArgumentException("Invalid embedded build value for " + name);
        }
        return normalized;
    }

    private static boolean requireBoolean(String value, String name) {
        String normalized = requireValue(value, name);
        return switch (normalized) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    "Invalid embedded boolean build value for " + name);
        };
    }
}
