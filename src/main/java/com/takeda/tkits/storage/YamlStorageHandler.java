package com.takeda.tkits.storage;

import com.takeda.tkits.TKits;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.KitContents;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class YamlStorageHandler implements StorageHandler {

    private final TKits plugin;
    private final File playerDataFolder;
    private final File globalKitsFile;
    private FileConfiguration globalKitsConfig;
    private final ExecutorService executor;
    private final Object globalLock = new Object(); 

    public YamlStorageHandler(TKits plugin) {
        this.plugin = plugin;
        this.playerDataFolder = new File(plugin.getDataFolder(), "playerdata");
        this.globalKitsFile = new File(plugin.getDataFolder(), "global_kits.yml");
        
        
        
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "T-Kits-YAML-Storage");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void init() {
        if (!playerDataFolder.exists() && !playerDataFolder.mkdirs()) {
             plugin.getMessageUtil().logSevere("Failed to create playerdata directory: " + playerDataFolder.getPath());
        }
        synchronized (globalLock) {
             if (!globalKitsFile.exists()) {
                 try {
                     if (!globalKitsFile.createNewFile()) {
                          plugin.getMessageUtil().logSevere("Failed to create global_kits.yml!");
                     }
                     globalKitsConfig = YamlConfiguration.loadConfiguration(globalKitsFile);
                     if (!globalKitsConfig.isConfigurationSection("kits")) {
                          globalKitsConfig.createSection("kits"); 
                     }
                     globalKitsConfig.save(globalKitsFile);
                 } catch (IOException e) {
                     plugin.getMessageUtil().logException("Could not create or initialize global_kits.yml", e);
                 }
             } else {
                  globalKitsConfig = YamlConfiguration.loadConfiguration(globalKitsFile);
                  
             }
         }
    }

    

    @Override
    public void shutdown() {
         executor.shutdown();
         plugin.getMessageUtil().logInfo("YAML Storage Handler Shut Down.");
    }

    private File getPlayerFile(UUID playerUUID) {
        return new File(playerDataFolder, playerUUID.toString() + ".yml");
    }

    private CompletableFuture<FileConfiguration> loadPlayerConfigAsync(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
             File playerFile = getPlayerFile(playerUUID);
             if (!playerFile.exists()) {
                 return new YamlConfiguration(); 
             }
             try {
                 return YamlConfiguration.loadConfiguration(playerFile);
             } catch (Exception e) {
                  plugin.getMessageUtil().logException("Failed to load config for player " + playerUUID, e);
                 return new YamlConfiguration(); 
             }
         }, executor);
    }


    @Override
    public CompletableFuture<Map<Integer, Kit>> loadPlayerKits(UUID playerUUID) {
        return loadPlayerConfigAsync(playerUUID).thenApplyAsync(config -> {
            Map<Integer, Kit> kits = new HashMap<>();
            ConfigurationSection kitsSection = config.getConfigurationSection("kits");
            if (kitsSection == null) {
                return kits; 
            }

            for (String key : kitsSection.getKeys(false)) {
                try {
                    int kitNumber = Integer.parseInt(key);
                    ConfigurationSection kitSection = kitsSection.getConfigurationSection(key);
                    if (kitSection != null) {
                         Kit kit = deserializeKit(kitSection, playerUUID, kitNumber);
                         if (kit != null) {
                             kits.put(kitNumber, kit);
                         }
                    }
                } catch (NumberFormatException e) {
                    plugin.getMessageUtil().logWarning("Invalid kit number format '" + key + "' in file for " + playerUUID);
                } catch (Exception e) { 
                    plugin.getMessageUtil().logException("Failed to deserialize kit " + key + " for player " + playerUUID, e);
                }
            }
            return kits;
        }, executor); 
    }

    @Override
    public CompletableFuture<Void> savePlayerKit(UUID playerUUID, Kit kit) {
         
        return loadPlayerConfigAsync(playerUUID).thenComposeAsync(config -> {
             
             if (!kit.getOwner().equals(playerUUID)) {
                 plugin.getMessageUtil().logSevere("CRITICAL SAVE ERROR: Attempted to save kit for player " + playerUUID
                     + " but kit owner is " + kit.getOwner() + ". Aborting save for Kit " + kit.getKitNumber());
                  return CompletableFuture.completedFuture(null); 
             }

             ConfigurationSection kitSection = config.createSection("kits." + kit.getKitNumber());
             serializeKit(kitSection, kit); 

             
             return CompletableFuture.runAsync(() -> {
                 File playerFile = getPlayerFile(playerUUID);
                 try {
                     config.save(playerFile);
                 } catch (IOException e) {
                     plugin.getMessageUtil().logException("Could not save player data for " + playerUUID, e);
                      throw new RuntimeException(e); 
                 }
             }, executor);
         }, executor); 
    }


    @Override
    public CompletableFuture<Void> deletePlayerKit(UUID playerUUID, int kitNumber) {
        return loadPlayerConfigAsync(playerUUID).thenComposeAsync(config -> {
            if (!config.isConfigurationSection("kits." + kitNumber)) {
                 return CompletableFuture.completedFuture(null); 
            }

             config.set("kits." + kitNumber, null);

             ConfigurationSection kitsSection = config.getConfigurationSection("kits");
            if (kitsSection != null && kitsSection.getKeys(false).isEmpty()) {
                 config.set("kits", null); 
             }

            
            return CompletableFuture.runAsync(() -> {
                 File playerFile = getPlayerFile(playerUUID);
                 try {
                     
                     if (config.getKeys(false).isEmpty()) {
                         if (playerFile.exists() && !playerFile.delete()) {
                             plugin.getMessageUtil().logWarning("Could not delete empty player file: " + playerFile.getName());
                         }
                     } else {
                         config.save(playerFile); 
                     }
                 } catch (IOException e) {
                     plugin.getMessageUtil().logException("Could not save player data after deleting kit for " + playerUUID, e);
                      throw new RuntimeException(e);
                 }
             }, executor);
        }, executor);
    }

     

    private CompletableFuture<Void> reloadGlobalConfigAsync() {
        return CompletableFuture.runAsync(() -> {
            synchronized (globalLock) {
                if (!globalKitsFile.exists()) {
                    init(); 
                } else {
                    globalKitsConfig = YamlConfiguration.loadConfiguration(globalKitsFile);
                }
            }
        }, executor);
    }


    @Override
    public CompletableFuture<List<Kit>> loadGlobalKits() {
        return reloadGlobalConfigAsync().thenApplyAsync(v -> {
            List<Kit> globalKits = new ArrayList<>();
            synchronized (globalLock) { 
                 ConfigurationSection kitsSection = globalKitsConfig.getConfigurationSection("kits");
                 if (kitsSection == null) {
                     return globalKits;
                 }

                 for (String key : kitsSection.getKeys(false)) {
                     String[] parts = key.split("_", 2); 
                     if (parts.length == 2) {
                          try {
                             UUID ownerUUID = UUID.fromString(parts[0]);
                             int kitNumber = Integer.parseInt(parts[1]);
                             ConfigurationSection kitSection = kitsSection.getConfigurationSection(key);
                             if (kitSection != null) {
                                 Kit kit = deserializeKit(kitSection, ownerUUID, kitNumber);
                                 if (kit != null && kit.isGlobal()) {
                                     globalKits.add(kit);
                                 } else if (kit != null && !kit.isGlobal()){
                                      plugin.getMessageUtil().logWarning("Kit " + key + " in global_kits.yml but not marked global. Data inconsistency?");
                                     
                                     
                                 }
                             }
                         } catch (IllegalArgumentException e) {
                              plugin.getMessageUtil().logWarning("Invalid UUID or kit number format in global kit key '" + key + "'. Skipping.");
                         } catch (Exception e) {
                             plugin.getMessageUtil().logException("Failed to deserialize global kit " + key, e);
                         }
                     } else {
                          plugin.getMessageUtil().logWarning("Invalid key format '" + key + "' in global_kits.yml. Should be UUID_KitNumber.");
                     }
                 }
             } 
             return globalKits;
        }, executor);
    }


    @Override
    public CompletableFuture<Void> saveGlobalKit(Kit kit) {
         if (!kit.isGlobal()) {
             
             
             
              plugin.getMessageUtil().logWarning("Attempted saveGlobalKit for a non-global kit instance: "
                     + kit.getOwner() + " Kit " + kit.getKitNumber() + ". Saving to player file only.");
             
              return savePlayerKit(kit.getOwner(), kit);
         }

        
        return savePlayerKit(kit.getOwner(), kit).thenComposeAsync(v -> reloadGlobalConfigAsync(), executor) 
              .thenComposeAsync(v -> CompletableFuture.runAsync(() -> {
                 synchronized (globalLock) { 
                      ConfigurationSection kitsSection = globalKitsConfig.getConfigurationSection("kits");
                      if (kitsSection == null) {
                           kitsSection = globalKitsConfig.createSection("kits"); 
                      }

                     String key = kit.getOwner().toString() + "_" + kit.getKitNumber();
                     ConfigurationSection kitSection = kitsSection.createSection(key); 
                     serializeKit(kitSection, kit); 

                     
                     try {
                         globalKitsConfig.save(globalKitsFile);
                     } catch (IOException e) {
                          plugin.getMessageUtil().logException("Could not save global_kits.yml after adding kit " + key, e);
                          throw new RuntimeException(e); 
                     }
                 }
             }, executor), executor); 
    }


    @Override
    public CompletableFuture<Void> deleteGlobalKit(UUID ownerUUID, int kitNumber) {
         
         return loadPlayerKits(ownerUUID).thenComposeAsync(playerKits -> { 
              Kit kit = playerKits.get(kitNumber);
              if (kit != null && kit.isGlobal()) {
                  Kit updatedKit = kit.toBuilder().global(false).build();
                  return savePlayerKit(ownerUUID, updatedKit); 
              }
              return CompletableFuture.completedFuture(null); 
         }, executor)
         .thenComposeAsync(v -> reloadGlobalConfigAsync(), executor) 
         .thenComposeAsync(v -> CompletableFuture.runAsync(() -> {
             
              synchronized (globalLock) { 
                 ConfigurationSection kitsSection = globalKitsConfig.getConfigurationSection("kits");
                 if (kitsSection == null) {
                     return; 
                 }
                 String key = ownerUUID.toString() + "_" + kitNumber;
                 if (!kitsSection.contains(key)) {
                      return; 
                 }

                 kitsSection.set(key, null); 

                  if (kitsSection.getKeys(false).isEmpty()) {
                      globalKitsConfig.set("kits", null); 
                  }

                 
                  try {
                      globalKitsConfig.save(globalKitsFile);
                  } catch (IOException e) {
                      plugin.getMessageUtil().logException("Could not save global_kits.yml after removing kit " + key, e);
                       throw new RuntimeException(e);
                  }
             } 
         }, executor), executor);
    }

    

    private void serializeKit(ConfigurationSection section, Kit kit) {
         section.set("owner", kit.getOwner().toString()); 
         section.set("global", kit.isGlobal());
         section.set("contents", KitContents.serialize(kit.getContents())); 
         section.set("enderchest_contents", KitContents.serialize(kit.getEnderChestContents()));
    }

    private Kit deserializeKit(ConfigurationSection section, UUID defaultOwner, int kitNumber) {
         try {
            String ownerStr = section.getString("owner");
            UUID owner = (ownerStr != null && !ownerStr.isEmpty()) ? UUID.fromString(ownerStr) : defaultOwner;

             KitContents contents = KitContents.deserialize(section.getString("contents")); 
             KitContents echestContents = KitContents.deserialize(section.getString("enderchest_contents"));
             boolean isGlobal = section.getBoolean("global", false);

             return Kit.builder()
                 .kitNumber(kitNumber)
                 .owner(owner)
                 .name("Kit " + kitNumber) 
                 .contents(contents)
                 .enderChestContents(echestContents)
                 .global(isGlobal)
                 .build();

         } catch (Exception e) { 
            plugin.getMessageUtil().logException("Deserialization error for kit " + kitNumber + " owned by " + defaultOwner + " in section " + section.getCurrentPath(), e);
            return null; 
         }
    }


    

     @Override
     public CompletableFuture<Void> migratePlayerData(UUID playerUUID, StorageHandler targetHandler) {
         return loadPlayerKits(playerUUID).thenComposeAsync(kits -> {
             if (kits.isEmpty()) {
                 plugin.getMessageUtil().logInfo("No YAML data found for player " + playerUUID + " to migrate.");
                 return CompletableFuture.completedFuture(null);
             }
              plugin.getMessageUtil().logInfo("Migrating " + kits.size() + " kits for player " + playerUUID + " from YAML to " + targetHandler.getClass().getSimpleName() + "...");
             List<CompletableFuture<Void>> saveFutures = kits.values().stream()
                 .map(kit -> targetHandler.savePlayerKit(playerUUID, kit)
                     .exceptionally(ex -> {
                         plugin.getMessageUtil().logException("Failed migrating kit " + kit.getKitNumber() + " for player " + playerUUID, ex);
                         return null; 
                      })
                 )
                 .collect(Collectors.toList());
             return CompletableFuture.allOf(saveFutures.toArray(new CompletableFuture[0]));
         }, executor);
     }

     @Override
     public CompletableFuture<Void> migrateAllData(StorageHandler targetHandler) {
         plugin.getMessageUtil().logInfo("Starting full migration from YAML to " + targetHandler.getClass().getSimpleName() + "...");
         List<CompletableFuture<Void>> allMigrations = new ArrayList<>();

         
          
          
          
          allMigrations.add(
              loadGlobalKits().thenComposeAsync(globalKits -> {
                  if (globalKits.isEmpty()) {
                      plugin.getMessageUtil().logInfo("No separate global kit entries found in global_kits.yml to migrate.");
                      return CompletableFuture.completedFuture(null);
                  }
                  plugin.getMessageUtil().logInfo("Migrating " + globalKits.size() + " potential global kit entries...");
                  List<CompletableFuture<Void>> globalSaveFutures = globalKits.stream()
                       
                      .map(targetHandler::saveGlobalKit)
                      .collect(Collectors.toList());
                  return CompletableFuture.allOf(globalSaveFutures.toArray(new CompletableFuture[0]))
                           .thenRun(() -> plugin.getMessageUtil().logInfo("Global kits migration step complete."));
              }, executor)
          );


         
         File[] playerFiles = playerDataFolder.listFiles((dir, name) -> name.endsWith(".yml") && name.length() == 40); 
         if (playerFiles == null) {
              plugin.getMessageUtil().logWarning("Could not list files in playerdata directory for migration.");
              playerFiles = new File[0]; 
         } else {
              plugin.getMessageUtil().logInfo("Found " + playerFiles.length + " potential player data files to migrate...");
         }

         for (File playerFile : playerFiles) {
             String fileName = playerFile.getName();
             try {
                 UUID playerUUID = UUID.fromString(fileName.substring(0, 36)); 
                 allMigrations.add(
                     migratePlayerData(playerUUID, targetHandler)
                         
                          .thenRun(() -> plugin.getLogger().fine("Migrated YAML data for player: " + playerUUID))
                 );
             } catch (IllegalArgumentException e) {
                  plugin.getMessageUtil().logWarning("Skipping non-UUID named file in playerdata: " + fileName);
             }
         }

         
         return CompletableFuture.allOf(allMigrations.toArray(new CompletableFuture[0]))
                  .thenRun(() -> {
                     plugin.getMessageUtil().logInfo("YAML migration process finished successfully.");
                     plugin.getMessageUtil().logWarning("REMEMBER TO CHANGE 'storage.type' in config.yml and RESTART the server!");
                  })
                  .exceptionally(ex -> {
                       plugin.getMessageUtil().logException("Critical error during YAML migration process.", ex);
                      return null;
                  });
     }
}