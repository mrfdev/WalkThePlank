package com.mrfdev.walktheplank.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class MessageServiceTest {
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    @Test
    void legacyReplacementRemainsLiteralAndRetainsTemplateStyle() {
        MessageService messages = service();
        String attackerControlled = "&4Mallory <bold>{{score}}</bold> \u00a7khidden";

        Component rendered = messages.render(
                "&7Player &c{{playerName}} &7scored &f{{score}}",
                Map.of("playerName", attackerControlled, "score", 42));

        assertEquals(
                "Player " + attackerControlled + " scored 42",
                PLAIN.serialize(rendered));
        TextNodeView literal = textNodeViews(rendered).stream()
                .filter(view -> view.component().content().equals(attackerControlled))
                .findFirst()
                .orElseThrow();
        assertEquals(NamedTextColor.RED, literal.effectiveColor());
        assertTrue(textNodes(rendered).stream()
                .noneMatch(node -> node.content().equals("42") && node.color() == NamedTextColor.DARK_RED));
    }

    @Test
    void miniMessageReplacementRemainsLiteral() {
        MessageService messages = service();
        String attackerControlled = "<bold><click:run_command:'/op @s'>&4Mallory</click></bold>";

        Component rendered = messages.render(
                MessageService.MINIMESSAGE_PREFIX
                        + "<gray>Player <red>{{playerName}}</red> scored <white>{{score}}</white>",
                Map.of("playerName", attackerControlled, "score", 9));

        assertEquals(
                "Player " + attackerControlled + " scored 9",
                PLAIN.serialize(rendered));
        TextComponent literal = textNodes(rendered).stream()
                .filter(node -> node.content().equals(attackerControlled))
                .findFirst()
                .orElseThrow();
        assertEquals(NamedTextColor.RED, literal.color());
        assertNull(literal.clickEvent());
    }

    @Test
    void prefixedTranslationDoesNotInterpretReplacementFormatting() {
        YamlConfiguration translations = new YamlConfiguration();
        translations.set("chat.prefix", "&3WalkThePlank \u00bb &7");
        translations.set("chat.playerNotFound", "Player &c{{playerName}} &7is not online.");
        MessageService messages = new MessageService(() -> translations);

        Component rendered = messages.prefixed(
                "chat.playerNotFound",
                Map.of("playerName", "&4<obfuscated>Unknown</obfuscated>"));

        assertEquals(
                "WalkThePlank \u00bb Player &4<obfuscated>Unknown</obfuscated> is not online.",
                PLAIN.serialize(rendered));
    }

    @Test
    void replacementTextThatLooksLikeAnotherPlaceholderIsNotProcessedAgain() {
        MessageService messages = service();

        Component rendered = messages.render(
                "{{playerName}} / {{score}}",
                Map.of("playerName", "{{score}}", "score", 12));

        assertEquals("{{score}} / 12", PLAIN.serialize(rendered));
    }

    private static MessageService service() {
        return new MessageService(YamlConfiguration::new);
    }

    private static List<TextComponent> textNodes(Component component) {
        List<TextComponent> result = new ArrayList<>();
        collectTextNodes(component, result);
        return result;
    }

    private static List<TextNodeView> textNodeViews(Component component) {
        List<TextNodeView> result = new ArrayList<>();
        collectTextNodeViews(component, null, result);
        return result;
    }

    private static void collectTextNodes(Component component, List<TextComponent> result) {
        if (component instanceof TextComponent text) {
            result.add(text);
        }
        for (Component child : component.children()) {
            collectTextNodes(child, result);
        }
    }

    private static void collectTextNodeViews(
            Component component,
            TextColor inheritedColor,
            List<TextNodeView> result) {
        TextColor effectiveColor = component.color() == null ? inheritedColor : component.color();
        if (component instanceof TextComponent text) {
            result.add(new TextNodeView(text, effectiveColor));
        }
        for (Component child : component.children()) {
            collectTextNodeViews(child, effectiveColor, result);
        }
    }

    private record TextNodeView(TextComponent component, TextColor effectiveColor) {}
}
