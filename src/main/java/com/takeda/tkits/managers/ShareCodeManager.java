package com.takeda.tkits.managers;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.takeda.tkits.TKits;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.KitContents;
import com.takeda.tkits.models.PlayerData;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bukkit.entity.Player;

import org.apache.commons.lang3.RandomStringUtils;


import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ShareCodeManager {

    private final TKits plugin;
    private Cache<String, ShareData> shareCodeCache; 

    private int codeLength;
    private boolean codeOneTimeUse;
    private long codeExpirationMinutes;

    public ShareCodeManager(TKits plugin) {
        this.plugin = plugin;
        loadConfigSettings(); 
        buildCache(); 
    }

    private void loadConfigSettings() {
         codeLength = plugin.getConfigManager().getMainConfig().getInt("sharing.code_length", 5);
         codeOneTimeUse = plugin.getConfigManager().getMainConfig().getBoolean("sharing.code_one_time_use", true);
         codeExpirationMinutes = plugin.getConfigManager().getMainConfig().getLong("sharing.code_expiration_minutes", 5);
          plugin.getLogger().fine("Share code settings loaded: Length=" + codeLength + ", OneTime=" + codeOneTimeUse + ", Expires=" + codeExpirationMinutes + "min");
    }

    
    public void reloadConfigSettings() {
        loadConfigSettings();
        
        buildCache();
         plugin.getMessageUtil().logInfo("Share code settings reloaded.");
    }

     private void buildCache() {
        
        if (this.shareCodeCache != null) {
            this.shareCodeCache.invalidateAll();
        }
        this.shareCodeCache = CacheBuilder.newBuilder()
                .expireAfterWrite(codeExpirationMinutes, TimeUnit.MINUTES)
                .maximumSize(10000) 
                .build();
        plugin.getLogger().fine("Share code cache rebuilt with expiry of " + codeExpirationMinutes + " minutes.");
     }

    /**
     * Generates a share code for a given kit number belonging to the player.
     * Reads the kit data from PlayerDataManager.
     * @param player The player generating the code.
     * @param kitNumber The kit number to share.
     * @return The generated share code, or null on failure.
     */
    public String generateShareCode(Player player, int kitNumber) {
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if (playerData == null) {
             plugin.getMessageUtil().logWarning("Attempted to generate share code for player " + player.getName() + " but PlayerData not found.");
            return null;
        }

        Kit kit = playerData.getKit(kitNumber);
        if (kit == null) {
             plugin.getMessageUtil().sendMessage(player, "kit_not_found", "kit_number", String.valueOf(kitNumber));
             plugin.getMessageUtil().playSound(player, "error");
            return null;
        }
        
        return generateShareCodeFromKit(player.getUniqueId(), kit);
    }

    /**
     * Generates a share code for a specific Kit object provided.
     * Use this when you have the Kit object already (e.g., from GUI editor).
     * @param ownerUUID The UUID of the player generating the code.
     * @param kit The Kit object to share (should be a snapshot/copy).
     * @return The generated share code, or null on failure.
     */
    public String generateShareCodeFromKit(UUID ownerUUID, Kit kit) {
        if (kit == null) return null;
        plugin.getMessageUtil().debug("generateShareCodeFromKit: Entered for kit " + kit.getKitNumber() + " owner " + ownerUUID); 

        
        boolean allowGlobalShare = plugin.getConfigManager().isAllowSharingGlobalKits();
        if (kit.isGlobal() && !allowGlobalShare) {
            plugin.getMessageUtil().debug("generateShareCodeFromKit: Failed - Cannot share global kit."); 
            
            
            
            return null;
        }
        

        String code;
        int retries = 0;
        final int maxRetries = 10; 

        do {
            
            code = RandomStringUtils.randomAlphanumeric(codeLength).toUpperCase();
            if (retries++ > maxRetries) {
                plugin.getMessageUtil().debug("generateShareCodeFromKit: Failed - Max retries reached."); 
                plugin.getMessageUtil().logSevere("Failed to generate a unique share code after " + maxRetries + " attempts for kit " + kit.getKitNumber() + " from " + ownerUUID);
                
                return null;
            }
        } while (shareCodeCache.getIfPresent(code) != null);

        
        Kit sharedKitCopy = kit.toBuilder()
            .contents(KitContents.deserialize(KitContents.serialize(kit.getContents())))
            .enderChestContents(KitContents.deserialize(KitContents.serialize(kit.getEnderChestContents())))
            
            .owner(ownerUUID)
            .kitNumber(kit.getKitNumber())
            .build();

        ShareData data = new ShareData(ownerUUID, sharedKitCopy, codeOneTimeUse);
        shareCodeCache.put(code, data);
        plugin.getMessageUtil().debug("generateShareCodeFromKit: Generated code " + code); 
        plugin.getLogger().info("Generated share code " + code + " for kit " + kit.getKitNumber() + " from " + ownerUUID + ".");

        return code;
    }


    /**
     * Redeems a share code to get the associated Kit data.
     * If the code is one-time use, it is invalidated upon successful redemption.
     * @param code The share code (case-insensitive).
     * @return The shared Kit if the code is valid and not expired, otherwise null.
     */
    public Kit redeemShareCode(String code) {
        if (code == null) return null;
        String upperCode = code.toUpperCase(); 
        ShareData data = shareCodeCache.getIfPresent(upperCode);

        if (data == null) {
            plugin.getLogger().fine("Attempted to redeem invalid/expired share code: " + upperCode);
            return null; 
        }

        
         Kit kitToReturn = data.getSharedKit().toBuilder()
             
             .contents(KitContents.deserialize(KitContents.serialize(data.getSharedKit().getContents())))
             .enderChestContents(KitContents.deserialize(KitContents.serialize(data.getSharedKit().getEnderChestContents())))
             .build();


        if (data.isOneTimeUse()) {
             shareCodeCache.invalidate(upperCode); 
              plugin.getLogger().info("Redeemed and invalidated one-time use share code: " + upperCode);
        } else {
              plugin.getLogger().info("Redeemed multi-use share code: " + upperCode);
        }

        return kitToReturn;
    }

    
    @Getter
    @AllArgsConstructor
    private static class ShareData {
        private final UUID originalOwner;
        private final Kit sharedKit; 
        private final boolean oneTimeUse;
    }
}