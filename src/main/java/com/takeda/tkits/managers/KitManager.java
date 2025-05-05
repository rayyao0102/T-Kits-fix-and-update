package com.takeda.tkits.managers;

import com.takeda.tkits.TKits;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.KitContents;
import com.takeda.tkits.models.PlayerData;
import com.takeda.tkits.services.CooldownService; // Import CooldownService
import com.takeda.tkits.services.UtilityService; // Import UtilityService for applyKitToPlayer internal call

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

    // --- Global Kit Cache Handling ---

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
    



    // --- Kit Saving/Editing ---

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
                 // Update global cache ONLY IF the saved kit is marked global
                 if (kitFromEditor.isGlobal()) {
                      addGlobalKitToCache(kitFromEditor); // Ensure cache reflects saved state if global
                 } else {
                      // If it WAS global but is now private, remove from cache
                      // This relies on setKitGlobalStatus handling cache removal for privacy changes.
                      // But, saving a previously global kit as private should also update cache here.
                      removeGlobalKitFromCache(kitFromEditor.getOwner(), kitFromEditor.getKitNumber());
                 }
                 plugin.getMessageUtil().sendActionBar(player, "kit_saved", "kit_number", String.valueOf(kitNumber));
                 plugin.getMessageUtil().playSound(player, "kit_save");
             }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
             .exceptionally(ex -> {
                  plugin.getMessageUtil().logException("Failed to save kit " + kitNumber + " for player " + player.getUniqueId(), ex);
                  // Revert memory? Risky, could overwrite newer data. Log and inform player.
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
                 playerData.setKit(kitNumber, kit); // Revert memory
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


    // --- Kit Loading ---

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
        boolean clearInv = plugin.getConfigManager().isClearInventoryOnLoad(); // Check config setting

        plugin.getMessageUtil().debug("applyKitToPlayer: Applying kit " + kit.getKitNumber() + " to " + player.getName() + ". Clear inventory: " + clearInv); // DEBUG

        // --- Clear Inventory / Stats (if configured) ---
        if (clearInv) {
            plugin.getMessageUtil().debug(" -> Clearing inventory and stats for " + player.getName() + " based on config."); // DEBUG
            inv.clear(); // Clear main inventory (0-35)
            inv.setArmorContents(new ItemStack[4]); // Clear armor slots (36-39)
            inv.setItemInOffHand(null); // Clear offhand slot (40)
            enderChest.clear(); // Clear Ender Chest

            // Clear potion effects
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));

            // Reset health, food, saturation, xp, level, fire
            try {
                // Set health to max health attribute
                player.setHealth(player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
            } catch (Exception e) {
                plugin.getMessageUtil().debug(" -> Error getting max health attribute, defaulting to 20.0"); // DEBUG
                player.setHealth(20.0); // Fallback if attribute fails
            }
            player.setFoodLevel(20);
            player.setSaturation(5.0f); // Default saturation level
            player.setExp(0);
            player.setLevel(0);
            player.setFireTicks(0);
        } else {
            plugin.getMessageUtil().debug(" -> NOT clearing inventory for " + player.getName() + " (clear_inventory_on_load=false). Existing items might be overwritten or remain."); // DEBUG
        }

        // --- Apply Main Contents ---
        // Get kit items, default to empty map if null
        Map<Integer, ItemStack> kitItems = (kit.getContents() != null && kit.getContents().getItems() != null)
                ? kit.getContents().getItems()
                : Collections.emptyMap();

        // Iterate ALL relevant player slots (0-40) to ensure consistency
        for (int slot = 0; slot <= 40; slot++) {
            ItemStack itemFromKit = kitItems.get(slot); // Get the item defined for this slot in the kit

            // Check if the kit defines an item for this slot (and it's not AIR)
            if (itemFromKit != null && itemFromKit.getType() != Material.AIR) {
                // Set the item from the kit (using a clone) into the correct player slot
                setItemSafe(inv, slot, itemFromKit.clone());

                // Detailed Debug Logging for SET items
                String metaStr = itemFromKit.hasItemMeta() ? itemFromKit.getItemMeta().toString() : "null";
                metaStr = metaStr.substring(0, Math.min(metaStr.length(), 100)); // Limit meta string length for logs
                plugin.getMessageUtil().debug(String.format("applyKitToPlayer: Slot %d SET to KitItem: %s Amount: %d Meta: %s...",
                                                             slot, itemFromKit.getType(), itemFromKit.getAmount(), metaStr)); // DEBUG
            } else {
                // If the kit does *not* define an item for this slot (or it's AIR),
                // clear the corresponding slot in the player's inventory.
                // This is crucial to remove leftover items if clearInv=false or if the kit is sparse.
                clearItemSafe(inv, slot);
                plugin.getMessageUtil().debug(String.format("applyKitToPlayer: Slot %d CLEARED (kit definition null/AIR)", slot)); // DEBUG
            }
        }

        // --- Apply Ender Chest Contents ---
        // Get Ender Chest items, default to empty map if null
        Map<Integer, ItemStack> echestKitItems = (kit.getEnderChestContents() != null && kit.getEnderChestContents().getItems() != null)
                ? kit.getEnderChestContents().getItems()
                : Collections.emptyMap();

        // Iterate ALL ender chest slots
        for (int slot = 0; slot < enderChest.getSize(); slot++) { // Use enderChest.getSize() (usually 27)
            ItemStack itemFromKit = echestKitItems.get(slot);

            // Set item from kit (clone) or null if kit doesn't define it for this slot
            enderChest.setItem(slot, (itemFromKit != null && itemFromKit.getType() != Material.AIR) ? itemFromKit.clone() : null);

            // Debug Logging for EChest
            if (itemFromKit == null || itemFromKit.getType() == Material.AIR) {
                plugin.getMessageUtil().debug(String.format("applyKitToPlayer: EChest Slot %d CLEARED", slot)); // DEBUG
            } else {
                 String metaStr = itemFromKit.hasItemMeta() ? itemFromKit.getItemMeta().toString() : "null";
                 metaStr = metaStr.substring(0, Math.min(metaStr.length(), 100));
                 plugin.getMessageUtil().debug(String.format("applyKitToPlayer: EChest Slot %d SET to KitItem: %s Amount: %d Meta: %s...",
                                                              slot, itemFromKit.getType(), itemFromKit.getAmount(), metaStr)); // DEBUG
            }
        }

        // Update the player's inventory view
        player.updateInventory();
        plugin.getMessageUtil().debug("applyKitToPlayer: Finished applying kit " + kit.getKitNumber() + " to " + player.getName()); // DEBUG
    }


     // --- Utility Methods ---

     public Kit getLastLoadedKit(Player player) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data != null) {
             int lastKitNum = data.getLastLoadedKitNumber();
             if (lastKitNum > 0) {
                 Kit kit = data.getKit(lastKitNum);
                 if (kit != null) {
                     return kit;
                 } else {
                     plugin.getMessageUtil().sendMessage(player, "last_kit_invalid", "kit_number", String.valueOf(lastKitNum)); // Added placeholder
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

    // Helper to safely set items in player inventory, handling armor/offhand slots
    private void setItemSafe(PlayerInventory inv, int slot, ItemStack item) {
        if (slot < 0 || slot > 40) return;
        ItemStack itemToSet = (item == null || item.getType() == Material.AIR) ? null : item; // Use null for AIR/null

        switch (slot) {
            case 36: inv.setBoots(itemToSet); break;
            case 37: inv.setLeggings(itemToSet); break;
            case 38: inv.setChestplate(itemToSet); break;
            case 39: inv.setHelmet(itemToSet); break;
            case 40: inv.setItemInOffHand(itemToSet); break;
            default: inv.setItem(slot, itemToSet); break;
        }
    }

    // Helper to safely clear items from specific slots
    private void clearItemSafe(PlayerInventory inv, int slot) {
        setItemSafe(inv, slot, null);
    }
}
// ========== END COMPLETE REVISED KitManager.java ==========
