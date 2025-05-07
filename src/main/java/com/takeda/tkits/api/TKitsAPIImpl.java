package com.takeda.tkits.api;

import com.takeda.tkits.TKits;
import com.takeda.tkits.managers.KitManager;
import com.takeda.tkits.managers.GuiIdentifier; 
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.PlayerData;
import com.takeda.tkits.services.CooldownService;
import com.takeda.tkits.services.UtilityService;
import com.takeda.tkits.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
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
            
            return Optional.ofNullable(data.getKit(kitNumber)).map(kit -> kit.toBuilder().build());
        } else {
            plugin.getLogger().log(Level.WARNING, "Synchronous API call getPlayerKit made for offline/uncached player: " + playerUUID + ". This can cause performance issues.");
            try {
                 Map<Integer, Kit> kits = plugin.getStorageHandler().loadPlayerKits(playerUUID).get(5, TimeUnit.SECONDS);
                 
                 return Optional.ofNullable(kits.get(kitNumber)).map(kit -> kit.toBuilder().build());
            } catch (Exception e) {
                 plugin.getMessageUtil().logException("API Error (sync): Failed fetching kit " + kitNumber + " for " + playerUUID, e);
                 return Optional.empty();
            }
        }
    }

    @Override
    public CompletableFuture<Optional<Kit>> getPlayerKitAsync(UUID playerUUID, int kitNumber) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(playerUUID);
        if (data != null) {
            
            return CompletableFuture.completedFuture(
                Optional.ofNullable(data.getKit(kitNumber)).map(kit -> kit.toBuilder().build())
            );
        }
        return plugin.getStorageHandler().loadPlayerKits(playerUUID)
               .thenApply(kits -> Optional.ofNullable(kits.get(kitNumber))
                                       
                                       .map(kit -> kit.toBuilder().build()))
                .exceptionally(ex -> {
                     plugin.getMessageUtil().logException("API Error (async): Failed fetching kit " + kitNumber + " for " + playerUUID, ex);
                     return Optional.empty();
                });
    }

    @Override
     public Map<Integer, Kit> getAllPlayerKits(UUID playerUUID) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(playerUUID);
         if (data != null) {
             
             return data.getKits();
         } else {
             plugin.getLogger().log(Level.WARNING, "Synchronous API call getAllPlayerKits made for offline/uncached player: " + playerUUID + ". This can cause performance issues.");
             try {
                  
                  
                  return plugin.getStorageHandler().loadPlayerKits(playerUUID).get(5, TimeUnit.SECONDS);
             } catch (Exception e) {
                  plugin.getMessageUtil().logException("API Error (sync): Failed fetching all kits for " + playerUUID, e);
                  return Collections.emptyMap();
             }
         }
     }

     @Override
     public CompletableFuture<Map<Integer, Kit>> getAllPlayerKitsAsync(UUID playerUUID) {
         PlayerData data = plugin.getPlayerDataManager().getPlayerData(playerUUID);
         if (data != null) {
              
              return CompletableFuture.completedFuture(data.getKits());
         }
         return plugin.getStorageHandler().loadPlayerKits(playerUUID)
                 .exceptionally(ex -> {
                      plugin.getMessageUtil().logException("API Error (async): Failed fetching all kits for " + playerUUID, ex);
                      return Collections.emptyMap();
                 });
     }

    @Override
    public boolean loadKitOntoPlayer(Player player, int kitNumber) {
        return plugin.getKitManager().loadKit(player, kitNumber);
    }

    @Override
    public int getLastLoadedKitNumber(Player player) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        return (data != null) ? data.getLastLoadedKitNumber() : -1;
    }

    @Override
     public List<Kit> getGlobalKits() {
        
        return plugin.getKitManager().getAllGlobalKits();
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
         Material mat = plugin.getConfigManager().getRegearBoxMaterial();
         String rawName = plugin.getConfigManager().getRegearBoxName();
         MessageUtil msg = plugin.getMessageUtil();
         ItemStack regearBox = new ItemStack(mat);
         ItemMeta meta = regearBox.getItemMeta();
         if (meta != null) {
            meta.displayName(msg.deserialize(rawName));
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
           if (!player.hasPermission("tkits.cooldown.bypass")) {
              long remaining = cooldownService.getRemainingCooldown(player.getUniqueId(), CooldownService.CooldownType.REGEAR);
              if (remaining > 0) {
                   msg.sendMessage(player, "on_cooldown", "time", String.format("%.1f", remaining / 1000.0));
                   msg.playSound(player, "cooldown");
                   return false;
              }
           }
          
          Optional<Kit> lastKitOpt = getCurrentLoadedKit(player);
          if (lastKitOpt.isEmpty()) {
               msg.sendMessage(player, "no_kit_loaded_yet");
               msg.playSound(player, "error");
               return false;
          }
          Kit lastKit = lastKitOpt.get(); 

           boolean success = utilityService.executeRegear(player, lastKit);
          if (success && !player.hasPermission("tkits.cooldown.bypass")) {
               cooldownService.applyCooldown(player.getUniqueId(), CooldownService.CooldownType.REGEAR);
               msg.sendActionBar(player, "regear_success");
               msg.playSound(player, "regear_success");
          } else if (!success) {
                msg.playSound(player, "error");
          }
          return success;
      }

       @Override
       public boolean performArrange(Player player) {
            MessageUtil msg = plugin.getMessageUtil();
            KitManager kitManager = plugin.getKitManager();
            UtilityService utilityService = plugin.getUtilityService();
            CooldownService cooldownService = plugin.getCooldownService();
             if (!player.hasPermission("tkits.cooldown.bypass")) {
                long remaining = cooldownService.getRemainingCooldown(player.getUniqueId(), CooldownService.CooldownType.ARRANGE);
                if (remaining > 0) {
                     msg.sendMessage(player, "on_cooldown", "time", String.format("%.1f", remaining / 1000.0));
                     msg.playSound(player, "cooldown");
                     return false;
                }
             }
            
            Optional<Kit> lastKitOpt = getCurrentLoadedKit(player);
            if (lastKitOpt.isEmpty()) {
                msg.sendMessage(player, "no_kit_loaded_yet");
                msg.playSound(player, "error");
                return false;
            }
            Kit lastKit = lastKitOpt.get(); 

            boolean success = utilityService.executeArrange(player, lastKit);
           if (success && !player.hasPermission("tkits.cooldown.bypass")) {
                cooldownService.applyCooldown(player.getUniqueId(), CooldownService.CooldownType.ARRANGE);
           } else if (!success) {
               msg.playSound(player, "error");
           }
           return success;
       }

    

    @Override
    public Optional<Kit> getCurrentLoadedKit(Player player) {
        if (player == null || !player.isOnline()) {
            return Optional.empty();
        }
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if (playerData == null) {
             plugin.getLogger().fine("getCurrentLoadedKit called for " + player.getName() + " but PlayerData was null.");
            return Optional.empty();
        }
        int lastLoadedKitNumber = playerData.getLastLoadedKitNumber();
        if (lastLoadedKitNumber <= 0) {
             plugin.getLogger().finest("getCurrentLoadedKit called for " + player.getName() + " but lastLoadedKitNumber was " + lastLoadedKitNumber);
            return Optional.empty();
        }
        
        return Optional.ofNullable(playerData.getKit(lastLoadedKitNumber))
                       .map(kit -> kit.toBuilder().build()); 
    }

    @Override
    public CompletableFuture<Optional<Kit>> choosePersonalKit(Player player, Component guiTitle) {
        
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if (playerData == null) {
            
            plugin.getMessageUtil().sendMessage(player, "data_loading_please_wait"); 
            return CompletableFuture.completedFuture(Optional.empty());
        }

        
        Map<Integer, Kit> kitsMap = playerData.getKits(); 
        if (kitsMap.isEmpty()) {
            plugin.getMessageUtil().sendMessage(player, "no_kits_to_choose"); 
            plugin.getMessageUtil().playSound(player, "error");
            return CompletableFuture.completedFuture(Optional.empty());
        }

        
        CompletableFuture<Optional<Kit>> choiceFuture = new CompletableFuture<>();

        
        if (plugin.getServer().isPrimaryThread()) {
            initiateKitChoice(player, guiTitle, new ArrayList<>(kitsMap.values()), choiceFuture);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                initiateKitChoice(player, guiTitle, new ArrayList<>(kitsMap.values()), choiceFuture)
            );
        }

        return choiceFuture;
    }

    
    private void initiateKitChoice(Player player, Component guiTitle, List<Kit> availableKits, CompletableFuture<Optional<Kit>> choiceFuture) {
        
        if (plugin.getGuiManager().getPendingKitChoicesMap().containsKey(player.getUniqueId())) {
             plugin.getMessageUtil().logWarning("Player " + player.getName() + " attempted to choose a kit while already having a pending choice.");
             choiceFuture.complete(Optional.empty()); 
             
             return;
        }

        
        plugin.getGuiManager().registerPendingKitChoice(player.getUniqueId(), choiceFuture);

        try {
            
            Inventory gui = plugin.getGuiManager().createPersonalKitChoiceInventory(player, guiTitle, availableKits);
            
            
            plugin.getGuiManager().pushGuiHistory(player.getUniqueId(), GuiIdentifier.PERSONAL_KIT_CHOICE, Collections.emptyMap());
            player.openInventory(gui);
        } catch (Exception e) {
            
            plugin.getMessageUtil().logException("Failed to initiate kit choice GUI for " + player.getName(), e);
            plugin.getGuiManager().cancelPendingKitChoice(player.getUniqueId(), Optional.empty()); 
            
            if (!choiceFuture.isDone()) {
                 choiceFuture.completeExceptionally(new RuntimeException("Failed to open kit selection GUI", e));
            }
            plugin.getMessageUtil().sendMessage(player, "error"); 
        }
    }
}