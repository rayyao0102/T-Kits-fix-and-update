package com.takeda.tkits.managers;

import com.takeda.tkits.TKits;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.KitContents;
import com.takeda.tkits.models.PlayerData;
import com.takeda.tkits.services.CooldownService; // Import CooldownService
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
    private final CooldownService cooldownService; // Added CooldownService
    private final Map<UUID, Map<Integer, Kit>> globalKitsCache;

    public KitManager(TKits plugin) {
        this.plugin = plugin;
        this.cooldownService = plugin.getCooldownService(); // Get instance
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
                 .sorted(Comparator.comparing(Kit::getOwner).thenComparing(Kit::getKitNumber)) // Sort for consistent display
                .collect(Collectors.toList());
    }

    public Kit getGlobalKit(UUID owner, int kitNumber) {
         return globalKitsCache.getOrDefault(owner, Collections.emptyMap()).get(kitNumber);
    }


    // --- Kit Saving/Editing ---

    public void saveKit(Player player, int kitNumber, Inventory editorInventory) {
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if (playerData == null) {
             plugin.getMessageUtil().sendMessage(player, "error"); // Should not happen if player online
             return;
        }

        // --- Config Checks for Saving ---
        Set<Material> forbiddenMaterials = plugin.getConfigManager().getPreventSaveMaterials();
        int maxItems = plugin.getConfigManager().getMaxTotalItemsInKit();
        int currentItemCount = 0;

        KitContents contents = new KitContents();
        ItemStack[] editorContents = editorInventory.getContents();
        Map<Integer, ItemStack> kitItems = new HashMap<>();

        for (int editorSlot = 0; editorSlot <= 40; editorSlot++) { // Check slots 0-40 (inclusive)
            int playerInvSlot = mapEditorSlotToPlayerInvSlot(editorSlot);
            ItemStack item = editorContents[editorSlot];
            if (playerInvSlot != -1 && item != null && item.getType() != Material.AIR) {
                 // Check forbidden materials
                 if (!forbiddenMaterials.isEmpty() && forbiddenMaterials.contains(item.getType())) {
                     plugin.getMessageUtil().sendMessage(player, "cannot_save_forbidden_item", "item", item.getType().toString()); // Add this message to config.yml
                     plugin.getMessageUtil().playSound(player, "error");
                     return;
                 }
                 kitItems.put(playerInvSlot, item.clone());
                 currentItemCount++; // Count non-air items
            }
        }

        // Check max items (only count main inventory/armor/offhand from editor)
        if (maxItems > 0 && currentItemCount > maxItems) {
             plugin.getMessageUtil().sendMessage(player, "kit_exceeds_max_items", "count", String.valueOf(currentItemCount), "max", String.valueOf(maxItems)); // Add this message to config.yml
             plugin.getMessageUtil().playSound(player, "error");
            return;
        }
        // --- End Config Checks ---

        contents.setItems(kitItems);

        Kit existingKit = playerData.getKit(kitNumber);
        Kit newKit;

        KitContents existingEchest = (existingKit != null) ? existingKit.getEnderChestContents() : new KitContents(); // Preserve existing or create empty

        if (existingKit != null) {
            newKit = existingKit.toBuilder()
                    .contents(contents)
                    .enderChestContents(existingEchest) // Preserve EChest contents during main kit save
                    .build();
        } else {
             newKit = Kit.builder()
                     .kitNumber(kitNumber)
                     .owner(player.getUniqueId())
                     .name("Kit " + kitNumber)
                     .contents(contents)
                     .enderChestContents(existingEchest) // Start with empty/preserved echest
                     .global(false) // Default to private
                     .build();
        }

        // Update cache and save (savePlayerKit is async and handles exceptions)
        playerData.setKit(kitNumber, newKit);
        plugin.getPlayerDataManager().savePlayerKit(player.getUniqueId(), newKit);

         // Update global cache if status changed implicitly? No, requires explicit toggle.
        if (newKit.isGlobal()) {
             addGlobalKitToCache(newKit); // Ensure cache is up-to-date if saving an already global kit
        }

        plugin.getMessageUtil().sendActionBar(player, "kit_saved", "kit_number", String.valueOf(kitNumber)); // Use action bar
        plugin.getMessageUtil().playSound(player, "kit_save");
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

         // --- Config Checks for Saving EChest ---
         Set<Material> forbiddenMaterials = plugin.getConfigManager().getPreventSaveMaterials();

         KitContents echestContents = new KitContents();
         Map<Integer, ItemStack> echestItems = new HashMap<>();
         for (int slot = 0; slot < 27; slot++) { // Standard EChest size
             ItemStack item = echestEditorInventory.getItem(slot);
             if (item != null && item.getType() != Material.AIR) {
                 // Check forbidden materials
                 if (!forbiddenMaterials.isEmpty() && forbiddenMaterials.contains(item.getType())) {
                     plugin.getMessageUtil().sendMessage(player, "cannot_save_forbidden_item_echest", "item", item.getType().toString()); // Add this message to config.yml
                     plugin.getMessageUtil().playSound(player, "error");
                     return;
                 }
                 echestItems.put(slot, item.clone());
             }
         }
         // --- End Config Checks ---

         echestContents.setItems(echestItems);

         // Update only the enderchest part of the kit
         Kit updatedKit = kit.toBuilder()
                 .enderChestContents(echestContents)
                 .build();

         playerData.setKit(kitNumber, updatedKit);
         plugin.getPlayerDataManager().savePlayerKit(player.getUniqueId(), updatedKit);

          plugin.getMessageUtil().sendActionBar(player, "kit_enderchest_saved", "kit_number", String.valueOf(kitNumber)); // Use action bar
         plugin.getMessageUtil().playSound(player, "kit_save");
     }

    public void clearKit(Player player, int kitNumber) {
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if (playerData == null || !playerData.hasKit(kitNumber)) {
            plugin.getMessageUtil().sendMessage(player, "kit_not_found", "kit_number", String.valueOf(kitNumber));
            return;
        }

        Kit kit = playerData.getKit(kitNumber); // Get reference before removing
        boolean wasGlobal = kit != null && kit.isGlobal();

        playerData.removeKit(kitNumber); // Remove from memory cache

        plugin.getStorageHandler().deletePlayerKit(player.getUniqueId(), kitNumber)
            .thenComposeAsync(v -> {
                 // Also update global status if it was global
                 if (wasGlobal) {
                     removeGlobalKitFromCache(player.getUniqueId(), kitNumber); // Remove from cache
                     return plugin.getStorageHandler().deleteGlobalKit(player.getUniqueId(), kitNumber); // Update storage (marks is_global=false)
                 }
                 return CompletableFuture.completedFuture(null);
            }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin)) // Run cache updates on main thread
            .thenRunAsync(() -> {
                plugin.getMessageUtil().sendActionBar(player, "kit_cleared", "kit_number", String.valueOf(kitNumber)); // Use action bar
                plugin.getMessageUtil().playSound(player, "confirmation_confirm");
             }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin)) // Messages on main thread
             .exceptionally(ex -> {
                plugin.getMessageUtil().logException("Failed to fully clear/delete kit " + kitNumber + " for " + player.getUniqueId(), ex);
                 plugin.getMessageUtil().sendMessage(player, "error");
                 // Attempt to revert memory state? Might be complex/risky. Logging is important.
                 if (kit != null) playerData.setKit(kitNumber, kit); // Attempt revert
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

        // --- Config Checks for Loading ---
        List<String> forbiddenWorlds = plugin.getConfigManager().getPreventLoadWorlds();
        if (!forbiddenWorlds.isEmpty() && forbiddenWorlds.contains(player.getWorld().getName())) {
            plugin.getMessageUtil().sendMessage(player, "cannot_load_kit_in_world", "world", player.getWorld().getName()); // Add this message
            plugin.getMessageUtil().playSound(player, "error");
            return false;
        }

        if (plugin.getConfigManager().isLoadRequiresEmptyInventory()) {
             PlayerInventory inv = player.getInventory();
             boolean isEmpty = true;
             // Check main slots, armor, and offhand
             for (ItemStack item : inv.getStorageContents()) { if (item != null && item.getType() != Material.AIR) isEmpty = false; break; }
             if (isEmpty) { for (ItemStack item : inv.getArmorContents()) { if (item != null && item.getType() != Material.AIR) isEmpty = false; break; } }
             if (isEmpty) { if (inv.getItemInOffHand().getType() != Material.AIR) isEmpty = false; }

             if (!isEmpty) {
                 plugin.getMessageUtil().sendMessage(player, "must_have_empty_inventory_to_load"); // Add this message
                 plugin.getMessageUtil().playSound(player, "error");
                 return false;
             }
        }
        // --- End Config Checks ---


        // --- Cooldown Check ---
         if (!player.hasPermission("tkits.cooldown.bypass")) {
            long remaining = cooldownService.getRemainingCooldown(player.getUniqueId(), CooldownService.CooldownType.KIT_LOAD);
             if (remaining > 0) {
                 plugin.getMessageUtil().sendMessage(player, "on_cooldown", "time", String.format("%.1f", remaining / 1000.0));
                 plugin.getMessageUtil().playSound(player, "cooldown");
                 return false;
             }
         }
         // --- End Cooldown Check ---

         // --- Combat Tag Check ---
         if (plugin.getCombatTagManager().isTagged(player) && !player.hasPermission("tkits.combattag.bypass")) {
             long remainingMillis = plugin.getCombatTagManager().getRemainingTagTimeMillis(player);
             double remainingSeconds = Math.ceil(remainingMillis / 1000.0);
             plugin.getMessageUtil().sendMessage(player, "in_combat", "time", String.format("%.1f", remainingSeconds));
             plugin.getMessageUtil().playSound(player, "error");
             return false;
         }
         // --- End Combat Tag Check ---

         Kit kit = playerData.getKit(kitNumber);
         if (kit == null) {
             plugin.getMessageUtil().sendMessage(player, "kit_not_found", "kit_number", String.valueOf(kitNumber));
             plugin.getMessageUtil().playSound(player, "error");
             return false;
         }

         if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
             plugin.getMessageUtil().sendMessage(player, "cannot_load_in_gamemode"); // Add message
             return false;
         }

        applyKitToPlayer(player, kit);
        playerData.setLastLoadedKitNumber(kitNumber); // Track last loaded

         // Apply Cooldown
         if (!player.hasPermission("tkits.cooldown.bypass")) {
             cooldownService.applyCooldown(player.getUniqueId(), CooldownService.CooldownType.KIT_LOAD);
         }

        plugin.getMessageUtil().sendActionBar(player, "kit_loaded", "kit_number", String.valueOf(kitNumber)); // Use action bar
        plugin.getMessageUtil().playSound(player, "kit_load");
        return true;
    }

    private void applyKitToPlayer(Player player, Kit kit) {
        PlayerInventory inv = player.getInventory();
        Inventory enderChest = player.getEnderChest();

        // --- Config Check: Clear Inventory ---
        if (plugin.getConfigManager().isClearInventoryOnLoad()) {
             plugin.getMessageUtil().debug("Clearing inventory for " + player.getName() + " based on config.");
            inv.clear();
            inv.setArmorContents(new ItemStack[4]);
            enderChest.clear();
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            // Reset more stats for a clean kit application
             player.setHealth(player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
             player.setFoodLevel(20);
             player.setSaturation(5.0f); // Base saturation
             player.setExp(0);
             player.setLevel(0);
            player.setFireTicks(0);
        } else {
             plugin.getMessageUtil().debug("NOT clearing inventory for " + player.getName() + " based on config (clear_inventory_on_load=false). Items may be overwritten.");
        }
        // --- End Config Check ---

        KitContents contents = kit.getContents();
        if (contents != null && contents.getItems() != null) {
            contents.getItems().forEach((slot, item) -> {
                 if (item != null) {
                     setItemSafe(inv, slot, item.clone());
                 }
            });
        }

        KitContents echestContents = kit.getEnderChestContents();
        if (echestContents != null && echestContents.getItems() != null) {
             echestContents.getItems().forEach((slot, item) -> {
                 if (item != null && slot >= 0 && slot < enderChest.getSize()) {
                     enderChest.setItem(slot, item.clone());
                 }
             });
        }

        player.updateInventory();
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
                     plugin.getMessageUtil().sendMessage(player, "last_kit_invalid");
                     data.setLastLoadedKitNumber(-1); // Reset if kit no longer exists
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
             plugin.getMessageUtil().sendActionBar(player, isGlobal ? "kit_already_global" : "kit_already_private", "kit_number", String.valueOf(kitNumber)); // Use action bar
             return CompletableFuture.completedFuture(null);
        }

         if(isGlobal && !player.hasPermission("tkits.kit.global")) {
              plugin.getMessageUtil().sendMessage(player, "no_permission");
              plugin.getMessageUtil().playSound(player, "error");
              return CompletableFuture.completedFuture(null);
         }

        Kit updatedKit = kit.toBuilder().global(isGlobal).build();
        playerData.setKit(kitNumber, updatedKit); // Update memory

        // Use appropriate storage method based on target status
        CompletableFuture<Void> storageFuture = isGlobal
                ? plugin.getStorageHandler().saveGlobalKit(updatedKit)
                : plugin.getStorageHandler().deleteGlobalKit(player.getUniqueId(), kitNumber); // deleteGlobalKit marks as false in storage

        return storageFuture.thenRunAsync(() -> {
                 // Update global cache after storage operation succeeds
                 if (isGlobal) {
                     addGlobalKitToCache(updatedKit);
                 } else {
                     removeGlobalKitFromCache(player.getUniqueId(), kitNumber);
                 }
                 plugin.getMessageUtil().sendActionBar(player, isGlobal ? "kit_set_global" : "kit_set_private", "kit_number", String.valueOf(kitNumber)); // Use action bar
                 plugin.getMessageUtil().playSound(player, "gui_click");
             }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
              .exceptionally(ex -> {
                 plugin.getMessageUtil().logException("Failed to update global status for kit " + kitNumber + " player " + player.getUniqueId(), ex);
                 playerData.setKit(kitNumber, kit); // Revert memory change on failure
                  if (isGlobal) removeGlobalKitFromCache(player.getUniqueId(), kitNumber); else addGlobalKitToCache(kit); // Revert cache
                 plugin.getMessageUtil().sendMessage(player, "error");
                 return null;
              });
    }

    // Helper to repair all damageable items in a kit's contents in memory (doesn't save automatically)
    public boolean repairKitItems(Kit kit) {
        if (kit == null || kit.getContents() == null || kit.getContents().getItems().isEmpty()) {
            return false;
        }

         boolean repaired = false;
         for (ItemStack item : kit.getContents().getItems().values()) {
             if (item != null && item.getItemMeta() instanceof Damageable) {
                 Damageable meta = (Damageable) item.getItemMeta();
                 if (meta.hasDamage()) {
                     meta.setDamage(0);
                     item.setItemMeta(meta);
                     repaired = true;
                 }
             }
         }
        // Also repair Ender Chest items?
         if (kit.getEnderChestContents() != null) {
              for (ItemStack item : kit.getEnderChestContents().getItems().values()) {
                  if (item != null && item.getItemMeta() instanceof Damageable) {
                     Damageable meta = (Damageable) item.getItemMeta();
                     if (meta.hasDamage()) {
                         meta.setDamage(0);
                         item.setItemMeta(meta);
                         repaired = true;
                     }
                  }
              }
         }
         return repaired;
    }


    // Static helper, maybe move to a Util class if used elsewhere
    public static int mapEditorSlotToPlayerInvSlot(int editorSlot) {
         if (editorSlot >= 0 && editorSlot <= 40) return editorSlot;
         return -1;
    }

    public static int mapPlayerInvSlotToEditorSlot(int playerInvSlot) {
        if (playerInvSlot >= 0 && playerInvSlot <= 40) return playerInvSlot;
         return -1;
    }

    // Copied from UtilityService for internal use
    private void setItemSafe(PlayerInventory inv, int slot, ItemStack item) {
        if (item == null || slot < 0 || slot > 40) return;
        switch (slot) {
            case 36: inv.setBoots(item); break; // Armor slots direct mapping
            case 37: inv.setLeggings(item); break;
            case 38: inv.setChestplate(item); break;
            case 39: inv.setHelmet(item); break;
            case 40: inv.setItemInOffHand(item); break; // Offhand
            default: inv.setItem(slot, item); break; // Main inventory 0-35
        }
    }
}

