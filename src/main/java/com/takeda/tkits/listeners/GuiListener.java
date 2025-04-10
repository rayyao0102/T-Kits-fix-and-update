package com.takeda.tkits.listeners;

import com.takeda.tkits.TKits;
import com.takeda.tkits.managers.GuiManager;
import com.takeda.tkits.util.MessageUtil;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class GuiListener implements Listener {

    private final TKits plugin;
    private final GuiManager guiManager;
    private final MessageUtil msg;
    private final NamespacedKey actionKey;
    private final Map<UUID, Long> kitroomCooldown = new ConcurrentHashMap<>(); // Map<PlayerUUID, LastTakeTimeMillis>

    public GuiListener(TKits plugin) {
        this.plugin = plugin;
        this.guiManager = plugin.getGuiManager();
        this.msg = plugin.getMessageUtil();
        this.actionKey = guiManager.getActionKey();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        // Conditional Debug Logging
        msg.debug(String.format("onInventoryClick: Start. Player=%s, ClickedInvType=%s, Slot=%d, Item=%s, Action=%s, TopInvHolder=%s",
                event.getWhoClicked().getName(),
                (event.getClickedInventory() == null ? "NULL" : event.getClickedInventory().getType()),
                event.getSlot(),
                (event.getCurrentItem() == null ? "NULL" : event.getCurrentItem().getType()),
                event.getAction(),
                (event.getView().getTopInventory().getHolder() == null ? "NULL" : event.getView().getTopInventory().getHolder().getClass().getSimpleName()))
        );

        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        Inventory clickedInventory = event.getClickedInventory();
        Inventory topInventory = event.getView().getTopInventory();

        // --- Check if interaction is relevant ---
        if (!(topInventory.getHolder() instanceof GuiManager)) { // Simplified check
             msg.debug("onInventoryClick: Ignoring click (Top inventory holder is not GuiManager)");
            // Allow vanilla interactions if needed
            return;
        }
         msg.debug("onInventoryClick: Click passed holder check (Holder IS GuiManager).");

        // --- Default Cancellation ---
        // Cancel interactions with our GUIs by default. Specific handlers below will un-cancel if needed.
        event.setCancelled(true);
        msg.debug("onInventoryClick: Default cancelling event.");

        // --- Explicit Cancellation Check (Redundant but safe) ---
        // Ensures clicks in the *top* inventory of Kitroom Main are always cancelled,
        // Removed explicit check for Kitroom Main Menu as it's now category selection
        // if (guiManager.isKitroomMainInventory(topInventory, player) && clickedInventory == topInventory) { ... }


        // --- Delegate to Specific Handlers Based on GUI Type ---
        if (guiManager.isKitEditorInventory(topInventory) || guiManager.isEnderChestEditorInventory(topInventory)) {
            msg.debug("onInventoryClick: Delegating to handleEditorClick.");
            handleEditorClick(event, player, clickedInventory, topInventory);
        } else if (guiManager.isKitroomCategoryInventory(topInventory)) { // Check for the new category GUI
            msg.debug("onInventoryClick: Delegating to handleKitroomCategoryClick.");
            handleKitroomCategoryClick( // Call the unified handler
                event,
                player,
                clickedInventory,
                topInventory
            );
        // Removed specific handling for Kitroom Main Menu (now category selection)
        // } else if (guiManager.isKitroomMainInventory(topInventory, player)) { ... }
        } else { // Handle other GUIs (Main Menu, Global List, Previews, Confirmation, Kitroom Category Selection)
             msg.debug("onInventoryClick: Delegating to handleGenericButtonClick for other GUI types.");
            handleGenericButtonClick(
                event,
                player,
                clickedInventory,
                topInventory
            );
        }
    }

    // Handles clicks specifically within Kit Editor or EChest Editor
    private void handleEditorClick(
        InventoryClickEvent event,
        Player player,
        Inventory clickedInventory,
        Inventory topInventory
    ) {
        int slot = event.getSlot();
        int maxItemSlot = guiManager.isKitEditorInventory(topInventory) ? 40 : 26;

        if ( (clickedInventory == topInventory && slot >= 0 && slot <= maxItemSlot) || clickedInventory == player.getInventory() ) {
            msg.debug("handleEditorClick: Allowing interaction in editable area/player inventory. Slot: " + slot);
            event.setCancelled(false);
        } else if (clickedInventory == topInventory) {
             msg.debug("handleEditorClick: Click in button area. Slot: " + slot + ". Delegating to button handler.");
            handleGenericButtonClick( event, player, clickedInventory, topInventory );
            event.setCancelled(true); // Ensure button clicks remain cancelled
        }
    }

    // Handles clicks within the Kitroom Category GUI (Unified Handler)
    private void handleKitroomCategoryClick(
        InventoryClickEvent event,
        Player player,
        Inventory clickedInventory,
        Inventory topInventory
    ) {
        int slot = event.getSlot();
        boolean isAdmin = player.hasPermission("tkits.kitroom.admin");
        UUID playerUUID = player.getUniqueId();

        // Bottom row buttons (always cancel after handling)
        if (clickedInventory == topInventory && slot >= 45) {
            msg.debug("handleKitroomCategoryClick: Clicked button area (slot " + slot + "). Delegating to handleGenericButtonClick.");
            handleGenericButtonClick(event, player, clickedInventory, topInventory);
            event.setCancelled(true); // Ensure buttons are cancelled after action
            return;
        }

        // --- Admin Interaction (Editing Layout) ---
        if (isAdmin) {
            msg.debug("handleKitroomCategoryClick (Admin): Allowing modification. Slot: " + slot + ", InvType: " + (clickedInventory == null ? "NULL" : clickedInventory.getType()));
            // Admins can freely move items within the top (0-44) and between top/player inventory
            event.setCancelled(false);
            plugin.getConfigManager().markKitroomChanged(); // Call on ConfigManager via plugin
            return; // Allow admin action
        }

        // --- Player Interaction (Taking Items) ---
        if (!isAdmin) {
            // Clicking in their own inventory is allowed (but likely cancelled by generic logic if shift-clicking doesn't result in adding to top)
            if (clickedInventory != topInventory) {
                 msg.debug("handleKitroomCategoryClick (Player): Clicked player inventory. Allowing.");
                 event.setCancelled(false);
                 return;
            }

            // Clicking an item in the Kitroom (Top Inventory, slots 0-44)
            if (clickedInventory == topInventory && slot >= 0 && slot < 45) {
                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem == null || clickedItem.getType() == Material.AIR) {
                    msg.debug("handleKitroomCategoryClick (Player): Clicked empty slot in kitroom. Cancelling.");
                    event.setCancelled(true);
                    return;
                }

                // --- Permission Check --- (Moved inside player interaction)
                boolean requiresPerm = plugin.getConfigManager().isKitroomRequirePerCategoryPermission();
                String category = guiManager.getEditingKitroomCategoryMap().get(playerUUID);
                if (requiresPerm && category != null) {
                     String permNode = "tkits.kitroom.category." + category.toLowerCase();
                     if (!player.hasPermission(permNode)) {
                         msg.debug("handleKitroomCategoryClick (Player): No permission for category. Cancelling.");
                         msg.sendMessage(player, "no_permission_category", "category", category);
                         msg.playSound(player, "error");
                         event.setCancelled(true);
                         return;
                     }
                }

                // --- Cooldown Check --- (Moved inside player interaction)
                int cooldownSeconds = plugin.getConfigManager().getKitroomItemTakeCooldownSeconds();
                if (cooldownSeconds > 0 && !player.hasPermission("tkits.cooldown.bypass")) { // Check bypass perm
                    long now = System.currentTimeMillis();
                    long lastTake = kitroomCooldown.getOrDefault(playerUUID, 0L);
                    long remainingMillis = (lastTake + (cooldownSeconds * 1000L)) - now;
                    if (remainingMillis > 0) {
                        msg.debug("handleKitroomCategoryClick (Player): Kitroom on cooldown. Cancelling.");
                        msg.sendMessage(player, "kitroom_on_cooldown", "time", String.format("%.1f", remainingMillis / 1000.0));
                        msg.playSound(player, "cooldown");
                        event.setCancelled(true);
                        return;
                    }
                }

                // --- Give Item Logic --- (Only run if checks pass)
                ItemStack itemToGive = clickedItem.clone();
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(itemToGive);

                 if (!leftover.isEmpty()) {
                     msg.debug("handleKitroomCategoryClick (Player): Inventory full. Dropping items.");
                     leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                 } else {
                     msg.debug("handleKitroomCategoryClick (Player): Giving item.");
                     msg.sendActionBar(player, "kitroom_item_given", "amount", String.valueOf(itemToGive.getAmount()), "item_name", getItemName(itemToGive));
                     msg.playSound(player, "regear_success");
                 }

                 // Apply cooldown after successfully taking an item
                 if (cooldownSeconds > 0) {
                    kitroomCooldown.put(playerUUID, System.currentTimeMillis());
                 }

                event.setCancelled(true); // Always cancel the click for players in kitroom item area
            }
            // Player clicking empty space or buttons in top - already handled or cancelled
        }
    }

    // REMOVED handleKitroomAdminClick and handleKitroomPlayerClick as logic is merged above

    // Handles Kitroom interaction for Admins (editing layout) - REMOVED
    /* private void handleKitroomAdminClick(
        InventoryClickEvent event,
        Player player,
        Inventory clickedInventory,
        Inventory topInventory
    ) { ... } */

    // Handles Kitroom interaction for Players (taking items) - REMOVED
    /* private void handleKitroomPlayerClick(
        InventoryClickEvent event,
        Player player,
        Inventory clickedInventory,
        Inventory topInventory
    ) { ... } */


    // Handles clicks on buttons in non-editor GUIs (identifies action and delegates to GuiManager)
    private void handleGenericButtonClick(InventoryClickEvent event, Player player, Inventory clickedInventory, Inventory topInventory) {
        ItemStack clickedItem = event.getCurrentItem();
        event.setCancelled(true); // Ensure cancellation

        // Initial checks: Must be top inventory, item must exist
        if (clickedInventory != topInventory || clickedItem == null || clickedItem.getType() == Material.AIR) {
             msg.debug("handleGenericButtonClick: Ignoring click (Not top inventory or null/air item).");
             return;
        }

        // --- Add check to ignore known filler items ---
        // Check for standard gray stained glass pane with a blank name
        // This prevents warnings when clicking filler items.
        if (clickedItem.getType() == Material.GRAY_STAINED_GLASS_PANE || clickedItem.getType() == Material.BLACK_STAINED_GLASS_PANE) {
            ItemMeta fillerMeta = clickedItem.getItemMeta();
            // Check if name is effectively blank (null, no name, or just color codes)
            if (fillerMeta != null && (!fillerMeta.hasDisplayName() || msg.stripColors(fillerMeta.displayName()).isBlank())) {
                 msg.debug("handleGenericButtonClick: Ignoring click (Likely a filler item). Item: " + clickedItem.getType() + " Slot: "+ event.getSlot());
                 return; // Ignore clicks on likely filler items
            }
        }
        // --- End filler item check ---

        ItemMeta meta = clickedItem.getItemMeta();
        if (meta == null) {
             msg.debug("handleGenericButtonClick: Ignoring click (Item meta is null). Item: " + clickedItem.getType());
             return;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        msg.debug("handleGenericButtonClick: Processing potential button click for " + player.getName() + " on " + clickedItem.getType() + " in slot " + event.getSlot());

        // Check for action key (Now less likely to hit for filler items)
        if (!pdc.has(actionKey, PersistentDataType.STRING)) {
            String guiTitle = msg.stripColors(player.getOpenInventory().title());
            // Use debug level, as clicking non-action items is normal user behavior, not a server warning.
            msg.debug("[T-Kits DEBUG] Clicked item MISSING action key in GUI: " + guiTitle + ", slot: " + event.getSlot() + ", item: " + clickedItem.getType());
            return;
        }

        String action = pdc.get(actionKey, PersistentDataType.STRING);

        if (action == null) {
            // Use debug level here too.
            msg.debug("[T-Kits DEBUG] Retrieved NULL action string from PDC even though key exists? Slot: " + event.getSlot());
            return;
        }

        msg.debug("handleGenericButtonClick: Retrieved action='" + action + "' from PDC for slot " + event.getSlot() + ". Calling GuiManager...");

        // Conditional Debug Logging (Example of getting PDC data if needed)
        // String categoryFromPDC = pdc.get(guiManager.getCategoryKey(), PersistentDataType.STRING);
        // msg.debug("handleGenericButtonClick: Action='" + action + "', Category='" + (categoryFromPDC == null ? "N/A" : categoryFromPDC) + "'. Calling GuiManager.handleButtonAction...");

        guiManager.handleButtonAction(player, action, pdc, event.getClick(), event.getSlot());
    }

    // --- Drag Event Handling ---
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof GuiManager)) return;

        Player player = (Player) event.getWhoClicked();
        boolean isAdminKitroomCategory = guiManager.isKitroomCategoryInventory(topInventory) && player.hasPermission("tkits.kitroom.admin"); // Check specific GUI
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
            msg.debug("onInventoryDrag: Drag involves top inventory. Highest slot: " + highestRawSlotInTop);
            if (isEditor) {
                int maxItemSlot = guiManager.isKitEditorInventory(topInventory) ? 40 : 26;
                if (highestRawSlotInTop > maxItemSlot) {
                     msg.debug("-> Cancelling drag (Editor button area)."); event.setCancelled(true);
                } else { msg.debug("-> Allowing drag (Editor item area)."); }
            } else if (isAdminKitroomCategory) { // Use updated variable name
                if (highestRawSlotInTop >= 45) { // Dragged into button row
                     msg.debug("-> Cancelling drag (Admin Kitroom Category button area)."); event.setCancelled(true);
                } else { // Dragged into item area
                     msg.debug("-> Allowing drag (Admin Kitroom Category item area).");
                     // No need to mark changed, save button handles it
                }
            } else { // Read-only GUI (e.g., Main Menu, Global List, Player Kitroom Category view)
                msg.debug("-> Cancelling drag (Read-only or Player Kitroom GUI). GUI Title: " + msg.stripColors(player.getOpenInventory().title()));
                event.setCancelled(true);
            }
        } else { msg.debug("onInventoryDrag: Drag does not involve top inventory. Allowing."); }
    }

    // --- GUI Close Handling ---
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof GuiManager) {
            if (event.getPlayer() instanceof Player) {
                Player player = (Player) event.getPlayer();
                 msg.debug("onInventoryClose: Detected close for player " + player.getName() + " from a GuiManager inventory.");
                guiManager.handleGuiClose(player, event.getInventory());
            }
        }
    }

    // Helper to get item name
    private String getItemName(ItemStack item) {
        if (item == null) return "Nothing";
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return msg.stripColors(item.getItemMeta().displayName()); // Use Component name if available
        }
        // Fallback to Material name
        return item.getType().toString().toLowerCase().replace('_', ' ');
    }
}
