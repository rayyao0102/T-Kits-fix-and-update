package com.takeda.tkits.config;

import com.takeda.tkits.TKits;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

@Getter
public class ConfigManager {

    private final TKits plugin;
    private File configFile;
    private FileConfiguration mainConfig;

    private File kitroomFile;
    private FileConfiguration kitroomConfigInternal; // Internal reference to manage saving

    private File guiFile; // Added for gui.yml
    private FileConfiguration guiConfig; // Added for gui.yml

    private volatile boolean kitroomAdminChanges = false;

    // --- New Config Fields ---
    // General
    private boolean debugEnabled;

    // Kits
    private boolean clearInventoryOnLoad;
    private boolean loadRequiresEmptyInventory;
    private List<String> preventLoadWorlds;
    private Set<Material> preventSaveMaterials;
    private int maxTotalItemsInKit;
    private boolean saveOnEditorClose;
    private boolean clearKitRequiresConfirmation;
    private int maxKitsPerPlayer;

    // Sharing
    private int shareCodeLength;
    private boolean shareCodeOneTimeUse;
    private int shareCodeExpirationMinutes;
    private boolean allowSharingGlobalKits;

    // Combat Tag
    private boolean combatTagEnabled;
    private int combatTagDurationSeconds;
    private List<String> combatTagBlockedCommands;
    private boolean combatTagPreventEnderpearl;

    // Regear
    private Material regearBoxMaterial;
    private String regearBoxName;

    // Arrange
    private String arrangeHandleExtraItems;

    // Kitroom
    private boolean kitroomRequirePerCategoryPermission;
    private int kitroomItemTakeCooldownSeconds;

    // Cooldowns (Example, assumes CooldownService handles loading)
    // We might store them here too if needed directly

    // Sounds (Example, assumes MessageUtil/other classes handle loading)
    // We might store them here too if needed directly

    // Messages (Loaded by MessageUtil)

    // Storage (Used during StorageHandler init)

    public ConfigManager(TKits plugin) {
        this.plugin = plugin;
        setupFiles();
        // Initial load on startup
        loadConfigs();
    }

    private void setupFiles() {
        if (!plugin.getDataFolder().exists()) {
             if(!plugin.getDataFolder().mkdirs()){
                 plugin.getLogger().severe("!!! Failed to create plugin data folder: " + plugin.getDataFolder().getPath());
             }
        }

        configFile = new File(plugin.getDataFolder(), "config.yml");
        kitroomFile = new File(plugin.getDataFolder(), "kitroom.yml");
        guiFile = new File(plugin.getDataFolder(), "gui.yml"); // Added

        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
        mainConfig = YamlConfiguration.loadConfiguration(configFile); // Load initial

        if (!kitroomFile.exists()) {
             plugin.getLogger().info("kitroom.yml not found, creating default structure (will be populated by KitroomManager).");
             plugin.saveResource("kitroom.yml", false); // Saves the empty file with comments
            kitroomConfigInternal = YamlConfiguration.loadConfiguration(kitroomFile);
            // Don't create default structure here, let KitroomManager handle it on first load
            saveKitroomConfig(); // Save the initially empty file structure if needed
        } else {
            kitroomConfigInternal = YamlConfiguration.loadConfiguration(kitroomFile);
        }

        // Added gui.yml handling
        if (!guiFile.exists()) {
            plugin.getLogger().info("gui.yml not found, creating default structure.");
            plugin.saveResource("gui.yml", false);
        }
        guiConfig = YamlConfiguration.loadConfiguration(guiFile); // Load initial gui config

    }

    public synchronized void loadConfigs() {
        try {
            mainConfig = YamlConfiguration.loadConfiguration(configFile);
            kitroomConfigInternal = YamlConfiguration.loadConfiguration(kitroomFile);
            guiConfig = YamlConfiguration.loadConfiguration(guiFile); // Load gui.yml
            kitroomAdminChanges = false;

            // --- Load values from mainConfig ---
            debugEnabled = mainConfig.getBoolean("debug", false);

            // Kits
            maxKitsPerPlayer = mainConfig.getInt("kits.max_kits_per_player", 7);
            saveOnEditorClose = mainConfig.getBoolean("kits.save_on_editor_close", true);
            clearKitRequiresConfirmation = mainConfig.getBoolean("kits.clear_kit_requires_confirmation", true);
            clearInventoryOnLoad = mainConfig.getBoolean("kits.clear_inventory_on_load", false);
            loadRequiresEmptyInventory = mainConfig.getBoolean("kits.load_requires_empty_inventory", false);
            preventLoadWorlds = mainConfig.getStringList("kits.prevent_load_in_worlds");
            maxTotalItemsInKit = mainConfig.getInt("kits.max_total_items_in_kit", -1);
            preventSaveMaterials = mainConfig.getStringList("kits.prevent_save_items").stream()
                    .map(s -> {
                        try {
                            return Material.valueOf(s.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Invalid material in 'kits.prevent_save_items': " + s);
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());

            // Sharing
            shareCodeLength = mainConfig.getInt("sharing.code_length", 5);
            shareCodeOneTimeUse = mainConfig.getBoolean("sharing.code_one_time_use", true);
            shareCodeExpirationMinutes = mainConfig.getInt("sharing.code_expiration_minutes", 5);
            allowSharingGlobalKits = mainConfig.getBoolean("sharing.allow_sharing_global_kits", true);

            // Combat Tag
            combatTagEnabled = mainConfig.getBoolean("combat_tag.enabled", true);
            combatTagDurationSeconds = mainConfig.getInt("combat_tag.duration_seconds", 10);
            combatTagBlockedCommands = mainConfig.getStringList("combat_tag.blocked_commands").stream()
                                                .map(String::toLowerCase) // Ensure lowercase for matching
                                                .collect(Collectors.toList());
            combatTagPreventEnderpearl = mainConfig.getBoolean("combat_tag.prevent_enderpearl", false);

            // Regear
            String regearMatName = mainConfig.getString("regear.box_material", "SHULKER_BOX");
            try {
                regearBoxMaterial = Material.valueOf(regearMatName.toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material for 'regear.box_material': " + regearMatName + ". Defaulting to SHULKER_BOX.");
                regearBoxMaterial = Material.SHULKER_BOX;
            }
            regearBoxName = mainConfig.getString("regear.box_name", "&aRegear Box for {player}");

            // Arrange
            arrangeHandleExtraItems = mainConfig.getString("arrange.handle_extra_items", "KEEP").toUpperCase();

            // Kitroom
            kitroomRequirePerCategoryPermission = mainConfig.getBoolean("kitroom.require_per_category_permission", false);
            kitroomItemTakeCooldownSeconds = mainConfig.getInt("kitroom.item_take_cooldown_seconds", 0);

            plugin.getLogger().info("Configurations loaded/reloaded. Debug mode: " + (debugEnabled ? "ENABLED" : "DISABLED"));

        } catch (Exception e) { // Catch broader exceptions during load
            plugin.getLogger().log(Level.SEVERE, "Could not load configuration files!", e);
        }
    }

    public synchronized FileConfiguration getKitroomConfig() {
         // Return a new instance loaded from file to ensure it's fresh, but manage writes via internal ref
         return YamlConfiguration.loadConfiguration(kitroomFile);
    }

     public synchronized FileConfiguration getInternalKitroomConfigForEdit() {
         // Method used by KitroomManager to get the reference it should modify before saving
         return kitroomConfigInternal;
     }

     // Added getter for gui.yml
     public synchronized FileConfiguration getGuiConfig() {
         // Return the loaded instance, assuming reloads happen via loadConfigs()
         if (guiConfig == null) {
             // Attempt to load if null, though this shouldn't happen after init
             guiConfig = YamlConfiguration.loadConfiguration(guiFile);
         }
         return guiConfig;
     }

    public synchronized void saveMainConfig() {
        try {
            mainConfig.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save config.yml!", e);
        }
    }

    public synchronized void saveKitroomConfig() {
        try {
            // Ensure internal reference is saved to file
            kitroomConfigInternal.save(kitroomFile);
            kitroomAdminChanges = false; // Reset flag after successful save
            plugin.getLogger().info("Kitroom layout saved to kitroom.yml.");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save kitroom.yml!", e);
        }
    }

    public synchronized void markKitroomChanged() {
        this.kitroomAdminChanges = true;
        plugin.getLogger().fine("Kitroom configuration marked as changed.");
    }
}
