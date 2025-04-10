package com.takeda.tkits.util;

import com.takeda.tkits.TKits;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class MessageUtil {

    private final TKits plugin;
    private String prefix;
    private final LegacyComponentSerializer legacySerializer;
    private final Map<String, String> messageCache; // Cache messages from config

    public MessageUtil(TKits plugin) {
        this.plugin = plugin;
        this.legacySerializer = LegacyComponentSerializer.builder()
                .character('&')
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat() // Support for <#RRGGBB> (although MiniMessage preferred)
                .extractUrls()
                .build();
        this.messageCache = new HashMap<>();
        reloadMessages(); // Load messages initially
        reloadPrefix(); // Load prefix initially
    }

     public void reloadMessages() {
        messageCache.clear();
        if (plugin.getConfigManager().getMainConfig().isConfigurationSection("messages")) {
             plugin.getConfigManager().getMainConfig().getConfigurationSection("messages").getKeys(false).forEach(key -> {
                messageCache.put(key, plugin.getConfigManager().getMainConfig().getString("messages." + key, ""));
            });
        }
         plugin.getLogger().fine("Message cache reloaded with " + messageCache.size() + " entries.");
     }

     public void reloadPrefix() {
          this.prefix = plugin.getConfigManager().getMainConfig().getString("messages.prefix", "&8[&aT-Kɪᴛs&8] &r");
          plugin.getLogger().fine("Plugin prefix reloaded.");
     }

    public Component deserialize(String text) {
         return deserialize(text, null); // Deserialize without player context
    }

    public Component deserialize(String text, Player player) {
         if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        // Apply PAPI placeholders first if player context is available
         String CCText = ChatColor.translateAlternateColorCodes('&', text);
         String papiApplied = (player != null && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
                            ? PlaceholderAPI.setPlaceholders(player, CCText)
                            : CCText;

        // Basic check: Primarily use legacy formatting if it doesn't seem like MiniMessage
        // A more robust MiniMessage detection could be added if mixing formats is expected
        // For now, stick to legacy primarily for consistency with config using '&'
         return legacySerializer.deserialize(papiApplied)
                .decoration(TextDecoration.ITALIC, false); // Ensure italics are off by default
    }

     // Get raw message string from cache
     public String getRawMessage(String key, String defaultValue) {
         return messageCache.getOrDefault(key, defaultValue);
     }

    public void sendMessage(CommandSender sender, String messageKey, String... replacements) {
         String messageFormat = messageCache.get(messageKey);
         if (messageFormat == null) {
             plugin.getLogger().warning("Missing message key in config.yml: messages." + messageKey);
             sendComponent(sender, Component.text("[T-Kits] Message missing: " + messageKey).color(NamedTextColor.RED));
             return;
         }
          if(messageFormat.trim().isEmpty()) {
              return; // Don't send empty messages
          }

         String message = prefix + replacePlaceholders(messageFormat, replacements);

         Component component;
         if (sender instanceof Player) {
             component = deserialize(message, (Player) sender);
         } else {
             // Deserialize for console without PAPI player context
              component = deserialize(ChatColor.translateAlternateColorCodes('&', message));
         }
         sendComponent(sender, component);
    }

    public void sendActionBar(Player player, String messageKey, String... replacements) {
         String messageFormat = messageCache.get(messageKey);
         if (messageFormat == null || messageFormat.trim().isEmpty()) {
              if (messageFormat == null) plugin.getLogger().warning("Missing action bar message key: messages." + messageKey);
             return; // Don't send empty or missing messages
         }
         String message = replacePlaceholders(messageFormat, replacements);
         Component component = deserialize(message, player);
         player.sendActionBar(component);
    }

     public void sendRawMessage(CommandSender sender, String rawMessage, Player placeholderPlayer) {
         if (rawMessage == null || rawMessage.isEmpty()) return;
         Component component = deserialize(rawMessage, placeholderPlayer);
         sendComponent(sender, component);
     }

     public void sendComponent(CommandSender sender, Component component) {
         if (component == null || (component instanceof TextComponent && ((TextComponent) component).content().isEmpty() && component.children().isEmpty())) {
            return; // Avoid sending empty components
        }
         sender.sendMessage(component);
     }


    public void playSound(Player player, String soundKey) {
        String soundName = plugin.getConfigManager().getMainConfig().getString("sounds." + soundKey);
        if (soundName == null || soundName.isEmpty()) {
             plugin.getLogger().finer("Missing sound key in config.yml: sounds." + soundKey);
            return;
        }
        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid sound name in config.yml for key '" + soundKey + "': " + soundName);
        }
    }


    private String replacePlaceholders(String message, String... replacements) {
        if (replacements.length % 2 != 0) {
            plugin.getLogger().warning("Invalid placeholder replacements provided (must be key-value pairs) for message: " + message);
            return message;
        }
        for (int i = 0; i < replacements.length; i += 2) {
             if (replacements[i] == null || replacements[i+1] == null) continue; // Avoid NPE if a replacement is null
            message = message.replace("{" + replacements[i] + "}", replacements[i+1]);
        }
        return message;
    }

     // Helper for getting legacy colored string (e.g., for Anvil GUI titles)
     public String getLegacyColorized(String text) {
         return ChatColor.translateAlternateColorCodes('&', text);
     }
      public String getLegacyColorized(Component component) {
          return legacySerializer.serialize(component);
      }

     // Helper for stripping color codes
      public String stripColors(String text) {
          return ChatColor.stripColor(getLegacyColorized(text));
      }
     public String stripColors(Component component) {
         return PlainTextComponentSerializer.plainText().serialize(component);
     }


     // Centralized logging methods
     public void logInfo(String message) {
         plugin.getLogger().info(stripColors(prefix) + message);
     }

     public void logWarning(String message) {
          plugin.getLogger().warning(stripColors(prefix) + message);
     }
      public void logSevere(String message) {
          plugin.getLogger().severe(stripColors(prefix) + message);
      }
      public void logException(String message, Throwable throwable) {
           plugin.getLogger().log(Level.SEVERE, stripColors(prefix) + message, throwable);
      }

     // New Debug Logging Method
     public void debug(String message) {
         if (!plugin.getConfigManager().isDebugEnabled()) return;
         plugin.getLogger().info("[DEBUG] " + stripColors(prefix) + message);
     }

     // Added getter for the serializer needed by GuiManager placeholder logic
     public LegacyComponentSerializer getLegacySerializer() {
          return legacySerializer;
     }
}
