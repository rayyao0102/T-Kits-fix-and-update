package com.takeda.tkits.api;

import com.takeda.tkits.TKits;
import com.takeda.tkits.managers.KitManager; // Import KitManager
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.PlayerData;
import com.takeda.tkits.services.CooldownService;
import com.takeda.tkits.services.UtilityService;
import com.takeda.tkits.util.MessageUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class TKitsAPIImpl implements TKitsAPI {

    private final TKits plugin;
    private final NamespacedKey regearBoxKey;

    public TKitsAPIImpl(TKits plugin) {
        this.plugin = plugin;
        this.regearBoxKey = new NamespacedKey(plugin, "regear_box_trigger");
    }

    @Override
    public Optional<Kit> getPlayerKit(UUID playerUUID, int kitNumber) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(playerUUID);
        if (data != null) {
            return Optional.ofNullable(data.getKit(kitNumber));
        } else {
            // Blocking synchronous call - Use with extreme caution, preferably only if absolutely necessary.
            plugin.getLogger().log(Level.WARNING, "Synchronous API call getPlayerKit made for offline/uncached player: " + playerUUID + ". This can cause performance issues.");
            try {
                 // Added timeout to prevent indefinite blocking
                 Map<Integer, Kit> kits = plugin.getStorageHandler().loadPlayerKits(playerUUID).get(5, TimeUnit.SECONDS);
                 return Optional.ofNullable(kits.get(kitNumber));
            } catch (Exception e) {
                 plugin.getMessageUtil().logException("API Error (sync): Failed fetching kit " + kitNumber + " for " + playerUUID, e);
                 return Optional.empty();
            }
        }
    }

    @Override
    public CompletableFuture<Optional<Kit>> getPlayerKitAsync(UUID playerUUID, int kitNumber) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(playerUUID); // Check cache first
        if (data != null) {
            return CompletableFuture.completedFuture(Optional.ofNullable(data.getKit(kitNumber)));
        }
        // If not cached, load from storage async
        return plugin.getStorageHandler().loadPlayerKits(playerUUID)
               .thenApply(kits -> Optional.ofNullable(kits.get(kitNumber)))
                .exceptionally(ex -> { // Handle potential storage errors
                     plugin.getMessageUtil().logException("API Error (async): Failed fetching kit " + kitNumber + " for " + playerUUID, ex);
                     return Optional.empty(); // Return empty optional on error
                });
    }

    @Override
     public Map<Integer, Kit> getAllPlayerKits(UUID playerUUID) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(playerUUID);
         if (data != null) {
             return data.getKits(); // Returns immutable copy from PlayerData
         } else {
             plugin.getLogger().log(Level.WARNING, "Synchronous API call getAllPlayerKits made for offline/uncached player: " + playerUUID + ". This can cause performance issues.");
             try {
                  return plugin.getStorageHandler().loadPlayerKits(playerUUID).get(5, TimeUnit.SECONDS); // Blocking with timeout
             } catch (Exception e) {
                  plugin.getMessageUtil().logException("API Error (sync): Failed fetching all kits for " + playerUUID, e);
                  return Collections.emptyMap();
             }
         }
     }

     @Override
     public CompletableFuture<Map<Integer, Kit>> getAllPlayerKitsAsync(UUID playerUUID) {
         PlayerData data = plugin.getPlayerDataManager().getPlayerData(playerUUID); // Check cache
         if (data != null) {
              return CompletableFuture.completedFuture(data.getKits()); // Return cached immutable copy
         }
         // Load from storage if not cached
         return plugin.getStorageHandler().loadPlayerKits(playerUUID)
                 .exceptionally(ex -> { // Handle errors
                      plugin.getMessageUtil().logException("API Error (async): Failed fetching all kits for " + playerUUID, ex);
                      return Collections.emptyMap(); // Return empty map on error
                 });
     }

    @Override
    public boolean loadKitOntoPlayer(Player player, int kitNumber) {
        // Delegate directly to KitManager which handles cooldowns, permissions, etc.
        return plugin.getKitManager().loadKit(player, kitNumber);
    }

    @Override
    public int getLastLoadedKitNumber(Player player) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        return (data != null) ? data.getLastLoadedKitNumber() : -1;
    }

    @Override
     public List<Kit> getGlobalKits() {
        return plugin.getKitManager().getAllGlobalKits(); // Accesses sorted, cached list
     }

    @Override
    public boolean isPlayerCombatTagged(Player player) {
        return plugin.getCombatTagManager().isTagged(player);
    }

    @Override
     public long getRemainingCombatTagMillis(Player player) {
        return plugin.getCombatTagManager().getRemainingTagTimeMillis(player);
    }

     @Override
     public ItemStack getRegearTriggerItem() {
         // Load material and name from ConfigManager
         Material mat = plugin.getConfigManager().getRegearBoxMaterial();
         String rawName = plugin.getConfigManager().getRegearBoxName(); // Name can have {player}
         MessageUtil msg = plugin.getMessageUtil();

         // Create the item
         ItemStack regearBox = new ItemStack(mat);
         ItemMeta meta = regearBox.getItemMeta();

         if (meta != null) {
            // Note: We can't resolve {player} here, it must be done when giving the item IF needed.
            // For now, just set the configured name directly.
             meta.displayName(msg.deserialize(rawName)); // Use deserialize for colors
            // Add lore or other meta if configured
            // meta.lore(List.of(msg.deserialize("&7Place this box to regear!")));
             meta.getPersistentDataContainer().set(regearBoxKey, PersistentDataType.BYTE, (byte) 1);
             regearBox.setItemMeta(meta);
         }
         return regearBox;
     }

      @Override
      public boolean performRegear(Player player) {
           MessageUtil msg = plugin.getMessageUtil();
           KitManager kitManager = plugin.getKitManager();
           UtilityService utilityService = plugin.getUtilityService();
           CooldownService cooldownService = plugin.getCooldownService();

          // --- Cooldown Check ---
           if (!player.hasPermission("tkits.cooldown.bypass")) {
              long remaining = cooldownService.getRemainingCooldown(player.getUniqueId(), CooldownService.CooldownType.REGEAR);
              if (remaining > 0) {
                   msg.sendMessage(player, "on_cooldown", "time", String.format("%.1f", remaining / 1000.0));
                   msg.playSound(player, "cooldown");
                   return false;
              }
           }

          Kit lastKit = kitManager.getLastLoadedKit(player);
          if (lastKit == null) {
               // Message already sent by getLastLoadedKit
               return false;
          }

           boolean success = utilityService.executeRegear(player, lastKit);

          if (success && !player.hasPermission("tkits.cooldown.bypass")) {
               cooldownService.applyCooldown(player.getUniqueId(), CooldownService.CooldownType.REGEAR); // Apply cooldown on success
               msg.sendActionBar(player, "regear_success");
               msg.playSound(player, "regear_success");
          } else if (!success) {
                msg.playSound(player, "error"); // Service should send specific error msg if needed
          }
          return success;
      }

       @Override
       public boolean performArrange(Player player) {
            MessageUtil msg = plugin.getMessageUtil();
            KitManager kitManager = plugin.getKitManager();
            UtilityService utilityService = plugin.getUtilityService();
            CooldownService cooldownService = plugin.getCooldownService();

            // --- Cooldown Check ---
             if (!player.hasPermission("tkits.cooldown.bypass")) {
                long remaining = cooldownService.getRemainingCooldown(player.getUniqueId(), CooldownService.CooldownType.ARRANGE);
                if (remaining > 0) {
                     msg.sendMessage(player, "on_cooldown", "time", String.format("%.1f", remaining / 1000.0));
                     msg.playSound(player, "cooldown");
                     return false;
                }
             }

           Kit lastKit = kitManager.getLastLoadedKit(player);
           if (lastKit == null) {
               return false;
           }

            boolean success = utilityService.executeArrange(player, lastKit);

           if (success && !player.hasPermission("tkits.cooldown.bypass")) {
                cooldownService.applyCooldown(player.getUniqueId(), CooldownService.CooldownType.ARRANGE); // Apply cooldown
                // Success messages handled by service
           } else if (!success) {
               msg.playSound(player, "error");
           }
           return success;
       }

}