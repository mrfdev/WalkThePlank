package com.mrfdev.walktheplank.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

public final class MessageService {
    /**
     * Explicit marker for trusted MiniMessage templates. Unmarked values retain the legacy
     * ampersand format used by existing installations.
     */
    public static final String MINIMESSAGE_PREFIX = "minimessage:";

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Supplier<YamlConfiguration> translations;

    public MessageService(Supplier<YamlConfiguration> translations) {
        this.translations = Objects.requireNonNull(translations, "translations");
    }

    public void send(CommandSender recipient, String path) {
        send(recipient, path, Map.of());
    }

    public void send(CommandSender recipient, String path, Map<String, ?> replacements) {
        recipient.sendMessage(prefixed(path, replacements));
    }

    public Component prefixed(String path, Map<String, ?> replacements) {
        return prefixedTemplate(rawTranslation(path), replacements);
    }

    public Component prefixedTemplate(
            String trustedTemplate,
            Map<String, ?> literalReplacements) {
        String prefix = translations.get().getString("chat.prefix", "&3WalkThePlank » &7");
        String message = trustedTemplate == null ? "" : trustedTemplate;
        if (usesMiniMessage(prefix) == usesMiniMessage(message)) {
            return render(combineTrustedTemplates(prefix, message), literalReplacements);
        }
        return render(prefix, Map.of()).append(render(message, literalReplacements));
    }

    public Component translated(String path, Map<String, ?> replacements) {
        return render(rawTranslation(path), replacements);
    }

    /**
     * Parses a trusted formatting template and then replaces its named tokens with literal text
     * components. Replacement values are never interpreted as legacy codes or MiniMessage tags.
     */
    public Component render(String trustedTemplate, Map<String, ?> literalReplacements) {
        Objects.requireNonNull(literalReplacements, "literalReplacements");
        Component parsed = deserialize(trustedTemplate);
        if (literalReplacements.isEmpty()) {
            return parsed;
        }

        List<String> keys = literalReplacements.keySet().stream()
                .map(key -> Objects.requireNonNull(key, "replacement key"))
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo))
                .toList();
        String alternatives = keys.stream()
                .map(Pattern::quote)
                .reduce((left, right) -> left + "|" + right)
                .orElseThrow();
        Pattern placeholders = Pattern.compile("\\{\\{(" + alternatives + ")}}");
        TextReplacementConfig replacement = TextReplacementConfig.builder()
                .match(placeholders)
                .replacement((match, matchedText) -> matchedText
                        .content(String.valueOf(literalReplacements.get(match.group(1))))
                        .build())
                .build();
        return parsed.replaceText(replacement);
    }

    /**
     * Deserializes trusted configuration or plugin-authored formatting.
     *
     * <p>Use {@link #render(String, Map)} whenever any part of the output comes from a player,
     * database row, runtime hook, or non-format configuration value.
     */
    public Component deserialize(String input) {
        String safeInput = input == null ? "" : input;
        if (usesMiniMessage(safeInput)) {
            return MINI_MESSAGE.deserialize(safeInput.substring(MINIMESSAGE_PREFIX.length()));
        }
        return LEGACY.deserialize(safeInput);
    }

    public List<Component> translatedList(String path) {
        List<Component> result = new ArrayList<>();
        for (String line : translations.get().getStringList(path)) {
            result.add(deserialize(line));
        }
        return List.copyOf(result);
    }

    public String raw(String path, String fallback) {
        return translations.get().getString(path, fallback);
    }

    public List<String> rawList(String path) {
        return List.copyOf(translations.get().getStringList(path));
    }

    private String rawTranslation(String path) {
        String value = translations.get().getString(path);
        if (value == null) {
            value = "Missing translation: " + path;
        }
        return value;
    }

    private static boolean usesMiniMessage(String value) {
        return value != null && value.startsWith(MINIMESSAGE_PREFIX);
    }

    private static String combineTrustedTemplates(String first, String second) {
        if (usesMiniMessage(first)) {
            return MINIMESSAGE_PREFIX
                    + first.substring(MINIMESSAGE_PREFIX.length())
                    + second.substring(MINIMESSAGE_PREFIX.length());
        }
        return first + second;
    }
}
