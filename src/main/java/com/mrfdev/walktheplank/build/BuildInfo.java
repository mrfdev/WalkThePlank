package com.mrfdev.walktheplank.build;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.plugin.java.JavaPlugin;

/** Immutable identity embedded into every 1MB release artifact. */
public record BuildInfo(
        String pluginVersion,
        String buildNumber,
        String artifactFile,
        String javaTarget,
        String paperTarget,
        String paperApiVersion,
        int paperMinimumBuild,
        String placeholderApiVersion,
        String sourceCommit,
        boolean sourceDirty) {
    private static final String RESOURCE = "build-info.properties";
    private static final Pattern PAPER_API_VERSION =
            Pattern.compile("^(\\d+\\.\\d+)\\.build\\.(\\d+)-(?:alpha|beta|stable)$");

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
        Matcher paperApi = PAPER_API_VERSION.matcher(paperApiVersion);
        if (!paperApi.matches() || !paperApi.group(1).equals(paperTarget)) {
            throw new IllegalArgumentException(
                    "paperApiVersion must be an exact build on the Paper target line");
        }
        if (paperMinimumBuild < 1) {
            throw new IllegalArgumentException("paperMinimumBuild must be positive");
        }
        if (Integer.parseInt(paperApi.group(2)) != paperMinimumBuild) {
            throw new IllegalArgumentException(
                    "paperMinimumBuild must match the exact compiled Paper API build");
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
                requirePositiveInt(
                        properties.getProperty("paperMinimumBuild"),
                        "paperMinimumBuild"),
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

    public int paperApiBuild() {
        Matcher paperApi = PAPER_API_VERSION.matcher(paperApiVersion);
        if (!paperApi.matches()) {
            throw new IllegalStateException("Validated Paper API version no longer matches");
        }
        return Integer.parseInt(paperApi.group(2));
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

    private static int requirePositiveInt(String value, String name) {
        String normalized = requireValue(value, name);
        if (!normalized.matches("[1-9]\\d*")) {
            throw new IllegalArgumentException(
                    "Invalid embedded positive integer build value for " + name);
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Embedded build value is too large for " + name,
                    exception);
        }
    }
}
