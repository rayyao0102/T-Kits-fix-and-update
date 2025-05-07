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


    
    
    /* 
    @Override
    public CompletableFuture<String> onRequestAsync(OfflinePlayer player, @NotNull String identifier) {
        
        return CompletableFuture.completedFuture(processRequest(player, identifier));
    }
    */

    @Override
    @Nullable
    public String onRequest(OfflinePlayer player, @NotNull String identifier) {
         
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
            
            
        }

        
        if (identifier.equalsIgnoreCase("last_loaded_kit")) {
             return (playerData != null && playerData.getLastLoadedKitNumber() > 0)
                 ? String.valueOf(playerData.getLastLoadedKitNumber()) : "None";
        }

        
        if (identifier.equalsIgnoreCase("combat_tagged")) {
             CombatTagManager ctm = plugin.getCombatTagManager();
             return (onlinePlayer != null && ctm.isTagged(onlinePlayer)) ? "Yes" : "No";
        }

        
         if (identifier.equalsIgnoreCase("combat_tag_time")) {
            CombatTagManager ctm = plugin.getCombatTagManager();
            double remainingSeconds = 0.0;
             if (onlinePlayer != null && ctm.isTagged(onlinePlayer)) {
                 remainingSeconds = ctm.getRemainingTagTimeMillis(onlinePlayer) / 1000.0;
            }
            return String.format("%.1fs", Math.max(0.0, remainingSeconds));
         }


        
        if (identifier.startsWith("kit_") && identifier.contains("_")) {
            String[] parts = identifier.split("_", 3);
            if (parts.length == 3) {
                 try {
                     int kitNum = Integer.parseInt(parts[1]);
                     String action = parts[2].toLowerCase();

                     Kit kit = null;
                     if (playerData != null) { 
                         kit = playerData.getKit(kitNum);
                     } else {
                        
                        
                        
                        
                        return "-"; 
                     }

                     switch (action) {
                         case "exists": return (kit != null) ? "Yes" : "No";
                         case "is_global": return (kit != null && kit.isGlobal()) ? "Yes" : "No";
                         
                     }
                 } catch (NumberFormatException ignored) { /* Invalid number */ }
            }
        }

        return null; 
    }
}