package com.takeda.tkits.placeholders;

import com.takeda.tkits.TKits;
import com.takeda.tkits.managers.CombatTagManager;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.PlayerData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;


import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TKitsPlaceholderExpansion extends PlaceholderExpansion {

    private final TKits plugin;

    public TKitsPlaceholderExpansion(TKits plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "tkits"; }
    @Override public @NotNull String getAuthor() { return "Takeda_Dev"; }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return true; }


    // Override this if you need async placeholder support (requires PAPI version >= 2.10.0)
    // Currently PAPI calls onRequest blocking, so keep logic fast. Avoid DB lookups here.
    /* 
    @Override
    public CompletableFuture<String> onRequestAsync(OfflinePlayer player, @NotNull String identifier) {
        // Implement async logic here if needed
        return CompletableFuture.completedFuture(processRequest(player, identifier));
    }
    */

    @Override
    @Nullable
    public String onRequest(OfflinePlayer player, @NotNull String identifier) {
         // If you only use async, return null here and rely on onRequestAsync
        return processRequest(player, identifier);
    }


    /**
     * Internal placeholder processing logic, called by sync or potentially async onRequest methods.
     * MUST BE FAST. Avoid blocking calls like direct database access for offline players.
     */
    private String processRequest(OfflinePlayer player, @NotNull String identifier) {
        if (player == null) return null;

        Player onlinePlayer = player.getPlayer();
        PlayerData playerData = null;
        if (onlinePlayer != null) {
            playerData = plugin.getPlayerDataManager().getPlayerData(onlinePlayer);
            // Don't return "Loading..." - PAPI expects immediate value or null.
            // If data is null, subsequent checks will handle it.
        }

        // %tkits_last_loaded_kit%
        if (identifier.equalsIgnoreCase("last_loaded_kit")) {
             return (playerData != null && playerData.getLastLoadedKitNumber() > 0)
                 ? String.valueOf(playerData.getLastLoadedKitNumber()) : "None";
        }

        // %tkits_combat_tagged%
        if (identifier.equalsIgnoreCase("combat_tagged")) {
             CombatTagManager ctm = plugin.getCombatTagManager();
             return (onlinePlayer != null && ctm.isTagged(onlinePlayer)) ? "Yes" : "No";
        }

        // %tkits_combat_tag_time%
         if (identifier.equalsIgnoreCase("combat_tag_time")) {
            CombatTagManager ctm = plugin.getCombatTagManager();
            double remainingSeconds = 0.0;
             if (onlinePlayer != null && ctm.isTagged(onlinePlayer)) {
                 remainingSeconds = ctm.getRemainingTagTimeMillis(onlinePlayer) / 1000.0;
            }
            return String.format("%.1fs", Math.max(0.0, remainingSeconds));
         }


        // Kit-specific placeholders (%tkits_kit_X_exists%, %tkits_kit_X_is_global%)
        if (identifier.startsWith("kit_") && identifier.contains("_")) {
            String[] parts = identifier.split("_", 3);
            if (parts.length == 3) {
                 try {
                     int kitNum = Integer.parseInt(parts[1]);
                     String action = parts[2].toLowerCase();

                     Kit kit = null;
                     if (playerData != null) { // Player online, use cache
                         kit = playerData.getKit(kitNum);
                     } else {
                        // Player offline - AVOID BLOCKING LOOKUP HERE.
                        // Return a default/placeholder value, or null.
                        // To show offline data, you might need a separate caching system
                        // or accept that placeholders are limited for offline players.
                        return "-"; // Return hyphen as placeholder for offline kit data
                     }

                     switch (action) {
                         case "exists": return (kit != null) ? "Yes" : "No";
                         case "is_global": return (kit != null && kit.isGlobal()) ? "Yes" : "No";
                         // case "name": return (kit != null) ? kit.getName() : "N/A"; // If kit names become customizable
                     }
                 } catch (NumberFormatException ignored) { /* Invalid number */ }
            }
        }

        return null; // Placeholder not found
    }
}