package com.takeda.tkits.listeners;

import com.takeda.tkits.TKits;
import com.takeda.tkits.managers.GuiManager;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.PlayerData;
import com.takeda.tkits.util.MessageUtil;

import net.kyori.adventure.text.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class GuiListener implements Listener {

    private final TKits plugin;
    private final GuiManager guiManager;
    private final MessageUtil msg;
    private final NamespacedKey actionKey;
    private final NamespacedKey kitNumberKey;
    private final NamespacedKey kitOwnerKey;
    private final Map<UUID, Long> kitroomCooldown = new ConcurrentHashMap<>();

    public GuiListener(TKits plugin) {
        this.plugin = plugin;
        this.guiManager = plugin.getGuiManager();
        this.msg = plugin.getMessageUtil();
        this.actionKey = guiManager.getActionKey();
        this.kitNumberKey = guiManager.getKitNumberKey();
        this.kitOwnerKey = guiManager.getKitOwnerKey();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        Inventory clickedInventory = event.getClickedInventory();
        Inventory topInventory = event.getView().getTopInventory();

        // Ignore if the top inventory isn't one of ours
        if (!(topInventory.getHolder() instanceof GuiManager)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topInvSize = topInventory.getSize();
        boolean isTopInventoryClick = rawSlot >= 0 && rawSlot < topInvSize;
        boolean isPlayerInventoryClick = rawSlot >= topInvSize;

        // Handle clicks in player inventory
        if (isPlayerInventoryClick) {
            // Prevent shift-clicking into most GUIs
            if (event.isShiftClick() &&
                !(guiManager.isKitEditorInventory(topInventory) ||
                  guiManager.isEnderChestEditorInventory(topInventory) ||
                  (guiManager.isKitroomCategoryInventory(topInventory) && player.hasPermission("tkits.kitroom.admin"))
                 )
               )
            {
                event.setCancelled(true);
                // msg.debug("GuiListener: Preventing shift-click into read-only GUI top inventory.");
                return;
            }
            // msg.debug("GuiListener: Allowing click in player inventory (Raw Slot " + rawSlot + ")");
            return; // Allow normal interaction in player inventory
        }

        // Handle clicks in the top (GUI) inventory
        if (isTopInventoryClick) {
            ItemStack clickedItem = event.getCurrentItem();
            int slot = event.getSlot();

            // Always cancel the event initially for GUI clicks, then un-cancel if needed
            event.setCancelled(true);
            // msg.debug("GuiListener: Default cancelled click in top inventory (Slot " + slot + ")");

            // --- Check for Button Actions first ---
            if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                ItemMeta meta = clickedItem.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    if (pdc.has(actionKey, PersistentDataType.STRING)) {
                        String action = pdc.get(actionKey, PersistentDataType.STRING);
                        msg.debug("  -> Button Action Found! Action: '" + action + "', Slot: " + slot + ", Item: " + clickedItem.getType());
                        if (action != null) {
                            // Delegate button actions to GuiManager
                            guiManager.handleButtonAction(player, action, pdc, event.getClick(), slot);
                        } else {
                             msg.debug("  -> Null action found on button? Remaining cancelled.");
                        }
                        return; // Button action handled (or ignored), event remains cancelled
                    }
                }
            } // End of button check

            // --- Handle Non-Button Interactions based on GUI Type ---

            // 1. Kit Editor / Ender Chest Editor Interaction
            if (guiManager.isKitEditorInventory(topInventory) || guiManager.isEnderChestEditorInventory(topInventory)) {
                int maxEditableSlot = guiManager.isKitEditorInventory(topInventory) ? 40 : 26;
                if (slot >= 0 && slot <= maxEditableSlot) {
                    // Allow interaction within the editable area
                    // msg.debug("  -> Editor Interaction: Allowing interaction in editable slot " + slot + ".");
                    event.setCancelled(false); // Un-cancel
                } else {
                    // Click outside editable area (e.g., on filler or button area)
                    // Event remains cancelled
                    // msg.debug("  -> Editor Interaction: Click in non-editable/non-button area (Slot " + slot + "). Remaining cancelled.");
                }
                return; // Processing for editor GUIs is complete
            }
            // 2. Kitroom Category Interaction
            else if (guiManager.isKitroomCategoryInventory(topInventory)) {
                 boolean isAdmin = player.hasPermission("tkits.kitroom.admin");
                 if (slot >= 0 && slot < 45) { // Item area
                     if (isAdmin) {
                          // Admin editing layout
                          // msg.debug("  -> Kitroom Admin Interaction: Allowing edit in slot " + slot + ".");
                          plugin.getConfigManager().markKitroomChanged(); // Mark potential change
                          event.setCancelled(false); // Un-cancel for admin
                     } else {
                          // Player trying to take an item
                          if (clickedItem != null && clickedItem.getType() != Material.AIR && !guiManager.isKitroomFiller(clickedItem)) {
                              // msg.debug("  -> Kitroom Player Interaction: Attempting to take item from slot " + slot + ".");
                              handleKitroomCategoryNonButtonClick(event, player, clickedInventory, topInventory);
                          } else {
                               // msg.debug("  -> Kitroom Player Interaction: Clicked empty/filler slot " + slot + ". Remaining cancelled.");
                          }
                          // Event remains cancelled for player taking items
                     }
                 } else {
                      // Click was in button area (45-53) but wasn't a button
                      // msg.debug("  -> Kitroom Interaction: Click in non-editable/non-button area (Slot " + slot + "). Remaining cancelled.");
                      // Event remains cancelled
                 }
                return; // Processing for kitroom category GUI is complete
            }
            // 3. Global Kit List Interaction (Loading/Previewing Kits)
            else if (guiManager.isGlobalListInventory(topInventory)) {
                 // Clicked on a player head (which isn't a button but has PDC)
                 if (clickedItem != null && clickedItem.getType() == Material.PLAYER_HEAD) {
                      // msg.debug("  -> Global List Interaction: Clicked on player head in slot " + slot + ".");
                      handleGlobalListNonButtonClick(event, player, clickedInventory, topInventory);
                 } else {
                      // msg.debug("  -> Global List Interaction: Clicked on non-head item/filler (Slot " + slot + "). Remaining cancelled.");
                 }
                 // Event remains cancelled by default (action handled programmatically)
                 return; // Processing for global list GUI is complete
            }
            // --- NEW: Personal Kit Choice GUI Interaction ---
            else if (guiManager.isPersonalKitChoiceInventory(topInventory)) {
                // msg.debug("  -> Personal Kit Choice Interaction in slot " + slot + ".");
                // Button clicks (like Cancel) are handled by the actionKey check earlier.
                // Handle clicks on the Kit items themselves here.
                if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                    ItemMeta meta = clickedItem.getItemMeta();
                    if (meta != null) {
                        PersistentDataContainer pdc = meta.getPersistentDataContainer();
                        // Check if it's a kit item (has kitNumberKey, no actionKey)
                        if (pdc.has(kitNumberKey, PersistentDataType.INTEGER) && !pdc.has(actionKey, PersistentDataType.STRING)) {
                            int kitNumber = pdc.getOrDefault(kitNumberKey, PersistentDataType.INTEGER, -1);
                            if (kitNumber > 0) {
                                msg.debug("    -> Kit item clicked: Kit " + kitNumber);
                                PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
                                Kit selectedKit = (playerData != null) ? playerData.getKit(kitNumber) : null;
                                // Create a snapshot (clone) to pass to the future
                                Kit kitSnapshot = (selectedKit != null) ? selectedKit.toBuilder().build() : null;

                                if (kitSnapshot != null) {
                                    // Complete the pending future successfully
                                    guiManager.cancelPendingKitChoice(player.getUniqueId(), Optional.of(kitSnapshot));
                                    msg.playSound(player, "gui_click"); // Confirmation sound
                                    player.closeInventory(); // Close GUI after selection
                                } else {
                                    // Kit might have been deleted between GUI open and click
                                    msg.sendMessage(player, "kit_not_found", "kit_number", String.valueOf(kitNumber));
                                    msg.playSound(player, "error");
                                    guiManager.cancelPendingKitChoice(player.getUniqueId(), Optional.empty()); // Cancel choice
                                    player.closeInventory();
                                }
                            } else {
                                 msg.debug("    -> Kit item clicked but invalid kit number in PDC.");
                            }
                        } else {
                             // msg.debug("    -> Clicked non-kit item (likely filler) in Kit Choice GUI.");
                        }
                    }
                }
                // Event remains cancelled by default for this GUI (prevents taking items)
                return;
            }
            // --- End New ---

            // Fallback: Click in other GUIs or unhandled areas remains cancelled
            // msg.debug("  -> Click in unhandled area or read-only GUI (Slot " + slot + "). Remaining cancelled.");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        InventoryView view = event.getView();
        Inventory topInventory = view.getTopInventory();
        if (!(topInventory.getHolder() instanceof GuiManager)) return;

        Player player = (Player) event.getWhoClicked();
        boolean isAdminKitroomCategory = guiManager.isKitroomCategoryInventory(topInventory) && player.hasPermission("tkits.kitroom.admin");
        boolean isEditor = guiManager.isKitEditorInventory(topInventory) || guiManager.isEnderChestEditorInventory(topInventory);

        boolean draggedIntoTop = false;
        int highestRawSlotInTop = -1;
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topInventory.getSize()) {
                draggedIntoTop = true;
                highestRawSlotInTop = Math.max(highestRawSlotInTop, rawSlot);
            }
        }

        if (draggedIntoTop) {
            // msg.debug("onInventoryDrag: Drag involves top inventory. Highest slot affected: " + highestRawSlotInTop + ". GUI: " + guiManager.identifyGuiFromHolder(topInventory));
            if (isEditor) {
                int maxEditableSlot = guiManager.isKitEditorInventory(topInventory) ? 40 : 26;
                if (highestRawSlotInTop > maxEditableSlot) {
                    // msg.debug(" -> Cancelling drag (Editor button/non-editable area).");
                    event.setCancelled(true);
                } else {
                    // msg.debug(" -> Allowing drag into Editor item area (Highest Slot " + highestRawSlotInTop + ").");
                }
            } else if (isAdminKitroomCategory) {
                if (highestRawSlotInTop >= 45) { // Dragged into button area
                    // msg.debug(" -> Cancelling drag (Admin Kitroom Category button area).");
                    event.setCancelled(true);
                } else { // Dragged within item area
                    // msg.debug(" -> Allowing drag into Admin Kitroom Category item area (Highest Slot " + highestRawSlotInTop + ").");
                    plugin.getConfigManager().markKitroomChanged();
                }
            } else { // Includes PERSONAL_KIT_CHOICE and all other read-only GUIs
                // msg.debug(" -> Cancelling drag (Read-only GUI or non-admin Kitroom/Kit Choice).");
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory closedInventory = event.getInventory();
        if (!(closedInventory.getHolder() instanceof GuiManager)) return;
        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();
            // msg.debug("onInventoryClose: Detected close for player " + player.getName() + " from a GuiManager inventory.");
            // Delegate all close logic, including cancelling pending futures, to GuiManager
            guiManager.handleGuiClose(player, closedInventory);
        }
    }

    // --- Helper Methods for Specific Non-Button Interactions ---
    private void handleKitroomCategoryNonButtonClick(InventoryClickEvent event, Player player, Inventory clickedInventory, Inventory topInventory) {
        int slot = event.getSlot();
        UUID playerUUID = player.getUniqueId();
        boolean isAdmin = player.hasPermission("tkits.kitroom.admin");

        // This method only handles player item taking now
        if (!isAdmin && slot >= 0 && slot < 45) {
            ItemStack clickedItem = event.getCurrentItem();
            if(clickedItem == null || clickedItem.getType() == Material.AIR || guiManager.isKitroomFiller(clickedItem)) {
                // msg.debug("handleKitroomCategoryNonButtonClick: Player clicked filler/empty. Doing nothing.");
                return;
            }
            // msg.debug("handleKitroomCategoryNonButtonClick: Player attempting to take item.");
            boolean requiresPerm = plugin.getConfigManager().isKitroomRequirePerCategoryPermission();
            String category = guiManager.getEditingKitroomCategoryMap().get(playerUUID);
            if (requiresPerm && category != null && !category.isEmpty()) {
                String permNode = "tkits.kitroom.category." + category.toLowerCase();
                if (!player.hasPermission(permNode)) {
                    msg.sendMessage(player, "no_permission_category", "category", guiManager.capitalize(category));
                    msg.playSound(player, "error");
                    return;
                }
            } else if (requiresPerm && (category == null || category.isEmpty())) {
                msg.logWarning("Kitroom permission check enabled, but couldn't get category for player " + player.getName());
                msg.sendMessage(player, "error");
                return;
            }
            int cooldownSeconds = plugin.getConfigManager().getKitroomItemTakeCooldownSeconds();
            if (cooldownSeconds > 0 && !player.hasPermission("tkits.cooldown.bypass")) {
                long now = System.currentTimeMillis();
                long lastTake = kitroomCooldown.getOrDefault(playerUUID, 0L);
                long remainingMillis = (lastTake + (cooldownSeconds * 1000L)) - now;
                if (remainingMillis > 0) {
                     msg.sendMessage(player, "kitroom_on_cooldown", "time", String.format("%.1f", remainingMillis / 1000.0));
                     msg.playSound(player, "cooldown");
                     return;
                }
            }
            ItemStack itemToGive = clickedItem.clone();
            // Remove player-specific lore before giving
            ItemMeta giveMeta = itemToGive.getItemMeta();
            if(giveMeta != null && giveMeta.hasLore()){
                List<Component> currentLore = giveMeta.lore();
                if(currentLore != null){
                    // Check if the last line matches the hint and remove it
                    if(!currentLore.isEmpty() && currentLore.get(currentLore.size() - 1).equals(guiManager.KITROOM_PLAYER_LORE_HINT)) {
                        currentLore.remove(currentLore.size() - 1);
                        // Also remove the empty line potentially added before it
                        // TODO: Use MessageUtil for plain text serialization
                        if(!currentLore.isEmpty() && plugin.getMessageUtil().getPlainTextSerializer().serialize(currentLore.get(currentLore.size() - 1)).trim().isEmpty()) {
                            currentLore.remove(currentLore.size() - 1);
                        }
                        giveMeta.lore(currentLore);
                        itemToGive.setItemMeta(giveMeta);
                    }
                }
            }

            Map<Integer, ItemStack> leftover = player.getInventory().addItem(itemToGive);
             if (!leftover.isEmpty()) {
                 msg.sendActionBar(player, "inventory_full_item_dropped");
                 leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
             }
             msg.sendActionBar(player, "kitroom_item_given", "amount", String.valueOf(itemToGive.getAmount()), "item_name", getItemName(itemToGive));
             msg.playSound(player, "kitroom_item_take");
             if (cooldownSeconds > 0 && !player.hasPermission("tkits.cooldown.bypass")) {
                kitroomCooldown.put(playerUUID, System.currentTimeMillis());
             }
        }
    }

     private void handleGlobalListNonButtonClick(InventoryClickEvent event, Player player, Inventory clickedInventory, Inventory topInventory) {
         ItemStack clickedItem = event.getCurrentItem();
         if (clickedItem == null || clickedItem.getType() != Material.PLAYER_HEAD) return;
         ItemMeta meta = clickedItem.getItemMeta();
         if (meta != null) {
              PersistentDataContainer pdc = meta.getPersistentDataContainer();
              if (pdc.has(kitOwnerKey, PersistentDataType.STRING) && pdc.has(kitNumberKey, PersistentDataType.INTEGER)) {
                  UUID ownerUUID = null;
                  int kitNumber = pdc.getOrDefault(kitNumberKey, PersistentDataType.INTEGER, -1);
                  try { ownerUUID = UUID.fromString(pdc.get(kitOwnerKey, PersistentDataType.STRING)); } catch (Exception ignored) {}
                  if (ownerUUID == null || kitNumber <= 0) { msg.logWarning("Invalid PDC data on global kit player head"); return; }
                  final UUID finalOwnerUUID = ownerUUID;
                  final int finalKitNumber = kitNumber;
                  if (event.isLeftClick()) {
                      msg.sendActionBar(player, "loading_kit", "kit_number", String.valueOf(finalKitNumber));
                      plugin.getApi().getPlayerKitAsync(finalOwnerUUID, finalKitNumber).thenAcceptAsync(optionalKit -> {
                           if (optionalKit.isPresent()) {
                               Kit kitToLoad = optionalKit.get();
                               if (!kitToLoad.isGlobal()) {
                                    msg.sendMessage(player, "kit_no_longer_global"); msg.playSound(player, "error");
                                    guiManager.openGlobalKitsList(player, guiManager.getPlayerGlobalKitPagesMap().getOrDefault(player.getUniqueId(), 1));
                                    return;
                               }
                               boolean success = plugin.getKitManager().loadKit(player, kitToLoad);
                               if (success) player.closeInventory();
                           } else {
                                msg.sendMessage(player, "kit_not_found", "kit_number", String.valueOf(finalKitNumber)); msg.playSound(player, "error");
                                guiManager.openGlobalKitsList(player, guiManager.getPlayerGlobalKitPagesMap().getOrDefault(player.getUniqueId(), 1));
                           }
                       }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin));
                  } else if (event.isRightClick()) {
                      // Delegate to the main button handler using the action name and PDC data
                      guiManager.handleButtonAction(player, "preview_global_kit", pdc, event.getClick(), event.getSlot());
                  }
              }
          }
     }

    private String getItemName(ItemStack item) {
        if (item == null) return "Nothing";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component displayName = meta.displayName();
            if (displayName != null) {
                // TODO: Use MessageUtil for plain text serialization
                return plugin.getMessageUtil().getPlainTextSerializer().serialize(displayName);
            }
        }
        // Fallback to material name
        return Arrays.stream(item.getType().toString().toLowerCase().split("_"))
                     .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1))
                     .collect(Collectors.joining(" "));
    }
} // End of GuiListener class