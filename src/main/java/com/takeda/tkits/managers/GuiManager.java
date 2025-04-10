// ============================================
//             GuiManager.java (Updated)
// ============================================
package com.takeda.tkits.managers;

import com.takeda.tkits.TKits;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.KitContents;
import com.takeda.tkits.models.PlayerData;
import com.takeda.tkits.util.GuiUtils;
import com.takeda.tkits.util.MessageUtil;
import com.takeda.tkits.config.ConfigManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.wesjd.anvilgui.AnvilGUI; // Shaded AnvilGUI
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.*; // Wildcard import ok
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

// Record for tracking confirmation actions
record ActionConfirmation(String action, int kitNumber, long timestamp) {}

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

    private FileConfiguration guiConfig; // Added field for guiConfig
    // Removed unused cached item fields (mainMenuKitroomButton, etc.) as items are loaded dynamically

    private final Map<UUID, Integer> editingKit = new ConcurrentHashMap<>();
    private final Map<UUID, Kit> editingEnderChest = new ConcurrentHashMap<>();
    private final Map<UUID, String> editingKitroomCategory = new ConcurrentHashMap<>();
    private final Map<UUID, Kit> previewingKit = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerGlobalKitPages = new ConcurrentHashMap<>();
    private final Map<UUID, ActionConfirmation> confirmationActions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Integer>> playerKitLoadConfirmations = new ConcurrentHashMap<>(); // kitNumber -> requires confirmation
    private final Map<UUID, Long> confirmClickCooldown = new ConcurrentHashMap<>();

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
        this.kitOwnerKey = new NamespacedKey(plugin, "tkits_kit_owner"); // UUID as string
        this.categoryKey = new NamespacedKey(plugin, "tkits_category");
        this.pageKey = new NamespacedKey(plugin, "tkits_page");

        // Initial load of GUI config
        reloadGuiConfig();

        this.KITROOM_ADMIN_LORE_HINT = msg.deserialize(
            "&c&lAᴅᴍɪɴ:&r &7Mᴏᴠɪɴɢ ɪᴛᴇᴍs ᴛᴏ ᴇᴅɪᴛ ʟᴀʏᴏᴜᴛ."
        );
        this.KITROOM_PLAYER_LORE_HINT = msg.deserialize("&a▸ ᴄʟɪᴄᴋ ᴛᴏ ᴛᴀᴋᴇ");
    }

    // Method to reload GUI config data
    public void reloadGuiConfig() {
        this.guiConfig = configManager.getGuiConfig(); // Get the latest loaded config
        plugin.getLogger().info("Reloading GUI configuration items...");
        msg.debug("GUI configuration reloaded.");
    }

    // --- openKitroomMain (Category Selection) ---
    public void openKitroomMainMenu(Player player) {
        int size = 45; // 5 rows typically
        Component title = getGuiTitle("kitroom_main", "&8Kitroom - Categories");
        Inventory gui = Bukkit.createInventory(this, size, title);

        // Fillers
        ItemStack filler = loadGuiItem("items.kitroom_main.filler", Material.GRAY_STAINED_GLASS_PANE, " ", null);
        if (filler != null) {
            for (int i = 0; i < size; i++) {
                gui.setItem(i, filler.clone());
            }
        }

        // Back Button
        ItemStack backButton = loadGuiItemWithAction("items.kitroom_main.back_button", "back_to_main", Material.BARRIER, "&cBack", List.of("Return to Main Menu."));
        gui.setItem(getSlot("items.kitroom_main.back_button.slot", 36), backButton);

        // Category Buttons
        List<String> categoryNames = kitroomManager.getCategoryNames(); // Get ordered list
        int numCategories = categoryNames.size();
        int slotsPerRow = 9;
        int totalWidth = numCategories; // Assume 1 slot per button for now

        // --- Dynamic Centering Logic (Simplified) ---
        // Calculate available slots in the middle 3 rows (9 to 35)
        int availableSlots = 27;
        int startSlot;
        int rowCount;

        if (numCategories <= slotsPerRow) { // 1 row needed
            rowCount = 1;
            startSlot = 18 + (slotsPerRow - totalWidth) / 2; // Center in row 2 (slots 18-26)
        } else if (numCategories <= slotsPerRow * 2) { // 2 rows needed
            rowCount = 2;
            // Center across rows 1 and 2 (slots 9-26)
            startSlot = 9 + (slotsPerRow - (int)Math.ceil((double)totalWidth / rowCount)) / 2;
        } else { // 3 rows needed
            rowCount = 3;
            // Center across rows 1, 2, 3 (slots 9-35)
             startSlot = 9 + (slotsPerRow - (int)Math.ceil((double)totalWidth / rowCount)) / 2;
        }
        // Ensure startSlot doesn't go negative if calculation is weird
        if(startSlot < 9) startSlot = 9;
        // --- End Dynamic Centering ---

        int currentSlot = startSlot;
        int currentCategoryIndex = 0;

        for (String categoryName : categoryNames) {
            // Calculate the row we should be on based on index and categories per row
            int targetRow = currentCategoryIndex / slotsPerRow;
            // Calculate the column within that row
            int targetCol = currentCategoryIndex % slotsPerRow;
            // Calculate the dynamic slot based on target row (0, 1, or 2 offset from first row) and centered starting column
            // This dynamic calculation is complex and prone to error, let's first prioritize config slots

            // Load per-category override or default appearance
            ItemStack categoryItem = loadCategoryButton(categoryName); // This uses correct paths now

            // Apply placeholders
            String formattedCategoryName = capitalize(categoryName);
            Map<String, String> placeholders = Map.of("{category_name}", formattedCategoryName);
            replaceItemPlaceholders(categoryItem, placeholders);

            // Set action and category data (CRITICAL for button function)
            setItemActionAndCategory(categoryItem, "open_kitroom_category", categoryName);
            msg.debug("Set action 'open_kitroom_category' and category '" + categoryName + "' for item: " + categoryItem.getType());

            // Determine slot from config override first
            String overridePath = "items.kitroom_main.category_overrides." + categoryName.toLowerCase();
            int configSlot = -1;
            if (guiConfig.isConfigurationSection(overridePath)) {
                configSlot = guiConfig.getInt(overridePath + ".slot", -1);
            }

            if (configSlot >= 0 && configSlot < size) { // Use config slot if valid
                gui.setItem(configSlot, categoryItem);
                msg.debug("Placed category '" + categoryName + "' in config slot: " + configSlot);
            } else {
                // Fallback to dynamic placement (less reliable for specific layouts)
                // Make sure dynamic slot is within the desired rows (9-35)
                if (currentSlot >= 9 && currentSlot <= 35) {
                     gui.setItem(currentSlot, categoryItem);
                     msg.debug("Placed category '" + categoryName + "' in dynamic slot: " + currentSlot);
                } else {
                     msg.debug("Skipped placing category '" + categoryName + "' - dynamic slot " + currentSlot + " is outside range 9-35.");
                }
                // Increment dynamic placement counters
                currentCategoryIndex++;
                currentSlot = startSlot + currentCategoryIndex; // Simple linear placement for fallback
            }
        }

        clearEditingStates(player.getUniqueId(), "openKitroomMainMenu");
        player.openInventory(gui);
        msg.debug("Opened Kitroom Main Menu for " + player.getName());
    }


    private ItemStack loadCategoryButton(String categoryName) {
        String overridePath = "items.kitroom_main.category_overrides." + categoryName.toLowerCase();
        msg.debug("Checking for category override at path: " + overridePath); // Debug path
        if (guiConfig.isConfigurationSection(overridePath)) {
            msg.debug("Found override for " + categoryName + ". Loading from override path.");
            return loadGuiItem(overridePath, Material.CHEST, "&b{category_name}", List.of("&7View {category_name}"));
        } else {
            msg.debug("No override for " + categoryName + ". Loading from default path 'items.kitroom_main.category_button'.");
            return loadGuiItem("items.kitroom_main.category_button", Material.CHEST, "&b{category_name}", List.of("&7View {category_name}"));
        }
    }

    // --- openKitroomCategory (Item View) ---
    public void openKitroomCategory(Player player, String categoryName) {
        Map<Integer, ItemStack> items = kitroomManager.getCategoryItems(categoryName);
        int size = 54; // 6 rows
        Component title = getGuiTitle("kitroom_category", "&8Kitroom - {category}").replaceText(config -> config.matchLiteral("{category}").replacement(capitalize(categoryName)));
        Inventory gui = Bukkit.createInventory(this, size, title);

        // Load items into the top 5 rows (slots 0-44)
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            int slot = entry.getKey();
            if (slot >= 0 && slot < 45) { // Only place in the item area
                gui.setItem(slot, entry.getValue().clone()); // Place clones
            }
        }

        // Fill bottom row (45-53) with filler initially
        ItemStack filler = loadGuiItem("items.kitroom_category.filler", Material.BLACK_STAINED_GLASS_PANE, "&r", null);
        for (int i = 45; i < size; i++) gui.setItem(i, filler.clone());

        // Add Back button
        ItemStack backButton = loadGuiItemWithAction("items.kitroom_category.back_button", "open_kitroom_main", Material.BARRIER, "&c⬅ Bᴀᴄᴋ", List.of("&7Return to Categories"));
        gui.setItem(getSlot("items.kitroom_category.back_button.slot", 45), backButton); // Ensure this loads correctly

        // Add Save button - Only visible to admins
        if (player.hasPermission("tkits.kitroom.admin")) {
            ItemStack saveButton = loadGuiItemWithAction("items.kitroom_category.save_button", "save_kitroom_category", Material.WRITABLE_BOOK, "&a💾 Sᴀᴠᴇ Cᴀᴛᴇɢᴏʀʏ", List.of("&7Save changes to this category"));
            setItemActionAndCategory(saveButton, "save_kitroom_category", categoryName);
            gui.setItem(getSlot("items.kitroom_category.save_button.slot", 53), saveButton);
        }

        // --- State Management ---
        clearEditingStates(player.getUniqueId(), "openKitroomCategory");
        editingKitroomCategory.put(player.getUniqueId(), categoryName);
        player.openInventory(gui);
        plugin.getLogger().fine("[T-Kits] Opened Kitroom category '" + categoryName + "' for player: " + player.getName());
    }

    @Override
    public Inventory getInventory() {
        return null;
    }


    // --- openMainMenu ---
    public void openMainMenu(Player player) {
        PlayerData playerData = plugin
            .getPlayerDataManager()
            .getPlayerData(player);
        if (playerData == null) {
            msg.sendMessage(player, "data_loading_please_wait");
            return;
        }
        int size = 45; // 5 rows
        Component title = getGuiTitle("main_menu", "&8[&aT-Kɪᴛs&8] &fMᴀɪɴ Mᴇɴᴜ");
        Inventory gui = Bukkit.createInventory(this, size, title);

        // Fill top and bottom rows completely first
        ItemStack filler = loadGuiItem("items.main_menu.filler", Material.GRAY_STAINED_GLASS_PANE, "&r", null);
        for (int i = 0; i < 9; i++) gui.setItem(i, filler.clone()); // Top row
        for (int i = 36; i < 45; i++) gui.setItem(i, filler.clone()); // Bottom row

        // Kit Buttons (Slots 19-25 / Row 3) <<< UPDATED START SLOT
        int kitButtonSlot = 19; // Changed from 10 to 19
        for (int i = 1; i <= 7; i++) {
            Kit kit = playerData.getKit(i);
            boolean exists = kit != null;
            String configPath = exists ? "items.main_menu.kit_button_available" : "items.main_menu.kit_button_empty";
            Material defaultMat = exists ? Material.LIME_SHULKER_BOX : Material.GREEN_SHULKER_BOX;
            String defaultName = (exists ? "&a" : "&7") + "ᴋɪᴛ {kit_number}";
            List<String> defaultLore = exists ? List.of("&e▸ Click to Edit", "&7(Shift+Click to Load)") : List.of("&e▸ Click to Create", "&7(Shift+Click to Load)");

            ItemStack item = loadGuiItem(configPath, defaultMat, defaultName, defaultLore, Map.of("{kit_number}", String.valueOf(i)));

            // Dynamically add/remove global/private lore lines based on config and kit status
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasLore()) {
                List<Component> currentLore = meta.lore();
                if (currentLore != null) {
                    List<Component> finalLore = new ArrayList<>();
                    String globalLineConfig = guiConfig.getString(configPath + ".lore_global", "&b🌍 ɢʟᴏʙᴀʟ");
                    String privateLineConfig = guiConfig.getString(configPath + ".lore_private", "&7🔒 ᴘʀɪᴠᴀᴛᴇ");
                    Component globalLine = msg.deserialize(globalLineConfig);
                    Component privateLine = msg.deserialize(privateLineConfig);

                    if (exists) {
                        finalLore.add(kit.isGlobal() ? globalLine : privateLine);
                    }
                    for (Component line : currentLore) {
                        if (!line.equals(globalLine) && !line.equals(privateLine)) {
                            finalLore.add(line);
                        }
                    }
                    meta.lore(finalLore);
                    item.setItemMeta(meta);
                }
            }

            setItemActionAndKitNum(item, "edit_kit", i);
            // Ensure placement doesn't go beyond intended slots (19-25)
            if (kitButtonSlot >= 19 && kitButtonSlot <= 25) {
                gui.setItem(kitButtonSlot++, item);
            }
        }
         // Fill remaining slots in the kit button row if fewer than 7 kits are displayed (e.g., 19-25)
         ItemStack kitRowFiller = loadGuiItem("items.main_menu.filler", Material.GRAY_STAINED_GLASS_PANE, "&r", null); // Use same filler
         for (int i = kitButtonSlot; i <= 25; i++) {
             gui.setItem(i, kitRowFiller.clone());
         }
         // Also fill row 2 (slots 10-17) which is now empty
         for (int i = 10; i <= 17; i++) {
             gui.setItem(i, kitRowFiller.clone());
         }


        // Bottom Row Buttons (Row 5 / Slots 36-44) - Paths already fixed
        ItemStack kitroom = loadGuiItemWithAction("items.main_menu.kitroom_button", "open_kitroom_main", Material.ENDER_CHEST, "&b<0xF0><0x9F><0x9B><0x8F>️ Kɪᴛʀᴏᴏᴍ", List.of("&7Access the server's item repository."));
        gui.setItem(getSlot("items.main_menu.kitroom_button.slot", 38), kitroom);

        ItemStack info = loadGuiItemWithAction("items.main_menu.info_button", "show_info", Material.BOOK, "&e<0xE2><0x84><0xB9>️ Iɴғᴏ", List.of("&7Plugin Version: &f{version}", "&7Author: &fTakeda_Dev"), Map.of("{version}", plugin.getDescription().getVersion()));
        gui.setItem(getSlot("items.main_menu.info_button.slot", 40), info);

        ItemStack globalKits = loadGuiItemWithAction("items.main_menu.global_kits_button", "open_global_kits", Material.BEACON, "&d<0xF0><0x9F><0x97><0xBA>️ Gʟᴏʙᴀʟ Kɪᴛs", List.of("&7Browse kits shared publicly", "&7by other players."));
        gui.setItem(getSlot("items.main_menu.global_kits_button.slot", 42), globalKits);

        // --- State Management ---
        clearEditingStates(player.getUniqueId(), "openMainMenu");
        player.openInventory(gui);
        plugin.getLogger().fine("[T-Kits] Opened main menu for player: " + player.getName());
    }

    // --- openKitEditor (Paths already fixed previously) ---
    public void openKitEditor(Player player, int kitNumber) {
        PlayerData playerData = plugin
            .getPlayerDataManager()
            .getPlayerData(player);
        if (playerData == null) {
             msg.sendMessage(player, "data_loading_please_wait");
             return;
        }
        int maxKits = plugin.getConfigManager().getMainConfig().getInt("kits.max_kits_per_player", 7);
        if (kitNumber < 1 || kitNumber > maxKits) {
            msg.sendMessage(player, "invalid_kit_number", "max_kits", String.valueOf(maxKits));
            return;
        }
        Kit kit = playerData.getKit(kitNumber); // Can be null if creating new
        Component title = getGuiTitle("kit_editor", "&8[&aT-Kɪᴛs&8] ✏️ &fᴋɪᴛ ᴇᴅɪᴛᴏʀ - Kɪᴛ {kit_number}")
            .replaceText(config -> config.matchLiteral("{kit_number}").replacement(String.valueOf(kitNumber)));
        Inventory gui = Bukkit.createInventory(this, 54, title); // 6 rows

        // --- Load Kit Contents (Slots 0-40) --- remains same
        if (kit != null && kit.getContents() != null) {
            kit.getContents().getItems().forEach((slot, item) -> {
                    int editorSlot = KitManager.mapPlayerInvSlotToEditorSlot(slot);
                    if (editorSlot != -1 && editorSlot <= 40) {
                        gui.setItem(editorSlot, item.clone());
                    }
                });
        }

        // Fill item area background (Slots 0-40)
        ItemStack itemAreaFiller = loadGuiItem("items.kit_editor.item_area_filler", Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&r", null);
        for (int i = 0; i <= 40; i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, itemAreaFiller.clone());
            }
        }

        // --- Top Button Row (Slots 41-44) ---
        ItemStack back = loadGuiItemWithAction("items.kit_editor.back_button", "open_main_menu", Material.BARRIER, "&c⬅ Bᴀᴄᴋ", List.of("&7Return to the main menu.", "&7(Saves kit if auto-save is enabled)"));
        gui.setItem(getSlot("items.kit_editor.back_button.slot", 41), back);

        ItemStack loadInv = loadGuiItemWithAction("items.kit_editor.load_inventory_button", "load_current_inventory", Material.HOPPER_MINECART, "&b<0xF0><0x9F><0x95><0x8E> Lᴏᴀᴅ Cᴜʀʀᴇɴᴛ Iɴᴠᴇɴᴛᴏʀʏ", List.of("&7Copies your current inventory,", "&7armor, and offhand into the editor."));
        gui.setItem(getSlot("items.kit_editor.load_inventory_button.slot", 42), loadInv);

        ItemStack save = loadGuiItemWithAction("items.kit_editor.save_kit_button", "save_kit", Material.NETHERITE_INGOT, "&a<0xE2><0x9C><0x94> Sᴀᴠᴇ Kɪᴛ", List.of("&7Saves the current layout", "&7as Kit {kit_number}"), Map.of("{kit_number}", String.valueOf(kitNumber)));
        replaceItemPlaceholders(save, Map.of("{kit_number}", String.valueOf(kitNumber)));
        gui.setItem(getSlot("items.kit_editor.save_kit_button.slot", 43), save);

        ItemStack editEChest = loadGuiItemWithAction("items.kit_editor.edit_echest_button", "edit_enderchest", Material.ENDER_CHEST, "&5<0xF0><0x9F><0xAA><0xA0> Eᴅɪᴛ Eɴᴅᴇʀ Cʜᴇsᴛ", List.of("&7Opens the editor for this kit's", "&7linked Ender Chest contents."));
        gui.setItem(getSlot("items.kit_editor.edit_echest_button.slot", 44), editEChest);

        // --- Bottom Button Row (Slots 45-53) ---
        ItemStack bottomFiller = loadGuiItem("items.kit_editor.button_area_filler", Material.GRAY_STAINED_GLASS_PANE, "&r", null);
        for (int i = 45; i < 54; i++) gui.setItem(i, bottomFiller.clone()); // Pre-fill bottom row

        ItemStack clear = loadGuiItemWithAction("items.kit_editor.clear_kit_button", "clear_kit_confirm", Material.BARRIER, "&c<0xE2><0x9D><0x8C> Cʟᴇᴀʀ Kɪᴛ", List.of("&4&lWarning:&r &cDeletes this kit permanently!", "&7Requires confirmation click."));
        gui.setItem(getSlot("items.kit_editor.clear_kit_button.slot", 47), clear);

        ItemStack repair = loadGuiItemWithAction("items.kit_editor.repair_items_button", "repair_kit_items", Material.DIAMOND_PICKAXE, "&6<0xE2><0x9A><0x92> Rᴇᴘᴀɪʀ Iᴛᴇᴍs", List.of("&7Repairs all damageable items", "&7in this kit's layout."));
        gui.setItem(getSlot("items.kit_editor.repair_items_button.slot", 48), repair);

        ItemStack share = loadGuiItemWithAction("items.kit_editor.share_kit_button", "share_kit", Material.WRITTEN_BOOK, "&e<0xF0><0x9F><0x94><0x97> Sʜᴀʀᴇ Kɪᴛ", List.of("&7Generates a temporary code", "&7to share this kit with others."));
        gui.setItem(getSlot("items.kit_editor.share_kit_button.slot", 49), share);

        ItemStack importKit = loadGuiItemWithAction("items.kit_editor.import_kit_button", "import_kit_anvil", Material.ENDER_EYE, "&d<0xE2><0xAC><0x87> Iᴍᴘᴏʀᴛ Kɪᴛ", List.of("&7Import a kit using a share code.", "&cOverwrites this kit slot!&r"));
        replaceItemPlaceholders(importKit, Map.of("{kit_number}", String.valueOf(kitNumber)));
        gui.setItem(getSlot("items.kit_editor.import_kit_button.slot", 50), importKit);

        boolean isGlobal = kit != null && kit.isGlobal();
        String togglePath = isGlobal ? "items.kit_editor.set_private_button" : "items.kit_editor.set_global_button";
        Material toggleDefaultMat = isGlobal ? Material.BEACON : Material.BARRIER;
        String toggleDefaultName = isGlobal ? "&b<0xF0><0x9F><0x97><0xBA>️ Sᴇᴛ Pʀɪᴠᴀᴛᴇ" : "&a<0xF0><0x9F><0x94><0x92> Sᴇᴛ Gʟᴏʙᴀʟ";
        List<String> toggleDefaultLore = isGlobal ? List.of("&7Makes this kit private,", "&7only you can load it.") : List.of("&7Makes this kit public,", "&7allowing others to browse it.");
        ItemStack toggleGlobal = loadGuiItem(togglePath, toggleDefaultMat, toggleDefaultName, toggleDefaultLore);
        replaceItemPlaceholders(toggleGlobal, Map.of("{kit_number}", String.valueOf(kitNumber)));
        setItemAction(toggleGlobal, isGlobal ? "set_kit_private" : "set_kit_global");
        gui.setItem(getSlot(togglePath + ".slot", 51), toggleGlobal);

        // --- State Management ---
        clearEditingStates(player.getUniqueId(), "openKitEditor");
        editingKit.put(player.getUniqueId(), kitNumber);
        plugin.getLogger().fine("[T-Kits DEBUG] Set editingKit state for " + player.getName() + " to " + kitNumber);

        player.openInventory(gui);
    }

    // --- Other GUI opening methods (openEnderChestEditor, openConfirmationGUI, etc.) ---
    // Paths were already fixed in the previous response for these methods. No changes needed here for the current issues.

    public void openEnderChestEditor(Player player, int mainKitNumber) {
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if (playerData == null) { msg.sendMessage(player, "error"); return; }
        Kit mainKit = playerData.getKit(mainKitNumber);
        if (mainKit == null) {
            msg.sendMessage(player, "kit_not_found", "kit_number", String.valueOf(mainKitNumber));
            msg.playSound(player, "error");
             openMainMenu(player);
            return;
        }
        Component title = getGuiTitle("enderchest_editor", "&8[&5T-Kɪᴛs&8] 📦 &fEᴄʜᴇsᴛ Eᴅɪᴛᴏʀ - Kɪᴛ {kit_number}")
            .replaceText(config -> config.matchLiteral("{kit_number}").replacement(String.valueOf(mainKitNumber)));
        Inventory gui = Bukkit.createInventory(this, 36, title); // 4 rows
        KitContents echestContents = mainKit.getEnderChestContents();
        if (echestContents != null && echestContents.getItems() != null) {
            echestContents.getItems().forEach((slot, item) -> {
                    if (slot >= 0 && slot < 27) gui.setItem(slot, item.clone()); // Slots 0-26
                });
        }
        ItemStack filler = loadGuiItem("items.echest_editor.filler", Material.BLACK_STAINED_GLASS_PANE, "&r", null);
        for (int i = 27; i < 36; i++) gui.setItem(i, filler.clone()); // Fill bottom row

        ItemStack back = loadGuiItemWithAction("items.echest_editor.back_button", "back_to_kit_editor", Material.BARRIER, "&c⬅ Bᴀᴄᴋ", List.of("&7Return to the main kit editor."));
        gui.setItem(getSlot("items.echest_editor.back_button.slot", 27), back);

        ItemStack save = loadGuiItemWithAction("items.echest_editor.save_button", "save_enderchest", Material.WRITABLE_BOOK, "&a💾 Sᴀᴠᴇ Eɴᴅᴇʀ Cʜᴇsᴛ", List.of("&7Saves this Ender Chest layout."));
        gui.setItem(getSlot("items.echest_editor.save_button.slot", 31), save);

        UUID uuid = player.getUniqueId();
        Kit existingKitContext = editingEnderChest.get(uuid);
        clearEditingStates(uuid, "openEnderChestEditor");
        if (existingKitContext != null) {
            editingKit.put(uuid, existingKitContext.getKitNumber());
        }
        editingKit.putIfAbsent(uuid, mainKitNumber);
        editingEnderChest.put(uuid, mainKit.toBuilder().build());
        plugin.getLogger().fine("[T-Kits DEBUG] Set editingEnderChest state for " + player.getName() + " (Kit " + mainKitNumber + ")");

        player.openInventory(gui);
    }

    public void openConfirmationGUI(
        Player player,
        String actionToConfirm,
        int kitNumber,
        Component title,
        Component confirmText,
        Component cancelText
    ) {
        Inventory gui = Bukkit.createInventory(this, 27, title);
        ItemStack filler = loadGuiItem("items.confirmation.filler", Material.GRAY_STAINED_GLASS_PANE, "&r", null);
        for (int i = 0; i < 27; i++) gui.setItem(i, filler.clone());

        ItemStack confirm = loadGuiItem("items.confirmation.confirm_button", Material.LIME_TERRACOTTA, "&a✔️ Cᴏɴғɪʀᴍ", null);
        ItemMeta confirmMeta = confirm.getItemMeta();
        if (confirmMeta != null) {
            confirmMeta.lore(List.of(confirmText.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)));
            PersistentDataContainer pdc = confirmMeta.getPersistentDataContainer();
            pdc.set(actionKey, PersistentDataType.STRING, actionToConfirm);
            if (kitNumber > 0) pdc.set(kitNumberKey, PersistentDataType.INTEGER, kitNumber);
            confirm.setItemMeta(confirmMeta);
        }
        gui.setItem(getSlot("items.confirmation.confirm_button.slot", 11), confirm);

        ItemStack cancel = loadGuiItem("items.confirmation.cancel_button", Material.RED_TERRACOTTA, "&c❌ Cᴀɴᴄᴇʟ", null);
        ItemMeta cancelMeta = cancel.getItemMeta();
        if (cancelMeta != null) {
            cancelMeta.lore(List.of(cancelText.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)));
            cancel.setItemMeta(cancelMeta);
        }
        setItemAction(cancel, "cancel_confirmation");
        gui.setItem(getSlot("items.confirmation.cancel_button.slot", 15), cancel);

        player.openInventory(gui);
    }

    public void openGlobalKitsList(Player player, int page) {
        List<Kit> globalKits = plugin.getKitManager().getAllGlobalKits();
        if (globalKits.isEmpty()) {
            msg.sendMessage(player, "no_global_kits_available");
            msg.playSound(player, "error");
            return;
        }
        int itemsPerPage = 36;
        int totalPages = (int) Math.ceil((double) globalKits.size() / itemsPerPage);
        final int finalPage = Math.max(1, Math.min(page, totalPages));

        playerGlobalKitPages.put(player.getUniqueId(), finalPage);

        int startIndex = (finalPage - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, globalKits.size());
        List<Kit> kitsOnPage = globalKits.subList(startIndex, endIndex);
        Component title = getGuiTitle("global_kits", "&8[&d<0xE2><0x9C><0xB0>&8] &fGʟᴏʙᴀʟ Kɪᴛs &8(Page {page}/{total_pages})")
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
            String nameFormat = guiConfig.getString("items.global_kits_list.player_head.name", "&bKit {kit_number} &7by &e{owner}");
            List<String> loreFormat = guiConfig.getStringList("items.global_kits_list.player_head.lore");
            if (loreFormat.isEmpty()) loreFormat = List.of("&7Owner: &f{owner_uuid}", "", "&e▸ Click to Preview");

            Component name = msg.deserialize(nameFormat.replace("{kit_number}", String.valueOf(kit.getKitNumber())).replace("{owner}", ownerName));
            List<Component> lore = loreFormat.stream()
                .map(line -> line.replace("{owner_uuid}", owner.getUniqueId().toString()))
                .map(msg::deserialize)
                .collect(Collectors.toList());

            ItemStack item = GuiUtils.createPlayerHead(owner, name, lore);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.set(actionKey, PersistentDataType.STRING, "preview_global_kit");
                pdc.set(kitNumberKey, PersistentDataType.INTEGER, kit.getKitNumber());
                pdc.set(kitOwnerKey, PersistentDataType.STRING, kit.getOwner().toString());
                item.setItemMeta(meta);
            }
            gui.setItem(currentSlot++, item);
        }
        ItemStack itemAreaFiller = loadGuiItem("items.global_kits_list.item_area_filler", Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&r", null);
        for (int i = currentSlot; i < itemsPerPage; i++) gui.setItem(i, itemAreaFiller.clone());

        ItemStack back = loadGuiItemWithAction("items.global_kits_list.back_button", "open_main_menu", Material.BARRIER, "&c⬅ Bᴀᴄᴋ", List.of("&7Return to the main menu."));
        gui.setItem(getSlot("items.global_kits_list.back_button.slot", 45), back);

        if (finalPage > 1) {
            ItemStack prev = loadGuiItemWithAction("items.global_kits_list.previous_page_button", "global_kits_page", Material.ARROW, "&e« Pʀᴇᴠɪᴏᴜs Pᴀɢᴇ", List.of("&7Go to page {page}"), Map.of("{page}", String.valueOf(finalPage - 1)));
            setItemActionAndPage(prev, "global_kits_page", finalPage - 1);
            gui.setItem(getSlot("items.global_kits_list.previous_page_button.slot", 48), prev);
        }

        ItemStack pageInfo = loadGuiItem("items.global_kits_list.page_info_item", Material.PAPER, "&fPage &e{page}&f/&e{total_pages}", null, Map.of("{page}", String.valueOf(finalPage), "{total_pages}", String.valueOf(totalPages)));
        gui.setItem(getSlot("items.global_kits_list.page_info_item.slot", 49), pageInfo);

        if (finalPage < totalPages) {
            ItemStack next = loadGuiItemWithAction("items.global_kits_list.next_page_button", "global_kits_page", Material.ARROW, "&eNᴇxᴛ Pᴀɢᴇ »", List.of("&7Go to page {page}"), Map.of("{page}", String.valueOf(finalPage + 1)));
            setItemActionAndPage(next, "global_kits_page", finalPage + 1);
            gui.setItem(getSlot("items.global_kits_list.next_page_button.slot", 50), next);
        }

        clearEditingStates(player.getUniqueId(), "openGlobalKitsList");
        player.openInventory(gui);
    }

    public void openKitPreview(Player player, Kit kitToPreview) {
        if (kitToPreview == null) {
             msg.sendMessage(player, "error");
             openGlobalKitsList(player, playerGlobalKitPages.getOrDefault(player.getUniqueId(), 1));
             return;
        }
        OfflinePlayer owner = Bukkit.getOfflinePlayer(kitToPreview.getOwner());
        String ownerName = owner.getName() != null ? owner.getName() : "Unknown";
        Component title = getGuiTitle("kit_preview", "&8[&9<0xF0><0x9F><0x94><0x8D>&8] &fPʀᴇᴠɪᴇᴡ - Kɪᴛ {kit_number} ({owner})")
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
        ItemStack filler = loadGuiItem("items.kit_preview.filler", Material.BLACK_STAINED_GLASS_PANE, "&r", null);
        for (int i = 41; i < 54; i++) {
             if (gui.getItem(i) == null) gui.setItem(i, filler.clone());
        }

        int backPage = playerGlobalKitPages.getOrDefault(player.getUniqueId(), 1);
        ItemStack back = loadGuiItem("items.kit_preview.back_button", Material.BARRIER, "&c⬅ Bᴀᴄᴋ", List.of("&7Return to Global Kits list."));
        setItemActionAndPage(back, "back_to_global_list", backPage);
        gui.setItem(getSlot("items.kit_preview.back_button.slot", 49), back);

        if (kitToPreview.getEnderChestContents() != null && !kitToPreview.getEnderChestContents().getItems().isEmpty()) {
            ItemStack viewEChest = loadGuiItemWithAction("items.kit_preview.view_echest_button", "preview_echest", Material.ENDER_CHEST, "&5👁️ Vɪᴇᴡ Eɴᴅᴇʀ Cʜᴇsᴛ", List.of("&7View the linked Ender Chest contents."));
            gui.setItem(getSlot("items.kit_preview.view_echest_button.slot", 51), viewEChest);
        }

        clearEditingStates(player.getUniqueId(), "openKitPreview");
        previewingKit.put(player.getUniqueId(), kitToPreview);
        player.openInventory(gui);
    }

    public void openEnderChestPreview(Player player, Kit mainKit) {
        if (mainKit == null || mainKit.getEnderChestContents() == null) {
            msg.sendMessage(player, "error");
             Kit previewCtx = previewingKit.get(player.getUniqueId());
             if (previewCtx != null) { openKitPreview(player, previewCtx); }
             else { openGlobalKitsList(player, playerGlobalKitPages.getOrDefault(player.getUniqueId(), 1)); }
            return;
        }
        OfflinePlayer owner = Bukkit.getOfflinePlayer(mainKit.getOwner());
        String ownerName = owner.getName() != null ? owner.getName() : "Unknown";
        Component title = getGuiTitle("echest_preview", "&8[&5<0xF0><0x9F><0xAA><0xA0>&8] &fEᴄʜᴇsᴛ Pʀᴇᴠɪᴇᴡ - Kɪᴛ {kit_number} ({owner})")
            .replaceText(cfg -> cfg.matchLiteral("{kit_number}").replacement(String.valueOf(mainKit.getKitNumber())))
            .replaceText(cfg -> cfg.matchLiteral("{owner}").replacement(ownerName));
        Inventory gui = Bukkit.createInventory(this, 36, title);
        KitContents echestContents = mainKit.getEnderChestContents();
        if (echestContents.getItems() != null) {
            echestContents.getItems().forEach((slot, item) -> {
                    if (slot >= 0 && slot < 27) gui.setItem(slot, item.clone());
                });
        }
        ItemStack filler = loadGuiItem("items.echest_preview.filler", Material.BLACK_STAINED_GLASS_PANE, "&r", null);
        for (int i = 27; i < 36; i++) {
             if (gui.getItem(i) == null) gui.setItem(i, filler.clone());
        }
        ItemStack back = loadGuiItemWithAction("items.echest_preview.back_button", "back_to_kit_preview", Material.BARRIER, "&c⬅ Bᴀᴄᴋ", List.of("&7Return to Kit Preview."));
        gui.setItem(getSlot("items.echest_preview.back_button.slot", 31), back);

        player.openInventory(gui);
    }

    // --- Anvil GUI (Paths already fixed previously) ---
    public void openImportAnvil(Player player, int targetKitNumber) {
        clearEditingStates(player.getUniqueId(), "openImportAnvil");
        editingKit.put(player.getUniqueId(), targetKitNumber);

        Component title = msg.deserialize("&d📥 ᴇɴᴛᴇʀ ɪᴍᴘᴏʀᴛ ᴄᴏᴅᴇ");
        ItemStack inputItem = new ItemStack(Material.PAPER);
        AtomicBoolean wasClickHandled = new AtomicBoolean(false);

        new AnvilGUI.Builder()
            .plugin(plugin)
            .title(msg.getLegacyColorized(title))
            .text("Enter Code")
            .itemLeft(inputItem)
            .onClick((slot, stateSnapshot) -> {
                if (slot != AnvilGUI.Slot.OUTPUT) return Collections.emptyList();
                wasClickHandled.set(true);
                String code = stateSnapshot.getText().trim();
                Player p = stateSnapshot.getPlayer();
                if (code.length() != plugin.getConfigManager().getShareCodeLength()) { // Use getter
                    msg.playSound(p, "error");
                    return List.of(AnvilGUI.ResponseAction.replaceInputText("Invalid Length"));
                }
                String upperCode = code.toUpperCase();
                msg.sendMessage(p, "importing_kit");
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    Kit importedKit = plugin.getShareCodeManager().redeemShareCode(upperCode);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                         Integer currentEditingKitNum = editingKit.get(p.getUniqueId());
                         if (currentEditingKitNum == null || currentEditingKitNum != targetKitNumber) {
                              plugin.getLogger().warning("Anvil import callback: Player " + p.getName() + " is no longer editing kit " + targetKitNumber);
                              return;
                         }

                        if (importedKit != null) {
                            PlayerData pd = plugin.getPlayerDataManager().getPlayerData(p);
                            if (pd != null) {
                                Kit kitToSave = importedKit.toBuilder()
                                    .kitNumber(targetKitNumber)
                                    .owner(p.getUniqueId())
                                    .global(false)
                                    .enderChestContents(KitContents.deserialize(KitContents.serialize(importedKit.getEnderChestContents())))
                                    .build();
                                pd.setKit(targetKitNumber, kitToSave);
                                plugin.getPlayerDataManager().savePlayerKit(p.getUniqueId(), kitToSave)
                                    .thenRun(() -> {
                                        msg.sendMessage(p, "import_success", "kit_number", String.valueOf(targetKitNumber));
                                        msg.playSound(p, "kit_import_success");
                                        this.openKitEditor(p, targetKitNumber);
                                    })
                                    .exceptionally(ex -> {
                                        msg.sendMessage(p, "import_fail_error");
                                        msg.playSound(p, "error");
                                        plugin.getMessageUtil().logException("Error saving imported kit via Anvil", ex);
                                        pd.removeKit(targetKitNumber);
                                        this.openKitEditor(p, targetKitNumber);
                                        return null;
                                    });
                            } else { msg.sendMessage(p, "error"); this.openKitEditor(p, targetKitNumber); }
                        } else {
                            msg.sendMessage(p, "import_fail_invalid_code");
                            msg.playSound(p, "kit_import_fail");
                            this.openKitEditor(p, targetKitNumber);
                        }
                    });
                });
                 return Collections.emptyList();
            })
            .onClose(stateSnapshot -> {
                if (!wasClickHandled.get()) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        Player pClose = stateSnapshot.getPlayer();
                        if (!pClose.isOnline()) return;
                        // Check if they are not in another GUI already (like the kit editor opened by the click)
                        InventoryView currentView = pClose.getOpenInventory();
                        if (currentView.getType() == InventoryType.CRAFTING || currentView.getTopInventory().getHolder() == null) { // Check if in player inv or similar
                             Integer currentEditing = editingKit.get(pClose.getUniqueId());
                             plugin.getLogger().fine("Anvil closed manually. Current editingKit state: " + currentEditing);
                             if (currentEditing != null) {
                                 this.openKitEditor(pClose, currentEditing);
                             } else {
                                  plugin.getLogger().warning("Anvil closed manually, but no Kit Editor state found. Opening main menu.");
                                  this.openMainMenu(pClose);
                             }
                        }
                    }, 1L);
                }
            })
            .open(player);
    }


    // --- Central Button Action Handler ---
    public void handleButtonAction( Player player, String action, PersistentDataContainer pdc, ClickType clickType, int clickedSlot ) {
        UUID playerUUID = player.getUniqueId();
        Integer editingKitNum = editingKit.get(playerUUID);
        Kit editingEchestKitContext = editingEnderChest.get(playerUUID);
        String editingKitroomCategoryCtx = editingKitroomCategory.get(playerUUID);
        Kit previewingKitCtx = previewingKit.get(playerUUID);

        msg.debug(String.format("handleButtonAction START for %s: action='%s', clickedSlot=%d, editingKit=%s, editingEChest=%s, editingKitroom=%s, previewingKit=%s",
                player.getName(), action, clickedSlot,
                editingKit.getOrDefault(playerUUID, null),
                editingEnderChest.containsKey(playerUUID) ? "YES" : "NO",
                editingKitroomCategory.getOrDefault(playerUUID, null),
                previewingKit.containsKey(playerUUID) ? "YES" : "NO"
        ));

        Integer kitNumFromPDC = pdc.getOrDefault(kitNumberKey, PersistentDataType.INTEGER, -1);
        UUID ownerUUIDFromPDC = null;
        String ownerStr = pdc.get(kitOwnerKey, PersistentDataType.STRING);
        if (ownerStr != null) try { ownerUUIDFromPDC = UUID.fromString(ownerStr); } catch (IllegalArgumentException ignored) {}
        String categoryFromPDC = pdc.get(categoryKey, PersistentDataType.STRING);
        Integer pageFromPDC = pdc.getOrDefault(pageKey, PersistentDataType.INTEGER, 1);

        if (action == null || action.isEmpty()) {
            msg.debug("Received empty or null action in handleButtonAction for player " + player.getName());
            return;
        }

        // --- Action Processing Switch ---
        try { // Wrap main switch in try-catch to handle unexpected errors within actions
            switch (action.toLowerCase()) {
                // --- Main Menu / Global List / Kitroom Main Actions ---
                case "edit_kit":
                    if (kitNumFromPDC > 0) {
                        if (clickType.isShiftClick()) { kitManager.loadKit(player, kitNumFromPDC); player.closeInventory(); }
                        else { this.openKitEditor(player, kitNumFromPDC); }
                    } break;
                case "show_info": msg.sendMessage(player,"info_placeholder","version",plugin.getDescription().getVersion()); player.closeInventory(); break;
                case "open_kitroom_main": this.openKitroomMainMenu(player); break;
                case "open_kitroom_category":
                    msg.debug("Action 'open_kitroom_category' triggered. Category from PDC: " + categoryFromPDC);
                    if (categoryFromPDC != null) {
                         this.openKitroomCategory(player, categoryFromPDC);
                    } else {
                         msg.debug("Category from PDC was null. Calling handleInvalidState.");
                         handleInvalidState(player, action);
                     } break;
                case "open_global_kits": this.openGlobalKitsList(player, 1); break;
                case "global_kits_page": this.openGlobalKitsList(player, pageFromPDC); break; // Next/Prev in list
                case "open_main_menu": this.openMainMenu(player); break; // Back button from some GUIs
                case "back_to_main": this.openMainMenu(player); break; // Explicit back button from Kitroom Main Menu

                // --- Kit Editor Actions (Requires editingKitNum Context) ---
                case "load_current_inventory": if (editingKitNum != null) { this.loadCurrentInventoryToEditor(player); } else { handleInvalidState(player, action); } break;
                case "save_kit": if (editingKitNum != null) { kitManager.saveKit(player, editingKitNum, player.getOpenInventory().getTopInventory()); } else { handleInvalidState(player, action); } break;
                case "clear_kit_confirm": if (editingKitNum != null) { this.handleClearConfirmClick(player, editingKitNum); } else { handleInvalidState(player, action); } break;
                case "repair_kit_items": if (editingKitNum != null) { this.handleRepairKitClick(player, editingKitNum); } else { handleInvalidState(player, action); } break;
                case "share_kit": if (editingKitNum != null) { this.handleShareKit(player, editingKitNum); } else { handleInvalidState(player, action); } break;
                case "import_kit_anvil": if (editingKitNum != null) { this.openImportAnvil(player, editingKitNum); } else { handleInvalidState(player, action); } break;
                case "set_kit_global": case "set_kit_private": if (editingKitNum != null) { this.handleSetGlobal(player, editingKitNum, action.equals("set_kit_global")); } else { handleInvalidState(player, action); } break;
                case "edit_enderchest": if (editingKitNum != null) { this.openEnderChestEditor(player, editingKitNum); } else { handleInvalidState(player, action); } break;

                 // --- Ender Chest Editor Actions (Requires editingEchestKitContext) ---
                case "back_to_kit_editor": if (editingEchestKitContext != null) { this.openKitEditor(player, editingEchestKitContext.getKitNumber()); } else { handleInvalidState(player, action); } break;
                case "save_enderchest": if (editingEchestKitContext != null) { kitManager.saveEnderChestKit(player, editingEchestKitContext.getKitNumber(), player.getOpenInventory().getTopInventory()); } else { handleInvalidState(player, action); } break;

                // --- Confirmation GUI Actions ---
                case "clear_kit_execute": if (kitNumFromPDC > 0) { kitManager.clearKit(player, kitNumFromPDC); this.openMainMenu(player); } else { handleInvalidState(player, action); } break;
                case "cancel_confirmation": Integer returnKit = editingKit.get(playerUUID); if (returnKit != null) { this.openKitEditor(player, returnKit); } else { this.openMainMenu(player); } msg.playSound(player, "confirmation_cancel"); break;

                // --- Global Kit List / Preview Actions ---
                case "preview_global_kit": if (ownerUUIDFromPDC != null && kitNumFromPDC > 0) { this.handlePreviewKit(player, ownerUUIDFromPDC, kitNumFromPDC); } else { handleInvalidState(player, action); } break;
                case "back_to_global_list": this.openGlobalKitsList(player, pdc.getOrDefault(pageKey, PersistentDataType.INTEGER, 1)); break; // Use page from PDC
                case "preview_echest": if (previewingKitCtx != null) { this.openEnderChestPreview(player, previewingKitCtx); } else { handleInvalidState(player, action); } break;
                case "back_to_kit_preview": if (previewingKitCtx != null) { this.openKitPreview(player, previewingKitCtx); } else { handleInvalidState(player, action); } break; // Back from echest preview

                // --- Kitroom Category Actions (Requires editingKitroomCategoryCtx) ---
                case "save_kitroom_category":
                    String categoryToSave = pdc.get(categoryKey, PersistentDataType.STRING);
                    if (categoryToSave != null && player.hasPermission("tkits.kitroom.admin")) {
                        if (!categoryToSave.equalsIgnoreCase(editingKitroomCategoryCtx)) {
                             plugin.getLogger().warning("Save category button clicked (" + categoryToSave + ") but player state indicates they are viewing category: " + editingKitroomCategoryCtx + ". Aborting save.");
                             handleInvalidState(player, action);
                             break;
                        }
                        InventoryView openView = player.getOpenInventory();
                        if (openView == null) break;
                        Inventory inv = openView.getTopInventory();
                        Map<Integer, ItemStack> newItems = new HashMap<>();
                        for (int slot = 0; slot < 45; slot++) {
                            ItemStack item = inv.getItem(slot);
                            if (item != null && item.getType() != Material.AIR) {
                                newItems.put(slot, item.clone());
                            }
                        }
                        kitroomManager.setCategoryItems(categoryToSave, newItems);
                        kitroomManager.saveKitroom();
                        msg.sendActionBar(player, "kitroom_category_saved", "category", capitalize(categoryToSave));
                        msg.playSound(player, "kit_save");
                        this.openKitroomMainMenu(player);
                    } else if (categoryToSave == null) {
                         plugin.getLogger().severe("Save category button clicked but category key was missing from PDC!");
                         handleInvalidState(player, action);
                    } else {
                        msg.sendMessage(player, "no_permission");
                        msg.playSound(player, "error");
                    }
                    break;

                // --- Default ---
                default:
                    msg.debug("Unhandled GUI action: '" + action + "'");
                    msg.sendRawMessage(player, "&cButton action not recognized.", null);
                    break;
            }
        } catch (Exception e) {
             plugin.getMessageUtil().logException("Error handling button action '" + action + "' for player " + player.getName(), e);
             msg.sendMessage(player, "error"); // Send generic error on exception
        }
    }

    // Helper to handle cases where an action is called without the required GUI state
    private void handleInvalidState(Player player, String attemptedAction) {
         msg.debug("Action '" + attemptedAction + "' called but player not in required GUI editing/context state.");
         msg.sendMessage(player, "error"); // Generic error
         openMainMenu(player); // Go back to main menu as a fallback
    }


    // --- GUI Close Handling ---
    public void handleGuiClose(Player player, Inventory closedInventory) {
        UUID playerUUID = player.getUniqueId();
        msg.debug("handleGuiClose called for " + player.getName());

        Integer editingKitNum = editingKit.get(playerUUID);
        ActionConfirmation confirmation = confirmationActions.get(playerUUID);

         if (editingKitNum != null && configManager.isSaveOnEditorClose()) {
             msg.debug("Auto-saving Kit " + editingKitNum + " for " + player.getName() + " on editor close.");
             saveKitFromInventory(player, editingKitNum, closedInventory);
         }

         if (confirmation != null) {
             msg.debug("GUI closed with pending confirmation for " + player.getName() + ". Cancelling confirmation.");
         }

         clearEditingStates(playerUUID, "handleGuiClose");
         msg.debug("Cleared editing states for " + player.getName() + " after GUI close.");

     }

     private void clearEditingStates(UUID playerUUID, String caller) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        String callerName = stackTrace.length > 2 ? stackTrace[2].getMethodName() : "Unknown";
        msg.debug("[T-Kits DEBUG] Clearing GUI states for " + playerUUID + " (Called from: " + caller + " in " + callerName + ")");

        editingKit.remove(playerUUID);
        editingEnderChest.remove(playerUUID);
        editingKitroomCategory.remove(playerUUID);
        previewingKit.remove(playerUUID);
        confirmClickCooldown.remove(playerUUID);
        confirmationActions.remove(playerUUID);
    }


    // --- Helper Method Implementations ---

    private void loadCurrentInventoryToEditor(Player player) {
        PlayerInventory pInv = player.getInventory();
        InventoryView openInvView = player.getOpenInventory();
        if(openInvView == null) return;
        Inventory editorInv = openInvView.getTopInventory();

        if (!isKitEditorInventory(editorInv)) {
            plugin.getLogger().warning("[T-Kits] loadCurrentInventoryToEditor called but player is not viewing the Kit Editor GUI.");
            return;
        }

        plugin.getLogger().fine("[T-Kits] Loading player inventory into Kit Editor for " + player.getName());
        for (int i = 0; i < 41; i++) {
            ItemStack item = pInv.getItem(i);
            int editorSlot = KitManager.mapPlayerInvSlotToEditorSlot(i);
            if (editorSlot != -1 && editorSlot <= 40) {
                editorInv.setItem(editorSlot, item == null ? null : item.clone());
            }
        }
        msg.sendActionBar(player, "inventory_loaded_to_gui");
        msg.playSound(player, "gui_click");
    }

    private void handleClearConfirmClick(Player player, int kitNum) {
        boolean requireConfirm = plugin.getConfigManager().isClearKitRequiresConfirmation();

        if (!requireConfirm) {
            plugin.getLogger().fine("[T-Kits] Clear confirmation disabled, executing clear directly for kit " + kitNum);
            kitManager.clearKit(player, kitNum);
            this.openMainMenu(player);
            return;
        }

        long now = System.currentTimeMillis();
        long lastClick = confirmClickCooldown.getOrDefault(player.getUniqueId(), 0L);
        long requiredDelay = 500;
        if (now - lastClick > requiredDelay) {
            confirmClickCooldown.put(player.getUniqueId(), now);
            msg.sendActionBar(player, "clear_confirmation_required", "kit_number", String.valueOf(kitNum));
            msg.playSound(player, "error");
        } else {
            confirmClickCooldown.remove(player.getUniqueId());
            Component title = getGuiTitle("confirmation", "&8[&4!&8] &cCᴏɴғɪʀᴍᴀᴛɪᴏɴ")
                                .replaceText(cfg -> cfg.matchLiteral("{kit_number}").replacement(String.valueOf(kitNum)));
            Component confirmText = msg.deserialize("&ᴄᴅᴇʟᴇᴛᴇ ᴋɪᴛ " + kitNum + " ᴘᴇʀᴍᴀɴᴇɴᴛʟʏ.");
            Component cancelText = msg.deserialize("&ᴀᴋᴇᴇᴘ ᴛʜᴇ ᴋɪᴛ.");
            this.openConfirmationGUI(player, "clear_kit_execute", kitNum, title, confirmText, cancelText);
            msg.playSound(player, "confirmation_open");
        }
    }

    private void handleRepairKitClick(Player player, int kitNum) {
        InventoryView openInvView = player.getOpenInventory();
        if(openInvView == null) return;
        Inventory editorInv = openInvView.getTopInventory();
        if (!isKitEditorInventory(editorInv)) { return; }

        Kit kitFromEditor = createKitFromEditor(player, kitNum, editorInv);
        if (kitFromEditor == null) { msg.sendMessage(player, "error"); return; }

        boolean repaired = kitManager.repairKitItems(kitFromEditor);

        if (repaired) {
            kitFromEditor.getContents().getItems().forEach((slot, item) -> {
                int editorSlot = KitManager.mapPlayerInvSlotToEditorSlot(slot);
                if (editorSlot != -1 && editorSlot <= 40) { editorInv.setItem(editorSlot, item.clone()); }
            });
            msg.sendActionBar(player, "kit_repaired", "kit_number", String.valueOf(kitNum));
            msg.playSound(player, "repair_success");
        } else {
            msg.sendActionBar(player, "kit_repair_failed", "kit_number", String.valueOf(kitNum));
            msg.playSound(player, "repair_fail");
        }
    }

    private void handleShareKit(Player player, int kitNum) {
        InventoryView openInvView = player.getOpenInventory();
        if(openInvView == null) return;
        Inventory editorInv = openInvView.getTopInventory();
        if (!isKitEditorInventory(editorInv)) { return; }

        Kit kitToShare = createKitFromEditor(player, kitNum, editorInv);
        if(kitToShare == null) { msg.sendActionBar(player, "error"); return; }

        msg.sendActionBar(player, "generating_share_code");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                String shareCode = plugin.getShareCodeManager().generateShareCodeFromKit(player.getUniqueId(), kitToShare);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (shareCode != null) {
                        long expiry = plugin.getConfigManager().getMainConfig().getLong("sharing.code_expiration_minutes", 5);
                        msg.sendMessage(player, "share_code_generated", "kit_number", String.valueOf(kitNum), "code", shareCode, "time", String.valueOf(expiry));
                        msg.playSound(player, "kit_share");
                        player.closeInventory();
                    } else {
                        msg.sendActionBar(player, "error");
                    }
                });
            });
    }

    private void handleSetGlobal(Player player, int kitNum, boolean targetGlobal) {
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if(playerData == null) { msg.sendMessage(player, "error"); return; }
        Kit savedKit = playerData.getKit(kitNum);

        if (savedKit == null) {
             msg.sendMessage(player, targetGlobal ? "cannot_set_unsaved_global" : "must_save_kit_first");
             if (targetGlobal) msg.playSound(player, "error");
             return;
         }

         kitManager.setKitGlobalStatus(player, kitNum, targetGlobal)
             .thenAcceptAsync(success -> {
                 if (Boolean.TRUE.equals(success)) {
                     Integer currentEditing = editingKit.get(player.getUniqueId());
                     if (currentEditing != null && currentEditing == kitNum) {
                        this.openKitEditor(player, kitNum);
                     }
                 }
             }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin));
    }

    private void handlePreviewKit(Player player, UUID ownerUUID, int kitNum) {
        Kit cachedKit = kitManager.getGlobalKit(ownerUUID, kitNum);
        if (cachedKit != null && cachedKit.isGlobal()) {
             plugin.getLogger().fine("[T-Kits DEBUG] handlePreviewKit: Found kit " + ownerUUID + "_" + kitNum + " in global cache. Opening preview.");
            this.openKitPreview(player, cachedKit);
            return;
        }
        msg.sendRawMessage(player, "&e⏳ Loading kit preview...", null);
        plugin.getApi().getPlayerKitAsync(ownerUUID, kitNum)
            .thenAcceptAsync(optionalKit -> {
                if (optionalKit.isPresent()) {
                    Kit loadedKit = optionalKit.get();
                    if (loadedKit.isGlobal()) {
                        plugin.getLogger().fine("[T-Kits DEBUG] handlePreviewKit: Loaded kit " + ownerUUID + "_" + kitNum + ". Caching and opening preview.");
                        kitManager.addGlobalKitToCache(loadedKit);
                        this.openKitPreview(player, loadedKit);
                    } else {
                        plugin.getLogger().warning("[T-Kits DEBUG] handlePreviewKit: Loaded kit " + ownerUUID + "_" + kitNum + " is not global.");
                        msg.sendMessage(player, "kit_no_longer_global");
                        msg.playSound(player, "error");
                        kitManager.removeGlobalKitFromCache(ownerUUID, kitNum);
                        this.openGlobalKitsList(player, playerGlobalKitPages.getOrDefault(player.getUniqueId(), 1));
                    }
                } else {
                     plugin.getLogger().warning("[T-Kits DEBUG] handlePreviewKit: Kit " + ownerUUID + "_" + kitNum + " not found via API.");
                     msg.sendMessage(player, "kit_no_longer_global");
                     msg.playSound(player, "error");
                     kitManager.removeGlobalKitFromCache(ownerUUID, kitNum);
                     this.openGlobalKitsList(player, playerGlobalKitPages.getOrDefault(player.getUniqueId(), 1));
                }
            }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
            .exceptionally(ex -> {
                 plugin.getMessageUtil().logException("Error loading global kit preview for " + ownerUUID + "_" + kitNum, ex);
                 Bukkit.getScheduler().runTask(plugin, () -> {
                    msg.sendMessage(player, "error");
                     this.openGlobalKitsList(player, playerGlobalKitPages.getOrDefault(player.getUniqueId(), 1));
                 });
                return null;
            });
    }

    // --- Utility Methods ---

     private Kit createKitFromEditor(Player player, int kitNumber, Inventory editorInventory) {
         PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
         KitContents contents = new KitContents();
         ItemStack[] editorContents = editorInventory.getContents();
         Map<Integer, ItemStack> kitItems = new HashMap<>();
         for (int editorSlot = 0; editorSlot <= 40; editorSlot++) {
             int playerInvSlot = KitManager.mapEditorSlotToPlayerInvSlot(editorSlot);
             ItemStack item = editorContents[editorSlot];
             if (playerInvSlot != -1 && item != null && item.getType() != Material.AIR) { kitItems.put(playerInvSlot, item.clone()); }
         }
         contents.setItems(kitItems);
         Kit existingSavedKit = (playerData != null) ? playerData.getKit(kitNumber) : null;
         KitContents existingEchest = (existingSavedKit != null && existingSavedKit.getEnderChestContents() != null) ? existingSavedKit.getEnderChestContents() : new KitContents();
         boolean isCurrentlyGlobal = (existingSavedKit != null) && existingSavedKit.isGlobal();
         return Kit.builder().kitNumber(kitNumber).owner(player.getUniqueId()).name("Kit " + kitNumber).contents(contents).enderChestContents(existingEchest).global(isCurrentlyGlobal).build();
     }


    private void setItemAction(ItemStack item, String action) { if (item == null || action == null || action.isEmpty()) return; ItemMeta meta = item.getItemMeta(); if (meta != null) { meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action); item.setItemMeta(meta); } }
    private void setItemActionAndKitNum(ItemStack item, String action, int kitNum) { if (item == null || action == null || action.isEmpty()) return; ItemMeta meta = item.getItemMeta(); if (meta != null) { PersistentDataContainer pdc = meta.getPersistentDataContainer(); pdc.set(actionKey, PersistentDataType.STRING, action); pdc.set(kitNumberKey, PersistentDataType.INTEGER, kitNum); item.setItemMeta(meta); } }
    private void setItemActionAndCategory(ItemStack item, String action, String category) {
       if (item == null || action == null || action.isEmpty() || category == null || category.isEmpty()) { msg.debug("Attempted to set null/empty action or category"); return; }
       ItemMeta meta = item.getItemMeta();
       if (meta != null) {
           PersistentDataContainer pdc = meta.getPersistentDataContainer();
           pdc.set(actionKey, PersistentDataType.STRING, action);
           pdc.set(categoryKey, PersistentDataType.STRING, category);
           item.setItemMeta(meta);
           msg.debug("Set item action: " + action + ", category: " + category + " on " + item.getType());
       } else { msg.debug("setItemActionAndCategory: Meta was null for item " + item.getType()); }
   }
    private void setItemActionAndPage(ItemStack item, String action, int pageNum) { if (item == null || action == null || action.isEmpty()) return; ItemMeta meta = item.getItemMeta(); if (meta != null) { PersistentDataContainer pdc = meta.getPersistentDataContainer(); pdc.set(actionKey, PersistentDataType.STRING, action); pdc.set(pageKey, PersistentDataType.INTEGER, pageNum); item.setItemMeta(meta); } }

    // --- GUI Identification Methods ---
    public boolean isKitEditorInventory(Inventory inv) {
        return isGuiInventory(inv, "kit_editor", "&8[&aT-Kɪᴛs&8] ✏️ &fᴋɪᴛ ᴇᴅɪᴛᴏʀ - Kɪᴛ {kit_number}");
    }
    public boolean isEnderChestEditorInventory(Inventory inv) {
        return isGuiInventory(inv, "enderchest_editor", "&8[&5T-Kɪᴛs&8] 📦 &fEᴄʜᴇsᴛ Eᴅɪᴛᴏʀ - Kɪᴛ {kit_number}");
    }
    public boolean isKitroomCategoryInventory(Inventory inv) {
        return isGuiInventory(inv, "kitroom_category", "&8[&b✦&8] &fKitroom - {category}"); // Use the actual default title format here
    }
    private boolean isGlobalListInventory(Inventory inv) {
        return isGuiInventory(inv, "global_kits", "&8[&d✦&8] &fGʟᴏʙᴀʟ Kɪᴛs &8(Page {page}/{total_pages})");
    }

    private boolean isGuiInventory(Inventory inv, String titleKey, String defaultTitleFormat) {
         try {
             if (inv == null || inv.getViewers().isEmpty()) return false;
             // InventoryHolder check might fail on close, so rely on title primarily
             // if (inv.getHolder() != this) return false;

             Component titleComponent = getGuiTitle(titleKey, defaultTitleFormat);
             String baseTitleFormat = msg.getLegacyColorized(titleComponent.toString().split("\\{")[0]);
             if(baseTitleFormat.trim().isEmpty()) return false;

             // Get title from the view associated with the player who might be closing it
             InventoryView view = inv.getViewers().get(0).getOpenInventory();
             Component openInvTitleComponent = view.title();
             String openInvTitle = msg.getLegacyColorized(openInvTitleComponent);

             msg.debug("Checking GUI: Key='" + titleKey + "', ExpectedBase='" + baseTitleFormat + "', Actual='" + openInvTitle + "'");
             return openInvTitle.startsWith(baseTitleFormat);
         } catch (Exception e) {
              plugin.getMessageUtil().debug("GUI check failed for key " + titleKey + ": " + e.getMessage());
             return false;
         }
     }

     // --- State Getters (Unchanged) ---
    public Map<UUID, Integer> getEditingKitMap() { return editingKit; }
    public Map<UUID, Kit> getEditingEnderChestMap() { return editingEnderChest; }
    public Map<UUID, String> getEditingKitroomCategoryMap() { return editingKitroomCategory; }
    public Map<UUID, Kit> getPreviewingKitMap() { return previewingKit; }
    public Map<UUID, Integer> getPlayerGlobalKitPagesMap() { return playerGlobalKitPages; }
    public NamespacedKey getActionKey() { return actionKey; }
    public NamespacedKey getKitNumberKey() { return kitNumberKey; }
    public NamespacedKey getKitOwnerKey() { return kitOwnerKey; }
    public NamespacedKey getCategoryKey() { return categoryKey; }
    public NamespacedKey getPageKey() { return pageKey; }

    // Helper to capitalize category names for titles
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    // --- GUI Config Loading Helpers ---

    private Component getGuiTitle(String key, String defaultValue) {
        String titleStr = guiConfig.getString("titles." + key, defaultValue);
        return msg.deserialize(titleStr);
    }

    private int getSlot(String path, int defaultValue) {
        return guiConfig.getInt(path, defaultValue);
    }

    private ItemStack loadGuiItem(String path, Material defaultMat, String defaultName, List<String> defaultLore) {
        return loadGuiItem(path, defaultMat, defaultName, defaultLore, null);
    }

    private ItemStack loadGuiItem(String path, Material defaultMat, String defaultName, List<String> defaultLore, Map<String, String> placeholders) {
        msg.debug("Loading GUI item from path: " + path);
        Material mat = Material.matchMaterial(guiConfig.getString(path + ".material", defaultMat.name()));
        if (mat == null || mat.isLegacy()) {
            msg.debug(" -> Invalid/Legacy material found at " + path + ".material. Using default: " + defaultMat.name());
            mat = defaultMat;
        }
        String name = guiConfig.getString(path + ".name", defaultName);
        List<String> lore = guiConfig.getStringList(path + ".lore");
        if (lore.isEmpty() && defaultLore != null) {
            lore = defaultLore;
        }
        int modelData = guiConfig.getInt(path + ".custom-model-data", 0);

        msg.debug(" -> Material: " + mat + ", Name: '" + name + "', Lore: " + lore.size() + " lines, ModelData: " + modelData);

        if (placeholders != null) {
            final Map<String, String> finalPlaceholders = placeholders;
            name = replacePlaceholders(name, finalPlaceholders);
            lore = lore.stream().map(line -> replacePlaceholders(line, finalPlaceholders)).collect(Collectors.toList());
        }

        return GuiUtils.createGuiItem(mat, name, lore, modelData);
    }

    private String replacePlaceholders(String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null) return text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return text;
    }

    private ItemStack loadGuiItemWithAction(String path, String action, Material defaultMat, String defaultName, List<String> defaultLore) {
        return loadGuiItemWithAction(path, action, defaultMat, defaultName, defaultLore, null);
    }

    private ItemStack loadGuiItemWithAction(String path, String action, Material defaultMat, String defaultName, List<String> defaultLore, Map<String, String> placeholders) {
        ItemStack item = loadGuiItem(path, defaultMat, defaultName, defaultLore, placeholders);
        setItemAction(item, action); // Set action *after* loading basic item
        return item;
    }

     private void replaceItemPlaceholders(ItemStack item, Map<String, String> placeholders) {
        if (item == null || placeholders == null || placeholders.isEmpty()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        if (meta.hasDisplayName()) {
             Component currentName = meta.displayName();
             if (currentName != null) {
                 String nameStr = msg.getLegacySerializer().serialize(currentName);
                 String finalName = replacePlaceholders(nameStr, placeholders);
                 meta.displayName(msg.deserialize(finalName));
             }
        }

        if (meta.hasLore()) {
            List<Component> currentLore = meta.lore();
            if (currentLore != null) {
                 List<Component> newLore = currentLore.stream()
                         .map(line -> msg.getLegacySerializer().serialize(line))
                         .map(lineStr -> replacePlaceholders(lineStr, placeholders))
                         .map(finalLine -> msg.deserialize(finalLine))
                         .collect(Collectors.toList());
                meta.lore(newLore);
            }
        }
        item.setItemMeta(meta);
     }

    private void saveKitFromInventory(Player player, int kitNumber, Inventory sourceInventory) {
        if (player == null || sourceInventory == null) {
            msg.debug("Could not save kit: player or sourceInventory was null");
            return;
        }

        if (!isKitEditorInventory(sourceInventory)) {
             Component editorTitleBase = getGuiTitle("kit_editor", "&8[&aT-Kɪᴛs&8] ✏️ &fᴋɪᴛ ᴇᴅɪᴛᴏʀ - Kɪᴛ {kit_number}");
             String baseTitleFormat = msg.getLegacyColorized(editorTitleBase.toString().split("\\{")[0]);
             // Safely get viewer and title, handle potential errors
             String openInvTitle = "";
             try {
                 if(!sourceInventory.getViewers().isEmpty()){
                     openInvTitle = msg.getLegacyColorized(sourceInventory.getViewers().get(0).getOpenInventory().title());
                 }
             } catch (Exception e){
                 msg.debug("Error getting inventory title during save check: " + e.getMessage());
             }

             if (!openInvTitle.startsWith(baseTitleFormat)) {
                 msg.debug("Could not auto-save kit: inventory title does not match kit editor.");
                 return;
             }
             msg.debug("Inventory holder mismatch on close, but title matches kit editor. Proceeding with save.");
        }

        try {
            kitManager.saveKit(player, kitNumber, sourceInventory);
            msg.debug("Saved kit " + kitNumber + " for " + player.getName() + " from editor inventory state.");
        } catch (Exception e) {
            msg.debug("Error saving kit from inventory: " + e.getMessage());
            plugin.getLogger().log(Level.WARNING, "Error saving kit " + kitNumber + " from inventory for " + player.getName(), e);
        }
    }

} // End of GuiManager class