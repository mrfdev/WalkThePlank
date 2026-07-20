package com.mrfdev.walktheplank.gui;

import com.mrfdev.walktheplank.text.MessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

public final class ItemFactory {
    private final MessageService messages;
    private final Logger logger;

    public ItemFactory(MessageService messages, Logger logger) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public ItemStack create(ConfigurationSection section) {
        return create(section, Map.of(), null);
    }

    public ItemStack create(ConfigurationSection section, List<Component> loreOverride) {
        return create(section, Map.of(), loreOverride);
    }

    public ItemStack create(
            ConfigurationSection section,
            Map<String, ?> literalReplacements) {
        return create(section, literalReplacements, null);
    }

    public ItemStack createPlayerHead(
            ConfigurationSection section,
            Player player,
            Map<String, ?> literalReplacements,
            List<Component> loreOverride) {
        Objects.requireNonNull(player, "player");
        ItemStack item = create(section, literalReplacements, loreOverride);
        if (!(item.getItemMeta() instanceof SkullMeta skull)) {
            throw new IllegalArgumentException(
                    "mainGui.playerItem.item must be PLAYER_HEAD");
        }
        skull.setPlayerProfile(player.getPlayerProfile());
        item.setItemMeta(skull);
        return item;
    }

    private ItemStack create(
            ConfigurationSection section,
            Map<String, ?> literalReplacements,
            List<Component> loreOverride) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(literalReplacements, "literalReplacements");
        Material material = resolveMaterial(section.getString("item", "BARRIER"));
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MenuAppearance.nonItalic(
                messages.render(section.getString("title", ""), literalReplacements)));

        if (loreOverride == null) {
            List<Component> lore = new ArrayList<>();
            for (String line : section.getStringList("lore")) {
                lore.add(MenuAppearance.nonItalic(
                        messages.render(line, literalReplacements)));
            }
            meta.lore(lore);
        } else {
            meta.lore(loreOverride.stream()
                    .map(MenuAppearance::nonItalic)
                    .toList());
        }

        meta.setEnchantmentGlintOverride(section.getBoolean("glow", false));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createFill(String materialName) {
        ItemStack item = new ItemStack(resolveMaterial(materialName));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MenuAppearance.nonItalic(Component.empty()));
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    private Material resolveMaterial(String configuredName) {
        String normalized = configuredName.strip().toUpperCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "SIGN" -> "OAK_SIGN";
            case "WOOD_DOOR" -> "OAK_DOOR";
            default -> normalized;
        };
        Material material = Material.matchMaterial(normalized);
        if (material == null || material.isAir() || !material.isItem()) {
            logger.warning("Invalid GUI material '" + configuredName + "'; using BARRIER");
            return Material.BARRIER;
        }
        return material;
    }
}
