package com.takeda.tkits.services;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.takeda.tkits.TKits;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class CooldownService {

    private final TKits plugin;
    
    private final Cache<UUID, Cache<CooldownType, Long>> cooldownCache;

    private long kitLoadCooldownMillis;
    private long regearCooldownMillis;
    private long arrangeCooldownMillis;

    public enum CooldownType {
        KIT_LOAD, REGEAR, ARRANGE 
    }

    public CooldownService(TKits plugin) {
        this.plugin = plugin;
        
        this.cooldownCache = CacheBuilder.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES) 
                .build();
        loadCooldowns(); 
    }

    
    public void loadCooldowns() {
         FileConfiguration config = plugin.getConfigManager().getMainConfig();
         kitLoadCooldownMillis = TimeUnit.SECONDS.toMillis(config.getLong("cooldowns.kit_load", 3));
         regearCooldownMillis = TimeUnit.SECONDS.toMillis(config.getLong("cooldowns.regear", 10));
         arrangeCooldownMillis = TimeUnit.SECONDS.toMillis(config.getLong("cooldowns.arrange", 5));
          plugin.getMessageUtil().logInfo("Command cooldowns loaded.");
    }

    /**
     * Gets the duration for a specific cooldown type in milliseconds.
     * @param type The cooldown type.
     * @return The duration in milliseconds.
     */
    public long getCooldownDuration(CooldownType type) {
        switch (type) {
            case KIT_LOAD: return kitLoadCooldownMillis;
            case REGEAR: return regearCooldownMillis;
            case ARRANGE: return arrangeCooldownMillis;
            default: return 0L;
        }
    }

    /**
     * Applies the cooldown for the specified type to the player.
     * @param playerUUID The player's UUID.
     * @param type The type of cooldown to apply.
     */
    public void applyCooldown(UUID playerUUID, CooldownType type) {
         long duration = getCooldownDuration(type);
         if (duration <= 0) return; 

         long expiryTime = System.currentTimeMillis() + duration;
         try {
             
              Cache<CooldownType, Long> playerCooldowns = cooldownCache.get(playerUUID, () ->
                  CacheBuilder.newBuilder()
                       .expireAfterWrite(duration + TimeUnit.SECONDS.toMillis(10), TimeUnit.MILLISECONDS) 
                      .build()
              );
             playerCooldowns.put(type, expiryTime);
         } catch (Exception e) { 
             plugin.getMessageUtil().logException("Error applying cooldown for player " + playerUUID, e);
         }
    }

    /**
     * Checks the remaining cooldown time for a player and type.
     * @param playerUUID The player's UUID.
     * @param type The cooldown type.
     * @return Remaining cooldown in milliseconds, or 0 if not on cooldown.
     */
    public long getRemainingCooldown(UUID playerUUID, CooldownType type) {
         Cache<CooldownType, Long> playerCooldowns = cooldownCache.getIfPresent(playerUUID);
         if (playerCooldowns == null) {
             return 0L; 
         }

         Long expiryTime = playerCooldowns.getIfPresent(type);
         if (expiryTime == null) {
             return 0L; 
         }

         long remaining = expiryTime - System.currentTimeMillis();
         if (remaining <= 0) {
              playerCooldowns.invalidate(type); 
             return 0L; 
         }

         return remaining;
    }

    /**
     * Resets a specific cooldown for a player.
     * @param playerUUID The player's UUID.
     * @param type The cooldown type to reset.
     */
     public void resetCooldown(UUID playerUUID, CooldownType type) {
          Cache<CooldownType, Long> playerCooldowns = cooldownCache.getIfPresent(playerUUID);
         if (playerCooldowns != null) {
             playerCooldowns.invalidate(type);
         }
     }

    /**
     * Resets all active cooldowns for a player.
     * @param playerUUID The player's UUID.
     */
     public void resetAllCooldowns(UUID playerUUID) {
         cooldownCache.invalidate(playerUUID);
     }

}