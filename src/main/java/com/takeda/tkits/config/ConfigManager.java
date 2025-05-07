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
    private FileConfiguration kitroomConfigInternal; 

    private File guiFile; 
    private FileConfiguration guiConfig; 

    private volatile boolean kitroomAdminChanges = false;

    
    
    private boolean debugEnabled;

    
    private boolean clearInventoryOnLoad;
    private boolean loadRequiresEmptyInventory;
    private List<String> preventLoadWorlds;
    private Set<Material> preventSaveMaterials;
    private int maxTotalItemsInKit;
    private boolean saveOnEditorClose;
    private boolean clearKitRequiresConfirmation;
    private int maxKitsPerPlayer;

    
    private int shareCodeLength;
    private boolean shareCodeOneTimeUse;
    private int shareCodeExpirationMinutes;
    private boolean allowSharingGlobalKits;

    
    private boolean combatTagEnabled;
    private int combatTagDurationSeconds;
    private List<String> combatTagBlockedCommands;
    private boolean combatTagPreventEnderpearl;

    
    private Material regearBoxMaterial;
    private String regearBoxName;

    
    private String arrangeHandleExtraItems;

    
    private boolean kitroomRequirePerCategoryPermission;
    private int kitroomItemTakeCooldownSeconds;

    
    

    
    

    

    

    public ConfigManager(TKits plugin) {
        this.plugin = plugin;
        setupFiles();
        
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
        guiFile = new File(plugin.getDataFolder(), "gui.yml"); 

        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
        mainConfig = YamlConfiguration.loadConfiguration(configFile); 

        if (!kitroomFile.exists()) {
             plugin.getLogger().info("kitroom.yml not found, creating default structure (will be populated by KitroomManager).");
             plugin.saveResource("kitroom.yml", false); 
            kitroomConfigInternal = YamlConfiguration.loadConfiguration(kitroomFile);
            
            saveKitroomConfig(); 
        } else {
            kitroomConfigInternal = YamlConfiguration.loadConfiguration(kitroomFile);
        }

        
        if (!guiFile.exists()) {
            plugin.getLogger().info("gui.yml not found, creating default structure.");
            plugin.saveResource("gui.yml", false);
        }
        guiConfig = YamlConfiguration.loadConfiguration(guiFile); 

    }

    public synchronized void loadConfigs() {
        try {
            mainConfig = YamlConfiguration.loadConfiguration(configFile);
            kitroomConfigInternal = YamlConfiguration.loadConfiguration(kitroomFile);
            guiConfig = YamlConfiguration.loadConfiguration(guiFile); 
            kitroomAdminChanges = false;

            
            debugEnabled = mainConfig.getBoolean("debug", false);

            
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

            
            shareCodeLength = mainConfig.getInt("sharing.code_length", 5);
            shareCodeOneTimeUse = mainConfig.getBoolean("sharing.code_one_time_use", true);
            shareCodeExpirationMinutes = mainConfig.getInt("sharing.code_expiration_minutes", 5);
            allowSharingGlobalKits = mainConfig.getBoolean("sharing.allow_sharing_global_kits", true);

            
            combatTagEnabled = mainConfig.getBoolean("combat_tag.enabled", true);
            combatTagDurationSeconds = mainConfig.getInt("combat_tag.duration_seconds", 10);
            combatTagBlockedCommands = mainConfig.getStringList("combat_tag.blocked_commands").stream()
                                                .map(String::toLowerCase) 
                                                .collect(Collectors.toList());
            combatTagPreventEnderpearl = mainConfig.getBoolean("combat_tag.prevent_enderpearl", false);

            
            String regearMatName = mainConfig.getString("regear.box_material", "SHULKER_BOX");
            try {
                regearBoxMaterial = Material.valueOf(regearMatName.toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material for 'regear.box_material': " + regearMatName + ". Defaulting to SHULKER_BOX.");
                regearBoxMaterial = Material.SHULKER_BOX;
            }
            regearBoxName = mainConfig.getString("regear.box_name", "&aRegear Box for {player}");

            
            arrangeHandleExtraItems = mainConfig.getString("arrange.handle_extra_items", "KEEP").toUpperCase();

            
            kitroomRequirePerCategoryPermission = mainConfig.getBoolean("kitroom.require_per_category_permission", false);
            kitroomItemTakeCooldownSeconds = mainConfig.getInt("kitroom.item_take_cooldown_seconds", 0);

            plugin.getLogger().info("Configurations loaded/reloaded. Debug mode: " + (debugEnabled ? "ENABLED" : "DISABLED"));

        } catch (Exception e) { 
            plugin.getLogger().log(Level.SEVERE, "Could not load configuration files!", e);
        }
    }

    public synchronized FileConfiguration getKitroomConfig() {
         
         return YamlConfiguration.loadConfiguration(kitroomFile);
    }

     public synchronized FileConfiguration getInternalKitroomConfigForEdit() {
         
         return kitroomConfigInternal;
     }

     
     public synchronized FileConfiguration getGuiConfig() {
         
         if (guiConfig == null) {
             
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
            
            kitroomConfigInternal.save(kitroomFile);
            kitroomAdminChanges = false; 
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
