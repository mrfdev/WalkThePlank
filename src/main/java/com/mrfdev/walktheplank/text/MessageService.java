package com.mrfdev.walktheplank.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

public final class MessageService {
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

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
        String prefix = translations.get().getString("chat.prefix", "&3WalkThePlank » &7");
        return deserialize(prefix + resolve(path, replacements));
    }

    public Component translated(String path, Map<String, ?> replacements) {
        return deserialize(resolve(path, replacements));
    }

    public Component deserialize(String input) {
        return LEGACY.deserialize(input == null ? "" : input);
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

    private String resolve(String path, Map<String, ?> replacements) {
        String value = translations.get().getString(path);
        if (value == null) {
            value = "Missing translation: " + path;
        }
        for (Map.Entry<String, ?> replacement : replacements.entrySet()) {
            value = value.replace("{{" + replacement.getKey() + "}}", String.valueOf(replacement.getValue()));
        }
        return value;
    }
}
