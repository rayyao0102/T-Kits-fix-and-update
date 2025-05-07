package com.takeda.tkits.managers;

import com.takeda.tkits.TKits;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.PlayerData;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class PlayerDataManager {

    private final TKits plugin;
    private final Map<UUID, PlayerData> playerDataMap; 
    private final Set<UUID> loadingPlayers; 

    public PlayerDataManager(TKits plugin) {
        this.plugin = plugin;
        this.playerDataMap = new ConcurrentHashMap<>();
        this.loadingPlayers = ConcurrentHashMap.newKeySet(); 
    }

    /**
     * Asynchronously loads player data from storage into the cache.
     * If data is already loading or loaded, returns the existing future or data.
     * @param playerUUID The UUID of the player to load.
     * @return A CompletableFuture containing the PlayerData, completed when loaded.
     */
    public CompletableFuture<PlayerData> loadPlayerData(UUID playerUUID) {
        if (playerDataMap.containsKey(playerUUID)) {
            return CompletableFuture.completedFuture(playerDataMap.get(playerUUID));
        }
        
        if (!loadingPlayers.add(playerUUID)) {
            
             
             
            plugin.getLogger().fine("Attempted to load data for player " + playerUUID + " while already loading.");
            
             return CompletableFuture.completedFuture(new PlayerData(playerUUID)); 
        }

        plugin.getLogger().fine("Loading data for player " + playerUUID + "...");
        PlayerData data = new PlayerData(playerUUID); 

        return plugin.getStorageHandler().loadPlayerKits(playerUUID)
             .thenApplyAsync(loadedKits -> {
                 loadedKits.forEach(data::setKit); 
                 playerDataMap.put(playerUUID, data); 
                 loadingPlayers.remove(playerUUID); 
                 plugin.getLogger().info("Successfully loaded data for player " + playerUUID + ". Found " + loadedKits.size() + " kits.");
                 return data;
             }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin)) 
              .exceptionally(ex -> {
                 plugin.getMessageUtil().logException("Failed to load player data for " + playerUUID, ex);
                  loadingPlayers.remove(playerUUID); 
                 
                 
                 
                 playerDataMap.put(playerUUID, data); 
                  plugin.getMessageUtil().logWarning("Returning empty PlayerData object for " + playerUUID + " due to loading error.");
                 return data; 
             });
    }

    /**
     * Removes player data from the cache. Typically called on player quit.
     * Optionally triggers a save operation before unloading.
     * @param playerUUID The UUID of the player to unload.
     * @param saveBeforeUnload If true, attempts to save data asynchronously before unloading cache.
     */
    public void unloadPlayerData(UUID playerUUID, boolean saveBeforeUnload) {
        PlayerData data = playerDataMap.remove(playerUUID);
         loadingPlayers.remove(playerUUID); 
        if (data != null) {
            plugin.getLogger().fine("Unloaded data cache for player " + playerUUID);
            if (saveBeforeUnload && !data.isSaving()) { 
                 savePlayerData(data).exceptionally(ex -> { 
                     plugin.getMessageUtil().logException("Error saving player data during unload for " + playerUUID, ex);
                     return null;
                 });
            }
        }
    }

    public PlayerData getPlayerData(Player player) {
        return getPlayerData(player.getUniqueId());
    }

    public PlayerData getPlayerData(UUID playerUUID) {
        
        return playerDataMap.get(playerUUID);
    }

    public Collection<PlayerData> getAllLoadedPlayerData() {
         return Collections.unmodifiableCollection(playerDataMap.values());
    }

    /**
     * Asynchronously saves all kits associated with a specific PlayerData object.
     * @param data The PlayerData to save.
     * @return A CompletableFuture that completes when all associated kits have been saved.
     */
    public CompletableFuture<Void> savePlayerData(PlayerData data) {
        if (data == null || data.getKits().isEmpty()) {
            return CompletableFuture.completedFuture(null); 
        }
        if (data.isSaving()) {
            plugin.getLogger().warning("Save already in progress for player " + data.getPlayerUUID() + ". Skipping duplicate request.");
            return CompletableFuture.completedFuture(null);
        }
        data.setSaving(true);

        plugin.getLogger().fine("Initiating async save for player " + data.getPlayerUUID() + "...");
        List<CompletableFuture<Void>> saveFutures = data.getKits().values().stream()
                .map(kit -> savePlayerKit(data.getPlayerUUID(), kit)) 
                .collect(Collectors.toList());

        return CompletableFuture.allOf(saveFutures.toArray(new CompletableFuture[0]))
               .whenComplete((v, throwable) -> {
                    data.setSaving(false); 
                   if (throwable != null) {
                        plugin.getMessageUtil().logException("Error during batch save for player " + data.getPlayerUUID(), throwable);
                   } else {
                        plugin.getLogger().finest("Async save completed for player " + data.getPlayerUUID());
                   }
                });
    }

    /**
     * Asynchronously saves a single kit for a specific player. Handles storage exceptions.
     * @param playerUUID The player owning the kit.
     * @param kit The kit to save.
     * @return A CompletableFuture that completes when the save attempt is finished (may complete exceptionally).
     */
    public CompletableFuture<Void> savePlayerKit(UUID playerUUID, Kit kit) {
         if (playerUUID == null || kit == null) {
             return CompletableFuture.failedFuture(new NullPointerException("PlayerUUID or Kit cannot be null for saving."));
         }
         
         if (!kit.getOwner().equals(playerUUID)) {
              plugin.getMessageUtil().logSevere("CRITICAL SAVE ERROR (savePlayerKit): Mismatched owner. Target: " + playerUUID + " Kit: " + kit);
             return CompletableFuture.failedFuture(new IllegalArgumentException("Kit owner mismatch during save attempt."));
         }

         return plugin.getStorageHandler().savePlayerKit(playerUUID, kit)
               .exceptionally(ex -> {
                    plugin.getMessageUtil().logException("Failed to save kit " + kit.getKitNumber() + " for player " + playerUUID, ex);
                    
                     
                    throw new RuntimeException("Storage error saving kit", ex); 
               });
    }

    /**
     * Saves data for all currently online players. Primarily used during shutdown.
     * Blocks the calling thread until all saves are complete.
     */
    public void saveAllPlayerDataBlocking() {
         if (playerDataMap.isEmpty()) return;
         plugin.getMessageUtil().logInfo("Saving data for " + playerDataMap.size() + " online players (blocking)...");
         long startTime = System.currentTimeMillis();

         
         List<CompletableFuture<Void>> saveFutures = new ArrayList<>();
         for (PlayerData data : playerDataMap.values()) {
             if (!data.getKits().isEmpty() && !data.isSaving()) { 
                  data.setSaving(true); 
                 saveFutures.add(
                     CompletableFuture.allOf(
                         data.getKits().values().stream()
                             .map(kit -> plugin.getStorageHandler().savePlayerKit(data.getPlayerUUID(), kit))
                             .toArray(CompletableFuture[]::new)
                     ).whenComplete((v, t) -> data.setSaving(false)) 
                      .exceptionally(ex -> { 
                           plugin.getMessageUtil().logException("Error during blocking save for player " + data.getPlayerUUID(), ex);
                           return null;
                       })
                 );
             } else if (data.isSaving()) {
                  plugin.getMessageUtil().logWarning("Save was already in progress for player " + data.getPlayerUUID() + " during shutdown save. Data might not be fully saved if previous save didn't finish.");
             }
         }

         
         try {
             CompletableFuture.allOf(saveFutures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS); 
             long duration = System.currentTimeMillis() - startTime;
             plugin.getMessageUtil().logInfo("Finished saving player data in " + duration + "ms.");
         } catch (ExecutionException | InterruptedException e) {
              plugin.getMessageUtil().logException("Error occurred while waiting for player data saving tasks to complete during shutdown.", e);
              if (e instanceof InterruptedException) Thread.currentThread().interrupt();
         } catch (java.util.concurrent.TimeoutException e) {
             plugin.getMessageUtil().logSevere("Timed out waiting for player data to save during shutdown! Some data might be lost.");
         }
    }

    /**
     * Asynchronously saves data for all currently online players. Returns a future that completes when all saves finish.
     * Use this for periodic saving if needed.
     * @return A CompletableFuture that completes when all saves finish.
     */
     public CompletableFuture<Void> saveAllPlayerData() {
        if (playerDataMap.isEmpty()) return CompletableFuture.completedFuture(null);
        plugin.getMessageUtil().logInfo("Initiating asynchronous save for all " + playerDataMap.size() + " online players...");
        long startTime = System.currentTimeMillis();

        List<CompletableFuture<Void>> allPlayerSaves = playerDataMap.values().stream()
            .map(this::savePlayerData) 
            .collect(Collectors.toList());

        return CompletableFuture.allOf(allPlayerSaves.toArray(new CompletableFuture[0]))
            .whenComplete((v, t) -> {
                 long duration = System.currentTimeMillis() - startTime;
                 if (t != null) {
                     plugin.getMessageUtil().logException("Error during asynchronous batch save of player data.", t);
                 } else {
                      plugin.getMessageUtil().logInfo("Asynchronous batch save for all players completed in " + duration + "ms.");
                 }
             });
     }

}