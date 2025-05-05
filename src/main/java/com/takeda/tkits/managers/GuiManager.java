package com.takeda.tkits.managers;

import com.takeda.tkits.TKits;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.KitContents;
import com.takeda.tkits.models.PlayerData;
import com.takeda.tkits.util.GuiUtils;
import com.takeda.tkits.util.MessageUtil;
import com.takeda.tkits.config.ConfigManager;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;

import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.wesjd.anvilgui.AnvilGUI; // Shaded AnvilGUI
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

@Getter
public class GuiManager implements InventoryHolder {

    // --- Fields ---
    private final TKits plugin;
    private final MessageUtil msg;
    private final KitManager kitManager;
    private final KitroomManager kitroomManager;
    private final ShareCodeManager shareCodeManager;
    private final ConfigManager configManager;
    private final NamespacedKey actionKey;
    private final NamespacedKey kitNumberKey;
    private final NamespacedKey kitOwnerKey;
    private final NamespacedKey categoryKey;
    private final NamespacedKey pageKey;

    private FileConfiguration guiConfig;

    // Player state tracking
    private final Map<UUID, String> editingKitroomCategory = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerGlobalKitPages = new ConcurrentHashMap<>();
    private final Map<UUID, Long> confirmClickCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Optional<Kit>>> pendingKitChoices = new ConcurrentHashMap<>(); // Added

    // GUI History and state
    private final Map<UUID, Deque<GuiState>> guiHistory = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> wasGuiClosedByButton = new ConcurrentHashMap<>();

    // Cached Filler Item Instances
    private ItemStack kitroomFillerInstance;
    private ItemStack kitroomButtonAreaFillerInstance;
    private ItemStack editorButtonAreaFillerInstance;

    // Use public visibility if nested, otherwise package-private if top-level in package
    // Now uses the external GuiIdentifier enum
    record GuiState(GuiIdentifier identifier, Map<String, Object> context) {}

    // --- Public Getters for State ---
    public Map<UUID, Integer> getPlayerGlobalKitPagesMap() { return this.playerGlobalKitPages; }
    public Map<UUID, String> getEditingKitroomCategoryMap() { return this.editingKitroomCategory; }
    public Map<UUID, CompletableFuture<Optional<Kit>>> getPendingKitChoicesMap() { return this.pendingKitChoices; } // Added Getter

    // --- Constants ---
    public final Component KITROOM_ADMIN_LORE_HINT;
    public final Component KITROOM_PLAYER_LORE_HINT;

    // --- Constructor ---
    public GuiManager(TKits plugin) {
        this.plugin = plugin;
        this.msg = plugin.getMessageUtil();
        this.kitManager = plugin.getKitManager();
        this.kitroomManager = plugin.getKitroomManager();
        this.shareCodeManager = plugin.getShareCodeManager();
        this.configManager = plugin.getConfigManager();
        this.actionKey = new NamespacedKey(plugin, "tkits_action");
        this.kitNumberKey = new NamespacedKey(plugin, "tkits_kit_number");
        this.kitOwnerKey = new NamespacedKey(plugin, "tkits_kit_owner");
        this.categoryKey = new NamespacedKey(plugin, "tkits_category");
        this.pageKey = new NamespacedKey(plugin, "tkits_page");

        reloadGuiConfig(); // Load initial configs and fillers

        this.KITROOM_ADMIN_LORE_HINT = msg.deserialize("&c&lAdmin: &7Moving items modifies layout.");
        this.KITROOM_PLAYER_LORE_HINT = msg.deserialize("&b▸ Click to Take Item");
    }

    // Method to reload GUI config data and cached items
    public void reloadGuiConfig() {
        this.guiConfig = configManager.getGuiConfig();
        plugin.getLogger().info("Reloading GUI configuration items...");

        // Load cached filler instances
        this.kitroomFillerInstance = loadGuiItem("items.kitroom_category.filler", Material.BLACK_STAINED_GLASS_PANE, "&r", null);
        this.kitroomButtonAreaFillerInstance = this.kitroomFillerInstance; // Default to same as main kitroom filler
        this.editorButtonAreaFillerInstance = loadGuiItem("items.kit_editor.button_area_filler", Material.GRAY_STAINED_GLASS_PANE, "&r", null);

        msg.debug("GUI configuration reloaded.");
    }

    // --- Filler Check Methods ---
    public boolean isKitroomFiller(ItemStack item) {
        if (item == null || kitroomFillerInstance == null) return false;
        return item.isSimilar(kitroomFillerInstance);
    }
     public boolean isKitroomButtonAreaFiller(ItemStack item) {
         if (item == null || kitroomButtonAreaFillerInstance == null) return false;
         return item.isSimilar(kitroomButtonAreaFillerInstance);
      }
      public boolean isEditorButtonAreaFiller(ItemStack item) {
           if (item == null || editorButtonAreaFillerInstance == null) return false;
           return item.isSimilar(editorButtonAreaFillerInstance);
      }

    // --- GUI History Management ---
    // Now uses the external GuiIdentifier enum type
    public void pushGuiHistory(UUID playerUUID, GuiIdentifier identifier, Map<String, Object> context) {
        msg.debug("[HISTORY] Pushing GUI: " + identifier + " with context " + context + " for " + playerUUID);
        Map<String, Object> safeContext = context == null ? Collections.emptyMap() : new HashMap<>(context);
        guiHistory.computeIfAbsent(playerUUID, k -> new ArrayDeque<>()).push(new GuiState(identifier, safeContext));
        msg.debug("[HISTORY] Stack (" + guiHistory.getOrDefault(playerUUID, new ArrayDeque<>()).size() + "): " + guiHistory.get(playerUUID));
    }

    private GuiState popGuiHistory(UUID playerUUID) {
        Deque<GuiState> history = guiHistory.get(playerUUID);
        if (history != null && !history.isEmpty()) {
            GuiState popped = history.pop();
            msg.debug("[HISTORY] Popped GUI: " + popped.identifier() + " with context " + popped.context() + " for " + playerUUID);
            if (history.isEmpty()) {
                guiHistory.remove(playerUUID);
                msg.debug("[HISTORY] Stack is now empty.");
            } else {
                msg.debug("[HISTORY] Stack (" + history.size() + "): " + history);
            }
            return popped;
        }
        msg.debug("[HISTORY] Attempted to pop, but stack was null or empty for " + playerUUID);
        return null;
    }

     private GuiState peekGuiHistory(UUID playerUUID) {
         Deque<GuiState> history = guiHistory.get(playerUUID);
         if (history != null && !history.isEmpty()) {
             return history.peek();
         }
         return null;
     }

     private void clearGuiHistory(UUID playerUUID) {
         guiHistory.remove(playerUUID);
         playerGlobalKitPages.remove(playerUUID);
         editingKitroomCategory.remove(playerUUID);
         // Also clear pending choice on history clear
         cancelPendingKitChoice(playerUUID, Optional.empty());
         msg.debug("[HISTORY] Cleared history and related state for " + playerUUID);
     }

     private void markGuiClosedByNavigation(UUID playerUUID) {
         wasGuiClosedByButton.put(playerUUID, true);
         msg.debug("[GUI CLOSE] Marked navigation-triggered close for " + playerUUID);
     }

     private void resetGuiCloseMarker(UUID playerUUID) {
         wasGuiClosedByButton.remove(playerUUID);
         msg.debug("[GUI CLOSE] Reset navigation-triggered marker for " + playerUUID);
     }

     private Map<String, Object> createContext(Object... keyValues) {
         Map<String, Object> context = new HashMap<>();
         if (keyValues.length % 2 != 0) {
             throw new IllegalArgumentException("Context arguments must be key-value pairs.");
         }
         for (int i = 0; i < keyValues.length; i += 2) {
             if (!(keyValues[i] instanceof String)) {
                 throw new IllegalArgumentException("Context keys must be strings.");
             }
             context.put((String) keyValues[i], keyValues[i + 1]);
         }
         return context;
     }

    // --- Specific GUI Opening Methods ---
    // These methods now use the external GuiIdentifier.CONSTANT_NAME syntax
    public void openMainMenu(Player player) {
        UUID playerUUID = player.getUniqueId();
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if (playerData == null) { msg.sendMessage(player, "data_loading_please_wait"); return; }
        Inventory gui = createMainMenuInventory(player, playerData);

        clearGuiHistory(playerUUID); // Clear history before opening main menu
        pushGuiHistory(playerUUID, GuiIdentifier.MAIN_MENU, Collections.emptyMap()); // Uses external enum
        player.openInventory(gui);
        msg.debug("[T-Kits] Opened main menu for player: " + player.getName());
    }

    public void openKitEditor(Player player, int kitNumber) {
        msg.debug("GuiManager.openKitEditor CALLED for kit " + kitNumber + " player " + player.getName());
        UUID playerUUID = player.getUniqueId();
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if (playerData == null) { msg.sendMessage(player, "data_loading_please_wait"); return; }
        Inventory gui = createKitEditorInventory(player, playerData, kitNumber);

        pushGuiHistory(playerUUID, GuiIdentifier.KIT_EDITOR, createContext("kitNumber", kitNumber)); // Uses external enum
        player.openInventory(gui);
    }

     public void openEnderChestEditor(Player player, int mainKitNumber) {
         UUID playerUUID = player.getUniqueId();
         PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
         if (playerData == null) { msg.sendMessage(player, "error"); return; }
         Kit mainKit = playerData.getKit(mainKitNumber);
         if (mainKit == null) { msg.sendMessage(player, "kit_not_found", "kit_number", String.valueOf(mainKitNumber)); msg.playSound(player, "error"); handleNavigationError(playerUUID, "openEnderChestEditor (kit not found)"); return; }
         Inventory gui = createEnderChestEditorInventory(player, mainKit);

         pushGuiHistory(playerUUID, GuiIdentifier.ENDERCHEST_EDITOR, createContext("kitNumber", mainKitNumber)); // Uses external enum
         player.openInventory(gui);
         msg.debug("Opened Ender Chest Editor for Kit " + mainKitNumber + " for " + player.getName());
     }

    public void openConfirmationGUI( Player player, String actionToConfirm, int kitNumber, Component title, Component confirmText, Component cancelText) {
        UUID playerUUID = player.getUniqueId();
        Inventory gui = createConfirmationInventory(actionToConfirm, kitNumber, title, confirmText, cancelText);

        Map<String, Object> context = createContext("confirmAction", actionToConfirm, "kitNumber", kitNumber);
        pushGuiHistory(playerUUID, GuiIdentifier.CONFIRMATION, context); // Uses external enum
        player.openInventory(gui);
        msg.debug("Opened Confirmation GUI for action " + actionToConfirm + " kit " + kitNumber + " for " + player.getName());
    }

    public void openGlobalKitsList(Player player, int page) {
        UUID playerUUID = player.getUniqueId();
        List<Kit> globalKits = kitManager.getAllGlobalKits();
        Inventory gui = createGlobalKitsListInventory(player, globalKits, page);

        if (gui == null) {
            msg.sendMessage(player, "no_global_kits_available"); msg.playSound(player, "error");
            handleNavigationBack(playerUUID); // Navigate back if no kits
            return;
        }

        playerGlobalKitPages.put(playerUUID, page);
        pushGuiHistory(playerUUID, GuiIdentifier.GLOBAL_KITS_LIST, createContext("page", page)); // Uses external enum
        player.openInventory(gui);
        msg.debug("Opened Global Kits list (Page " + page + ") for " + player.getName());
    }

    public void openKitPreview(Player player, Kit kitToPreview) {
        UUID playerUUID = player.getUniqueId();
        if (kitToPreview == null) { msg.sendMessage(player, "error"); handleNavigationError(playerUUID, "openKitPreview (kit null)"); return; }
        Inventory gui = createKitPreviewInventory(player, kitToPreview);

        pushGuiHistory(playerUUID, GuiIdentifier.KIT_PREVIEW, createContext("kitNumber", kitToPreview.getKitNumber(), "owner", kitToPreview.getOwner())); // Uses external enum
        player.openInventory(gui);
        msg.debug("Opened Kit Preview for kit " + kitToPreview.getKitNumber() + " (Owner: " + kitToPreview.getOwner() + ") for " + player.getName());
    }

     public void openEnderChestPreview(Player player, Kit mainKit) {
         UUID playerUUID = player.getUniqueId();
         if (mainKit == null || mainKit.getEnderChestContents() == null) { msg.sendMessage(player, "error"); handleNavigationError(playerUUID, "openEnderChestPreview (kit null or no echest)"); return; }
         Inventory gui = createEnderChestPreviewInventory(player, mainKit);

         pushGuiHistory(playerUUID, GuiIdentifier.ENDERCHEST_PREVIEW, createContext("kitNumber", mainKit.getKitNumber(), "owner", mainKit.getOwner())); // Uses external enum
         player.openInventory(gui);
         msg.debug("Opened Ender Chest Preview for kit " + mainKit.getKitNumber() + " for " + player.getName());
     }

     public void openKitroomMainMenu(Player player) {
         UUID playerUUID = player.getUniqueId();
         Inventory gui = createKitroomMainMenuInventory(player);

         GuiState currentState = peekGuiHistory(playerUUID);
         // Prevent pushing duplicate Kitroom Main state if already there
         if (currentState == null || currentState.identifier() != GuiIdentifier.KITROOM_MAIN) { // Uses external enum
              // Clear history before opening Kitroom Main if coming from somewhere else
              clearGuiHistory(playerUUID);
              pushGuiHistory(playerUUID, GuiIdentifier.KITROOM_MAIN, Collections.emptyMap()); // Uses external enum
         } else {
              msg.debug("Already at Kitroom Main, not pushing history again.");
         }
         editingKitroomCategory.remove(playerUUID); // Ensure category editing state is cleared
         player.openInventory(gui);
         msg.debug("Opened Kitroom Main Menu for " + player.getName());
     }

     public void openKitroomCategory(Player player, String categoryName) {
         UUID playerUUID = player.getUniqueId();
         Inventory gui = createKitroomCategoryInventory(player, categoryName);

         editingKitroomCategory.put(playerUUID, categoryName);
         pushGuiHistory(playerUUID, GuiIdentifier.KITROOM_CATEGORY, createContext("category", categoryName)); // Uses external enum
         player.openInventory(gui);
         msg.debug("Opened Kitroom category '" + categoryName + "' for player: " + player.getName());
     }

    // --- Inventory Creation Helpers ---

    // Add new creation method for the Kit Choice GUI
    public Inventory createPersonalKitChoiceInventory(Player player, Component title, List<Kit> availableKits) {
        int numKits = availableKits.size();
        // Calculate size: rows needed for kits + 1 row for cancel/filler
        int rowsForKits = (int) Math.ceil((double) numKits / 9.0);
        int totalRows = Math.max(2, rowsForKits + 1); // Ensure at least 2 rows (1 for kits/filler, 1 for cancel)
        int size = Math.min(54, totalRows * 9); // Ensure size is multiple of 9, max 54

        // Use the provided title, or fallback to gui.yml default if title is null/empty
        Component finalTitle = title;
        // TODO: Use MessageUtil for plain text serialization if needed, avoid direct serializer access if possible
        String plainTitle = (finalTitle != null) ? plugin.getMessageUtil().getPlainTextSerializer().serialize(finalTitle).trim() : "";
        if (finalTitle == null || plainTitle.isEmpty()) {
             finalTitle = getGuiTitle("personal_kit_choice", "&8» &dChoose your Kit");
        }

        Inventory gui = Bukkit.createInventory(this, size, finalTitle);
        ItemStack filler = loadGuiItem("items.personal_kit_choice.filler", Material.GRAY_STAINED_GLASS_PANE, "&r", null);
        int kitSlot = 0;

        // Place Kit Items
        for (Kit kit : availableKits) {
             if (kitSlot >= size - 9) { // Stop if we run out of space before the last row
                 plugin.getLogger().warning("Not enough space in Personal Kit Choice GUI to display all kits for " + player.getName());
                 break;
             }

             // Load item appearance from gui.yml
             ItemStack item = loadGuiItem("items.personal_kit_choice.kit_item", Material.CHEST, "&bKit {kit_number}",
                                          List.of("&7Click to select this kit."),
                                          Map.of("{kit_number}", String.valueOf(kit.getKitNumber())));

             ItemMeta meta = item.getItemMeta();
             if (meta != null) {
                 // Add kit number PDC for identification on click
                 meta.getPersistentDataContainer().set(kitNumberKey, PersistentDataType.INTEGER, kit.getKitNumber());
                 item.setItemMeta(meta);
             }
             gui.setItem(kitSlot++, item);
        }

        // Fill remaining kit area slots (before the last row)
        for (int i = kitSlot; i < size - 9; i++) {
            gui.setItem(i, filler.clone());
        }

        // Fill last row and place cancel button
        int cancelSlot = getSlot("items.personal_kit_choice.cancel_button.slot", size - 5); // Default middle of last row
        for (int i = size - 9; i < size; i++) {
            if (i == cancelSlot) {
                 ItemStack cancel = loadGuiItemWithAction("items.personal_kit_choice.cancel_button", "cancel_kit_choice", Material.BARRIER, "&cCancel", List.of("&7Close without selecting a kit."));
                 gui.setItem(i, cancel);
            } else {
                // Only set filler if the slot is empty (might have been partially filled by kits if size is small)
                if (gui.getItem(i) == null) {
                    gui.setItem(i, filler.clone());
                }
            }
        }
        return gui;
    }

    // Other create... methods
    private Inventory createMainMenuInventory(Player player, PlayerData playerData) {
         int size = 45;
         Component title = getGuiTitle("main_menu", "&8[&dT&5-&dKits&8] &f&lMain Menu");
         Inventory gui = Bukkit.createInventory(this, size, title);
         ItemStack filler = loadGuiItem("items.main_menu.filler", Material.GRAY_STAINED_GLASS_PANE, "&r", null);
         for (int i = 0; i < 9; i++) gui.setItem(i, filler.clone());
         for (int i = 36; i < 45; i++) gui.setItem(i, filler.clone());
         int kitButtonSlotStart = 10;
         int kitButtonSlotEnd = 16;
         int currentKitSlot = kitButtonSlotStart;
         for (int i = 1; i <= configManager.getMaxKitsPerPlayer(); i++) {
             if (currentKitSlot > kitButtonSlotEnd) break;
             Kit kit = playerData.getKit(i);
             boolean exists = kit != null;
             String configPath = exists ? "items.main_menu.kit_button_available" : "items.main_menu.kit_button_empty";
             Material defaultMat = exists ? Material.CYAN_SHULKER_BOX : Material.LIGHT_GRAY_SHULKER_BOX;
             String defaultName = (exists ? "&b&lKit &f{kit_number}" : "&7&lKit {kit_number}");
             List<String> defaultLore = exists ?
                 List.of("&d&lStatus:", "", "&e▸ &aLeft-Click: &7Edit", "&6▸ &fShift+Click: &bLoad") :
                 List.of("", "&a▸ &fClick: &7Create", "&6▸ &fShift+Click: &bLoad");
             ItemStack item = loadGuiItem(configPath, defaultMat, defaultName, defaultLore, Map.of("{kit_number}", String.valueOf(i)));
             ItemMeta meta = item.getItemMeta();

             if (meta != null) {
                  if(exists && meta.hasLore()) {
                      List<Component> currentLore = meta.lore();
                      if (currentLore != null) {
                          List<Component> finalLore = new ArrayList<>();
                          String globalLineConfig = guiConfig.getString(configPath + ".lore_global", "&6✦ &eGlobal");
                          String privateLineConfig = guiConfig.getString(configPath + ".lore_private", "&7✦ &7Private");
                          Component globalLine = msg.deserialize(globalLineConfig);
                          Component privateLine = msg.deserialize(privateLineConfig);
                          boolean statusLineReplaced = false;
                          for (Component line : currentLore) {
                              // TODO: Use MessageUtil for stripping colors
                              String plainLine = plugin.getMessageUtil().stripColors(line).trim();
                              // Check for the specific placeholder text or the resolved text
                              if (plainLine.equalsIgnoreCase("Status:") || line.equals(globalLine) || line.equals(privateLine)) {
                                  if (!statusLineReplaced) { // Only replace/add status once
                                       finalLore.add(kit.isGlobal() ? globalLine : privateLine); statusLineReplaced = true;
                                  }
                              } else { finalLore.add(line); }
                          }
                          if (!statusLineReplaced) { finalLore.add(0, kit.isGlobal() ? globalLine : privateLine); } // Add if not found/replaced
                          meta.lore(finalLore);
                      }
                  }
                 setItemActionAndKitNum(item, "edit_kit", i);
             } else {
                  msg.logWarning("ItemMeta was NULL for main menu kit button " + i + ". Cannot set action.");
             }
             gui.setItem(currentKitSlot++, item);
         }
         for (int i = currentKitSlot; i <= kitButtonSlotEnd; i++) gui.setItem(i, filler.clone());
         ItemStack kitroom = loadGuiItemWithAction("items.main_menu.kitroom_button", "open_kitroom_main", Material.ENDER_CHEST, "&b❖ &dKitroom", List.of("&7Access the server's item repository."));
         gui.setItem(getSlot("items.main_menu.kitroom_button.slot", 38), kitroom);
         ItemStack info = loadGuiItemWithAction("items.main_menu.info_button", "show_info", Material.BOOK, "&eℹ &fInformation", List.of("&7Plugin Version: &b{version}", "&7Author: &dTakeda_Dev"), Map.of("{version}", plugin.getDescription().getVersion()));
         gui.setItem(getSlot("items.main_menu.info_button.slot", 40), info);
         ItemStack globalKits = loadGuiItemWithAction("items.main_menu.global_kits_button", "open_global_kits", Material.BEACON, "&d✯ &bGlobal Kits", List.of("&7Browse kits shared publicly", "&7by other players."));
         gui.setItem(getSlot("items.main_menu.global_kits_button.slot", 42), globalKits);
         return gui;
     }

    private Inventory createKitEditorInventory(Player player, PlayerData playerData, int kitNumber) {
        Kit kit = playerData.getKit(kitNumber);
        Component title = getGuiTitle("kit_editor", "&8[&dT&5-&dKits&8] &e✏ &fKit Editor &7- &bKit {kit_number}")
            .replaceText(config -> config.matchLiteral("{kit_number}").replacement(String.valueOf(kitNumber)));
        Inventory gui = Bukkit.createInventory(this, 54, title);

        // Apply items from the saved kit (Slots 0-40)
        if (kit != null && kit.getContents() != null) {
            kit.getContents().getItems().forEach((slot, item) -> {
                int editorSlot = KitManager.mapPlayerInvSlotToEditorSlot(slot);
                if (editorSlot != -1 && editorSlot <= 40) {
                    gui.setItem(editorSlot, item.clone());
                }
            });
        }

        // Fill button area (slots 45-53)
        ItemStack bottomFiller = this.editorButtonAreaFillerInstance != null ? this.editorButtonAreaFillerInstance : loadGuiItem("items.kit_editor.button_area_filler", Material.GRAY_STAINED_GLASS_PANE, "&r", null);
        for (int i = 45; i < 54; i++) {
            if (gui.getItem(i) == null) {
                 gui.setItem(i, bottomFiller.clone());
            }
        }

        // Add control buttons
        ItemStack back = loadGuiItemWithAction("items.kit_editor.back_button", "back", Material.BARRIER, "&c◀ &fBack", List.of("&7Return to the main menu.", "&8(Saves kit if auto-save enabled)"));
        gui.setItem(getSlot("items.kit_editor.back_button.slot", 41), back);
        ItemStack loadInv = loadGuiItemWithAction("items.kit_editor.load_inventory_button", "load_current_inventory", Material.HOPPER_MINECART, "&b⬆ &aLoad Inventory", List.of("&7Copies your current inventory,", "&7armor, and offhand into the editor."));
        gui.setItem(getSlot("items.kit_editor.load_inventory_button.slot", 42), loadInv);
        ItemStack save = loadGuiItemWithAction("items.kit_editor.save_kit_button", "save_kit", Material.WRITABLE_BOOK, "&a💾 &fSave Kit", List.of("&7Saves the current layout", "&7as Kit &b{kit_number}&7."), Map.of("{kit_number}", String.valueOf(kitNumber)));
        setItemActionAndKitNum(save, "save_kit", kitNumber);
        gui.setItem(getSlot("items.kit_editor.save_kit_button.slot", 43), save);
        ItemStack editEChest = loadGuiItemWithAction("items.kit_editor.edit_echest_button", "edit_enderchest", Material.ENDER_CHEST, "&5❖ &dEdit Ender Chest", List.of("&7Opens the editor for this kit's", "&7linked Ender Chest contents."));
        setItemActionAndKitNum(editEChest, "edit_enderchest", kitNumber);
        gui.setItem(getSlot("items.kit_editor.edit_echest_button.slot", 44), editEChest);
        ItemStack clear = loadGuiItemWithAction("items.kit_editor.clear_kit_button", "clear_kit_confirm", Material.TNT, "&c✗ &fClear Kit", List.of("&4⚠ &cDeletes this kit permanently!", "&7Requires confirmation click."));
        setItemActionAndKitNum(clear, "clear_kit_confirm", kitNumber);
        gui.setItem(getSlot("items.kit_editor.clear_kit_button.slot", 47), clear);
        ItemStack repair = loadGuiItemWithAction("items.kit_editor.repair_items_button", "repair_kit_items", Material.ANVIL, "&6⚒ &eRepair Items", List.of("&7Repairs all damageable items", "&7in this kit's layout."));
        setItemActionAndKitNum(repair, "repair_kit_items", kitNumber);
        gui.setItem(getSlot("items.kit_editor.repair_items_button.slot", 48), repair);
        ItemStack share = loadGuiItemWithAction("items.kit_editor.share_kit_button", "share_kit", Material.WRITTEN_BOOK, "&e✉ &bShare Kit", List.of("&7Generates a temporary code", "&7to share this kit with others."));
        setItemActionAndKitNum(share, "share_kit", kitNumber);
        gui.setItem(getSlot("items.kit_editor.share_kit_button.slot", 49), share);
        ItemStack importKit = loadGuiItemWithAction("items.kit_editor.import_kit_button", "import_kit_anvil", Material.ENDER_EYE, "&d⬇ &aImport Kit", List.of("&7Import a kit using a share code.", "&c⚠ Overwrites this kit slot!"));
        setItemActionAndKitNum(importKit, "import_kit_anvil", kitNumber);
        gui.setItem(getSlot("items.kit_editor.import_kit_button.slot", 50), importKit);

        boolean isGlobal = kit != null && kit.isGlobal();
        String togglePath = isGlobal ? "items.kit_editor.set_private_button" : "items.kit_editor.set_global_button";
        Material toggleDefaultMat = isGlobal ? Material.GRAY_DYE : Material.LIME_DYE;
        String toggleDefaultName = isGlobal ? "&b⊖ &7Set Private" : "&a⊕ &eSet Global";
        List<String> toggleDefaultLore = isGlobal ? List.of("&7Makes this kit private,", "&7only you can load it.") : List.of("&7Makes this kit public,", "&7allowing others to browse it.");
        ItemStack toggleGlobal = loadGuiItem(togglePath, toggleDefaultMat, toggleDefaultName, toggleDefaultLore);
        replaceItemPlaceholders(toggleGlobal, Map.of("{kit_number}", String.valueOf(kitNumber)));
        setItemActionAndKitNum(toggleGlobal, isGlobal ? "set_kit_private" : "set_kit_global", kitNumber);
        gui.setItem(getSlot(togglePath + ".slot", 51), toggleGlobal);

        return gui;
    }

    private Inventory createEnderChestEditorInventory(Player player, Kit mainKit) {
         Component title = getGuiTitle("enderchest_editor", "&8[&5T&d-&5Kits&8] &6📦 &fEchest Editor &7- &bKit {kit_number}")
             .replaceText(config -> config.matchLiteral("{kit_number}").replacement(String.valueOf(mainKit.getKitNumber())));
         Inventory gui = Bukkit.createInventory(this, 36, title);
         KitContents echestContents = mainKit.getEnderChestContents();
         if (echestContents != null && echestContents.getItems() != null) {
             echestContents.getItems().forEach((slot, item) -> { if (slot >= 0 && slot < 27) gui.setItem(slot, item.clone()); });
         }
         ItemStack filler = loadGuiItem("items.echest_editor.filler", Material.BLACK_STAINED_GLASS_PANE, "&r", null);
         for (int i = 27; i < 36; i++) gui.setItem(i, filler.clone());
         ItemStack back = loadGuiItemWithAction("items.echest_editor.back_button", "back", Material.BARRIER, "&c◀ &fBack", List.of("&7Return to the main kit editor."));
         gui.setItem(getSlot("items.echest_editor.back_button.slot", 27), back);
         ItemStack save = loadGuiItemWithAction("items.echest_editor.save_button", "save_enderchest", Material.WRITABLE_BOOK, "&a💾 &fSave Ender Chest", List.of("&7Saves this Ender Chest layout."));
         setItemActionAndKitNum(save, "save_enderchest", mainKit.getKitNumber());
         gui.setItem(getSlot("items.echest_editor.save_button.slot", 31), save);
         return gui;
     }

    private Inventory createConfirmationInventory(String actionToConfirm, int kitNumber, Component title, Component confirmText, Component cancelText) {
         Inventory gui = Bukkit.createInventory(this, 27, title);
         ItemStack filler = loadGuiItem("items.confirmation.filler", Material.GRAY_STAINED_GLASS_PANE, "&r", null);
         for (int i = 0; i < 27; i++) gui.setItem(i, filler.clone());
         ItemStack confirm = loadGuiItem("items.confirmation.confirm_button", Material.LIME_TERRACOTTA, "&a✔ &fConfirm", null);
         ItemMeta confirmMeta = confirm.getItemMeta();
         if (confirmMeta != null) {
             confirmMeta.lore(List.of(confirmText.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)));
             setItemActionAndKitNum(confirm, actionToConfirm, kitNumber);
             confirm.setItemMeta(confirmMeta);
         }
         gui.setItem(getSlot("items.confirmation.confirm_button.slot", 11), confirm);
         ItemStack cancel = loadGuiItem("items.confirmation.cancel_button", Material.RED_TERRACOTTA, "&c✘ &fCancel", null);
         ItemMeta cancelMeta = cancel.getItemMeta();
         if (cancelMeta != null) {
             cancelMeta.lore(List.of(cancelText.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)));
             setItemAction(cancel, "back");
             cancel.setItemMeta(cancelMeta);
         }
         gui.setItem(getSlot("items.confirmation.cancel_button.slot", 15), cancel);
         return gui;
     }

    private Inventory createGlobalKitsListInventory(Player player, List<Kit> globalKits, int page) {
         if (globalKits.isEmpty()) return null;

         int itemsPerPage = 36; // Slots 0-35 for items
         int totalPages = Math.max(1, (int) Math.ceil((double) globalKits.size() / itemsPerPage));
         final int finalPage = Math.max(1, Math.min(page, totalPages));

         int startIndex = (finalPage - 1) * itemsPerPage;
         int endIndex = Math.min(startIndex + itemsPerPage, globalKits.size());
         List<Kit> kitsOnPage = globalKits.subList(startIndex, endIndex);
         Component title = getGuiTitle("global_kits", "&8[&d✯&8] &fGlobal Kits &8(&ePage {page}/{total_pages}&8)")
             .replaceText(cfg -> cfg.matchLiteral("{page}").replacement(String.valueOf(finalPage)))
             .replaceText(cfg -> cfg.matchLiteral("{total_pages}").replacement(String.valueOf(totalPages)));
         Inventory gui = Bukkit.createInventory(this, 54, title);
         ItemStack buttonAreaFiller = loadGuiItem("items.global_kits_list.button_area_filler", Material.BLACK_STAINED_GLASS_PANE, "&r", null);
         for (int i = 36; i < 54; i++) gui.setItem(i, buttonAreaFiller.clone());
         int currentSlot = 0;
         for (Kit kit : kitsOnPage) {
              if (currentSlot >= itemsPerPage) break;
             OfflinePlayer owner = Bukkit.getOfflinePlayer(kit.getOwner());
             String ownerName = owner.getName() != null ? owner.getName() : "Unknown";
             String nameFormat = guiConfig.getString("items.global_kits_list.player_head.name", "&bKit &e{kit_number} &7by &f{owner}");
             List<String> loreFormat = guiConfig.getStringList("items.global_kits_list.player_head.lore");
             if (loreFormat.isEmpty()) loreFormat = List.of("&7Owner: &f{owner_uuid}", "", "&e▸ &aLeft-Click: &bLoad", "&d▸ &fRight-Click: &ePreview");
             Component name = msg.deserialize(nameFormat.replace("{kit_number}", String.valueOf(kit.getKitNumber())).replace("{owner}", ownerName));
             List<Component> lore = loreFormat.stream()
                 .map(line -> line.replace("{owner_uuid}", owner.getUniqueId().toString()))
                 .map(msg::deserialize).collect(Collectors.toList());
             ItemStack item = GuiUtils.createPlayerHead(owner, name, lore);
             ItemMeta meta = item.getItemMeta();
             if (meta != null) {
                 PersistentDataContainer pdc = meta.getPersistentDataContainer();
                 pdc.set(kitNumberKey, PersistentDataType.INTEGER, kit.getKitNumber());
                 pdc.set(kitOwnerKey, PersistentDataType.STRING, kit.getOwner().toString());
                 item.setItemMeta(meta);
             }
             gui.setItem(currentSlot++, item);
         }
         // Fill remaining item area slots
         ItemStack itemAreaFiller = loadGuiItem("items.global_kits_list.item_area_filler", Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&r", null);
         for (int i = currentSlot; i < itemsPerPage; i++) {
             if (gui.getItem(i) == null) gui.setItem(i, itemAreaFiller.clone());
         }
         // Add buttons
         ItemStack back = loadGuiItemWithAction("items.global_kits_list.back_button", "back", Material.BARRIER, "&c◀ &fBack", List.of("&7Return to the main menu."));
         gui.setItem(getSlot("items.global_kits_list.back_button.slot", 45), back);
         if (finalPage > 1) {
             ItemStack prev = loadGuiItemWithAction("items.global_kits_list.previous_page_button", "global_kits_page", Material.ARROW, "&e« &fPrevious", List.of("&7Go to page &b{page}"), Map.of("{page}", String.valueOf(finalPage - 1)));
             setItemActionAndPage(prev, "global_kits_page", finalPage - 1);
             gui.setItem(getSlot("items.global_kits_list.previous_page_button.slot", 48), prev);
         }
         ItemStack pageInfo = loadGuiItem("items.global_kits_list.page_info_item", Material.PAPER, "&fPage &b{page}&f/&b{total_pages}", null, Map.of("{page}", String.valueOf(finalPage), "{total_pages}", String.valueOf(totalPages)));
         gui.setItem(getSlot("items.global_kits_list.page_info_item.slot", 49), pageInfo);
         if (finalPage < totalPages) {
             ItemStack next = loadGuiItemWithAction("items.global_kits_list.next_page_button", "global_kits_page", Material.ARROW, "&eNext »", List.of("&7Go to page &b{page}"), Map.of("{page}", String.valueOf(finalPage + 1)));
             setItemActionAndPage(next, "global_kits_page", finalPage + 1);
             gui.setItem(getSlot("items.global_kits_list.next_page_button.slot", 50), next);
         }
         return gui;
     }

    private Inventory createKitPreviewInventory(Player player, Kit kitToPreview) {
         OfflinePlayer owner = Bukkit.getOfflinePlayer(kitToPreview.getOwner());
         String ownerName = owner.getName() != null ? owner.getName() : "Unknown";
         Component title = getGuiTitle("kit_preview", "&8[&9👁&8] &fPreview &7- &bKit {kit_number} &7({owner})")
             .replaceText(cfg -> cfg.matchLiteral("{kit_number}").replacement(String.valueOf(kitToPreview.getKitNumber())))
             .replaceText(cfg -> cfg.matchLiteral("{owner}").replacement(ownerName));
         Inventory gui = Bukkit.createInventory(this, 54, title);
         KitContents contents = kitToPreview.getContents();
         if (contents != null && contents.getItems() != null) {
             contents.getItems().forEach((slot, item) -> {
                 int editorSlot = KitManager.mapPlayerInvSlotToEditorSlot(slot);
                 if (editorSlot != -1 && editorSlot <= 40) gui.setItem(editorSlot, item.clone());
             });
         }

         // Fill button area
         ItemStack filler = loadGuiItem("items.kit_preview.filler", Material.BLACK_STAINED_GLASS_PANE, "&r", null);
         for (int i = 45; i < 54; i++) { // Only fill button area slots 45-53
             if (gui.getItem(i) == null) gui.setItem(i, filler.clone());
         }

         // Add buttons
         ItemStack back = loadGuiItemWithAction("items.kit_preview.back_button", "back", Material.BARRIER, "&c◀ &fBack", List.of("&7Return to Global Kits list."));
         gui.setItem(getSlot("items.kit_preview.back_button.slot", 49), back);
         if (kitToPreview.getEnderChestContents() != null && !kitToPreview.getEnderChestContents().getItems().isEmpty()) {
             ItemStack viewEChest = loadGuiItemWithAction("items.kit_preview.view_echest_button", "preview_echest", Material.ENDER_CHEST, "&5❖ &dView Ender Chest", List.of("&7View the linked Ender Chest contents."));
             setItemActionAndKitData(viewEChest, "preview_echest", kitToPreview.getKitNumber(), kitToPreview.getOwner());
             gui.setItem(getSlot("items.kit_preview.view_echest_button.slot", 51), viewEChest);
         }
         return gui;
     }

    private Inventory createEnderChestPreviewInventory(Player player, Kit mainKit) {
         OfflinePlayer owner = Bukkit.getOfflinePlayer(mainKit.getOwner());
         String ownerName = owner.getName() != null ? owner.getName() : "Unknown";
         Component title = getGuiTitle("echest_preview", "&8[&5❖&8] &fEchest Preview &7- &bKit {kit_number} &7({owner})")
             .replaceText(cfg -> cfg.matchLiteral("{kit_number}").replacement(String.valueOf(mainKit.getKitNumber())))
             .replaceText(cfg -> cfg.matchLiteral("{owner}").replacement(ownerName));
         Inventory gui = Bukkit.createInventory(this, 36, title);
         KitContents echestContents = mainKit.getEnderChestContents();
         if (echestContents.getItems() != null) {
             echestContents.getItems().forEach((slot, item) -> { if (slot >= 0 && slot < 27) gui.setItem(slot, item.clone()); });
         }
         ItemStack filler = loadGuiItem("items.echest_preview.filler", Material.BLACK_STAINED_GLASS_PANE, "&r", null);
         for (int i = 27; i < 36; i++) if (gui.getItem(i) == null) gui.setItem(i, filler.clone());
         ItemStack back = loadGuiItemWithAction("items.echest_preview.back_button", "back", Material.BARRIER, "&c◀ &fBack", List.of("&7Return to Kit Preview."));
         gui.setItem(getSlot("items.echest_preview.back_button.slot", 31), back);
         return gui;
     }

    private Inventory createKitroomMainMenuInventory(Player player) {
         int size = 45;
         Component title = getGuiTitle("kitroom_main", "&8[&b❖&8] &fKitroom &7- &dCategories");
         Inventory gui = Bukkit.createInventory(this, size, title);
         Set<Integer> usedSlots = new HashSet<>();
         msg.debug("[KitroomMain] Initializing GUI (Size: " + size + ")");

         // Apply Filler
         ItemStack filler = loadGuiItem("items.kitroom_main.filler", Material.GRAY_STAINED_GLASS_PANE, "&r", null);
         if (filler != null) {
             for (int i = 0; i < 9; i++) { gui.setItem(i, filler.clone()); usedSlots.add(i); }
             for (int i = 36; i < size; i++) { gui.setItem(i, filler.clone()); usedSlots.add(i); }
         }
         msg.debug("[KitroomMain] Applied filler. Used slots: " + usedSlots);

         // Place Back Button
         ItemStack backButton = loadGuiItemWithAction("items.kitroom_main.back_button", "back", Material.BARRIER, "&c◀ &fBack", List.of("&7Return to Main Menu."));
         int backButtonSlot = getSlot("items.kitroom_main.back_button.slot", 40);
         gui.setItem(backButtonSlot, backButton);
         usedSlots.add(backButtonSlot);
         msg.debug("[KitroomMain] Placed back button at slot " + backButtonSlot + ". Used slots: " + usedSlots);

         // Process Categories
         List<String> categoryNames = kitroomManager.getCategoryNames();
         List<String> categoriesToPlaceDynamically = new ArrayList<>();
         msg.debug("[KitroomMain] Processing categories: " + categoryNames);

         for (String categoryName : categoryNames) {
             String categoryLower = categoryName.toLowerCase();
             String overridePath = "items.kitroom_main.category_overrides." + categoryLower;
             int configSlot = guiConfig.getInt(overridePath + ".slot", -1);
             boolean placed = false;

             msg.debug("[KitroomMain] -> Checking category '" + categoryName + "'. Override path: '" + overridePath + "', Configured slot: " + configSlot);

             if (configSlot >= 0 && configSlot < 36) {
                 if (!usedSlots.contains(configSlot)) {
                     ItemStack categoryItem = loadCategoryButton(categoryName);
                     applyPlaceholdersAndAction(categoryItem, categoryName);
                     gui.setItem(configSlot, categoryItem);
                     usedSlots.add(configSlot);
                     placed = true;
                     msg.debug("[KitroomMain]    -> Placed override for '" + categoryName + "' at configured slot " + configSlot + ".");
                 } else {
                     msg.debug("[KitroomMain]    -> Configured slot " + configSlot + " for '" + categoryName + "' is already used. Adding to dynamic list.");
                     categoriesToPlaceDynamically.add(categoryName);
                 }
             } else {
                 if(configSlot != -1) msg.debug("[KitroomMain]    -> Configured slot " + configSlot + " for '" + categoryName + "' is outside valid range (0-35). Adding to dynamic list.");
                 else msg.debug("[KitroomMain]    -> No slot override found for '" + categoryName + "'. Adding to dynamic list.");
                 categoriesToPlaceDynamically.add(categoryName);
             }
         }

         // Place Dynamic Categories
         int dynamicSlot = 9; // Start from slot 9
         final int maxDynamicSlot = 35; // End at slot 35
         msg.debug("[KitroomMain] Placing dynamic categories: " + categoriesToPlaceDynamically + ". Starting search at slot " + dynamicSlot);

         for (String categoryName : categoriesToPlaceDynamically) {
             while (dynamicSlot <= maxDynamicSlot && usedSlots.contains(dynamicSlot)) {
                 dynamicSlot++;
             }

             if (dynamicSlot <= maxDynamicSlot) {
                 ItemStack categoryItem = loadCategoryButton(categoryName);
                 applyPlaceholdersAndAction(categoryItem, categoryName);
                 gui.setItem(dynamicSlot, categoryItem);
                 usedSlots.add(dynamicSlot);
                 msg.debug("[KitroomMain] -> Placed dynamic category '" + categoryName + "' at slot " + dynamicSlot + ".");
                 dynamicSlot++;
             } else {
                 msg.logWarning("Could not find a free dynamic slot (9-35) for kitroom category: " + categoryName + ". Item will not be displayed.");
                 msg.debug("[KitroomMain] -> No free slot found for dynamic category '" + categoryName + "'.");
             }
         }
          msg.debug("[KitroomMain] Finished placing all categories. Final used slots: " + usedSlots);
         return gui;
     }

    private Inventory createKitroomCategoryInventory(Player player, String categoryName) {
        Map<Integer, ItemStack> items = kitroomManager.getCategoryItems(categoryName);
        int size = 54;
        Component title = getGuiTitle("kitroom_category", "&8[&b❖&8] &fKitroom &7- &e{category}")
            .replaceText(config -> config.matchLiteral("{category}").replacement(capitalize(categoryName)));
        Inventory gui = Bukkit.createInventory(this, size, title);

        // Place Actual Items (Slots 0-44)
        msg.debug("[KitroomCategory] Populating GUI for category '" + categoryName + "' with " + items.size() + " items.");
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            int slot = entry.getKey();
            ItemStack originalItem = entry.getValue();

            if (slot >= 0 && slot < 45) {
                ItemStack itemClone = originalItem.clone();
                if (!player.hasPermission("tkits.kitroom.admin")) {
                    ItemMeta meta = itemClone.getItemMeta();
                    if (meta != null) {
                        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                        lore.add(Component.empty());
                        lore.add(KITROOM_PLAYER_LORE_HINT);
                        meta.lore(lore);
                        itemClone.setItemMeta(meta);
                    }
                }
                gui.setItem(slot, itemClone);
                // msg.debug("[KitroomCategory] -> Placed item " + itemClone.getType() + " in slot " + slot);
            } else {
                msg.logWarning("Kitroom item found outside valid slot range (0-44) for category '" + categoryName + "', slot: " + slot + ". Item: " + originalItem.getType());
            }
        }

        // Place Control Buttons (Slots 45-53)
        int backButtonSlot = getSlot("items.kitroom_category.back_button.slot", 49);
        if (backButtonSlot >= 45 && backButtonSlot < size) {
            ItemStack backButton = loadGuiItemWithAction("items.kitroom_category.back_button", "back", Material.BARRIER, "&c◀ &fBack", List.of("&7Return to Category Selection."));
            gui.setItem(backButtonSlot, backButton);
            // msg.debug("[KitroomCategory] Placed Back button in slot " + backButtonSlot);
        } else {
            msg.logWarning("Configured slot " + backButtonSlot + " for kitroom_category.back_button is outside the button row (45-53).");
        }

        int saveButtonSlot = -1;
        if (player.hasPermission("tkits.kitroom.admin")) {
            saveButtonSlot = getSlot("items.kitroom_category.save_button.slot", 53);
            if (saveButtonSlot >= 45 && saveButtonSlot < size) {
                ItemStack saveButton = loadGuiItemWithAction("items.kitroom_category.save_button", "save_kitroom_category", Material.WRITABLE_BOOK, "&a💾 &fSave Category", List.of("&7Save changes to this category"));
                setItemActionAndCategory(saveButton, "save_kitroom_category", categoryName);
                gui.setItem(saveButtonSlot, saveButton);
                // msg.debug("[KitroomCategory] Placed Save button (Admin) in slot " + saveButtonSlot);
            } else {
                msg.logWarning("Configured slot " + saveButtonSlot + " for kitroom_category.save_button is outside the button row (45-53).");
                saveButtonSlot = -1;
            }
        }

        // Fill *remaining empty* slots in BOTTOM ROW ONLY (45-53)
        ItemStack bottomRowFiller = GuiUtils.createGuiItem(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), null, 0); // Simple filler
        for (int i = 45; i < size; i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, bottomRowFiller.clone());
            }
        }
        // msg.debug("[KitroomCategory] Filled empty slots in bottom row (45-53) with BLACK_STAINED_GLASS_PANE.");

        return gui;
    }

    @Override
    public Inventory getInventory() { return null; }

    // --- Anvil GUI ---
    public void openImportAnvil(Player player, int targetKitNumber) {
        UUID playerUUID = player.getUniqueId();
        Component title = msg.deserialize("&b&lImport Kit");
        ItemStack inputItem = new ItemStack(Material.PAPER);
        AtomicBoolean wasClickHandled = new AtomicBoolean(false);

        new AnvilGUI.Builder()
            .plugin(plugin)
            .title(msg.getLegacySerializer().serialize(title))
            .text("Enter Code")
            .itemLeft(inputItem)
            .onClick((slot, stateSnapshot) -> {
                if (slot != AnvilGUI.Slot.OUTPUT) return Collections.emptyList();
                wasClickHandled.set(true);
                String code = stateSnapshot.getText().trim();
                Player p = stateSnapshot.getPlayer();
                GuiState parentState = peekGuiHistory(p.getUniqueId());
                if (parentState == null || parentState.identifier() != GuiIdentifier.KIT_EDITOR) { // Uses external enum
                     msg.logWarning("Anvil import action aborted for " + p.getName() + ": No valid parent Kit Editor state found.");
                     msg.sendMessage(p, "error");
                     return List.of(AnvilGUI.ResponseAction.close());
                }
                int currentEditingKitNumAnvil = (int) parentState.context().getOrDefault("kitNumber", -1);
                if (currentEditingKitNumAnvil == -1 || currentEditingKitNumAnvil != targetKitNumber) {
                     msg.logWarning("Anvil import action aborted for " + p.getName() + ": Mismatched target kit number. Required: " + targetKitNumber + ", Found in parent state: " + currentEditingKitNumAnvil);
                     msg.sendMessage(p, "error");
                     return List.of(AnvilGUI.ResponseAction.close());
                }
                if (code.length() != configManager.getShareCodeLength()) {
                    msg.playSound(p, "error"); return List.of(AnvilGUI.ResponseAction.replaceInputText("Invalid Length"));
                }
                String upperCode = code.toUpperCase();
                msg.sendMessage(p, "importing_kit");
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    Kit importedKit = shareCodeManager.redeemShareCode(upperCode);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        GuiState currentState = peekGuiHistory(p.getUniqueId());
                        if (currentState == null || currentState.identifier() != GuiIdentifier.KIT_EDITOR || (int) currentState.context().getOrDefault("kitNumber", -1) != targetKitNumber) { // Uses external enum
                             msg.logWarning("Anvil import processing aborted (callback) for " + p.getName() + ": Player state changed during async operation.");
                             msg.sendMessage(p, "error");
                             return;
                        }
                        if (importedKit != null) {
                            PlayerData pd = plugin.getPlayerDataManager().getPlayerData(p);
                            if (pd != null) {
                                Kit kitToSave;
                                try {
                                    kitToSave = importedKit.toBuilder()
                                        .kitNumber(targetKitNumber)
                                        .owner(p.getUniqueId())
                                        .global(false)
                                        .contents(KitContents.deserialize(KitContents.serialize(importedKit.getContents())))
                                        .enderChestContents(KitContents.deserialize(KitContents.serialize(importedKit.getEnderChestContents())))
                                        .build();
                                } catch (Exception e) {
                                     plugin.getMessageUtil().logException("Error building kit from imported data in Anvil callback", e);
                                     msg.sendMessage(p, "import_fail_error"); msg.playSound(p, "error");
                                     openKitEditor(p, targetKitNumber);
                                     return;
                                }
                                plugin.getPlayerDataManager().savePlayerKit(p.getUniqueId(), kitToSave).thenRunAsync(() -> {
                                     pd.setKit(targetKitNumber, kitToSave);
                                     msg.sendActionBar(player, "import_success", "kit_number", String.valueOf(targetKitNumber)); msg.playSound(p, "kit_import_success");
                                     openKitEditor(p, targetKitNumber);
                                 }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin)).exceptionally(ex -> {
                                     msg.sendMessage(p, "import_fail_error"); msg.playSound(p, "error"); plugin.getMessageUtil().logException("Error saving imported kit via Anvil", ex);
                                     openKitEditor(p, targetKitNumber);
                                     return null;
                                 });
                            } else { msg.sendMessage(p, "error"); openKitEditor(p, targetKitNumber); }
                        } else {
                            msg.sendMessage(p, "import_fail_invalid_code"); msg.playSound(p, "kit_import_fail");
                            openKitEditor(p, targetKitNumber);
                        }
                    });
                });
                return List.of(AnvilGUI.ResponseAction.close());
            })
            .onClose(stateSnapshot -> {
                Player pClose = stateSnapshot.getPlayer();
                if (!wasClickHandled.get()) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (pClose.isOnline()) handleNavigationBack(pClose.getUniqueId());
                    }, 1L);
                }
            })
            .preventClose()
            .open(player);
    }

    // --- Central Button Action Handler ---
    public void handleButtonAction( Player player, String action, PersistentDataContainer pdc, ClickType clickType, int clickedSlot ) {
        UUID playerUUID = player.getUniqueId();
        markGuiClosedByNavigation(playerUUID);

        // Extract data from PDC
        Integer kitNumFromPDC = pdc.getOrDefault(kitNumberKey, PersistentDataType.INTEGER, -1);
        UUID ownerUUIDFromPDC = null;
        String ownerStr = pdc.get(kitOwnerKey, PersistentDataType.STRING);
        if (ownerStr != null) try { ownerUUIDFromPDC = UUID.fromString(ownerStr); } catch (IllegalArgumentException ignored) {}
        String categoryFromPDC = pdc.get(categoryKey, PersistentDataType.STRING);
        Integer pageFromPDC = pdc.getOrDefault(pageKey, PersistentDataType.INTEGER, 1);

        // Get context from current GUI state
        GuiState currentState = peekGuiHistory(playerUUID);
        int currentKitNumCtx = -1;
        String currentCategoryCtx = null;
        if (currentState != null) {
            currentKitNumCtx = (int) currentState.context().getOrDefault("kitNumber", -1);
            currentCategoryCtx = (String) currentState.context().get("category");
        }

        msg.debug(String.format("handleButtonAction CALLED for %s: action='%s', kitPDC=%d ...", player.getName(), action, kitNumFromPDC));
        if (action == null || action.isEmpty()) { msg.debug("Received empty/null action"); resetGuiCloseMarker(playerUUID); return; }

        try {
            if (action.equalsIgnoreCase("back")) {
                handleNavigationBack(playerUUID); return;
            }

            // --- Action Switch ---
            switch (action.toLowerCase()) {
                case "edit_kit":
                    if (kitNumFromPDC > 0) {
                        if (clickType.isShiftClick()) { kitManager.loadKit(player, kitNumFromPDC); player.closeInventory(); }
                        else { openKitEditor(player, kitNumFromPDC); }
                    } else { handleInvalidState(player, action, "Missing kit number from PDC"); }
                    break;
                case "open_main_menu": openMainMenu(player); break;
                case "open_kitroom_main": openKitroomMainMenu(player); break;
                case "open_kitroom_category":
                    if (categoryFromPDC != null) openKitroomCategory(player, categoryFromPDC);
                    else handleInvalidState(player, action, "Missing category from PDC");
                    break;
                case "open_global_kits": openGlobalKitsList(player, 1); break;
                case "global_kits_page": openGlobalKitsList(player, pageFromPDC); break;
                case "edit_enderchest":
                    if (currentKitNumCtx <= 0) { handleInvalidState(player, action, "Missing kitNumber context for edit_enderchest"); break; }
                    openEnderChestEditor(player, currentKitNumCtx);
                    break;
                case "clear_kit_confirm":
                    if (currentKitNumCtx <= 0) { handleInvalidState(player, action, "Missing kitNumber context for clear_kit_confirm"); break; }
                    handleClearConfirmClick(player, currentKitNumCtx);
                    break;
                case "import_kit_anvil":
                    if (currentKitNumCtx <= 0) { handleInvalidState(player, action, "Missing kitNumber context for import_kit_anvil"); break; }
                    openImportAnvil(player, currentKitNumCtx);
                    break;
                case "preview_global_kit":
                    if (ownerUUIDFromPDC == null || kitNumFromPDC <= 0) { handleInvalidState(player, action, "Missing owner/kit number from PDC"); break; }
                    handlePreviewKit(player, ownerUUIDFromPDC, kitNumFromPDC);
                    break;
                case "preview_echest":
                     if (currentState == null || currentState.identifier() != GuiIdentifier.KIT_PREVIEW) { handleInvalidState(player, action, "Invalid parent state for preview_echest"); break; } // Uses external enum
                     int previewKitNum = (int) currentState.context().getOrDefault("kitNumber", -1);
                     UUID previewOwner = (UUID) currentState.context().get("owner");
                     if(previewKitNum <= 0 || previewOwner == null) { handleInvalidState(player, action, "Missing context in parent state for preview_echest"); break; }
                     Kit kitToPreviewEchest = kitManager.getGlobalKit(previewOwner, previewKitNum);
                     if (kitToPreviewEchest == null) { handlePreviewKit(player, previewOwner, previewKitNum); msg.sendMessage(player, "Kit data expired, reloading..."); break; }
                     openEnderChestPreview(player, kitToPreviewEchest);
                    break;
                case "show_info":
                    msg.sendMessage(player,"info_placeholder","version",plugin.getDescription().getVersion());
                    player.closeInventory();
                    break;
                case "load_current_inventory":
                    loadCurrentInventoryToEditor(player);
                    resetGuiCloseMarker(playerUUID);
                    break;
                case "save_kit":
                     if (currentKitNumCtx <= 0) { handleInvalidState(player, action, "Missing kitNumber context for save_kit"); break; }
                    kitManager.saveKit(player, currentKitNumCtx, player.getOpenInventory().getTopInventory());
                    resetGuiCloseMarker(playerUUID);
                    break;
                case "save_enderchest":
                     if (currentKitNumCtx <= 0) { handleInvalidState(player, action, "Missing kitNumber context for save_enderchest"); break; }
                    kitManager.saveEnderChestKit(player, currentKitNumCtx, player.getOpenInventory().getTopInventory());
                    resetGuiCloseMarker(playerUUID);
                    break;
                case "repair_kit_items":
                     if (currentKitNumCtx <= 0) { handleInvalidState(player, action, "Missing kitNumber context for repair_kit_items"); break; }
                    handleRepairKitClick(player, currentKitNumCtx);
                    resetGuiCloseMarker(playerUUID);
                    break;
                case "share_kit":
                     if (currentKitNumCtx <= 0) { handleInvalidState(player, action, "Missing kitNumber context for share_kit"); break; }
                    handleShareKit(player, currentKitNumCtx);
                    break;
                case "set_kit_global": case "set_kit_private":
                    if (kitNumFromPDC <= 0) { handleInvalidState(player, action, "Missing kit number from PDC for global toggle"); break; }
                    handleSetGlobal(player, kitNumFromPDC, action.equals("set_kit_global"));
                    break;
                case "clear_kit_execute":
                    if (kitNumFromPDC > 0) {
                        kitManager.clearKit(player, kitNumFromPDC);
                        handleNavigationBack(playerUUID);
                    } else { handleInvalidState(player, action, "Missing kit number from confirmation PDC"); }
                    break;
                case "save_kitroom_category":
                    if (categoryFromPDC == null) { handleInvalidState(player, action, "Missing category context from PDC for save"); break;}
                    handleSaveKitroomCategory(player, categoryFromPDC);
                    break;

                // --- NEW Case ---
                 case "cancel_kit_choice":
                     msg.debug("[Kit Choice] Cancelled by Button");
                     cancelPendingKitChoice(playerUUID, Optional.empty()); // Complete future
                     player.closeInventory(); // Close the GUI
                     // History is managed by handleGuiClose now
                     break;

                default:
                    msg.debug("Unhandled GUI action: '" + action + "'");
                    msg.sendRawMessage(player, "&cButton action not recognized.", null);
                    resetGuiCloseMarker(playerUUID);
                    player.closeInventory();
                    clearGuiHistory(playerUUID);
                    break;
            }
        } catch (Exception e) {
            plugin.getMessageUtil().logException("Error handling button action '" + action + "' for player " + player.getName(), e);
            msg.sendMessage(player, "error");
            // Attempt to cancel pending future on error during button handling, if applicable
            if (currentState != null && currentState.identifier() == GuiIdentifier.PERSONAL_KIT_CHOICE) { // Uses external enum
                 cancelPendingKitChoice(playerUUID, Optional.empty());
            }
            resetGuiCloseMarker(playerUUID);
            player.closeInventory();
            clearGuiHistory(playerUUID);
        }
    }

    // --- Navigation & State Helpers ---
    private void handleNavigationBack(UUID playerUUID) {
        msg.debug("[NAV] handleNavigationBack called for " + playerUUID);
        Player player = Bukkit.getPlayer(playerUUID);
        if (player == null || !player.isOnline()) { msg.debug("[NAV] Player offline, aborting back nav."); clearGuiHistory(playerUUID); return; }

        GuiState currentState = popGuiHistory(playerUUID); // Remove current from stack
        if (currentState == null) {
            msg.debug("[NAV] History stack empty, cannot go back. Closing.");
            player.closeInventory();
            return;
        }
        msg.debug("[NAV] Popped state: " + currentState.identifier());

        // If we were in a kit choice GUI and went back, ensure the future is cancelled
        if (currentState.identifier() == GuiIdentifier.PERSONAL_KIT_CHOICE) { // Uses external enum
            cancelPendingKitChoice(playerUUID, Optional.empty());
            msg.debug("[NAV] Cancelled pending kit choice due to 'back' action.");
        }

        GuiState parentState = peekGuiHistory(playerUUID); // Look at the new top
        markGuiClosedByNavigation(playerUUID);

        if (parentState != null) {
             Bukkit.getScheduler().runTaskLater(plugin, () -> {
                 if (!player.isOnline()) return;
                 msg.debug("[NAV] Attempting to reopen parent GUI: " + parentState.identifier() + " with context " + parentState.context());
                 try {
                      Inventory parentInventory = getInventoryForState(player, parentState);
                      if (parentInventory != null) {
                           player.openInventory(parentInventory);
                      } else {
                           msg.logWarning("Failed to get inventory for parent state: " + parentState);
                           handleNavigationError(playerUUID, "handleNavigationBack (failed to get parent inventory)");
                      }
                 } catch (Exception e) {
                      msg.logException("Error reopening parent GUI (" + parentState.identifier() + ")", e);
                      handleNavigationError(playerUUID, "handleNavigationBack (exception reopening parent)");
                 }
                 msg.debug("[NAV] handleNavigationBack scheduled task complete.");
             }, 1L);
         } else {
             msg.debug("[NAV] No parent found in history, closing GUI naturally.");
             player.closeInventory(); // Close GUI
             // clearGuiHistory(playerUUID); // History already empty or cleared if needed
         }
    }

    private Inventory getInventoryForState(Player player, GuiState state) {
        Map<String, Object> context = state.context();
        int kitNumber = (int) context.getOrDefault("kitNumber", -1);
        UUID ownerUUID = (UUID) context.get("owner");
        String category = (String) context.get("category");
        int page = (int) context.getOrDefault("page", 1);
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);

        // Added check for player data needed by PERSONAL_KIT_CHOICE as well
        if (playerData == null && (state.identifier() == GuiIdentifier.MAIN_MENU || // Uses external enum
                                    state.identifier() == GuiIdentifier.KIT_EDITOR ||
                                    state.identifier() == GuiIdentifier.ENDERCHEST_EDITOR ||
                                    state.identifier() == GuiIdentifier.PERSONAL_KIT_CHOICE)) { // Add check here
            msg.logWarning("Player data null when trying to get inventory for state: " + state.identifier());
            return null;
        }

        switch (state.identifier()) { // Uses external enum
            case MAIN_MENU:          return createMainMenuInventory(player, playerData);
            case KIT_EDITOR:
                 if(kitNumber <= 0) { msg.logWarning("Invalid kit number in context for KIT_EDITOR: " + kitNumber); return null; }
                 return createKitEditorInventory(player, playerData, kitNumber);
            case ENDERCHEST_EDITOR:
                if(kitNumber <= 0) { msg.logWarning("Invalid kit number in context for ENDERCHEST_EDITOR: " + kitNumber); return null; }
                Kit kitForEchest = playerData.getKit(kitNumber);
                return (kitForEchest != null) ? createEnderChestEditorInventory(player, kitForEchest) : null;
            case GLOBAL_KITS_LIST:   return createGlobalKitsListInventory(player, kitManager.getAllGlobalKits(), page);
            case KIT_PREVIEW:
                 if(kitNumber <= 0 || ownerUUID == null) { msg.logWarning("Invalid context for KIT_PREVIEW: " + context); return null; }
                 Kit kitToPreview = kitManager.getGlobalKit(ownerUUID, kitNumber);
                 return (kitToPreview != null) ? createKitPreviewInventory(player, kitToPreview) : null;
            case ENDERCHEST_PREVIEW:
                 if(kitNumber <= 0 || ownerUUID == null) { msg.logWarning("Invalid context for ENDERCHEST_PREVIEW: " + context); return null; }
                 Kit kitForEchestPreview = kitManager.getGlobalKit(ownerUUID, kitNumber);
                 return (kitForEchestPreview != null) ? createEnderChestPreviewInventory(player, kitForEchestPreview) : null;
            case KITROOM_MAIN:       return createKitroomMainMenuInventory(player);
            case KITROOM_CATEGORY:
                 if(category == null) { msg.logWarning("Null category in context for KITROOM_CATEGORY"); return null; }
                 return createKitroomCategoryInventory(player, category);
            case CONFIRMATION:
                 msg.logWarning("Attempted to getInventoryForState for CONFIRMATION GUI.");
                 return null;
            case PERSONAL_KIT_CHOICE:
                 msg.logWarning("Attempted to getInventoryForState for PERSONAL_KIT_CHOICE GUI.");
                 // Should not typically navigate *back* to this state via normal means.
                 // If needed, recreate using context (though title might be lost).
                 // Component defaultTitle = getGuiTitle("personal_kit_choice", "&8» &dChoose your Kit");
                 // List<Kit> kits = (playerData != null) ? new ArrayList<>(playerData.getKits().values()) : Collections.emptyList();
                 // return createPersonalKitChoiceInventory(player, defaultTitle, kits);
                 return null;
            default:
                msg.logSevere("Unknown GuiIdentifier in getInventoryForState: " + state.identifier());
                return null;
        }
    }

    private void handleNavigationError(UUID playerUUID, String contextMessage) {
        msg.logWarning("[NAV] Handling navigation error for " + playerUUID + ". Context: " + contextMessage);
        // Ensure pending choice is cancelled on error
        cancelPendingKitChoice(playerUUID, Optional.empty());
        clearGuiHistory(playerUUID); // Clear history on error
        Player player = Bukkit.getPlayer(playerUUID);
        if(player != null && player.isOnline()) {
             msg.sendMessage(player, "error");
             Bukkit.getScheduler().runTaskLater(plugin, () -> {
                  if (player.isOnline()) {
                       InventoryView currentView = player.getOpenInventory();
                       if (currentView.getTopInventory().getHolder() instanceof GuiManager) {
                            player.closeInventory();
                       }
                  }
             }, 1L);
        }
    }

    private void handleInvalidState(Player player, String attemptedAction, String reason) {
         msg.debug("Action '" + attemptedAction + "' failed due to invalid state. Reason: " + reason);
         msg.sendMessage(player, "gui_action_expired");
         msg.playSound(player, "error");
         // Check if we were in a kit choice state and cancel the future
         GuiState currentState = peekGuiHistory(player.getUniqueId());
         if (currentState != null && currentState.identifier() == GuiIdentifier.PERSONAL_KIT_CHOICE) { // Uses external enum
             cancelPendingKitChoice(player.getUniqueId(), Optional.empty());
         }
         resetGuiCloseMarker(player.getUniqueId());
         handleNavigationBack(player.getUniqueId()); // Try to go back gracefully
    }

    // --- NEW: Methods for managing pending kit choices ---
    public void registerPendingKitChoice(UUID playerUUID, CompletableFuture<Optional<Kit>> future) {
        CompletableFuture<Optional<Kit>> previous = pendingKitChoices.put(playerUUID, future);
        if (previous != null && !previous.isDone()) {
            msg.logWarning("Player " + playerUUID + " already had a pending kit choice. Cancelling previous.");
            previous.complete(Optional.empty()); // Cancel the old one
        }
        msg.debug("[Kit Choice] Registered pending choice for " + playerUUID);
    }

    public void cancelPendingKitChoice(UUID playerUUID, Optional<Kit> result) {
        CompletableFuture<Optional<Kit>> future = pendingKitChoices.remove(playerUUID);
        if (future != null && !future.isDone()) {
            future.complete(result);
            msg.debug("[Kit Choice] Completed/Cancelled pending choice for " + playerUUID + " with result: " + result.isPresent());
        } else if (future != null) {
             // Future existed but was already completed (e.g., button click then immediate ESC close)
             msg.debug("[Kit Choice] Attempted to cancel choice for " + playerUUID + ", but future was already done.");
        }
    }

    // --- GUI Close Handling ---
    public void handleGuiClose(Player player, Inventory closedInventory) {
        UUID playerUUID = player.getUniqueId();
        GuiIdentifier closedGuiType = identifyGuiFromHolder(closedInventory); // Uses external enum

        boolean wasNavigationClose = wasGuiClosedByButton.getOrDefault(playerUUID, false);
        resetGuiCloseMarker(playerUUID); // Always reset marker after checking

        msg.debug(String.format("[GUI CLOSE] START for %s. Closed GUI Type: %s, NavigationClose: %b",
                player.getName(), closedGuiType, wasNavigationClose)
        );

        // Handle specific cleanup based on closed GUI type FIRST
        if (closedGuiType == GuiIdentifier.KITROOM_CATEGORY) { // Uses external enum
             editingKitroomCategory.remove(playerUUID);
             msg.debug("[GUI CLOSE] Cleared kitroom category editing state.");
        } else if (closedGuiType == GuiIdentifier.PERSONAL_KIT_CHOICE) { // Uses external enum
             // If closed by any means other than selecting a kit via click (handled in listener)
             // or cancelling via button (handled in button action), cancel the future.
             // This check ensures the future isn't completed twice.
             if (pendingKitChoices.containsKey(playerUUID)) {
                 cancelPendingKitChoice(playerUUID, Optional.empty());
                 msg.debug("[GUI CLOSE] Cancelled pending kit choice due to close/back out.");
             }
        }

        // Then handle general logic (auto-save, history clear)
        if (wasNavigationClose) {
            msg.debug("[GUI CLOSE] Closed by button/back action, navigation handled elsewhere.");
            // No auto-save needed on navigation close
        } else {
            // Manual Close (ESC, death, etc.)
            msg.debug("[GUI CLOSE] Manual close detected (ESC/Other?).");

            // Auto-save for Kit Editor
            if (closedGuiType == GuiIdentifier.KIT_EDITOR && configManager.isSaveOnEditorClose()) { // Uses external enum
                 int kitNumFromTitle = getKitNumberFromTitle(closedInventory);
                 if (kitNumFromTitle > 0) {
                     msg.debug("[GUI CLOSE] Auto-saving Kit " + kitNumFromTitle + " due to manual close.");
                     saveKitFromInventory(player, kitNumFromTitle, closedInventory);
                 } else {
                     msg.debug("[GUI CLOSE] Auto-save enabled, but couldn't determine kit number from closed Kit Editor.");
                 }
            }

            // Clear history entirely on manual close
            clearGuiHistory(playerUUID); // This also cancels pending choice if needed
            msg.debug("[GUI CLOSE] Cleared GUI history due to manual close.");
        }

        msg.debug("[GUI CLOSE] END for " + player.getName());
    }

    private int getKitNumberFromTitle(Inventory inv) {
        if (inv == null) return -1;
        try {
            if (!(inv.getHolder() instanceof GuiManager)) return -1;
            List<HumanEntity> viewers = inv.getViewers();
            // Title might not be available if called too late after close
            InventoryView view = viewers.isEmpty() ? null : viewers.get(0).getOpenInventory();
            Component titleComp = (view != null) ? view.title() : null;

            if (titleComp == null) {
                 // Fallback: Try peeking at history if title unavailable
                 UUID viewerUUID = viewers.isEmpty() ? null : viewers.get(0).getUniqueId();
                 if (viewerUUID != null) {
                      GuiState lastState = peekGuiHistory(viewerUUID);
                      // Uses external enum
                      if (lastState != null && (lastState.identifier() == GuiIdentifier.KIT_EDITOR || lastState.identifier() == GuiIdentifier.ENDERCHEST_EDITOR)) {
                           msg.debug("getKitNumberFromTitle: Using history context as fallback.");
                           return (int) lastState.context().getOrDefault("kitNumber", -1);
                      }
                 }
                 msg.debug("getKitNumberFromTitle: No viewers/title, and no relevant history found.");
                 return -1;
            }

            // TODO: Use MessageUtil for stripping colors
            String title = plugin.getMessageUtil().stripColors(titleComp).trim();
            msg.debug("getKitNumberFromTitle: Stripped Title = '" + title + "'");

            // TODO: Use MessageUtil for stripping colors
            String rawEditorTitleFormat = plugin.getMessageUtil().stripColors(getGuiTitle("kit_editor", ""));
            String rawEchestTitleFormat = plugin.getMessageUtil().stripColors(getGuiTitle("enderchest_editor", ""));

            String editorTitleStart = rawEditorTitleFormat.split("\\{kit_number}", 2)[0].trim();
            String echestTitleStart = rawEchestTitleFormat.split("\\{kit_number}", 2)[0].trim();

             msg.debug("getKitNumberFromTitle: Comparing against EditorStart='" + editorTitleStart + "', EChestStart='" + echestTitleStart + "'");

            String numPart = null;
            if (!editorTitleStart.isEmpty() && title.startsWith(editorTitleStart)) {
                numPart = title.substring(editorTitleStart.length());
            } else if (!echestTitleStart.isEmpty() && title.startsWith(echestTitleStart)) {
                numPart = title.substring(echestTitleStart.length());
            }

            if (numPart != null && !numPart.isEmpty()) {
                 String potentialNumber = numPart.trim().split("[^0-9]", 2)[0]; // Extract leading numbers
                 msg.debug("getKitNumberFromTitle: Extracted potential number part = '" + potentialNumber + "'");
                 // Ensure the extracted part is not empty before parsing
                 if (!potentialNumber.isEmpty()) {
                    return Integer.parseInt(potentialNumber);
                 } else {
                    msg.debug("getKitNumberFromTitle: Extracted number part was empty after filtering.");
                 }
            } else {
                 msg.debug("getKitNumberFromTitle: Title did not match expected formats.");
            }

        } catch (Exception e) {
            msg.debug("getKitNumberFromTitle: Failed to extract kit number from title: " + e.getMessage());
        }
        return -1;
    }

    // identifyGuiFromHolder - Now uses the external GuiIdentifier enum
    public GuiIdentifier identifyGuiFromHolder(Inventory inventory) {
        try {
            if (inventory == null || !(inventory.getHolder() instanceof GuiManager)) return null;
            Component actualTitleComponent = null;
            try {
                 List<HumanEntity> viewers = inventory.getViewers();
                 // Use title from view if available, more reliable during close event
                 actualTitleComponent = viewers.isEmpty() ? null : viewers.get(0).getOpenInventory().title();
            } catch (Exception e) { msg.debug("identifyGuiFromHolder: Could not get title from viewers: " + e.getMessage()); }

            // Fallback to trying history if title is null (e.g., called very late after close)
            if (actualTitleComponent == null && !inventory.getViewers().isEmpty()) {
                 UUID viewerUUID = inventory.getViewers().get(0).getUniqueId();
                 GuiState lastState = peekGuiHistory(viewerUUID);
                 if(lastState != null) {
                      msg.debug("identifyGuiFromHolder: Using history identifier as fallback: " + lastState.identifier());
                      return lastState.identifier();
                 }
            }

            if (actualTitleComponent == null) { msg.debug("identifyGuiFromHolder: Title component was null and no history fallback."); return null; }
            // TODO: Use MessageUtil for stripping colors
            String actualTitleStripped = plugin.getMessageUtil().stripColors(actualTitleComponent).trim();

            // Compare against stripped base titles (excluding placeholders)
            // Check potentially ambiguous titles first or more specific ones
            // TODO: Use MessageUtil for stripping colors
             if (actualTitleStripped.startsWith(plugin.getMessageUtil().stripColors(getGuiTitle("personal_kit_choice", "")).trim())) return GuiIdentifier.PERSONAL_KIT_CHOICE;
             if (actualTitleStripped.startsWith(plugin.getMessageUtil().stripColors(getGuiTitle("kit_editor", "")).split("\\{", 2)[0].trim())) return GuiIdentifier.KIT_EDITOR;
             if (actualTitleStripped.startsWith(plugin.getMessageUtil().stripColors(getGuiTitle("enderchest_editor", "")).split("\\{", 2)[0].trim())) return GuiIdentifier.ENDERCHEST_EDITOR;
             if (actualTitleStripped.startsWith(plugin.getMessageUtil().stripColors(getGuiTitle("global_kits", "")).split("\\{", 2)[0].trim())) return GuiIdentifier.GLOBAL_KITS_LIST;
             if (actualTitleStripped.startsWith(plugin.getMessageUtil().stripColors(getGuiTitle("kit_preview", "")).split("\\{", 2)[0].trim())) return GuiIdentifier.KIT_PREVIEW;
             if (actualTitleStripped.startsWith(plugin.getMessageUtil().stripColors(getGuiTitle("echest_preview", "")).split("\\{", 2)[0].trim())) return GuiIdentifier.ENDERCHEST_PREVIEW;
             if (actualTitleStripped.startsWith(plugin.getMessageUtil().stripColors(getGuiTitle("kitroom_category", "")).split("\\{", 2)[0].trim())) return GuiIdentifier.KITROOM_CATEGORY;
             // Check less specific titles later
             if (actualTitleStripped.startsWith(plugin.getMessageUtil().stripColors(getGuiTitle("kitroom_main", "")).trim())) return GuiIdentifier.KITROOM_MAIN;
             if (actualTitleStripped.startsWith(plugin.getMessageUtil().stripColors(getGuiTitle("confirmation", "")).trim())) return GuiIdentifier.CONFIRMATION;
             if (actualTitleStripped.startsWith(plugin.getMessageUtil().stripColors(getGuiTitle("main_menu", "")).trim())) return GuiIdentifier.MAIN_MENU;


            msg.debug("identifyGuiFromHolder: Could not match title '" + actualTitleStripped + "' to known GUI types.");
            return null;
        } catch (Exception e) {
             plugin.getMessageUtil().logException("GUI identification failed unexpectedly", e);
             return null;
        }
    }

    // createKitFromEditor
    Kit createKitFromEditor(Player player, int kitNumber, Inventory editorInventory) {
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        KitContents contents = new KitContents();
        ItemStack[] editorContents = editorInventory.getContents();
        Map<Integer, ItemStack> kitItems = new HashMap<>();
        int itemCount = 0;

        msg.debug("createKitFromEditor: Starting snapshot for Kit " + kitNumber + " for player " + player.getName());

        for (int editorSlot = 0; editorSlot <= 40; editorSlot++) {
            int playerInvSlot = KitManager.mapEditorSlotToPlayerInvSlot(editorSlot);
            ItemStack item = editorContents[editorSlot];

            if (playerInvSlot != -1 && item != null && item.getType() != Material.AIR) {
                Set<Material> forbiddenMaterials = configManager.getPreventSaveMaterials();
                if (!forbiddenMaterials.isEmpty() && forbiddenMaterials.contains(item.getType())) {
                    msg.sendMessage(player, "cannot_save_forbidden_item", "item", item.getType().toString()); msg.playSound(player, "error");
                    msg.debug(" -> Aborted: Forbidden item type " + item.getType());
                    return null;
                }
                kitItems.put(playerInvSlot, item.clone());
                itemCount++;
            }
        }
        contents.setItems(kitItems);
        msg.debug("createKitFromEditor: Created snapshot with " + itemCount + " valid items.");

        int maxItems = configManager.getMaxTotalItemsInKit();
        if (maxItems > 0 && itemCount > maxItems) {
            msg.sendMessage(player, "kit_exceeds_max_items", "count", String.valueOf(itemCount), "max", String.valueOf(maxItems)); msg.playSound(player, "error");
            msg.debug(" -> Aborted: Exceeded max item count (" + itemCount + " > " + maxItems + ")");
            return null;
        }

        Kit existingSavedKit = (playerData != null) ? playerData.getKit(kitNumber) : null;
        KitContents existingEchest = (existingSavedKit != null && existingSavedKit.getEnderChestContents() != null) ? existingSavedKit.getEnderChestContents() : new KitContents();
        boolean isCurrentlyGlobal = (existingSavedKit != null) && existingSavedKit.isGlobal();

        return Kit.builder().kitNumber(kitNumber).owner(player.getUniqueId()).name("Kit " + kitNumber).contents(contents).enderChestContents(existingEchest).global(isCurrentlyGlobal).build();
    }

    // --- Helper Method Implementations ---
    private void loadCurrentInventoryToEditor(Player player) {
        GuiState currentState = peekGuiHistory(player.getUniqueId());
        if (currentState == null || currentState.identifier() != GuiIdentifier.KIT_EDITOR) { // Uses external enum
             handleInvalidState(player, "load_current_inventory", "Not in Kit Editor state"); return;
        }
        PlayerInventory pInv = player.getInventory();
        Inventory editorInv = player.getOpenInventory().getTopInventory();
        if (!isKitEditorInventory(editorInv)) { msg.debug("loadCurrentInventoryToEditor: Aborting, player no longer viewing Kit Editor."); return; }

        for (int i = 0; i < 41; i++) {
            int editorSlot = KitManager.mapPlayerInvSlotToEditorSlot(i);
            if (editorSlot != -1 && editorSlot <= 40) {
                ItemStack pItem = pInv.getItem(i);
                editorInv.setItem(editorSlot, (pItem == null || pItem.getType() == Material.AIR) ? null : pItem.clone());
            }
        }
        msg.sendActionBar(player, "inventory_loaded_to_gui"); msg.playSound(player, "gui_click");
    }

    private void handleClearConfirmClick(Player player, int kitNum) {
        boolean requireConfirm = configManager.isClearKitRequiresConfirmation();
        UUID playerUUID = player.getUniqueId();
        if (!requireConfirm) {
            kitManager.clearKit(player, kitNum);
            handleNavigationBack(playerUUID);
            return;
        }
        long now = System.currentTimeMillis();
        long lastClick = confirmClickCooldown.getOrDefault(playerUUID, 0L);
        long requiredDelay = 500;
        if (now - lastClick > requiredDelay) {
            confirmClickCooldown.put(playerUUID, now);
            msg.sendActionBar(player, "clear_confirmation_required", "kit_number", String.valueOf(kitNum)); msg.playSound(player, "error");
            resetGuiCloseMarker(playerUUID);
        } else {
            confirmClickCooldown.remove(playerUUID);
            Component title = getGuiTitle("confirmation", "&8[&c!&8] &e&lConfirmation")
                                .replaceText(cfg -> cfg.matchLiteral("{kit_number}").replacement(String.valueOf(kitNum)));
            Component confirmText = msg.deserialize("&cDelete Kit " + kitNum + " permanently.");
            Component cancelText = msg.deserialize("&aKeep the kit.");
            openConfirmationGUI(player, "clear_kit_execute", kitNum, title, confirmText, cancelText); msg.playSound(player, "confirmation_open");
        }
    }

    private void handleRepairKitClick(Player player, int kitNum) {
         GuiState currentState = peekGuiHistory(player.getUniqueId());
         // Uses external enum
         if (currentState == null || currentState.identifier() != GuiIdentifier.KIT_EDITOR || (int)currentState.context().getOrDefault("kitNumber", -1) != kitNum) {
             handleInvalidState(player, "repair_kit_items", "Mismatched kit number context"); return;
         }
        Inventory editorInv = player.getOpenInventory().getTopInventory();
         if (!isKitEditorInventory(editorInv)) { msg.debug("handleRepairKitClick: Player not viewing Kit Editor."); return; }
        Kit kitFromEditor = createKitFromEditor(player, kitNum, editorInv);
        if (kitFromEditor == null) { msg.sendMessage(player, "error"); return; }
        boolean repaired = kitManager.repairKitItems(kitFromEditor);
        if (repaired) {
            for (int editorSlot = 0; editorSlot <= 40; editorSlot++) {
                 int playerInvSlot = KitManager.mapEditorSlotToPlayerInvSlot(editorSlot);
                 ItemStack repairedItem = kitFromEditor.getContents().getItems().get(playerInvSlot);
                 editorInv.setItem(editorSlot, (repairedItem != null) ? repairedItem.clone() : null);
            }
            msg.sendActionBar(player, "kit_repaired", "kit_number", String.valueOf(kitNum)); msg.playSound(player, "repair_success");
        } else { msg.sendActionBar(player, "kit_repair_failed", "kit_number", String.valueOf(kitNum)); msg.playSound(player, "repair_fail"); }
    }

    private void handleShareKit(Player player, int kitNum) {
         GuiState currentState = peekGuiHistory(player.getUniqueId());
         // Uses external enum
         if (currentState == null || currentState.identifier() != GuiIdentifier.KIT_EDITOR || (int)currentState.context().getOrDefault("kitNumber", -1) != kitNum) {
              handleInvalidState(player, "share_kit", "Mismatched kit number context"); return;
         }
        Inventory editorInv = player.getOpenInventory().getTopInventory();
         if (!isKitEditorInventory(editorInv)) { msg.debug("handleShareKit: Player not viewing Kit Editor."); return; }
        Kit kitToShare = createKitFromEditor(player, kitNum, editorInv);
        if(kitToShare == null) { msg.sendActionBar(player, "error"); resetGuiCloseMarker(player.getUniqueId()); return; }
        msg.sendActionBar(player, "generating_share_code");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String shareCode = shareCodeManager.generateShareCodeFromKit(player.getUniqueId(), kitToShare);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (shareCode != null) {
                    long expiry = configManager.getShareCodeExpirationMinutes();
                    msg.sendMessage(player, "share_code_generated", "kit_number", String.valueOf(kitNum), "code", shareCode, "time", String.valueOf(expiry));
                    msg.playSound(player, "kit_share");
                    player.closeInventory();
                } else {
                    boolean allowGlobalShare = configManager.isAllowSharingGlobalKits();
                    if (kitToShare.isGlobal() && !allowGlobalShare) msg.sendMessage(player, "cannot_share_global_kit");
                    else msg.sendMessage(player, "error");
                    msg.playSound(player, "error");
                    resetGuiCloseMarker(player.getUniqueId());
                }
            });
        });
    }

    private void handleSetGlobal(Player player, int kitNum, boolean targetGlobal) {
        UUID playerUUID = player.getUniqueId();
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if(playerData == null) { msg.sendMessage(player, "error"); resetGuiCloseMarker(playerUUID); return; }
        Kit savedKit = playerData.getKit(kitNum);
        if (savedKit == null) {
             msg.sendMessage(player, targetGlobal ? "cannot_set_unsaved_global" : "must_save_kit_first");
             if (targetGlobal) msg.playSound(player, "error");
             resetGuiCloseMarker(playerUUID);
             return;
         }
         kitManager.setKitGlobalStatus(player, kitNum, targetGlobal)
             .thenAcceptAsync(v -> {
                  if (player.isOnline()) {
                      GuiState currentState = peekGuiHistory(playerUUID);
                      // Uses external enum
                      if (currentState != null && currentState.identifier() == GuiIdentifier.KIT_EDITOR && (int)currentState.context().getOrDefault("kitNumber", -1) == kitNum) {
                           msg.debug("Refreshing Kit Editor after global status change for kit " + kitNum);
                           openKitEditor(player, kitNum);
                      } else {
                           msg.debug("Skipping GUI refresh for global status: Player no longer in relevant Kit Editor.");
                           resetGuiCloseMarker(playerUUID);
                      }
                  }
             }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
             .exceptionally(ex -> {
                  resetGuiCloseMarker(playerUUID);
                  return null;
             });
    }

    private void handlePreviewKit(Player player, UUID ownerUUID, int kitNum) {
        Kit cachedKit = kitManager.getGlobalKit(ownerUUID, kitNum);
        if (cachedKit != null && cachedKit.isGlobal()) { openKitPreview(player, cachedKit); return; }
        msg.sendActionBar(player, "loading_kit_preview");
        plugin.getApi().getPlayerKitAsync(ownerUUID, kitNum)
            .thenAcceptAsync(optionalKit -> {
                if (optionalKit.isPresent()) {
                    Kit loadedKit = optionalKit.get();
                    if (loadedKit.isGlobal()) { kitManager.addGlobalKitToCache(loadedKit); openKitPreview(player, loadedKit); }
                    else { msg.sendMessage(player, "kit_no_longer_global"); msg.playSound(player, "error"); kitManager.removeGlobalKitFromCache(ownerUUID, kitNum); openGlobalKitsList(player, playerGlobalKitPages.getOrDefault(player.getUniqueId(), 1)); }
                } else { msg.sendMessage(player, "kit_not_found", "kit_number", String.valueOf(kitNum)); msg.playSound(player, "error"); kitManager.removeGlobalKitFromCache(ownerUUID, kitNum); openGlobalKitsList(player, playerGlobalKitPages.getOrDefault(player.getUniqueId(), 1)); }
            }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
            .exceptionally(ex -> {
                 plugin.getMessageUtil().logException("Error loading global kit preview for " + ownerUUID + "_" + kitNum, ex);
                 Bukkit.getScheduler().runTask(plugin, () -> { msg.sendMessage(player, "error"); openGlobalKitsList(player, playerGlobalKitPages.getOrDefault(player.getUniqueId(), 1)); });
                 return null;
            });
    }

    private void handleSaveKitroomCategory(Player player, String categoryToSave) {
       UUID playerUUID = player.getUniqueId();
       GuiState currentState = peekGuiHistory(playerUUID);
       String currentCategoryCtx = null;
       // Uses external enum
       if (currentState != null && currentState.identifier() == GuiIdentifier.KITROOM_CATEGORY) {
           currentCategoryCtx = (String) currentState.context().get("category");
       }
       msg.debug("Save Kitroom Category action. Category from PDC: " + categoryToSave + ". Current State Context: " + currentCategoryCtx);
       if (categoryToSave == null) { plugin.getLogger().severe("Save category button clicked but category key was missing from PDC!"); handleInvalidState(player, "save_kitroom_category", "Missing category from PDC"); return; }
       if (!player.hasPermission("tkits.kitroom.admin")) { msg.sendMessage(player, "no_permission"); msg.playSound(player, "error"); resetGuiCloseMarker(playerUUID); return; }
       if (currentCategoryCtx == null || !categoryToSave.equalsIgnoreCase(currentCategoryCtx)) { plugin.getLogger().warning("Save category button clicked (" + categoryToSave + ") but player state context (" + currentCategoryCtx + ") is missing or doesn't match. Aborting save."); handleInvalidState(player, "save_kitroom_category", "Mismatched category context"); return; }
       InventoryView openView = player.getOpenInventory();
       if (openView == null || openView.getTopInventory().getType() != InventoryType.CHEST || openView.getTopInventory().getSize() != 54) { msg.logWarning("Attempted kitroom save but top inventory is not the expected type/size. Player: " + player.getName()); handleInvalidState(player, "save_kitroom_category", "Invalid inventory view"); return; }
       Inventory inv = openView.getTopInventory();
       Map<Integer, ItemStack> newItems = new HashMap<>();
       for (int slot = 0; slot < 45; slot++) {
           ItemStack item = inv.getItem(slot);
           if (item != null && item.getType() != Material.AIR && !isKitroomFiller(item)) {
                // msg.debug("handleSaveKitroomCategory: Saving item " + item.getType() + " from slot " + slot);
                newItems.put(slot, item.clone());
           } else if (item != null && isKitroomFiller(item)) {
               msg.debug("handleSaveKitroomCategory: Skipping kitroom filler item in slot " + slot);
           }
       }
       kitroomManager.setCategoryItems(categoryToSave, newItems);
       kitroomManager.saveKitroom(); // This now gets the internal reference and saves
       msg.sendActionBar(player, "kitroom_category_saved", "category", capitalize(categoryToSave));
       msg.playSound(player, "kit_save");
       handleNavigationBack(playerUUID); // Navigate back after saving
   }

    // --- Item Action Helpers ---
    private void setItemAction(ItemStack item, String action) { if (item == null || action == null || action.isEmpty()) return; ItemMeta meta = item.getItemMeta(); if (meta != null) { meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action); item.setItemMeta(meta); } }
    private void setItemActionAndKitNum(ItemStack item, String action, int kitNum) { if (item == null || action == null || action.isEmpty()) return; ItemMeta meta = item.getItemMeta(); if (meta != null) { PersistentDataContainer pdc = meta.getPersistentDataContainer(); pdc.set(actionKey, PersistentDataType.STRING, action); pdc.set(kitNumberKey, PersistentDataType.INTEGER, kitNum); item.setItemMeta(meta); } else { msg.debug("setItemActionAndKitNum: Meta was null for item " + item.getType() + ". Cannot set action/kitNum."); } }
    private void setItemActionAndKitData(ItemStack item, String action, int kitNum, UUID ownerUUID) { if (item == null || action == null || action.isEmpty() || ownerUUID == null) return; ItemMeta meta = item.getItemMeta(); if (meta != null) { PersistentDataContainer pdc = meta.getPersistentDataContainer(); pdc.set(actionKey, PersistentDataType.STRING, action); pdc.set(kitNumberKey, PersistentDataType.INTEGER, kitNum); pdc.set(kitOwnerKey, PersistentDataType.STRING, ownerUUID.toString()); item.setItemMeta(meta); } }
    private void setItemActionAndCategory(ItemStack item, String action, String category) { if (item == null || action == null || action.isEmpty() || category == null || category.isEmpty()) { msg.debug("Attempted to set null/empty action or category"); return; } ItemMeta meta = item.getItemMeta(); if (meta != null) { PersistentDataContainer pdc = meta.getPersistentDataContainer(); pdc.set(actionKey, PersistentDataType.STRING, action); pdc.set(categoryKey, PersistentDataType.STRING, category); item.setItemMeta(meta); } else { msg.debug("setItemActionAndCategory: Meta was null for item " + item.getType()); } }
    private void setItemActionAndPage(ItemStack item, String action, int pageNum) { if (item == null || action == null || action.isEmpty()) return; ItemMeta meta = item.getItemMeta(); if (meta != null) { PersistentDataContainer pdc = meta.getPersistentDataContainer(); pdc.set(actionKey, PersistentDataType.STRING, action); pdc.set(pageKey, PersistentDataType.INTEGER, pageNum); item.setItemMeta(meta); } }

    // --- GUI Identification Methods ---
    // Now use the external GuiIdentifier enum
     public boolean isKitEditorInventory(Inventory inv) { return isGuiInventory(inv, GuiIdentifier.KIT_EDITOR); }
     public boolean isEnderChestEditorInventory(Inventory inv) { return isGuiInventory(inv, GuiIdentifier.ENDERCHEST_EDITOR); }
     public boolean isKitroomCategoryInventory(Inventory inv) { return isGuiInventory(inv, GuiIdentifier.KITROOM_CATEGORY); }
     public boolean isGlobalListInventory(Inventory inv) { return isGuiInventory(inv, GuiIdentifier.GLOBAL_KITS_LIST); }
     public boolean isKitPreviewInventory(Inventory inv) { return isGuiInventory(inv, GuiIdentifier.KIT_PREVIEW); }
     public boolean isEnderChestPreviewInventory(Inventory inv) { return isGuiInventory(inv, GuiIdentifier.ENDERCHEST_PREVIEW); }
     public boolean isKitroomMainMenuInventory(Inventory inv) { return isGuiInventory(inv, GuiIdentifier.KITROOM_MAIN); }
     public boolean isConfirmationInventory(Inventory inv) { return isGuiInventory(inv, GuiIdentifier.CONFIRMATION); }
     public boolean isMainMenuInventory(Inventory inv) { return isGuiInventory(inv, GuiIdentifier.MAIN_MENU); }
     public boolean isPersonalKitChoiceInventory(Inventory inv) { return isGuiInventory(inv, GuiIdentifier.PERSONAL_KIT_CHOICE); } // Added

     private boolean isGuiInventory(Inventory inv, GuiIdentifier expectedType) {
         GuiIdentifier actualType = identifyGuiFromHolder(inv);
         return actualType == expectedType;
     }

    // --- Utility Methods ---
    public String capitalize(String str) { if (str == null || str.isEmpty()) return str; return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase(); }

    private void saveKitFromInventory(Player player, int kitNumber, Inventory sourceInventory) {
        if (player == null || sourceInventory == null) { msg.debug("Could not save kit: player or sourceInventory was null"); return; }
        msg.debug("Attempting synchronous save for Kit " + kitNumber + " for " + player.getName() + " from closed inventory state.");
        try {
             Kit kitSnapshot = createKitFromEditor(player, kitNumber, sourceInventory);
             if (kitSnapshot != null) {
                  // Pass the sourceInventory which holds the state just before close
                  kitManager.saveKit(player, kitNumber, sourceInventory);
                 msg.debug("Auto-save triggered and completed for kit " + kitNumber);
             } else { msg.debug("Auto-save aborted for kit " + kitNumber + ": createKitFromEditor returned null (validation failed)."); }
        } catch (Exception e) {
            msg.debug("Error during auto-save: " + e.getMessage());
            plugin.getLogger().log(Level.WARNING, "Error auto-saving kit " + kitNumber + " for " + player.getName(), e);
        }
    }

    private void applyPlaceholdersAndAction(ItemStack categoryItem, String categoryName) {
        String formattedCategoryName = capitalize(categoryName);
        Map<String, String> placeholders = Map.of("{category}", formattedCategoryName);
        replaceItemPlaceholders(categoryItem, placeholders);
        setItemActionAndCategory(categoryItem, "open_kitroom_category", categoryName);
    }

    private ItemStack loadCategoryButton(String categoryName) {
        String categoryLower = categoryName.toLowerCase();
        String overridePath = "items.kitroom_main.category_overrides." + categoryLower;
        if (guiConfig.isSet(overridePath + ".material")) {
            String defaultButtonPath = "items.kitroom_main.category_button";
            Material defaultMat = Material.matchMaterial(guiConfig.getString(defaultButtonPath + ".material", "CHEST"));
            String defaultName = guiConfig.getString(defaultButtonPath + ".name", "&b{category}");
            List<String> defaultLore = guiConfig.getStringList(defaultButtonPath + ".lore");
            if (defaultLore.isEmpty()) defaultLore = List.of("&7View items in &e{category}");
            return loadGuiItem(overridePath, defaultMat, defaultName, defaultLore);
        } else {
            return loadGuiItem("items.kitroom_main.category_button", Material.CHEST, "&b{category}", List.of("&7View items in &e{category}"));
        }
    }

    // --- GUI Config Loading Helpers ---
    private Component getGuiTitle(String key, String defaultValue) {
        String titleStr = guiConfig.getString("titles." + key);
        if (titleStr == null || titleStr.isEmpty()) {
             plugin.getLogger().warning("Missing title definition in gui.yml for key: titles." + key + ". Using default: " + defaultValue);
             titleStr = defaultValue;
             // Handle default title for the new GUI specifically if needed
             if (key.equals("personal_kit_choice") && (defaultValue == null || defaultValue.isEmpty())) { // Added null check for safety
                 titleStr = "&8» &dChoose your Kit"; // Default if even defaultValue is empty
             }
        }
        return msg.deserialize(titleStr);
    }

    private int getSlot(String path, int defaultValue) { return guiConfig.getInt(path, defaultValue); }
    private ItemStack loadGuiItem(String path, Material defaultMat, String defaultName, List<String> defaultLore) { return loadGuiItem(path, defaultMat, defaultName, defaultLore, null); }

    private ItemStack loadGuiItem(String path, Material defaultMat, String defaultName, List<String> defaultLore, Map<String, String> placeholders) {
        Material mat = Material.matchMaterial(guiConfig.getString(path + ".material", defaultMat.name()));
        if (mat == null || mat.isLegacy()) mat = defaultMat;
        String name = guiConfig.getString(path + ".name", defaultName);
        List<String> lore = guiConfig.getStringList(path + ".lore");
        if (lore.isEmpty() && defaultLore != null) lore = defaultLore;
        int modelData = guiConfig.getInt(path + ".custom-model-data", 0);
        final Map<String, String> finalPlaceholders = placeholders == null ? Collections.emptyMap() : placeholders;
        String finalName = replacePlaceholders(name, finalPlaceholders);
        List<String> finalLore = lore.stream().map(line -> replacePlaceholders(line, finalPlaceholders)).collect(Collectors.toList());
        Component nameComp = msg.deserialize(finalName);
        List<Component> loreComps = finalLore.stream().map(msg::deserialize).collect(Collectors.toList());
        return GuiUtils.createGuiItem(mat, nameComp, loreComps, modelData);
    }

    private String replacePlaceholders(String text, Map<String, String> placeholders) { if (text == null || placeholders == null) return text; for (Map.Entry<String, String> entry : placeholders.entrySet()) { text = text.replace(entry.getKey(), entry.getValue()); } return text; }
    private ItemStack loadGuiItemWithAction(String path, String action, Material defaultMat, String defaultName, List<String> defaultLore) { return loadGuiItemWithAction(path, action, defaultMat, defaultName, defaultLore, null); }

    private ItemStack loadGuiItemWithAction(String path, String action, Material defaultMat, String defaultName, List<String> defaultLore, Map<String, String> placeholders) {
        ItemStack item = loadGuiItem(path, defaultMat, defaultName, defaultLore, placeholders);
        setItemAction(item, action);
        return item;
    }


    private void replaceItemPlaceholders(ItemStack item, Map<String, String> placeholders) {
        if (item == null || placeholders == null || placeholders.isEmpty()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        boolean metaChanged = false;
        if (meta.hasDisplayName()) {
            Component currentName = meta.displayName();
            if (currentName != null) {
                String nameStr = msg.getLegacySerializer().serialize(currentName);
                String finalName = replacePlaceholders(nameStr, placeholders);
                Component newName = msg.deserialize(finalName);
                if (!currentName.equals(newName)) { meta.displayName(newName.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)); metaChanged = true; }
            }
        }
        if (meta.hasLore()) {
            List<Component> currentLore = meta.lore();
            if (currentLore != null) {
                List<Component> newLore = currentLore.stream()
                    .map(line -> msg.getLegacySerializer().serialize(line))
                    .map(lineStr -> replacePlaceholders(lineStr, placeholders))
                    .map(finalLine -> msg.deserialize(finalLine).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                    .collect(Collectors.toList());
                if (!currentLore.equals(newLore)) { meta.lore(newLore); metaChanged = true; }
            }
        }
        if (metaChanged) item.setItemMeta(meta);
    }

} // End of GuiManager class