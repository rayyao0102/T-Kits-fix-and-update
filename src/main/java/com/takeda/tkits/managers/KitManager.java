package com.takeda.tkits.managers;

import com.takeda.tkits.TKits;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.KitContents;
import com.takeda.tkits.models.PlayerData;
import com.takeda.tkits.services.CooldownService; 
import com.takeda.tkits.services.UtilityService; 

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class KitManager {

    private final TKits plugin;
    private final CooldownService cooldownService;
    private final UtilityService utilityService;
    private final Map<UUID, Map<Integer, Kit>> globalKitsCache;

    public KitManager(TKits plugin) {
        this.plugin = plugin;
        this.cooldownService = plugin.getCooldownService();
        this.utilityService = plugin.getUtilityService();
        this.globalKitsCache = new ConcurrentHashMap<>();
    }

    

    public void loadGlobalKitsFromStorage() {
        plugin.getMessageUtil().logInfo("Loading global kits from storage...");
        plugin.getStorageHandler().loadGlobalKits().thenAcceptAsync(kits -> {
            globalKitsCache.clear();
            kits.forEach(this::addGlobalKitToCache);
            plugin.getMessageUtil().logInfo("Loaded " + globalKitsCache.values().stream().mapToInt(Map::size).sum() + " global kits into cache.");
        }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
        .exceptionally(ex -> {
             plugin.getMessageUtil().logException("Failed to load global kits from storage.", ex);
             return null;
        });
    }

    public void addGlobalKitToCache(Kit kit) {
        if (kit != null && kit.isGlobal()) {
            globalKitsCache.computeIfAbsent(kit.getOwner(), k -> new ConcurrentHashMap<>()).put(kit.getKitNumber(), kit);
            plugin.getLogger().finer(() -> "Added/Updated global kit cache: " + kit.getOwner() + "_" + kit.getKitNumber());
        }
    }

    public void removeGlobalKitFromCache(UUID owner, int kitNumber) {
         Map<Integer, Kit> ownerKits = globalKitsCache.get(owner);
         if (ownerKits != null) {
             if (ownerKits.remove(kitNumber) != null) {
                 plugin.getLogger().finer(() -> "Removed global kit from cache: " + owner + "_" + kitNumber);
             }
             if (ownerKits.isEmpty()) {
                 globalKitsCache.remove(owner);
             }
         }
    }

    public List<Kit> getAllGlobalKits() {
        return globalKitsCache.values().stream()
                .flatMap(ownerMap -> ownerMap.values().stream())
                 .sorted(Comparator.comparing(Kit::getOwner).thenComparing(Kit::getKitNumber))
                .collect(Collectors.toList());
    }

    public Kit getGlobalKit(UUID owner, int kitNumber) {
         return globalKitsCache.getOrDefault(owner, Collections.emptyMap()).get(kitNumber);
    }
    



    

    public void saveKit(Player player, int kitNumber, Inventory editorInventory) {
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if (playerData == null) {
             plugin.getMessageUtil().sendMessage(player, "error"); return;
        }

        Kit kitFromEditor = plugin.getGuiManager().createKitFromEditor(player, kitNumber, editorInventory);
        if (kitFromEditor == null) {
             plugin.getMessageUtil().debug("saveKit: Aborted because createKitFromEditor returned null (validation failed).");
             return;
        }

        playerData.setKit(kitNumber, kitFromEditor);
        plugin.getPlayerDataManager().savePlayerKit(player.getUniqueId(), kitFromEditor)
             .thenRunAsync(() -> {
                 
                 if (kitFromEditor.isGlobal()) {
                      addGlobalKitToCache(kitFromEditor); 
                 } else {
                      
                      
                      
                      removeGlobalKitFromCache(kitFromEditor.getOwner(), kitFromEditor.getKitNumber());
                 }
                 plugin.getMessageUtil().sendActionBar(player, "kit_saved", "kit_number", String.valueOf(kitNumber));
                 plugin.getMessageUtil().playSound(player, "kit_save");
             }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
             .exceptionally(ex -> {
                  plugin.getMessageUtil().logException("Failed to save kit " + kitNumber + " for player " + player.getUniqueId(), ex);
                  
                  plugin.getMessageUtil().sendMessage(player, "error");
                  return null;
              });
    }

     public void saveEnderChestKit(Player player, int kitNumber, Inventory echestEditorInventory) {
          PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
          if (playerData == null) return;

          Kit kit = playerData.getKit(kitNumber);
          if (kit == null) {
              plugin.getMessageUtil().sendMessage(player, "kit_not_found", "kit_number", String.valueOf(kitNumber));
              plugin.getMessageUtil().playSound(player, "error");
              return;
          }

         Set<Material> forbiddenMaterials = plugin.getConfigManager().getPreventSaveMaterials();
         KitContents echestContents = new KitContents();
         Map<Integer, ItemStack> echestItems = new HashMap<>();
         for (int slot = 0; slot < 27; slot++) {
             ItemStack item = echestEditorInventory.getItem(slot);
             if (item != null && item.getType() != Material.AIR) {
                 if (!forbiddenMaterials.isEmpty() && forbiddenMaterials.contains(item.getType())) {
                     plugin.getMessageUtil().sendMessage(player, "cannot_save_forbidden_item_echest", "item", item.getType().toString());
                     plugin.getMessageUtil().playSound(player, "error");
                     return;
                 }
                 echestItems.put(slot, item.clone());
             }
         }
         echestContents.setItems(echestItems);

         Kit updatedKit = kit.toBuilder()
                 .enderChestContents(echestContents)
                 .build();

         playerData.setKit(kitNumber, updatedKit);
         plugin.getPlayerDataManager().savePlayerKit(player.getUniqueId(), updatedKit)
             .thenRunAsync(() -> {
                 plugin.getMessageUtil().sendActionBar(player, "kit_enderchest_saved", "kit_number", String.valueOf(kitNumber));
                 plugin.getMessageUtil().playSound(player, "kit_save");
             }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
             .exceptionally(ex -> {
                 plugin.getMessageUtil().logException("Failed to save EChest for kit " + kitNumber + " player " + player.getUniqueId(), ex);
                 playerData.setKit(kitNumber, kit); 
                 plugin.getMessageUtil().sendMessage(player, "error");
                 return null;
              });
     }

    public void clearKit(Player player, int kitNumber) {
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if (playerData == null || !playerData.hasKit(kitNumber)) {
            plugin.getMessageUtil().sendMessage(player, "kit_not_found", "kit_number", String.valueOf(kitNumber));
            return;
        }

        Kit kit = playerData.getKit(kitNumber);
        boolean wasGlobal = kit != null && kit.isGlobal();

        playerData.removeKit(kitNumber);

        plugin.getStorageHandler().deletePlayerKit(player.getUniqueId(), kitNumber)
            .thenComposeAsync(v -> {
                 if (wasGlobal) {
                     removeGlobalKitFromCache(player.getUniqueId(), kitNumber);
                     return plugin.getStorageHandler().deleteGlobalKit(player.getUniqueId(), kitNumber);
                 }
                 return CompletableFuture.completedFuture(null);
            }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
            .thenRunAsync(() -> {
                plugin.getMessageUtil().sendActionBar(player, "kit_cleared", "kit_number", String.valueOf(kitNumber));
                plugin.getMessageUtil().playSound(player, "confirmation_confirm");
             }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
             .exceptionally(ex -> {
                plugin.getMessageUtil().logException("Failed to fully clear/delete kit " + kitNumber + " for " + player.getUniqueId(), ex);
                 plugin.getMessageUtil().sendMessage(player, "error");
                 if (kit != null) playerData.setKit(kitNumber, kit);
                 if (wasGlobal && kit != null) addGlobalKitToCache(kit);
                return null;
             });
    }


    

    public boolean loadKit(Player player, int kitNumber) {
         PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
         if (playerData == null) {
              plugin.getMessageUtil().sendMessage(player, "data_loading_please_wait");
             return false;
         }
         Kit kit = playerData.getKit(kitNumber);
         if (kit == null) {
             plugin.getMessageUtil().sendMessage(player, "kit_not_found", "kit_number", String.valueOf(kitNumber));
             plugin.getMessageUtil().playSound(player, "error");
             return false;
         }
         return loadKit(player, kit);
    }

     public boolean loadKit(Player player, Kit kit) {
         if (kit == null) {
              plugin.getMessageUtil().sendMessage(player, "error");
             return false;
         }
         PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
         if (playerData == null) {
              plugin.getMessageUtil().sendMessage(player, "data_loading_please_wait");
             return false;
         }

        List<String> forbiddenWorlds = plugin.getConfigManager().getPreventLoadWorlds();
        if (!forbiddenWorlds.isEmpty() && forbiddenWorlds.contains(player.getWorld().getName())) {
            plugin.getMessageUtil().sendMessage(player, "cannot_load_kit_in_world", "world", player.getWorld().getName());
            plugin.getMessageUtil().playSound(player, "error");
            return false;
        }

        if (plugin.getConfigManager().isLoadRequiresEmptyInventory()) {
             PlayerInventory inv = player.getInventory();
             boolean isEmpty = true;
             for (ItemStack item : inv.getStorageContents()) { if (item != null && item.getType() != Material.AIR) {isEmpty = false; break;} }
             if (isEmpty) { for (ItemStack item : inv.getArmorContents()) { if (item != null && item.getType() != Material.AIR) {isEmpty = false; break;} } }
             if (isEmpty) { if (inv.getItemInOffHand() != null && inv.getItemInOffHand().getType() != Material.AIR) isEmpty = false; }

             if (!isEmpty) {
                 plugin.getMessageUtil().sendMessage(player, "must_have_empty_inventory_to_load");
                 plugin.getMessageUtil().playSound(player, "error");
                 return false;
             }
        }

         if (!player.hasPermission("tkits.cooldown.bypass")) {
            long remaining = cooldownService.getRemainingCooldown(player.getUniqueId(), CooldownService.CooldownType.KIT_LOAD);
             if (remaining > 0) {
                 plugin.getMessageUtil().sendMessage(player, "on_cooldown", "time", String.format("%.1f", remaining / 1000.0));
                 plugin.getMessageUtil().playSound(player, "cooldown");
                 return false;
             }
         }

         if (plugin.getCombatTagManager().isTagged(player) && !player.hasPermission("tkits.combattag.bypass")) {
             long remainingMillis = plugin.getCombatTagManager().getRemainingTagTimeMillis(player);
             double remainingSeconds = Math.ceil(remainingMillis / 1000.0);
             plugin.getMessageUtil().sendMessage(player, "in_combat", "time", String.format("%.1f", remainingSeconds));
             plugin.getMessageUtil().playSound(player, "error");
             return false;
         }

         if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
             plugin.getMessageUtil().sendMessage(player, "cannot_load_in_gamemode");
             return false;
         }

        applyKitToPlayer(player, kit);

        if (kit.getOwner().equals(player.getUniqueId())) {
            playerData.setLastLoadedKitNumber(kit.getKitNumber());
        }

         if (!player.hasPermission("tkits.cooldown.bypass")) {
             cooldownService.applyCooldown(player.getUniqueId(), CooldownService.CooldownType.KIT_LOAD);
         }

        plugin.getMessageUtil().sendActionBar(player, "kit_loaded", "kit_number", String.valueOf(kit.getKitNumber()));
        plugin.getMessageUtil().playSound(player, "kit_load");
        return true;
    }

    private void applyKitToPlayer(Player player, Kit kit) {
        PlayerInventory inv = player.getInventory();
        Inventory enderChest = player.getEnderChest();
        boolean clearInv = plugin.getConfigManager().isClearInventoryOnLoad(); 

        plugin.getMessageUtil().debug("applyKitToPlayer: Applying kit " + kit.getKitNumber() + " to " + player.getName() + ". Clear inventory: " + clearInv); 

        
        if (clearInv) {
            plugin.getMessageUtil().debug(" -> Clearing inventory and stats for " + player.getName() + " based on config."); 
            inv.clear(); 
            inv.setArmorContents(new ItemStack[4]); 
            inv.setItemInOffHand(null); 
            enderChest.clear(); 

            
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));

            
            try {
                
                player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
            } catch (Exception e) {
                plugin.getMessageUtil().debug(" -> Error getting max health attribute, defaulting to 20.0"); 
                player.setHealth(20.0); 
            }
            player.setFoodLevel(20);
            player.setSaturation(5.0f); 
            player.setExp(0);
            player.setLevel(0);
            player.setFireTicks(0);
        } else {
            plugin.getMessageUtil().debug(" -> NOT clearing inventory for " + player.getName() + " (clear_inventory_on_load=false). Existing items might be overwritten or remain."); 
        }

        
        
        Map<Integer, ItemStack> kitItems = (kit.getContents() != null && kit.getContents().getItems() != null)
                ? kit.getContents().getItems()
                : Collections.emptyMap();

        
        for (int slot = 0; slot <= 40; slot++) {
            ItemStack itemFromKit = kitItems.get(slot); 

            
            if (itemFromKit != null && itemFromKit.getType() != Material.AIR) {
                
                setItemSafe(inv, slot, itemFromKit.clone());

                
                String metaStr = itemFromKit.hasItemMeta() ? itemFromKit.getItemMeta().toString() : "null";
                metaStr = metaStr.substring(0, Math.min(metaStr.length(), 100)); 
                plugin.getMessageUtil().debug(String.format("applyKitToPlayer: Slot %d SET to KitItem: %s Amount: %d Meta: %s...",
                                                             slot, itemFromKit.getType(), itemFromKit.getAmount(), metaStr)); 
            } else {
                
                
                
                clearItemSafe(inv, slot);
                plugin.getMessageUtil().debug(String.format("applyKitToPlayer: Slot %d CLEARED (kit definition null/AIR)", slot)); 
            }
        }

        
        
        Map<Integer, ItemStack> echestKitItems = (kit.getEnderChestContents() != null && kit.getEnderChestContents().getItems() != null)
                ? kit.getEnderChestContents().getItems()
                : Collections.emptyMap();

        
        for (int slot = 0; slot < enderChest.getSize(); slot++) { 
            ItemStack itemFromKit = echestKitItems.get(slot);

            
            enderChest.setItem(slot, (itemFromKit != null && itemFromKit.getType() != Material.AIR) ? itemFromKit.clone() : null);

            
            if (itemFromKit == null || itemFromKit.getType() == Material.AIR) {
                plugin.getMessageUtil().debug(String.format("applyKitToPlayer: EChest Slot %d CLEARED", slot)); 
            } else {
                 String metaStr = itemFromKit.hasItemMeta() ? itemFromKit.getItemMeta().toString() : "null";
                 metaStr = metaStr.substring(0, Math.min(metaStr.length(), 100));
                 plugin.getMessageUtil().debug(String.format("applyKitToPlayer: EChest Slot %d SET to KitItem: %s Amount: %d Meta: %s...",
                                                              slot, itemFromKit.getType(), itemFromKit.getAmount(), metaStr)); 
            }
        }

        
        player.updateInventory();
        plugin.getMessageUtil().debug("applyKitToPlayer: Finished applying kit " + kit.getKitNumber() + " to " + player.getName()); 
    }


     

     public Kit getLastLoadedKit(Player player) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data != null) {
             int lastKitNum = data.getLastLoadedKitNumber();
             if (lastKitNum > 0) {
                 Kit kit = data.getKit(lastKitNum);
                 if (kit != null) {
                     return kit;
                 } else {
                     plugin.getMessageUtil().sendMessage(player, "last_kit_invalid", "kit_number", String.valueOf(lastKitNum)); 
                     data.setLastLoadedKitNumber(-1);
                 }
             }
        }
        plugin.getMessageUtil().sendMessage(player, "no_kit_loaded_yet");
        return null;
    }

     public CompletableFuture<Void> setKitGlobalStatus(Player player, int kitNumber, boolean isGlobal) {
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if (playerData == null) return CompletableFuture.completedFuture(null);

        Kit kit = playerData.getKit(kitNumber);
        if (kit == null) {
            plugin.getMessageUtil().sendMessage(player, "kit_not_found", "kit_number", String.valueOf(kitNumber));
            plugin.getMessageUtil().playSound(player, "error");
            return CompletableFuture.completedFuture(null);
        }

        if (kit.isGlobal() == isGlobal) {
             plugin.getMessageUtil().sendActionBar(player, isGlobal ? "kit_already_global" : "kit_already_private", "kit_number", String.valueOf(kitNumber));
             return CompletableFuture.completedFuture(null);
        }

         if(isGlobal && !player.hasPermission("tkits.kit.global")) {
              plugin.getMessageUtil().sendMessage(player, "no_permission");
              plugin.getMessageUtil().playSound(player, "error");
              return CompletableFuture.completedFuture(null);
         }

        Kit updatedKit = kit.toBuilder().global(isGlobal).build();
        playerData.setKit(kitNumber, updatedKit);

        CompletableFuture<Void> storageFuture;
         if (isGlobal) {
             storageFuture = plugin.getStorageHandler().saveGlobalKit(updatedKit);
         } else {
             storageFuture = plugin.getStorageHandler().deleteGlobalKit(player.getUniqueId(), kitNumber);
         }

        return storageFuture.thenRunAsync(() -> {
                 if (isGlobal) {
                     addGlobalKitToCache(updatedKit);
                 } else {
                     removeGlobalKitFromCache(player.getUniqueId(), kitNumber);
                 }
                 plugin.getMessageUtil().sendActionBar(player, isGlobal ? "kit_set_global" : "kit_set_private", "kit_number", String.valueOf(kitNumber));
                 plugin.getMessageUtil().playSound(player, "gui_click");
             }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
              .exceptionally(ex -> {
                 plugin.getMessageUtil().logException("Failed to update global status for kit " + kitNumber + " player " + player.getUniqueId(), ex);
                 playerData.setKit(kitNumber, kit);
                  if (isGlobal) removeGlobalKitFromCache(player.getUniqueId(), kitNumber); else addGlobalKitToCache(kit);
                 plugin.getMessageUtil().sendMessage(player, "error");
                 return null;
              });
    }

    public boolean repairKitItems(Kit kit) {
        if (kit == null) return false;
         boolean repaired = false;
         if (kit.getContents() != null && kit.getContents().getItems() != null) {
             for (ItemStack item : kit.getContents().getItems().values()) {
                 if (item != null && item.getItemMeta() instanceof Damageable meta) {
                     if (meta.hasDamage()) {
                         meta.setDamage(0); item.setItemMeta(meta); repaired = true;
                     }
                 }
             }
         }
         if (kit.getEnderChestContents() != null && kit.getEnderChestContents().getItems() != null) {
              for (ItemStack item : kit.getEnderChestContents().getItems().values()) {
                  if (item != null && item.getItemMeta() instanceof Damageable meta) {
                     if (meta.hasDamage()) {
                         meta.setDamage(0); item.setItemMeta(meta); repaired = true;
                     }
                  }
              }
         }
         return repaired;
    }

    public static int mapEditorSlotToPlayerInvSlot(int editorSlot) {
         if (editorSlot >= 0 && editorSlot <= 40) return editorSlot;
         return -1;
    }

    public static int mapPlayerInvSlotToEditorSlot(int playerInvSlot) {
        if (playerInvSlot >= 0 && playerInvSlot <= 40) return playerInvSlot;
         return -1;
    }

    
    private void setItemSafe(PlayerInventory inv, int slot, ItemStack item) {
        if (slot < 0 || slot > 40) return;
        ItemStack itemToSet = (item == null || item.getType() == Material.AIR) ? null : item; 

        switch (slot) {
            case 36: inv.setBoots(itemToSet); break;
            case 37: inv.setLeggings(itemToSet); break;
            case 38: inv.setChestplate(itemToSet); break;
            case 39: inv.setHelmet(itemToSet); break;
            case 40: inv.setItemInOffHand(itemToSet); break;
            default: inv.setItem(slot, itemToSet); break;
        }
    }

    
    private void clearItemSafe(PlayerInventory inv, int slot) {
        setItemSafe(inv, slot, null);
    }
}

